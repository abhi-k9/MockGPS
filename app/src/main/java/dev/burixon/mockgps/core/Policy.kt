package dev.burixon.mockgps.core

enum class OperatingMode { AUTO, FORCE_REAL, MANUAL_MOCK }
enum class ReadinessState { UNKNOWN, BLOCKED, READY }
enum class ServiceState { UNKNOWN, STOPPED, STARTING, INJECTING, ERROR }
enum class ProviderState { UNKNOWN, NONE, READY }
enum class InjectionState { UNKNOWN, NONE, CONFIRMED }

/** Configuration, intention and observations must never be represented by one Boolean. */
data class PolicySnapshot(
    val mode: OperatingMode = OperatingMode.FORCE_REAL,
    val desired: Boolean = false,
    val service: ServiceState = ServiceState.UNKNOWN,
    val providers: ProviderState = ProviderState.UNKNOWN,
    val injection: InjectionState = InjectionState.UNKNOWN,
    val result: String = "RECOVERY_REQUIRED",
    val generation: Long = 0,
    val pendingAtElapsed: Long? = null,
    val readiness: ReadinessState = ReadinessState.UNKNOWN,
    val lastEventTime: Long = -1,
    val lastSequence: Long = -1,
    val delaySeconds: Long = 120,
    val unattendedApproval: Boolean = false
) {
    val needsConfirmation: Boolean get() = result.startsWith("CONFIRMATION_REQUIRED")
    fun displayStatus(): String = when {
        result == "CLEANUP_UNCERTAIN" -> "Cleanup uncertain — open app for recovery"
        service == ServiceState.ERROR -> "Error: $result"
        service == ServiceState.INJECTING && injection == InjectionState.CONFIRMED -> "Injecting simulated location"
        service == ServiceState.STARTING -> "Starting — injection not yet confirmed"
        pendingAtElapsed != null -> "Waiting for automation delay"
        result == "CONFIRMATION_REQUIRED_UNATTENDED_UNAVAILABLE" -> "Unattended automation is not armed — open app to continue"
        needsConfirmation -> "Open app and confirm simulation"
        service == ServiceState.UNKNOWN -> "Previous provider state unverified"
        readiness == ReadinessState.UNKNOWN && mode == OperatingMode.AUTO -> "Stopped; waiting for automation readiness"
        else -> "Injection stopped; genuine fix not verified"
    }
}

sealed interface PolicyEvent {
    data class SelectMode(val mode: OperatingMode) : PolicyEvent
    data class Readiness(val state: ReadinessState, val eventTime: Long, val sequence: Long = eventTime) : PolicyEvent
    data class SourceReadiness(val state: ReadinessState) : PolicyEvent
    data class Deadline(val generation: Long) : PolicyEvent
    data class Confirm(val generation: Long) : PolicyEvent
    data class UnattendedApproval(val enabled: Boolean) : PolicyEvent
    data class AutomationUnavailable(val generation: Long) : PolicyEvent
    data object ManualStart : PolicyEvent
    data class Delay(val seconds: Long) : PolicyEvent
    data object Recover : PolicyEvent
}

