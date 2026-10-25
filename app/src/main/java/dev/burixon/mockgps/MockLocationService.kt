package dev.burixon.mockgps

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.burixon.mockgps.core.OperatingMode
import dev.burixon.mockgps.core.ServiceState
import dev.burixon.mockgps.engine.AndroidProviderPort
import dev.burixon.mockgps.engine.Coordinates
import dev.burixon.mockgps.engine.EngineResult
import dev.burixon.mockgps.engine.MockFix
import dev.burixon.mockgps.engine.ProviderEngine
import kotlin.random.Random

/** Non-sticky foreground session. Arming is visible; unattended starts reuse that session. */
class MockLocationService : Service() {
    companion object {
        const val EXTRA_GENERATION = "generation"
        private const val ACTION_ARM = "dev.abhik.mockgps.ARM_AUTOMATION"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mockgps"
        private const val UPDATE_INTERVAL = 1_000L
        private const val DRIFT_INTERVAL = 2_500L
        private var live: MockLocationService? = null

        private fun checkThread() = check(Looper.myLooper() == Looper.getMainLooper())

        /** A pending start intent or a running idle process is not proof of foreground admission. */
        fun isAutomationArmed(): Boolean {
            checkThread()
            val current = live ?: return false
            return current.armed && current.foreground && !current.stopping && !current.destroying &&
                AutomationSettings.unattendedEnabled(current) && current.hasLocationPermission()
        }

        /** Requests ordinary service admission while visible; promotion completes asynchronously. */
        fun arm(activity: Activity): Boolean {
            checkThread()
            if (!AutomationSettings.unattendedEnabled(activity)) return false
            if (isAutomationArmed()) return true
            if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) return false
            return try {
                activity.startService(Intent(activity, MockLocationService::class.java).setAction(ACTION_ARM))
                true
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        }

        /** Never launches a service from a receiver/alarm. False means no admitted session handled it. */
        fun startApprovedAutomation(context: Context, generation: Long): Boolean {
            checkThread()
            if (!isAutomationArmed() || !ControlCenter.canRun(context, generation)) return false
            val current = live ?: return false
            current.startInjection(generation)
            // Provider failures are reported by startInjection with their precise failure code.
            return true
        }

        /** Cleanup preserves only an explicitly armed, still-consented foreground session. */
        fun stopNow(context: Context): Boolean {
            checkThread()
            val current = live
            if (current != null) return current.shutdown(report = true).success
            val result = ProviderEngine(AndroidProviderPort(context.applicationContext)).stop()
            context.stopService(Intent(context, MockLocationService::class.java))
            return result.success
        }

        fun hasOwnedProviders(context: Context): Boolean =
            AndroidProviderPort(context.applicationContext).ownedProviders().isNotEmpty()
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var engine: ProviderEngine
    private var generation = -1L
    private var stopping = false
    private var destroying = false
    private var armed = false
    private var lastCleanup: EngineResult? = null
    private var foreground = false
    private var firstInjection = true
    private var driftPoint: Coordinates? = null
    private var driftBase: Coordinates? = null
    private var nextDriftAt = 0L
    private val statusListener: () -> Unit = {
        if (foreground && !stopping && !updateNotification(sessionNotification())) {
            fail("FOREGROUND_NOTIFICATION_FAILED", keepArmed = false)
        }
    }

    private val publisher = object : Runnable {
        override fun run() {
            try {
                publishTick()
            } catch (_: Exception) {
                fail("SIMULATION_UPDATE_FAILED")
            }
        }

        private fun publishTick() {
            if (stopping) return
            if (!ControlCenter.canRun(this@MockLocationService, generation)) {
                shutdown(report = true)
                return
            }
            val latitude = State.getLat(this@MockLocationService)
            val longitude = State.getLon(this@MockLocationService)
            if (latitude == null || longitude == null) {
                fail("NO_LOCATION_SELECTED")
                return
            }
            val base = Coordinates(latitude, longitude)
            if (!base.valid) {
                fail("INVALID_COORDINATES")
                return
            }
            val result = engine.publish(MockFix(currentPoint(base), System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos()))
            if (!result.success) {
                fail(result.code, result)
                return
            }
            if (firstInjection) {
                firstInjection = false
                ControlCenter.serviceInjected(this@MockLocationService, generation)
                // A state listener may stop the session after a failed mandatory status update.
                if (stopping || !foreground || generation < 0 || !ControlCenter.canRun(this@MockLocationService, generation)) return
            }
            handler.postDelayed(this, UPDATE_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = ProviderEngine(AndroidProviderPort(this))
        live = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MockGPS simulation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Simulation and unattended automation session status"
            }
        )
        ControlCenter.addListener(statusListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ARM) {
            if (AutomationSettings.unattendedEnabled(this)) {
                stopping = false
                lastCleanup = null
                live = this
                if (promoteForeground(sessionNotification())) {
                    armed = true
                    ControlCenter.automationArmingChanged(this)
                }
            } else if (generation < 0 || !ControlCenter.canRun(this, generation)) {
                shutdown(report = true, keepArmed = false)
            }
            return START_NOT_STICKY
        }
        val requestedGeneration = intent?.getLongExtra(EXTRA_GENERATION, -1L) ?: -1L
        if (requestedGeneration < 0 || !ControlCenter.canRun(this, requestedGeneration)) {
            // Null, redelivered and stale intents cannot create or reactivate a session.
            if (generation < 0 || !ControlCenter.canRun(this, generation)) shutdown(report = true)
            return START_NOT_STICKY
        }
        startInjection(requestedGeneration)
        return START_NOT_STICKY
    }

    private fun startInjection(requestedGeneration: Long) {
        if (!ControlCenter.canRun(this, requestedGeneration)) return
        if (generation == requestedGeneration && !stopping) return
        handler.removeCallbacks(publisher)
        if (generation >= 0) {
            val previousCleanup = engine.stop()
            if (!previousCleanup.success) {
                generation = requestedGeneration
                fail("RECOVERY_CLEANUP_FAILED", previousCleanup)
                return
            }
        }
        generation = requestedGeneration
        live = this
        stopping = false
        lastCleanup = null
        firstInjection = true
        clearDrift()
        if (!promoteForeground(notification("Starting simulation", "Provider readiness is not yet confirmed."))) return
        // An explicit visible manual start may retain its admitted foreground session too.
        if (AutomationSettings.unattendedEnabled(this)) armed = true
        val latitude = State.getLat(this)
        val longitude = State.getLon(this)
        if (latitude == null || longitude == null) {
            fail("NO_LOCATION_SELECTED")
            return
        }
        val result = engine.start(Coordinates(latitude, longitude))
        if (!result.success) {
            fail(result.code, result)
            return
        }
        try {
            ControlCenter.serviceStarted(this, generation)
            publisher.run()
        } catch (_: Exception) {
            fail("SIMULATION_START_FAILED")
        }
    }

    private fun promoteForeground(status: Notification): Boolean {
        if (!hasLocationPermission()) {
            armed = false
            fail("LOCATION_PERMISSION_REQUIRED", keepArmed = false)
            return false
        }
        if (foreground) {
            if (updateNotification(status)) return true
            armed = false
            fail("FOREGROUND_NOTIFICATION_FAILED", keepArmed = false)
            return false
        }
        return try {
            startForeground(NOTIFICATION_ID, status, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            foreground = true
            true
        } catch (_: SecurityException) {
            armed = false
            fail("LOCATION_FOREGROUND_PERMISSION_DENIED", keepArmed = false)
            false
        } catch (_: RuntimeException) {
            armed = false
            fail("FOREGROUND_START_REJECTED", keepArmed = false)
            false
        }
    }

    private fun currentPoint(base: Coordinates): Coordinates {
        if (!State.isDrifting(this)) {
            clearDrift()
            return base
        }
        val now = SystemClock.elapsedRealtime()
        if (driftBase != base || driftPoint == null || now >= nextDriftAt) {
            driftPoint = base.offset(Random.nextDouble(2.0, 15.0), Random.nextDouble(0.0, Math.PI * 2.0))
            driftBase = base
            nextDriftAt = now + DRIFT_INTERVAL
        }
        return driftPoint ?: base
    }

    private fun clearDrift() {
        driftPoint = null
        driftBase = null
        nextDriftAt = 0L
    }

    private fun fail(code: String, knownCleanup: EngineResult? = null, keepArmed: Boolean = true) {
        val failedGeneration = generation.takeIf { it >= 0 } ?: ControlCenter.snapshot(this).generation
        val cleanup = shutdown(report = false, keepArmed = keepArmed)
        val uncertain = !cleanup.success || knownCleanup?.cleanupUncertain == true
        ControlCenter.serviceFailed(this, failedGeneration, code, uncertain)
        if (foreground && armed) updateNotification(sessionNotification())
        ControlCenter.automationArmingChanged(this)
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun shutdown(report: Boolean, keepArmed: Boolean = true): EngineResult {
        if (stopping) return lastCleanup ?: EngineResult(false, "CLEANUP_IN_PROGRESS", true)
        stopping = true
        handler.removeCallbacksAndMessages(null)
        val stoppedGeneration = generation
        val result = engine.stop()
        lastCleanup = result
        generation = -1L
        clearDrift()
        if (report && stoppedGeneration >= 0) ControlCenter.serviceStopped(this, stoppedGeneration, !result.success)
        if (keepArmed && armed && foreground && !destroying && AutomationSettings.unattendedEnabled(this) && hasLocationPermission()) {
            // No provider/injection activity remains. Only foreground admission is retained.
            stopping = false
            if (!updateNotification(sessionNotification())) releaseSession()
        } else {
            releaseSession()
        }
        return result
    }

    private fun releaseSession() {
        armed = false
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        if (live === this) live = null
        stopSelf()
        ControlCenter.automationArmingChanged(this)
    }

    private fun sessionNotification(): Notification {
        val state = ControlCenter.snapshot(this)
        return when {
            generation >= 0 && state.desired && state.service == ServiceState.INJECTING ->
                notification("Simulation active", "Android accepted both test-provider updates.")
            generation >= 0 && state.desired && state.service == ServiceState.STARTING ->
                notification("Starting simulation", "Provider readiness is not yet confirmed.")
            state.service == ServiceState.ERROR || lastCleanup?.cleanupUncertain == true -> notification("Automation needs attention", "Open MockGPS for the result and recovery instructions.")
            generation >= 0 && !state.desired ->
                notification("Stopping simulation", "Provider cleanup is in progress and has not yet been verified.")
            state.mode == OperatingMode.FORCE_REAL -> notification("Automation paused", "Force real blocks simulation. No mock location is being injected.")
            else -> notification("Automation ready", "Waiting for an eligible request. No mock location is being injected.")
        }
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getBroadcast(
            this, 1001, Intent(this, AutomationStopReceiver::class.java).setAction("dev.abhik.mockgps.FORCE_REAL"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mock_location)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Force real", stop).build())
            .build()
    }

    private fun updateNotification(status: Notification): Boolean = try {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, status)
        true
    } catch (_: SecurityException) {
        // POST_NOTIFICATIONS can be denied while foreground admission remains valid.
        true
    } catch (_: RuntimeException) {
        false
    }

    override fun onDestroy() {
        destroying = true
        ControlCenter.removeListener(statusListener)
        if (live != null && live !== this) {
            // A newer instance owns the journal; an obsolete callback must not remove its providers.
            handler.removeCallbacksAndMessages(null)
            super.onDestroy()
            return
        }
        val destroyedGeneration = generation
        val wasStopping = stopping
        val result = shutdown(report = false, keepArmed = false)
        if (!wasStopping && destroyedGeneration >= 0) ControlCenter.serviceStopped(this, destroyedGeneration, !result.success)
        ControlCenter.automationArmingChanged(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
