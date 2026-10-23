package dev.burixon.mockgps

import android.annotation.SuppressLint
import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.Build
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import androidx.core.view.GravityCompat
import dev.burixon.mockgps.core.OperatingMode
import dev.burixon.mockgps.core.ServiceState
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.camera.CameraPosition
import android.graphics.drawable.GradientDrawable
import android.widget.ImageButton
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.geojson.Point
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import android.widget.FrameLayout
import android.widget.Toast

class MainActivity : ComponentActivity() {

    private lateinit var versionText: TextView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var mapView: MapView

    private lateinit var menuButton: TextView
    private lateinit var coordinatesText: TextView
    private lateinit var copyCoordinatesButton: ImageButton
    private lateinit var toggleButton: Button
    private lateinit var mockLocationButton: ImageButton
    private lateinit var modeStatus: TextView
    private lateinit var autoButton: Button
    private lateinit var forceRealButton: Button
    private lateinit var confirmPendingButton: Button
    private var displayedGeneration = 0L
    private var resumed = false
    private var observingRealLocation = false
    private val controlListener: () -> Unit = {
        runOnUiThread { if (resumed) refreshFromState() }
    }
    private lateinit var broadcastSwitch: CompoundButton
    private lateinit var driftSwitch: CompoundButton

    private lateinit var scaleContainer: View
    private lateinit var scaleLine: View
    private lateinit var scaleLabel: TextView
    private lateinit var bottomPanel: View

    private var statusBarInsetTop = 0

    private var map: MapLibreMap? = null
    private val mapPrefs by lazy {
        getSharedPreferences(
            "map_camera",
            MODE_PRIVATE
        )
    }
    private var selectedLocationSource: GeoJsonSource? = null
    private var realLocationSource: GeoJsonSource? = null
    private var lastSelectedLat: Double? = null
    private var lastSelectedLon: Double? = null

    private val locationManager by lazy {
        getSystemService(
            LocationManager::class.java
        )
    }

    private val locationListener =
        object : LocationListener {

