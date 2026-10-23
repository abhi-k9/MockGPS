package dev.burixon.mockgps

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import dev.burixon.mockgps.core.*

/** All entry points/effects execute on the main looper in this one application process. */
object ControlCenter {
    private var state: PolicySnapshot? = null
    private val listeners = linkedSetOf<() -> Unit>()
    private var notifying = false
    private var handlingStorageFailure = false
    private var durableLastSequence = -1L
    private const val REQUEST_ID = 2001
    private const val NOTIFICATION_ID = 2002
    private const val CHANNEL = "automation_requests"

    private fun checkThread() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun ensure(context: Context): PolicySnapshot {
        checkThread()
        state?.let { return it }
        val loaded = runCatching { PolicyStore(context).load() }.getOrDefault(PolicySnapshot())
        durableLastSequence = loaded.lastSequence
        val recovered = Policy.reduce(loaded, PolicyEvent.Recover, SystemClock.elapsedRealtime())
        state = recovered
        val migrationSucceeded = runCatching { State.migrate(context) }.isSuccess
        cancelPending(context)
        // Framework test providers can outlive a killed app. Cleanup only this app's journal.
        val clean = MockLocationService.stopNow(context) && !State.legacyProviderStateUnverified(context)
        val reconciled = recovered.copy(service = if (clean && migrationSucceeded) ServiceState.STOPPED else ServiceState.ERROR,
            providers = if (clean) ProviderState.NONE else ProviderState.UNKNOWN,
            injection = if (clean) InjectionState.NONE else InjectionState.UNKNOWN,
            result = if (!migrationSucceeded) "STORAGE_UNAVAILABLE" else if (clean) "RECOVERED_INACTIVE" else "CLEANUP_UNCERTAIN")
        save(context, reconciled)
        return requireNotNull(state)
    }
    fun snapshot(context: Context): PolicySnapshot = ensure(context)
    fun addListener(listener: () -> Unit) { checkThread(); listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { checkThread(); listeners.remove(listener) }
    fun changed(context: Context) {
        ensure(context)
        if (notifying) return
        notifying = true
        try {
            listeners.toList().forEach { it() }
            TileService.requestListeningState(context, ComponentName(context, ModeTileService::class.java))
        } finally { notifying = false }
    }
    private fun save(context: Context, s: PolicySnapshot): Boolean {
        try {
            PolicyStore(context).save(s)
        } catch (_: RuntimeException) {
            // Safety stops must still work when private storage is full/unavailable.
            // No subsequent start is permitted from an unpersisted transition.
            state = s.copy(desired = false, service = ServiceState.ERROR,
                providers = ProviderState.UNKNOWN, injection = InjectionState.UNKNOWN,
                generation = s.generation + 1, pendingAtElapsed = null, result = "STORAGE_UNAVAILABLE")
            cancelPending(context)
            if (!handlingStorageFailure) {
                handlingStorageFailure = true
                try { MockLocationService.stopNow(context) } finally { handlingStorageFailure = false }
            }
            changed(context)
            return false
        }
        durableLastSequence = s.lastSequence
        state = s
        changed(context)
        return true
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun stopAndReport(context: Context, next: PolicySnapshot) {
        cancelPending(context)
        save(context, next.copy(desired = false)) // invalidate generation before any asynchronous service callback
        val clean = MockLocationService.stopNow(context) && !State.legacyProviderStateUnverified(context)
        val current = ensure(context)
        save(context, current.copy(service = if (clean) ServiceState.STOPPED else ServiceState.ERROR,
            providers = if (clean) ProviderState.NONE else ProviderState.UNKNOWN,
            injection = if (clean) InjectionState.NONE else InjectionState.UNKNOWN,
            result = if (clean) next.result else "CLEANUP_UNCERTAIN"))
    }
    fun forceReal(context: Context) {
        val s = ensure(context)
        stopAndReport(context, s.copy(mode = OperatingMode.FORCE_REAL, generation = s.generation + 1,
            pendingAtElapsed = null, result = "STOPPED"))
    }
    fun selectAuto(context: Context) {
        val s = ensure(context)
        stopAndReport(context, s.copy(mode = OperatingMode.AUTO, generation = s.generation + 1,
            vehicle = VehicleState.UNKNOWN, pendingAtElapsed = null, result = "RECONCILE_REQUIRED"))
    }
    fun setDelay(context: Context, seconds: Long) {
        require(seconds in 0..86400)
        stopAndReport(context, Policy.reduce(ensure(context), PolicyEvent.Delay(seconds), now()))
    }
    fun setIntegrationEnabled(context: Context, enabled: Boolean): Boolean {
        // A no-op must not reset replay protection for an unchanged credential.
        val s = ensure(context)
        if (AutomationAccess.isEnabled(context) == enabled) return true
        // Stop first: a failed credential write must not keep a previously active simulation running.
        stopAndReport(context, s.copy(generation = s.generation + 1, pendingAtElapsed = null,
            vehicle = VehicleState.UNKNOWN, result = "RECONCILE_REQUIRED"))
        try {
            AutomationAccess.setEnabled(context, enabled)
        } catch (_: RuntimeException) {
            save(context, ensure(context).copy(service = ServiceState.ERROR, result = "CREDENTIAL_STORAGE_FAILED"))
            return false
        }
        return save(context, ensure(context).copy(lastSequence = -1, lastEventTime = -1))
    }
    fun manualStart(activity: Activity) {
        val old = ensure(activity)
        val next = Policy.reduce(old, PolicyEvent.ManualStart, now())
        if (next != old && next.desired) begin(activity, next)
    }
    fun confirmPending(activity: Activity, generation: Long) {
        val old = ensure(activity)
        val next = Policy.reduce(old, PolicyEvent.Confirm(generation), now())
        if (next != old && next.desired) begin(activity, next)
    }
    private fun begin(activity: Activity, next: PolicySnapshot) {
        cancelPending(activity)
        // The only caller is an explicit visible Activity button; no broadcast/tile trampoline auto-start.
        if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
            save(activity, next.copy(desired = false, service = ServiceState.ERROR, result = "VISIBLE_CONFIRMATION_REQUIRED")); return
        }
        if (State.getLat(activity) == null || State.getLon(activity) == null) {
            save(activity, next.copy(desired = false, service = ServiceState.ERROR, result = "SELECT_LOCATION")); return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            save(activity, next.copy(desired = false, service = ServiceState.ERROR, result = "LOCATION_PERMISSION_REQUIRED")); return
        }
        val clean = MockLocationService.stopNow(activity)
        if (!clean) {
            save(activity, next.copy(desired = false, service = ServiceState.ERROR,
                providers = ProviderState.UNKNOWN, injection = InjectionState.UNKNOWN, result = "CLEANUP_UNCERTAIN")); return
        }
        if (!save(activity, next.copy(service = ServiceState.STARTING, providers = ProviderState.NONE, injection = InjectionState.NONE))) return
        try {
            ContextCompat.startForegroundService(activity, Intent(activity, MockLocationService::class.java)
                .putExtra(MockLocationService.EXTRA_GENERATION, next.generation))
        } catch (_: SecurityException) {
            serviceFailed(activity, next.generation, "START_PERMISSION_DENIED", false)
        } catch (_: IllegalStateException) {
            serviceFailed(activity, next.generation, "BACKGROUND_START_REJECTED", false)
        }
    }
    fun canRun(context: Context, generation: Long): Boolean {
        val s = ensure(context)
        return s.desired && s.generation == generation && s.mode != OperatingMode.FORCE_REAL &&
            (s.mode == OperatingMode.MANUAL_MOCK && s.vehicle != VehicleState.CONNECTED ||
                s.mode == OperatingMode.AUTO && s.vehicle == VehicleState.DISCONNECTED)
    }
    fun serviceStarted(context: Context, generation: Long) {
        if (!canRun(context, generation)) return
        save(context, ensure(context).copy(service = ServiceState.STARTING, result = "STARTING"))
    }
    fun serviceInjected(context: Context, generation: Long) {
        if (!canRun(context, generation)) return
        val s = ensure(context)
        if (s.injection == InjectionState.CONFIRMED) return
        State.clearLegacyUncertainty(context) // both named providers now belong to this journaled session
        save(context, s.copy(service = ServiceState.INJECTING, providers = ProviderState.READY,
            injection = InjectionState.CONFIRMED, result = "INJECTING"))
    }
    fun serviceFailed(context: Context, generation: Long, code: String, cleanupUncertain: Boolean) {
        val s = ensure(context)
        if (generation != s.generation) return
        cancelPending(context)
        val uncertain = cleanupUncertain || State.legacyProviderStateUnverified(context)
        save(context, s.copy(desired = false, service = ServiceState.ERROR,
            providers = if (uncertain) ProviderState.UNKNOWN else ProviderState.NONE,
            injection = if (uncertain) InjectionState.UNKNOWN else InjectionState.NONE,
            pendingAtElapsed = null, result = if (uncertain) "CLEANUP_UNCERTAIN" else code))
    }
    fun serviceStopped(context: Context, generation: Long, cleanupUncertain: Boolean) {
        val s = ensure(context)
        if (generation != s.generation) return
        val uncertain = cleanupUncertain || State.legacyProviderStateUnverified(context)
        save(context, s.copy(desired = false, service = if (uncertain) ServiceState.ERROR else ServiceState.STOPPED,
            providers = if (uncertain) ProviderState.UNKNOWN else ProviderState.NONE,
            injection = if (uncertain) InjectionState.UNKNOWN else InjectionState.NONE,
            result = if (uncertain) "CLEANUP_UNCERTAIN" else "STOPPED"))
    }
    fun external(context: Context, request: ExternalRequest): AuthResult {
        val s = ensure(context)
        val auth = CommandAuth.authorize(AutomationAccess.isEnabled(context), AutomationAccess.token(context), request,
            System.currentTimeMillis(), s.lastSequence)
        if (!auth.accepted) return auth
        val consumed = s.copy(lastEventTime = request.eventTime, lastSequence = request.sequence)
        when (request.command) {
            "stop" -> stopAndReport(context, consumed.copy(mode = OperatingMode.FORCE_REAL,
                generation = s.generation + 1, pendingAtElapsed = null, result = "STOPPED"))
            "status" -> return auth // Read-only: never consumes a vehicle/control ordering sequence.
            else -> {
                val vehicle = when (request.command) {
                    "vehicle_connected" -> VehicleState.CONNECTED
                    "vehicle_disconnected" -> VehicleState.DISCONNECTED
                    else -> VehicleState.UNKNOWN
                }
                val next = Policy.reduce(s, PolicyEvent.Vehicle(vehicle, request.eventTime, request.sequence), now())
                if (next.generation != s.generation) {
                    stopAndReport(context, next)
                    if (next.pendingAtElapsed != null && ensure(context).generation == next.generation) schedule(context, next)
                } else save(context, next)
            }
        }
        return if (durableLastSequence >= request.sequence) auth else AuthResult(false, "STORAGE_UNAVAILABLE")
    }
    private fun alarmIntent(context: Context, generation: Long = 0) = PendingIntent.getBroadcast(context, REQUEST_ID,
        Intent(context, DelayReceiver::class.java).putExtra("generation", generation),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun cancelPending(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
    private fun schedule(context: Context, s: PolicySnapshot) {
        try {
            context.getSystemService(AlarmManager::class.java).set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                s.pendingAtElapsed!!, alarmIntent(context, s.generation))
        } catch (_: RuntimeException) {
            save(context, ensure(context).copy(pendingAtElapsed = null, service = ServiceState.ERROR, result = "SCHEDULING_FAILED"))
        }
    }
    fun deadline(context: Context, generation: Long) {
        val s = ensure(context)
        val next = Policy.reduce(s, PolicyEvent.Deadline(generation), now())
        if (next == s) return
        if (!save(context, next)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Automation requests", NotificationManager.IMPORTANCE_DEFAULT))
        if (!manager.areNotificationsEnabled()) {
            save(context, next.copy(result = "CONFIRMATION_REQUIRED_NOTIFICATIONS_BLOCKED")); return
        }
        val open = PendingIntent.getActivity(context, REQUEST_ID, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_mock_location).setContentTitle("Simulation requested")
                .setContentText("Open MockGPS to review vehicle state and confirm.")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build())
        } catch (_: SecurityException) {
            save(context, next.copy(result = "CONFIRMATION_REQUIRED_NOTIFICATIONS_BLOCKED"))
        }
    }
    fun reboot(context: Context) {
        val s = ensure(context)
        State.clearLegacyUncertainty(context) // framework test providers do not survive a device reboot
        stopAndReport(context, Policy.reduce(s, PolicyEvent.Recover, now()).copy(result = "REBOOT_INACTIVE"))
    }
}