/** Pure serialized policy; Android effects occur only after the resulting state is persisted. */
object Policy {
    fun reduce(s: PolicySnapshot, e: PolicyEvent, nowElapsed: Long): PolicySnapshot = when (e) {
        is PolicyEvent.SelectMode -> if (e.mode == s.mode || (s.mode == OperatingMode.FORCE_REAL && e.mode != OperatingMode.AUTO)) s else s.copy(
            mode = e.mode, desired = false, generation = s.generation + 1,
            pendingAtElapsed = null, readiness = ReadinessState.UNKNOWN, result = "MODE_SELECTED"
        )
        is PolicyEvent.Readiness -> {
            if (e.sequence < 0 || e.sequence <= s.lastSequence ||
                (e.state == ReadinessState.READY && (e.eventTime < s.lastEventTime ||
                    (e.eventTime == s.lastEventTime && s.readiness != ReadinessState.READY)))) s else {
                val next = s.copy(lastEventTime = maxOf(s.lastEventTime, e.eventTime), lastSequence = e.sequence, readiness = e.state)
                readiness(next, s, e.state, nowElapsed)
            }
        }
        is PolicyEvent.SourceReadiness -> readiness(s.copy(readiness = e.state), s, e.state, nowElapsed)
        is PolicyEvent.Deadline -> if (
            e.generation == s.generation && s.mode == OperatingMode.AUTO &&
            s.readiness == ReadinessState.READY && s.pendingAtElapsed != null && nowElapsed >= s.pendingAtElapsed
        ) {
            if (s.unattendedApproval) s.copy(pendingAtElapsed = null, desired = true,
                service = ServiceState.STARTING, result = "STARTING")
            else s.copy(pendingAtElapsed = null, result = "CONFIRMATION_REQUIRED")
        } else s
        is PolicyEvent.Confirm -> if (e.generation == s.generation && s.mode == OperatingMode.AUTO &&
            s.readiness == ReadinessState.READY && s.needsConfirmation
        ) s.copy(desired = true, service = ServiceState.STARTING, result = "STARTING") else s
        is PolicyEvent.UnattendedApproval -> if (e.enabled == s.unattendedApproval) s else s.copy(
            unattendedApproval = e.enabled, desired = false, generation = s.generation + 1,
            mode = if (s.mode == OperatingMode.FORCE_REAL) s.mode else OperatingMode.AUTO,
            pendingAtElapsed = null, readiness = ReadinessState.UNKNOWN, result = "APPROVAL_CHANGED"
        )
        is PolicyEvent.AutomationUnavailable -> if (e.generation == s.generation &&
            s.mode == OperatingMode.AUTO && s.unattendedApproval && s.desired &&
            s.readiness == ReadinessState.READY && s.service == ServiceState.STARTING
        ) s.copy(desired = false, service = ServiceState.STOPPED,
            result = "CONFIRMATION_REQUIRED_UNATTENDED_UNAVAILABLE") else s
        PolicyEvent.ManualStart -> if (s.mode == OperatingMode.FORCE_REAL || s.readiness == ReadinessState.BLOCKED ||
            (s.mode == OperatingMode.MANUAL_MOCK && s.desired)) s else s.copy(
            mode = OperatingMode.MANUAL_MOCK, desired = true, service = ServiceState.STARTING,
            generation = s.generation + 1, pendingAtElapsed = null, result = "STARTING"
        )
        is PolicyEvent.Delay -> {
            require(e.seconds in 0..86400)
            s.copy(delaySeconds = e.seconds, desired = false, generation = s.generation + 1,
                pendingAtElapsed = null, readiness = ReadinessState.UNKNOWN, result = "RECONCILE_REQUIRED")
        }
        PolicyEvent.Recover -> s.copy(
            desired = false, service = ServiceState.UNKNOWN, providers = ProviderState.UNKNOWN,
            injection = InjectionState.UNKNOWN, generation = s.generation + 1,
            pendingAtElapsed = null, readiness = ReadinessState.UNKNOWN, result = "RECOVERY_REQUIRED"
        )
    }
    private fun readiness(next: PolicySnapshot, old: PolicySnapshot, state: ReadinessState, now: Long): PolicySnapshot =
        if (state != ReadinessState.READY) next.copy(
            mode = if (old.mode == OperatingMode.FORCE_REAL) old.mode else OperatingMode.AUTO,
            desired = false, generation = old.generation + 1, pendingAtElapsed = null, result = "READINESS_RECONCILED")
        else if (old.mode != OperatingMode.AUTO || (state == old.readiness &&
            !(old.unattendedApproval && !old.desired && old.pendingAtElapsed == null))) next
        else next.copy(desired = false, generation = old.generation + 1,
            pendingAtElapsed = now + old.delaySeconds * 1000, result = "PENDING")

}
