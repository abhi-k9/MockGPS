package dev.burixon.mockgps

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.burixon.mockgps.core.InjectionState
import dev.burixon.mockgps.core.OperatingMode
import dev.burixon.mockgps.core.ServiceState
import dev.burixon.mockgps.core.ReadinessState
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var locationMap: LocationMap
    private lateinit var drawer: DrawerLayout
    private lateinit var toggle: MaterialButton
    private lateinit var coordinates: TextView
    private lateinit var broadcastSwitch: CompoundButton
    private lateinit var shortcutsSwitch: CompoundButton
    private lateinit var driftSwitch: CompoundButton
    private var generation = 0L
    private var resumed = false
    private var observing = false
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val controlListener: () -> Unit = { runOnUiThread { if (resumed) render() } }
    private val locationListener = LocationListener { location ->
        if (!State.isServiceRunning(this) && isGenuine(location)) {
            // Genuine location is deliberately held in memory, never in preferences.
            locationMap.showGenuine(location.latitude, location.longitude)
        }
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        render()
        startLocationUpdates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        drawer = findViewById(R.id.drawerLayout)
        toggle = findViewById(R.id.toggleButton)
        coordinates = findViewById(R.id.coordinatesText)
        broadcastSwitch = findViewById(R.id.broadcastSwitch)
        shortcutsSwitch = findViewById(R.id.shortcutIntegrationSwitch)
        driftSwitch = findViewById(R.id.driftSwitch)
        locationMap = LocationMap(this, findViewById(R.id.mapContainer), savedInstanceState,
            select = ::selectLocation, ready = { loaded ->
                findViewById<View>(R.id.mapUnavailable).isVisible = !loaded
            })
        setupInsets()
        setupControls()
        render()
    }

    private fun setupInsets() {
        for (id in listOf(R.id.rootContent, R.id.drawer)) {
            val view = findViewById<View>(id)
            val left = view.paddingLeft
            val top = view.paddingTop
            val right = view.paddingRight
            val bottom = view.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
                val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                target.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
                insets
            }
        }
    }

    private fun setupControls() {
        findViewById<View>(R.id.menuButton).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        findViewById<View>(R.id.closeSettingsButton).setOnClickListener { drawer.closeDrawer(GravityCompat.START) }
        findViewById<View>(R.id.mockLocationButton).setOnClickListener { locationMap.centerSelection() }
        findViewById<View>(R.id.retryMapButton).setOnClickListener { locationMap.retry() }
        findViewById<View>(R.id.editCoordinatesButton).setOnClickListener { showCoordinatesDialog() }
        findViewById<View>(R.id.copyCoordinatesButton).setOnClickListener {
            val lat = State.getLat(this) ?: return@setOnClickListener
            val lon = State.getLon(this) ?: return@setOnClickListener
            copySensitive(getString(R.string.selected_location), String.format(Locale.US, "%.6f, %.6f", lat, lon))
            toast(R.string.coordinates_copied)
        }
        findViewById<View>(R.id.setupButton).setOnClickListener { setupPrerequisites() }
        findViewById<View>(R.id.autoButton).setOnClickListener { ControlCenter.selectAuto(this); render() }
        findViewById<View>(R.id.forceRealButton).setOnClickListener { ControlCenter.forceReal(this); render() }
        toggle.setOnClickListener {
            val state = ControlCenter.snapshot(this)
            if (state.desired || state.service in setOf(ServiceState.INJECTING, ServiceState.STARTING)) {
                ControlCenter.forceReal(this)
            } else if (!hasLocationPermission() || !isMockLocationAllowed()) {
                setupPrerequisites()
            } else if (State.getLat(this) == null || State.getLon(this) == null) {
                showCoordinatesDialog()
            } else if (state.mode != OperatingMode.FORCE_REAL) {
                stopLocationUpdates()
                ControlCenter.manualStart(this)
            }
            render()
        }
        findViewById<View>(R.id.confirmPendingButton).setOnClickListener {
            if (hasLocationPermission() && isMockLocationAllowed()) ControlCenter.confirmPending(this, generation)
            else setupPrerequisites()
            render()
        }
        findViewById<View>(R.id.reviewShortcutButton).setOnClickListener {
            val requestedGeneration = generation
            MaterialAlertDialogBuilder(this).setTitle(R.string.shortcut_review)
                .setMessage(R.string.review_request_description)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.allow_request) { _, _ ->
                    ControlCenter.reviewShortcutRequest(this, requestedGeneration)
                    render()
                }.show()
        }
        findViewById<View>(R.id.showTokenButton).setOnClickListener { showCredential() }
        findViewById<View>(R.id.revokeTokenButton).setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(R.string.revoke_credential)
                .setMessage(R.string.revoke_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.revoke_short) { _, _ ->
                    if (AutomationAccess.revoke(this)) toast(R.string.integration_revoked)
                    render()
                }.show()
        }
        findViewById<View>(R.id.delayButton).setOnClickListener { showDelayDialog() }
        findViewById<View>(R.id.diagnosticsButton).setOnClickListener { showDiagnostics() }
        findViewById<View>(R.id.githubButton).setOnClickListener { openLink("https://github.com/abhi-k9/MockGPS") }
        findViewById<View>(R.id.helpButton).setOnClickListener { openLink("https://github.com/abhi-k9/MockGPS#get-started") }
        findViewById<TextView>(R.id.versionText).text = getString(R.string.version_attribution, BuildConfig.VERSION_NAME)
    }

    private fun render() {
        val state = ControlCenter.snapshot(this)
        generation = state.generation
        val running = state.desired || state.service in setOf(ServiceState.INJECTING, ServiceState.STARTING)
        val reviewPending = ControlCenter.shortcutReviewPending(this)
        findViewById<TextView>(R.id.modeStatus).text = when {
            state.result == "CLEANUP_UNCERTAIN" -> getString(R.string.status_cleanup)
            state.service == ServiceState.ERROR -> getString(R.string.status_error)
            reviewPending -> getString(R.string.status_review)
            state.service == ServiceState.INJECTING && state.injection == InjectionState.CONFIRMED -> getString(R.string.status_running)
            state.service in setOf(ServiceState.STARTING, ServiceState.INJECTING) -> getString(R.string.status_starting)
            state.needsConfirmation -> getString(R.string.status_confirmation)
            state.pendingAtElapsed != null -> getString(R.string.status_waiting)
            else -> getString(R.string.status_stopped)
        }
        findViewById<TextView>(R.id.statusDescription).text = if (reviewPending)
            getString(R.string.review_request_description) else state.displayStatus()
        findViewById<MaterialButton>(R.id.autoButton).isChecked = state.mode != OperatingMode.FORCE_REAL
        findViewById<MaterialButton>(R.id.forceRealButton).isChecked = state.mode == OperatingMode.FORCE_REAL
        findViewById<View>(R.id.confirmPendingButton).isVisible = state.needsConfirmation
        findViewById<View>(R.id.reviewShortcutButton).isVisible = reviewPending
        bindSwitch(broadcastSwitch, State.isBroadcastEnabled(this)) { ControlCenter.setIntegrationEnabled(this, it); render() }
        bindSwitch(shortcutsSwitch, AutomationShortcuts.enabled(this)) { ControlCenter.setShortcutsEnabled(this, it); render() }
        bindSwitch(driftSwitch, State.isDrifting(this)) { State.setDrifting(this, it) }
        findViewById<Button>(R.id.delayButton).text = resources.getQuantityString(R.plurals.delay_seconds, state.delaySeconds.toInt(), state.delaySeconds)
        toggle.isEnabled = running || (state.mode != OperatingMode.FORCE_REAL && state.readiness != ReadinessState.BLOCKED)
        toggle.setText(when {
            running -> R.string.stop_and_force_real
            state.mode == OperatingMode.FORCE_REAL -> R.string.return_auto_before_manual
            state.readiness == ReadinessState.BLOCKED -> R.string.automation_paused
            else -> R.string.manual_start
        })
        toggle.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        toggle.backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(getColor(R.color.divider), getColor(if (running) R.color.simulation_stop else R.color.primary)),
        )
        val lat = State.getLat(this)
        val lon = State.getLon(this)
        coordinates.text = if (lat != null && lon != null) String.format(Locale.US, "%.6f, %.6f", lat, lon)
            else getString(R.string.no_location_selected)
        findViewById<View>(R.id.copyCoordinatesButton).isEnabled = lat != null && lon != null
        findViewById<View>(R.id.mockLocationButton).isEnabled = lat != null && lon != null
        val ready = hasLocationPermission() && isMockLocationAllowed()
        findViewById<View>(R.id.setupCard).isVisible = !ready
        findViewById<TextView>(R.id.setupDescription).setText(if (!hasLocationPermission()) R.string.setup_location else R.string.select_mock_app)
        locationMap.updateSelection()
        if (running) stopLocationUpdates() else if (resumed) startLocationUpdates()
    }

    private fun bindSwitch(view: CompoundButton, enabled: Boolean, action: (Boolean) -> Unit) {
        view.setOnCheckedChangeListener(null)
        view.isChecked = enabled
        view.setOnCheckedChangeListener { _, checked -> action(checked) }
    }

    private fun selectLocation(lat: Double, lon: Double) {
        if (!State.validLat(lat) || !State.validLon(lon)) return
        State.setLocation(this, lat, lon)
        render()
    }

    private fun showCoordinatesDialog() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        fun field(label: Int, value: Double?) = EditText(this).apply {
            hint = getString(label)
            contentDescription = getString(label)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            value?.let { setText(String.format(Locale.US, "%.6f", it)) }
            setSingleLine(true)
            fields.addView(this)
        }
        val latitude = field(R.string.latitude, State.getLat(this))
        val longitude = field(R.string.longitude, State.getLon(this))
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.edit_coordinates).setView(fields)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.use_location, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val lat = latitude.text.toString().toDoubleOrNull()
                val lon = longitude.text.toString().toDoubleOrNull()
                if (lat == null || !State.validLat(lat)) latitude.error = getString(R.string.invalid_latitude)
                else if (lon == null || !State.validLon(lon)) longitude.error = getString(R.string.invalid_longitude)
                else { selectLocation(lat, lon); locationMap.centerSelection(); dialog.dismiss() }
            }
        }
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun showCredential() {
        if (!State.isBroadcastEnabled(this)) { toast(R.string.enable_integration_first); return }
        val token = AutomationAccess.token(this) ?: run { toast(R.string.credential_unavailable); return }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.automation_credential)
            .setMessage(getString(R.string.credential_warning, token))
            .setPositiveButton(R.string.copy_credential) { _, _ ->
                copySensitive(getString(R.string.automation_credential), token)
                toast(R.string.credential_copied)
            }.setNegativeButton(android.R.string.cancel, null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun copySensitive(label: String, value: String) {
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun showDelayDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(ControlCenter.snapshot(this@MainActivity).delaySeconds.toString())
            contentDescription = getString(R.string.delay_description)
            selectAll()
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.automation_delay)
            .setMessage(R.string.delay_description).setView(input)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val seconds = input.text.toString().toLongOrNull()
                if (seconds == null || seconds !in 0L..3600L) input.error = getString(R.string.invalid_delay)
                else { ControlCenter.setDelay(this, seconds); render(); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun showDiagnostics() {
        val state = ControlCenter.snapshot(this)
        val diagnostic = ControlCenter.commandDiagnostic()
        val accepted = if (state.lastEventTime < 0) getString(R.string.no_accepted_event)
            else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(state.lastEventTime))
        MaterialAlertDialogBuilder(this).setTitle(R.string.diagnostics)
            .setMessage(getString(R.string.mode_status, state.mode.name, state.displayStatus(), state.readiness.name,
                accepted, state.result, diagnostic.lastAccepted, diagnostic.lastRejected))
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun setupPrerequisites() {
        if (!hasLocationPermission()) {
            val preferences = getSharedPreferences("ui_setup", MODE_PRIVATE)
            if (preferences.getBoolean("location_requested", false) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                return
            }
            preferences.edit().putBoolean("location_requested", true).apply()
            val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
            permissionLauncher.launch(permissions.toTypedArray())
        } else if (!isMockLocationAllowed()) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isMockLocationAllowed(): Boolean = getSystemService(AppOpsManager::class.java)
        .checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, applicationInfo.uid, packageName) == AppOpsManager.MODE_ALLOWED

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (observing || !resumed || State.isServiceRunning(this) || !hasLocationPermission()) return
        try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.takeIf(::isGenuine)?.let {
                locationMap.showGenuine(it.latitude, it.longitude)
            }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, locationListener, Looper.getMainLooper())
            observing = true
        } catch (_: SecurityException) {
            // Permission may change after the UI check.
        } catch (_: IllegalArgumentException) {
            // Some Android devices do not expose a GPS provider.
        }
    }

    private fun stopLocationUpdates() {
        observing = false
        try { locationManager.removeUpdates(locationListener) } catch (_: SecurityException) { }
    }

    private fun isGenuine(location: Location): Boolean {
        val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else {
            @Suppress("DEPRECATION") location.isFromMockProvider
        }
        return !mock && SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos in 0..60_000_000_000L &&
            State.validLat(location.latitude) && State.validLon(location.longitude)
    }

    private fun openLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        catch (_: android.content.ActivityNotFoundException) { toast(R.string.no_browser) }
    }
    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStart() { super.onStart(); locationMap.view.onStart() }
    override fun onResume() {
        super.onResume()
        locationMap.view.onResume()
        resumed = true
        ControlCenter.addListener(controlListener)
        if (!AutomationShortcuts.publish(this) && AutomationShortcuts.enabled(this)) toast(R.string.shortcut_refresh_failed)
        render()
    }
    override fun onPause() {
        resumed = false
        ControlCenter.removeListener(controlListener)
        stopLocationUpdates()
        locationMap.saveCamera()
        locationMap.clearGenuine()
        locationMap.view.onPause()
        super.onPause()
    }
    override fun onStop() { locationMap.view.onStop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { locationMap.view.onSaveInstanceState(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() { ControlCenter.removeListener(controlListener); stopLocationUpdates(); locationMap.view.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); locationMap.view.onLowMemory() }
}