            override fun onLocationChanged(
                location: Location
            ) {

                if (
                    State.isServiceRunning(
                        this@MainActivity
                    )
                ) {
                    return
                }

                if (isGenuineLocation(location)) {
                    updateRealLocation(location.latitude, location.longitude)
                }
            }
        }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts
                .RequestMultiplePermissions()
        ) {
            refreshFromState()
            startRealLocationUpdates()
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        MapLibre.getInstance(
            this
        )

        setContentView(
            R.layout.activity_main
        )

        drawerLayout =
            findViewById(
                R.id.drawerLayout
            )

        drawerLayout.addDrawerListener(
            object : DrawerLayout.SimpleDrawerListener() {

                override fun onDrawerOpened(
                    drawerView: View
                ) {
                    drawerView.bringToFront()
                    drawerView.invalidate()
                }
            }
        )

        val mapContainer =
            findViewById<FrameLayout>(
                R.id.mapContainer
            )

        mapView =
            MapView(
                this,
                MapLibreMapOptions.createFromAttributes(this)
                    .textureMode(true)
            )

        mapContainer.addView(
            mapView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        menuButton =
            findViewById(
                R.id.menuButton
            )

        coordinatesText =
            findViewById(
                R.id.coordinatesText
            )

        copyCoordinatesButton =
            findViewById(
                R.id.copyCoordinatesButton
            )

        versionText =
            findViewById(
                R.id.versionText
            )

        versionText.text = getString(R.string.version_attribution, BuildConfig.VERSION_NAME)

        toggleButton =
            findViewById(
                R.id.toggleButton
            )

        mockLocationButton =
            findViewById(
                R.id.mockLocationButton
            )

        modeStatus = findViewById(R.id.modeStatus)
        autoButton = findViewById(R.id.autoButton)
        forceRealButton = findViewById(R.id.forceRealButton)
        confirmPendingButton = findViewById(R.id.confirmPendingButton)

        broadcastSwitch =
            findViewById(
                R.id.broadcastSwitch
            )

        driftSwitch =
            findViewById(
                R.id.driftSwitch
            )

        scaleContainer =
            findViewById(
                R.id.scaleContainer
            )

        scaleLine =
            findViewById(
                R.id.scaleLine
            )

        scaleLabel =
            findViewById(
                R.id.scaleLabel
            )

        bottomPanel =
            findViewById(
                R.id.bottomPanel
            )

        mapView.onCreate(
            savedInstanceState
        )

        setupInsets()
        setupUi()
        setupMap()

        requestPermissionsIfNeeded()

        refreshFromState()
    }

    private fun setupInsets() {

        val drawer =
            findViewById<View>(
                R.id.drawer
            )

        val drawerPaddingLeft = drawer.paddingLeft
        val drawerPaddingTop = drawer.paddingTop
        val drawerPaddingRight = drawer.paddingRight
        val drawerPaddingBottom = drawer.paddingBottom

        val bottomPanelPaddingLeft = bottomPanel.paddingLeft
        val bottomPanelPaddingTop = bottomPanel.paddingTop
        val bottomPanelPaddingRight = bottomPanel.paddingRight
        val bottomPanelPaddingBottom = bottomPanel.paddingBottom

        val safeInsetTypes =
            WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()

        ViewCompat.setOnApplyWindowInsetsListener(
            drawer
        ) { view, insets ->

            val safeInsets =
                insets.getInsets(
                    safeInsetTypes
                )

            statusBarInsetTop =
                safeInsets.top

            view.setPadding(
                maxOf(drawerPaddingLeft, safeInsets.left),
                maxOf(drawerPaddingTop, safeInsets.top),
                maxOf(drawerPaddingRight, safeInsets.right),
                maxOf(drawerPaddingBottom, safeInsets.bottom)
            )

            view.bringToFront()
            updateCompassPosition()

            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(
            bottomPanel
        ) { view, insets ->

            val safeInsets =
                insets.getInsets(
                    safeInsetTypes
                )

            view.setPadding(
                maxOf(bottomPanelPaddingLeft, safeInsets.left),
                bottomPanelPaddingTop,
                maxOf(bottomPanelPaddingRight, safeInsets.right),
                maxOf(bottomPanelPaddingBottom, safeInsets.bottom)
            )

            insets
        }
    }

    private fun centerOnMockLocation() {

        val loadedMap =
            map ?: return

        val lat =
            State.getLat(this)

        val lon =
            State.getLon(this)

        if (
            lat == null ||
            lon == null
        ) {
            return
        }

        val currentZoom =
            loadedMap.cameraPosition.zoom

        val targetZoom =
            currentZoom.coerceAtLeast(
                15.0
            )

        loadedMap.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(
                    lat,
                    lon
                ),
                targetZoom
            )
        )
    }

    private fun updateMockLocationButtonAppearance() {

        val color =
            if (
                State.isServiceRunning(this)
            ) {
                AndroidColor.rgb(
                    224,
                    72,
                    82
                )
            } else {
                AndroidColor.rgb(
                    90,
                    160,
                    235
                )
            }

        mockLocationButton.background =
            GradientDrawable().apply {
                shape =
                    GradientDrawable.OVAL

                setColor(color)

                setStroke(
                    1.dp,
                    AndroidColor.argb(
                        90,
                        255,
                        255,
                        255
                    )
                )
            }
    }

    private fun setupRealLocationLayer() {

        val loadedMap = map ?: return
        val style = loadedMap.style ?: return

        if (style.getSource("real-location-source") != null) {
            realLocationSource =
                style.getSource("real-location-source") as? GeoJsonSource
            return
        }

        val source =
            GeoJsonSource(
                "real-location-source"
            )

        style.addSource(source)

        val layer =
            CircleLayer(
                "real-location-layer",
                "real-location-source"
            ).withProperties(
                circleRadius(5f),
                circleColor(
                    AndroidColor.rgb(
                        135,
                        140,
                        148
                    )
                ),
                circleOpacity(1f),
                circleStrokeColor(
                    AndroidColor.WHITE
                ),
                circleStrokeWidth(1.5f)
            )

        style.addLayer(layer)

        realLocationSource = source
    }

    private fun updateCompassPosition() {

        val loadedMap =
            map ?: return

        loadedMap.uiSettings.setCompassMargins(
            8.dp,
            statusBarInsetTop + 8.dp,
            8.dp,
            8.dp
        )
    }

    private fun setupUi() {

        menuButton.setOnClickListener {
            drawerLayout.openDrawer(
                GravityCompat.START
            )
        }

        copyCoordinatesButton.setOnClickListener {

            val lat =
                State.getLat(this)

            val lon =
                State.getLon(this)

            if (
                lat == null ||
                lon == null
            ) {
                return@setOnClickListener
            }

            val coordinates =
                String.format(
                    Locale.US,
                    "%.6f, %.6f",
                    lat,
                    lon
                )

            val clipboard =
                getSystemService(
                    android.content.ClipboardManager::class.java
                )

            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText(
                    "MockGPS coordinates",
                    coordinates
                )
            )

            Toast.makeText(
                this,
                getString(R.string.coordinates_copied),
                Toast.LENGTH_SHORT
            ).show()
        }

        toggleButton.setOnClickListener {

            animateButtonPress()

            val current = ControlCenter.snapshot(this)
            if (current.desired || current.service == ServiceState.INJECTING || current.service == ServiceState.STARTING) {
                stopMocking()
            } else {
                startMocking()
            }
        }

        mockLocationButton.setOnClickListener {
            centerOnMockLocation()
        }

        autoButton.setOnClickListener { ControlCenter.selectAuto(this) }
        forceRealButton.setOnClickListener { stopMocking() }
        confirmPendingButton.setOnClickListener {
            if (hasLocationPermission() && isMockLocationAllowed()) {
                ControlCenter.confirmPending(this, displayedGeneration)
            } else {
                startPrerequisiteHelp()
            }
        }
        findViewById<Button>(R.id.showTokenButton).setOnClickListener { showAutomationCredential() }
        findViewById<Button>(R.id.revokeTokenButton).setOnClickListener {
            if (AutomationAccess.revoke(this)) {
                Toast.makeText(this, R.string.integration_revoked, Toast.LENGTH_LONG).show()
            }
            refreshFromState()
        }
        findViewById<Button>(R.id.delayButton).setOnClickListener { showDelayDialog() }
        broadcastSwitch.setOnCheckedChangeListener { _, checked ->
            ControlCenter.setIntegrationEnabled(this, checked)
        }

        driftSwitch.setOnCheckedChangeListener {
                _: CompoundButton,
                checked: Boolean ->

            State.setDrifting(
                this,
                checked
            )

            if (!checked) {
                refreshFromState()
            }
        }

        findViewById<TextView>(
            R.id.githubButton
        ).setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://github.com/abhi-k9/MockGPS/".toUri()
                )
            )
        }

        findViewById<TextView>(
            R.id.websiteButton
        ).setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://burixon.dev/MockGPS/".toUri()
                )
            )
        }

        findViewById<TextView>(
            R.id.donationsButton
        ).setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://buycoffee.to/burixon-code".toUri()
                )
            )
        }
    }

    private fun saveCameraPosition() {

        val loadedMap =
            map ?: return

        val position =
            loadedMap.cameraPosition

        val target =
            position.target ?: return

        mapPrefs.edit {
            putLong("lat", target.latitude.toBits())
            putLong("lon", target.longitude.toBits())
            putLong("zoom", position.zoom.toBits())
            putLong("bearing", position.bearing.toBits())
            putLong("tilt", position.tilt.toBits())
        }
    }

    private fun restoreCameraPosition(): Boolean {

        val loadedMap =
            map ?: return false

        if (
            !mapPrefs.contains("lat") ||
            !mapPrefs.contains("lon") ||
            !mapPrefs.contains("zoom")
        ) {
            return false
        }

        val lat =
            Double.fromBits(
                mapPrefs.getLong(
                    "lat",
                    0L
                )
            )

        val lon =
            Double.fromBits(
                mapPrefs.getLong(
                    "lon",
                    0L
                )
            )

        val zoom =
            Double.fromBits(
                mapPrefs.getLong(
                    "zoom",
                    10.0.toBits()
                )
            )

        val bearing =
            Double.fromBits(
                mapPrefs.getLong(
                    "bearing",
                    0.0.toBits()
                )
            )

        val tilt =
            Double.fromBits(
                mapPrefs.getLong(
                    "tilt",
                    0.0.toBits()
                )
            )

        loadedMap.cameraPosition =
            CameraPosition.Builder()
                .target(
                    LatLng(
                        lat,
                        lon
                    )
                )
                .zoom(zoom)
                .bearing(bearing)
                .tilt(tilt)
                .build()

        return true
    }

    private fun setupMap() {

        mapView.getMapAsync {
                loadedMap ->

            map = loadedMap

            loadedMap.uiSettings
                .isLogoEnabled = false

            loadedMap.uiSettings
                .isAttributionEnabled = true

            updateCompassPosition()

            loadedMap.setStyle(
                "https://tiles.openfreemap.org/styles/liberty"
            ) {
                setupRealLocationLayer()

                loadedMap.addOnMapClickListener {
                        point ->

                    selectLocation(
                        point.latitude,
                        point.longitude,
                        false
                    )

                    true
                }

                loadedMap.addOnCameraIdleListener {
                    updateScaleBar()
                    saveCameraPosition()
                }

                val restored =
                    restoreCameraPosition()

                showStoredLocations(
                    moveCamera = !restored
                )

                updateScaleBar()
                updateMockLocationButtonAppearance()
            }
        }
    }

    private fun refreshFromState() {

        val snapshot = ControlCenter.snapshot(this)
        displayedGeneration = snapshot.generation
        val running = snapshot.desired || snapshot.service == ServiceState.INJECTING ||
            snapshot.service == ServiceState.STARTING
        val acceptedAt = if (snapshot.lastEventTime < 0) getString(R.string.no_accepted_event)
            else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(snapshot.lastEventTime))
        modeStatus.text = getString(R.string.mode_status, snapshot.mode.name, snapshot.displayStatus(),
            snapshot.vehicle.name, acceptedAt, snapshot.result)
        autoButton.isEnabled = snapshot.mode != OperatingMode.AUTO
        forceRealButton.isEnabled = true // Retry cleanup even when FORCE_REAL is already selected.
        confirmPendingButton.visibility = if (snapshot.needsConfirmation) View.VISIBLE else View.GONE
        broadcastSwitch.setOnCheckedChangeListener(null)
        broadcastSwitch.isChecked = State.isBroadcastEnabled(this)
        broadcastSwitch.setOnCheckedChangeListener { _, checked ->
            ControlCenter.setIntegrationEnabled(this, checked)
        }
        findViewById<Button>(R.id.delayButton).text = getString(R.string.delay_seconds, snapshot.delaySeconds)
        if (snapshot.service == ServiceState.INJECTING || snapshot.service == ServiceState.STARTING) {
            stopRealLocationUpdates()
        } else if (resumed) {
            startRealLocationUpdates()
        }

        driftSwitch.setOnCheckedChangeListener(
            null
        )

        driftSwitch.isChecked =
            State.isDrifting(
                this
            )

        driftSwitch.setOnCheckedChangeListener {
                _: CompoundButton,
                checked: Boolean ->

            State.setDrifting(
                this,
                checked
            )
        }

        toggleButton.isEnabled = running || (snapshot.mode != OperatingMode.FORCE_REAL &&
            snapshot.vehicle != dev.burixon.mockgps.core.VehicleState.CONNECTED)
        toggleButton.text =
            if (snapshot.mode == OperatingMode.FORCE_REAL && !running) {
                getString(R.string.return_auto_before_manual)
            } else if (running) {
                getString(R.string.stop_and_force_real)
            } else if (snapshot.vehicle == dev.burixon.mockgps.core.VehicleState.CONNECTED) {
                getString(R.string.vehicle_connected_before_manual)
            } else {
                getString(R.string.manual_start)
            }

        toggleButton.setBackgroundResource(
            if (running) {
                R.drawable.button_stop
            } else {
                R.drawable.button_start
            }
        )

        updateMockLocationButtonAppearance()
        updateSelectedMarkerAppearance()

        val lat =
            State.getLat(this)

        val lon =
            State.getLon(this)

        if (
            lat != null &&
            lon != null
        ) {

            coordinatesText.text =
                String.format(
                    Locale.US,
                    "%.6f,%.6f",
                    lat,
                    lon
                )

            if (
                lat != lastSelectedLat ||
                lon != lastSelectedLon
            ) {

                lastSelectedLat = lat
                lastSelectedLon = lon

                updateSelectedMarker(
                    lat,
                    lon,
                    false
                )
            }
        }
    }

    private fun startPrerequisiteHelp() {
        if (!hasLocationPermission()) requestPermissionsIfNeeded()
        else if (!isMockLocationAllowed()) {
            Toast.makeText(this, R.string.select_mock_app, Toast.LENGTH_LONG).show()
            showDeveloperOptions()
        }
    }

    private fun startMocking() {
        if (!hasLocationPermission() || !isMockLocationAllowed()) {
            startPrerequisiteHelp()
            return
        }
        // FORCE_REAL can only be cleared by the explicit AUTO control.
        if (ControlCenter.snapshot(this).mode == OperatingMode.FORCE_REAL) return
        if (State.getLat(this) == null || State.getLon(this) == null) {
            Toast.makeText(this, R.string.select_location_first, Toast.LENGTH_LONG).show()
            return
        }
        stopRealLocationUpdates()
        ControlCenter.manualStart(this)
        refreshFromState()
    }

    private fun stopMocking() {
        ControlCenter.forceReal(this)
        refreshFromState()
    }

    private fun showAutomationCredential() {
        if (!State.isBroadcastEnabled(this)) {
            Toast.makeText(this, R.string.enable_integration_first, Toast.LENGTH_LONG).show()
            return
        }
        val token = AutomationAccess.token(this) ?: run {
            Toast.makeText(this, R.string.credential_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.automation_credential)
            .setMessage(getString(R.string.credential_warning, token))
            .setPositiveButton(R.string.copy_credential) { _, _ ->
                val clip = ClipData.newPlainText(getString(R.string.automation_credential), token)
                if (Build.VERSION.SDK_INT >= 33) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                Toast.makeText(this, R.string.credential_copied, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun showDelayDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(String.format(Locale.getDefault(), "%d", ControlCenter.snapshot(this@MainActivity).delaySeconds))
            contentDescription = getString(R.string.delay_description)
            selectAll()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.disconnect_delay)
            .setMessage(R.string.delay_description)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val seconds = input.text.toString().toLongOrNull()
                if (seconds == null || seconds !in 0L..3600L) {
                    input.error = getString(R.string.invalid_delay)
                } else {
                    ControlCenter.setDelay(this, seconds)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun selectLocation(
        lat: Double,
        lon: Double,
        moveCamera: Boolean
    ) {

        if (
            !State.validLat(lat) ||
            !State.validLon(lon)
        ) {
            return
        }

        State.setLocation(
            this,
            lat,
            lon
        )

        lastSelectedLat = lat
        lastSelectedLon = lon

        updateSelectedMarker(
            lat,
            lon,
            moveCamera
        )

        coordinatesText.text =
            String.format(
                Locale.US,
                "%.6f,%.6f",
                lat,
                lon
            )
    }

    private fun updateSelectedMarker(
        lat: Double,
        lon: Double,
        moveCamera: Boolean
    ) {

        val loadedMap =
            map ?: return

        val point =
            LatLng(
                lat,
                lon
            )

        val markerColor = if (State.isServiceRunning(this)) {
            AndroidColor.rgb(224, 72, 82)
        } else {
            AndroidColor.rgb(90, 160, 235)
        }
        val style = loadedMap.style ?: return
        val source = selectedLocationSource ?: GeoJsonSource("selected-location-source").also {
            style.addSource(it)
            style.addLayer(CircleLayer("selected-location-layer", "selected-location-source")
                .withProperties(circleRadius(9f), circleColor(markerColor),
                    circleStrokeColor(AndroidColor.WHITE), circleStrokeWidth(2f)))
            selectedLocationSource = it
        }
        source.setGeoJson(Point.fromLngLat(lon, lat))
        style.getLayerAs<CircleLayer>("selected-location-layer")?.setProperties(circleColor(markerColor))

        if (moveCamera) {

            loadedMap.animateCamera(
                CameraUpdateFactory
                    .newLatLngZoom(
                        point,
                        loadedMap.cameraPosition.zoom
                    )
            )
        }
    }

    private fun updateSelectedMarkerAppearance() {

        val lat =
            State.getLat(this)

        val lon =
            State.getLon(this)

        if (
            lat == null ||
            lon == null
        ) {
            return
        }

        updateSelectedMarker(
            lat,
            lon,
            false
        )
    }

    private fun showStoredLocations(
        moveCamera: Boolean = true
    ) {

        val selectedLat =
            State.getLat(this)

        val selectedLon =
            State.getLon(this)

        if (
            selectedLat != null &&
            selectedLon != null
        ) {

            lastSelectedLat =
                selectedLat

            lastSelectedLon =
                selectedLon

            updateSelectedMarker(
                selectedLat,
                selectedLon,
                moveCamera
            )
        }

        refreshRealLocation()
    }

    private fun updateRealLocation(
        lat: Double,
        lon: Double
    ) {

        if (
            !State.validLat(lat) ||
            !State.validLon(lon)
        ) {
            return
        }

        State.setRealLocation(
            this,
            lat,
            lon
        )

        updateRealMarker(
            lat,
            lon
        )
    }

    private fun refreshRealLocation() {

        val lat =
            State.getRealLat(this)

        val lon =
            State.getRealLon(this)

        if (
            lat == null ||
            lon == null
        ) {
            return
        }

        updateRealMarker(
            lat,
            lon
        )
    }

    private fun updateRealMarker(
        lat: Double,
        lon: Double
    ) {

        val loadedMap =
            map ?: return

        val style =
            loadedMap.style
                ?: return

        if (
            realLocationSource == null
        ) {
            setupRealLocationLayer()
        }

        val source =
            realLocationSource
                ?: style.getSource(
                    "real-location-source"
                ) as? GeoJsonSource
                ?: return

        source.setGeoJson(
            Point.fromLngLat(
                lon,
                lat
            )
        )

        realLocationSource =
            source
    }

    @SuppressLint("MissingPermission")
    private fun captureRealLocation() {

        if (!hasLocationPermission()) {
            return
        }

        var best: Location? = null

        try {
            best = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?.takeIf(::isGenuineLocation)
        } catch (_: SecurityException) {
            return
        } catch (_: Exception) {
        }

        try {

            val network =
                locationManager.getLastKnownLocation(
                    LocationManager.NETWORK_PROVIDER
                )

            if (
                network != null && isGenuineLocation(network) &&
                (
                        best == null ||
                                network.time > best.time
                        )
            ) {
                best = network
            }

        } catch (_: SecurityException) {
            // Permission can change while the Activity is running.
        } catch (_: Exception) {
        }

        best?.takeIf(::isGenuineLocation)?.let {
            updateRealLocation(it.latitude, it.longitude)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRealLocationUpdates() {

        if (observingRealLocation || !resumed || State.isServiceRunning(this) || !hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000L,
                2f,
                locationListener,
                Looper.getMainLooper()
            )
            observingRealLocation = true

        } catch (_: SecurityException) {
            // Permission may have been revoked after our check.
        } catch (_: Exception) {
        }
    }

    private fun stopRealLocationUpdates() {
        observingRealLocation = false
        try {
            locationManager.removeUpdates(
                locationListener
            )
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun requestPermissionsIfNeeded() {

        val permissions =
            mutableListOf(
                Manifest.permission
                    .ACCESS_FINE_LOCATION,

                Manifest.permission
                    .ACCESS_COARSE_LOCATION
            )

        if (
            android.os.Build.VERSION.SDK_INT >= 33
        ) {
            permissions.add(
                Manifest.permission
                    .POST_NOTIFICATIONS
            )
        }

        val missing =
            permissions.filter {
                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }

        if (
            missing.isNotEmpty()
        ) {
            permissionLauncher.launch(
                missing.toTypedArray()
            )
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isMockLocationAllowed():
            Boolean {

        val appOps =
            getSystemService(
                AppOpsManager::class.java
            )

        val mode =
            appOps.checkOpNoThrow(
                AppOpsManager
                    .OPSTR_MOCK_LOCATION,
                applicationInfo.uid,
                packageName
            )

        return mode ==
                AppOpsManager.MODE_ALLOWED
    }

    private fun showDeveloperOptions() {

        try {

            startActivity(
                Intent(
                    Settings
                        .ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                )
            )

        } catch (_: Exception) {

            startActivity(
                Intent(
                    Settings.ACTION_SETTINGS
                )
            )
        }
    }

    private fun updateScaleBar() {

        val loadedMap =
            map ?: return

        val zoom =
            loadedMap
                .cameraPosition
                .zoom

        val target =
            loadedMap
                .cameraPosition
                .target
                ?: return

        val latitude =
            target.latitude

        val metersPerPixel =
            156543.03392 *
                    cos(
                        Math.toRadians(
                            latitude
                        )
                    ) /
                    2.0.pow(zoom)

        val maxWidthPx =
            scaleContainer.width *
                    0.85

        if (
            maxWidthPx <= 0.0
        ) {
            return
        }

        val maxDistance =
            metersPerPixel *
                    maxWidthPx

        val exponent =
            floor(
                log10(
                    maxDistance
                )
            )

        val base =
            10.0.pow(
                exponent
            )

        val normalized =
            maxDistance /
                    base

        val niceDistance =
            when {

                normalized >= 5.0 ->
                    5.0 * base

                normalized >= 2.0 ->
                    2.0 * base

                else ->
                    base
            }

        val barWidthPx =
            (
                    niceDistance /
                            metersPerPixel
                    )
                .roundToInt()

        scaleLine.layoutParams =
            scaleLine.layoutParams.apply {

                width =
                    barWidthPx
                        .coerceAtLeast(
                            24.dp
                        )
            }

        scaleLine.requestLayout()

        scaleLabel.text =
            when {

                niceDistance >= 1000.0 ->

                    if (
                        niceDistance %
                        1000.0 == 0.0
                    ) {

                        "${(
                                niceDistance /
                                        1000.0
                                ).roundToInt()} km"

                    } else {

                        String.format(
                            Locale.US,
                            "%.1f km",
                            niceDistance /
                                    1000.0
                        )
                    }

                else ->
                    "${niceDistance.roundToInt()} m"
            }
    }

    private fun isGenuineLocation(location: Location): Boolean {
        val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
        val ageNanos = android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return !mock && ageNanos in 0..60_000_000_000L &&
            State.validLat(location.latitude) && State.validLon(location.longitude)
    }

    private fun animateButtonPress() {

        toggleButton.animate()
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(70L)
            .setInterpolator(
                AccelerateDecelerateInterpolator()
            )
            .withEndAction {

                toggleButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(130L)
                    .start()
            }
            .start()
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()

        mapView.onResume()

        resumed = true
        ControlCenter.addListener(controlListener)
        refreshFromState()
        captureRealLocation()
        startRealLocationUpdates()
    }

    override fun onPause() {

        saveCameraPosition()

        stopRealLocationUpdates()

        resumed = false
        ControlCenter.removeListener(controlListener)

        mapView.onPause()

        super.onPause()
    }

    override fun onStop() {

        mapView.onStop()

        super.onStop()
    }

    override fun onSaveInstanceState(
        outState: Bundle
    ) {

        mapView.onSaveInstanceState(
            outState
        )

        super.onSaveInstanceState(
            outState
        )
    }

    override fun onDestroy() {

        ControlCenter.removeListener(controlListener)

        stopRealLocationUpdates()

        mapView.onDestroy()

        super.onDestroy()
    }

    override fun onLowMemory() {

        super.onLowMemory()

        mapView.onLowMemory()
    }

    private val Int.dp: Int
        get() =
            (
                    this *
                            resources
                                .displayMetrics
                                .density
                    )
                .roundToInt()
}
