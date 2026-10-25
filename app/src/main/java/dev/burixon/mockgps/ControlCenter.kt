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
    private var sources = SourceAgreement()
    private var shortcutReview: Long? = null
    private var diagnostic = CommandDiagnostic()
    fun commandDiagnostic(): CommandDiagnostic { checkThread(); return diagnostic }
    fun rejected(context: Context, code: String) {
        checkThread()
        val next = diagnostic.rejected(code)
        if (next != diagnostic) { diagnostic = next; changed(context) }
    }
    private fun accepted(context: Context, command: String) {
        diagnostic = diagnostic.accepted(command)
        changed(context)
    }
    private val listeners = linkedSetOf<() -> Unit>()
    private var notifying = false
    private var handlingStorageFailure = false
    private var armingRequested = false
    private var durableLastSequence = -1L
    private const val REQUEST_ID = 2001
    private const val NOTIFICATION_ID = 2002
    private const val CHANNEL = "automation_requests"

    private fun checkThread() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun ensure(context: Context): PolicySnapshot {
        checkThread()
        state?.let { return it }
        val loaded = runCatching { PolicyStore(context).load() }.getOrDefault(PolicySnapshot())
            .copy(unattendedApproval = AutomationSettings.unattendedEnabled(context))
        sources = SourceAgreement(AutomationAccess.isEnabled(context), AutomationShortcuts.enabled(context))
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
        val previous = state
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
        runCatching { AutomationAlerts.onTransition(context, previous, s) }
        changed(context)
        return true
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun stopAndReport(context: Context, next: PolicySnapshot): Boolean {
        shortcutReview = null
        cancelPending(context)
        val invalidated = save(context, next.copy(desired = false)) // invalidate generation before any asynchronous service callback
        val clean = MockLocationService.stopNow(context) && !State.legacyProviderStateUnverified(context)
        val current = ensure(context)
        val persisted = save(context, current.copy(service = if (clean) ServiceState.STOPPED else ServiceState.ERROR,
            providers = if (clean) ProviderState.NONE else ProviderState.UNKNOWN,
            injection = if (clean) InjectionState.NONE else InjectionState.UNKNOWN,
            result = if (clean) next.result else "CLEANUP_UNCERTAIN"))
        return invalidated && clean && persisted
    }
    private fun stopAndSchedule(context: Context, next: PolicySnapshot): Boolean = PendingStart.commit(
        next,
        persistAndStop = { stopAndReport(context, it) },
        current = { ensure(context) },
        schedule = { schedule(context, it) },
    )
    fun forceReal(context: Context) {
        val s = ensure(context)
        stopAndReport(context, s.copy(mode = OperatingMode.FORCE_REAL, generation = s.generation + 1,
            pendingAtElapsed = null, result = "STOPPED"))
    }
    fun selectAuto(context: Context) {
        val s = ensure(context)
        if (sources.shortcutsEnabled) sources = SourceAgreement(sources.externalEnabled, true)
        stopAndReport(context, s.copy(mode = OperatingMode.AUTO, generation = s.generation + 1,
            readiness = ReadinessState.UNKNOWN, pendingAtElapsed = null, result = "RECONCILE_REQUIRED"))
    }
    fun setDelay(context: Context, seconds: Long) {
        ensure(context)
        if (sources.shortcutsEnabled) sources = SourceAgreement(sources.externalEnabled, true)
        require(seconds in 0..86400)
        stopAndReport(context, Policy.reduce(ensure(context), PolicyEvent.Delay(seconds), now()))
    }
    fun setIntegrationEnabled(context: Context, enabled: Boolean): Boolean {
        // A no-op must not reset replay protection for an unchanged credential.
        val s = ensure(context)
        if (AutomationAccess.isEnabled(context) == enabled) return true
        // Stop first: a failed credential write must not keep a previously active simulation running.
        stopAndReport(context, s.copy(generation = s.generation + 1, pendingAtElapsed = null,
            readiness = ReadinessState.UNKNOWN, result = "RECONCILE_REQUIRED"))
        try {
            AutomationAccess.setEnabled(context, enabled)
        } catch (_: RuntimeException) {
            save(context, ensure(context).copy(service = ServiceState.ERROR, result = "CREDENTIAL_STORAGE_FAILED"))
            return false
        }
        sources = SourceAgreement(enabled, AutomationShortcuts.enabled(context))
        return save(context, ensure(context).copy(lastSequence = -1, lastEventTime = -1))
    }
    /** This opt-in can be changed only through the local visible settings screen. */
    fun setUnattendedApproval(activity: Activity, enabled: Boolean): Boolean {
        val s = ensure(activity)
        if (s.unattendedApproval == enabled && AutomationSettings.unattendedEnabled(activity) == enabled) return true
        sources = SourceAgreement(AutomationAccess.isEnabled(activity), AutomationShortcuts.enabled(activity))
        stopAndReport(activity, s.copy(generation = s.generation + 1, pendingAtElapsed = null,
            readiness = ReadinessState.UNKNOWN, result = "RECONCILE_REQUIRED"))
        try {
            AutomationShortcuts.invalidateAuthority(activity)
            AutomationSettings.setUnattendedEnabled(activity, enabled)
        } catch (_: RuntimeException) {
            // Fail closed even if a preference commit changed memory before failing.
            runCatching { AutomationSettings.setUnattendedEnabled(activity, false) }
            save(activity, ensure(activity).copy(unattendedApproval = false, desired = false,
                service = ServiceState.ERROR, result = "APPROVAL_STORAGE_FAILED"))
            MockLocationService.stopNow(activity)
            armingRequested = false
            return false
        }
        val current = ensure(activity)
        val next = Policy.reduce(current, PolicyEvent.UnattendedApproval(enabled), now()).copy(
            // An observation queued before consent changed cannot approve a new start.
            lastEventTime = maxOf(current.lastEventTime, System.currentTimeMillis()))
        if (!save(activity, next)) return false
        if (!enabled) {
            armingRequested = false
            val clean = MockLocationService.stopNow(activity)
            if (!clean) save(activity, ensure(activity).copy(service = ServiceState.ERROR, result = "CLEANUP_UNCERTAIN"))
        }
        AutomationShortcuts.publish(activity)
        if (enabled) armUnattendedIfEnabled(activity)
        return true
    }

    /** Never called by alarms, external broadcasts or application initialization. */
    fun armUnattendedIfEnabled(activity: Activity) {
        val s = ensure(activity)
        if (!s.unattendedApproval || !AutomationSettings.unattendedEnabled(activity) ||
            armingRequested || MockLocationService.isAutomationArmed() || activity.isFinishing ||
            activity.isDestroyed || !activity.hasWindowFocus()) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        armingRequested = true
        if (!MockLocationService.arm(activity)) {
            armingRequested = false
            changed(activity)
        }
    }

    fun automationArmingChanged(context: Context) {
        checkThread()
        armingRequested = false
        changed(context)
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
            // This path already requires a focused visible Activity. Use its ordinary start
            // admission, then promote in onStartCommand before touching any providers.
            // startForegroundService creates a mandatory promotion obligation: Android can
            // crash the process if a safety event cancels it before onStartCommand arrives.
            // An ordinary visible start can be canceled without promoting a stale session.
            activity.startService(Intent(activity, MockLocationService::class.java)
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
            (s.mode == OperatingMode.MANUAL_MOCK && s.readiness != ReadinessState.BLOCKED ||
                s.mode == OperatingMode.AUTO && s.readiness == ReadinessState.READY)
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
            System.currentTimeMillis(), s.lastSequence, s.lastEventTime, if (sources.shortcutsEnabled) sources.external else s.readiness)
        if (!auth.accepted) { rejected(context, auth.code); return auth }
        val consumed = s.copy(lastEventTime = maxOf(s.lastEventTime, request.eventTime), lastSequence = request.sequence)
        when (val command = requireNotNull(request.automationCommand)) {
            AutomationCommand.STOP -> stopAndReport(context, consumed.copy(mode = OperatingMode.FORCE_REAL,
                generation = s.generation + 1, pendingAtElapsed = null, result = "STOPPED"))
            AutomationCommand.STATUS -> { accepted(context, command.wireName); return auth } // Read-only policy; diagnostic only.
            else -> {
                val readiness = requireNotNull(command.readinessState)
                sources = sources.external(readiness)
                val next = if (!sources.shortcutsEnabled) Policy.reduce(s, PolicyEvent.Readiness(readiness, request.eventTime, request.sequence), now())
                    else Policy.reduce(s, PolicyEvent.SourceReadiness(sources.readiness), now()).copy(
                        lastSequence = request.sequence, lastEventTime = maxOf(s.lastEventTime, request.eventTime))
                if (next.generation != s.generation) {
                    stopAndSchedule(context, next)
                } else save(context, next)
            }
        }
        return if (durableLastSequence >= request.sequence) {
            accepted(context, requireNotNull(request.command)); auth
        } else {
            rejected(context, "STORAGE_UNAVAILABLE"); AuthResult(false, "STORAGE_UNAVAILABLE")
        }
    }
    fun setShortcutsEnabled(context: Context, enabled: Boolean) {
        ensure(context)
        if (AutomationShortcuts.enabled(context) == enabled) return
        sources = SourceAgreement(AutomationAccess.isEnabled(context), enabled)
        stopAndReport(context, Policy.reduce(ensure(context), PolicyEvent.SourceReadiness(ReadinessState.UNKNOWN), now()))
        try { AutomationShortcuts.setEnabled(context, enabled) } catch (_: RuntimeException) {
            sources = SourceAgreement(AutomationAccess.isEnabled(context), AutomationShortcuts.enabled(context))
            rejected(context, "SHORTCUT_STORAGE_FAILED")
        }
    }
    fun shortcutCommand(context: Context, command: String) {
        val action = AutomationCommand.fromShortcut(command) ?: return
        val s = ensure(context)
        if (!AutomationShortcuts.enabled(context)) return
        if (action == AutomationCommand.STATUS) return
        if (action == AutomationCommand.STOP) { forceReal(context); return }
        // Shortcut requests have no observation timestamp; local review is the default unless explicitly waived.
        sources = if (action == AutomationCommand.REQUEST_SIMULATION) sources.simulationRequest(s.unattendedApproval)
            else sources.shortcuts(requireNotNull(action.readinessState))
        val next = Policy.reduce(s, PolicyEvent.SourceReadiness(sources.readiness), now())
        val persisted = stopAndSchedule(context, next)
        if (action == AutomationCommand.REQUEST_SIMULATION && !s.unattendedApproval && persisted &&
            ensure(context).generation == next.generation && ensure(context).mode == OperatingMode.AUTO) {
            shortcutReview = ensure(context).generation
            changed(context)
        }
    }
    fun shortcutReviewPending(context: Context): Boolean = shortcutReview == ensure(context).generation
    fun reviewShortcutRequest(activity: Activity, generation: Long) {
        val s = ensure(activity)
        if (!activity.hasWindowFocus() || generation != shortcutReview || generation != s.generation ||
            !AutomationShortcuts.enabled(activity) || s.mode != OperatingMode.AUTO) return
        shortcutReview = null
        sources = sources.shortcuts(ReadinessState.READY)
        val next = Policy.reduce(s, PolicyEvent.SourceReadiness(sources.readiness), now())
        stopAndSchedule(activity, next)
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
        if (next.desired && next.unattendedApproval) {
            beginUnattended(context, next)
            return
        }
        if (!save(context, next)) return
        showConfirmation(context, next)
    }

    private fun beginUnattended(context: Context, next: PolicySnapshot) {
        if (!AutomationSettings.unattendedEnabled(context)) {
            save(context, next.copy(unattendedApproval = false, desired = false, service = ServiceState.ERROR,
                result = "APPROVAL_STORAGE_FAILED"))
            return
        }
        if (State.getLat(context) == null || State.getLon(context) == null) {
            save(context, next.copy(desired = false, service = ServiceState.ERROR, result = "SELECT_LOCATION"))
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            save(context, next.copy(desired = false, service = ServiceState.ERROR, result = "LOCATION_PERMISSION_REQUIRED"))
            return
        }
        if (!save(context, next)) return
        // No component is launched from the alarm. Only an already-armed FGS may inject.
        if (!MockLocationService.startApprovedAutomation(context, next.generation)) {
            val fallback = Policy.reduce(ensure(context), PolicyEvent.AutomationUnavailable(next.generation), now())
            if (save(context, fallback)) AutomationAlerts.unavailable(context)
        }
    }

    private fun showConfirmation(context: Context, next: PolicySnapshot) {
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
                .setContentText("Open MockGPS to review readiness state and confirm.")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build())
        } catch (_: SecurityException) {
            save(context, next.copy(result = "CONFIRMATION_REQUIRED_NOTIFICATIONS_BLOCKED"))
        }
    }
    fun reboot(context: Context) {
        val s = ensure(context)
        sources = SourceAgreement(AutomationAccess.isEnabled(context), AutomationShortcuts.enabled(context))
        State.clearLegacyUncertainty(context) // framework test providers do not survive a device reboot
        stopAndReport(context, Policy.reduce(s, PolicyEvent.Recover, now()).copy(result = "REBOOT_INACTIVE"))
    }
}
