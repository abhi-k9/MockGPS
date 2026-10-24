package dev.burixon.mockgps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import dev.burixon.mockgps.engine.AndroidProviderPort
import dev.burixon.mockgps.engine.Coordinates
import dev.burixon.mockgps.engine.EngineResult
import dev.burixon.mockgps.engine.MockFix
import dev.burixon.mockgps.engine.ProviderEngine
import kotlin.random.Random

/** Internal non-sticky service. Only visible, explicit user actions may request a start. */
class MockLocationService : Service() {
    companion object {
        const val EXTRA_GENERATION = "generation"
        private const val NOTIFICATION_ID = 1001
        private const val FAILURE_NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "mockgps"
        private const val UPDATE_INTERVAL = 1_000L
        private const val DRIFT_INTERVAL = 2_500L
        private var live: MockLocationService? = null

        /** Main-thread only. Works after process death using the durable ownership journal. */
        fun stopNow(context: Context): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper())
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
    private var lastCleanup: EngineResult? = null
    private var foreground = false
    private var firstInjection = true
    private var driftPoint: Coordinates? = null
    private var driftBase: Coordinates? = null
    private var nextDriftAt = 0L

    private val publisher = object : Runnable {
        override fun run() {
            try {
                publishTick()
            } catch (_: Exception) {
                // Unexpected state/notification failures must still remove providers before returning.
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
            val point = currentPoint(base)
            val result = engine.publish(MockFix(point, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos()))
            if (!result.success) {
                fail(result.code, result)
                return
            }
            if (firstInjection) {
                firstInjection = false
                ControlCenter.serviceInjected(this@MockLocationService, generation)
                updateNotification("Simulation active", "Android accepted both test-provider updates.")
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
                description = "Developer mock-location service status"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedGeneration = intent?.getLongExtra(EXTRA_GENERATION, -1L) ?: -1L
        if (requestedGeneration < 0 || !ControlCenter.canRun(this, requestedGeneration)) {
            // A null/redelivered/stale intent cannot reactivate injection or change the selected mode.
            if (generation < 0 || !ControlCenter.canRun(this, generation)) shutdown(report = true)
            return START_NOT_STICKY
        }
        if (generation == requestedGeneration && !stopping) return START_NOT_STICKY
        handler.removeCallbacks(publisher)
        if (generation >= 0) {
            val previousCleanup = engine.stop()
            if (!previousCleanup.success) {
                generation = requestedGeneration
                fail("RECOVERY_CLEANUP_FAILED", previousCleanup)
                return START_NOT_STICKY
            }
        }
        generation = requestedGeneration
        live = this
        stopping = false
        lastCleanup = null
        firstInjection = true
        driftPoint = null
        driftBase = null
        nextDriftAt = 0L
        try {
            startForeground(
                NOTIFICATION_ID, notification("Starting simulation", "Provider readiness is not yet confirmed."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            foreground = true
        } catch (_: SecurityException) {
            fail("LOCATION_FOREGROUND_PERMISSION_DENIED")
            return START_NOT_STICKY
        } catch (_: RuntimeException) {
            fail("FOREGROUND_START_REJECTED")
            return START_NOT_STICKY
        }
        val latitude = State.getLat(this)
        val longitude = State.getLon(this)
        if (latitude == null || longitude == null) {
            fail("NO_LOCATION_SELECTED")
            return START_NOT_STICKY
        }
        val result = engine.start(Coordinates(latitude, longitude))
        if (!result.success) {
            fail(result.code, result)
            return START_NOT_STICKY
        }
        try {
            ControlCenter.serviceStarted(this, generation)
            publisher.run()
        } catch (_: Exception) {
            fail("SIMULATION_START_FAILED")
        }
        return START_NOT_STICKY
    }

    private fun currentPoint(base: Coordinates): Coordinates {
        if (!State.isDrifting(this)) {
            driftPoint = null
            driftBase = null
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

    private fun fail(code: String, knownCleanup: EngineResult? = null) {
        val cleanup = shutdown(report = false)
        val uncertain = !cleanup.success || knownCleanup?.cleanupUncertain == true
        ControlCenter.serviceFailed(this, generation, code, uncertain)
        showFailureNotification(uncertain)
    }

    private fun shutdown(report: Boolean): EngineResult {
        if (stopping) return lastCleanup ?: EngineResult(false, "CLEANUP_IN_PROGRESS", true)
        stopping = true
        handler.removeCallbacksAndMessages(null)
        val result = engine.stop()
        lastCleanup = result
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        driftPoint = null
        driftBase = null
        if (live === this) live = null
        if (report && generation >= 0) ControlCenter.serviceStopped(this, generation, !result.success)
        stopSelf()
        return result
    }

    private fun notification(title: String, text: String, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mock_location)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, text))
        } catch (_: SecurityException) {
            // Notification permission is independent of successful provider injection.
        }
    }

    private fun showFailureNotification(uncertain: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(FAILURE_NOTIFICATION_ID, notification(
                if (uncertain) "Provider cleanup uncertain" else "Simulation stopped",
                "Open MockGPS for the result and recovery instructions.", ongoing = false
            ))
        } catch (_: SecurityException) {
            // The actionable result remains in the app when notifications are unavailable.
        }
    }

    override fun onDestroy() {
        if (live != null && live !== this) {
            // A newer service instance owns the journal; an obsolete callback must not remove its providers.
            handler.removeCallbacksAndMessages(null)
            super.onDestroy()
            return
        }
        val wasStopping = stopping
        val result = shutdown(report = false)
        if (!wasStopping && generation >= 0) ControlCenter.serviceStopped(this, generation, !result.success)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
