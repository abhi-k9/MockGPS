package dev.burixon.mockgps.core

enum class OperatingMode { AUTO, FORCE_REAL, MANUAL_MOCK }
enum class VehicleState { UNKNOWN, CONNECTED, DISCONNECTED }
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
    val vehicle: VehicleState = VehicleState.UNKNOWN,
    val lastEventTime: Long = -1,
    val lastSequence: Long = -1,
    val delaySeconds: Long = 120
) {
    val needsConfirmation: Boolean get() = result.startsWith("CONFIRMATION_REQUIRED")
    fun displayStatus(): String = when {
        result == "CLEANUP_UNCERTAIN" -> "Cleanup uncertain — open app for recovery"
        service == ServiceState.ERROR -> "Error: $result"
        service == ServiceState.INJECTING && injection == InjectionState.CONFIRMED -> "Injecting simulated location"
        service == ServiceState.STARTING -> "Starting — injection not yet confirmed"
        pendingAtElapsed != null -> "Waiting after vehicle disconnect"
        needsConfirmation -> "Open app and confirm simulation"
        service == ServiceState.UNKNOWN -> "Previous provider state unverified"
        vehicle == VehicleState.UNKNOWN && mode == OperatingMode.AUTO -> "Stopped; waiting for vehicle reconciliation"
        else -> "Injection stopped; genuine fix not verified"
    }
}

sealed interface PolicyEvent {
    data class SelectMode(val mode: OperatingMode) : PolicyEvent
    data class Vehicle(val state: VehicleState, val eventTime: Long, val sequence: Long = eventTime) : PolicyEvent
    data class Deadline(val generation: Long) : PolicyEvent
    data class Confirm(val generation: Long) : PolicyEvent
    data object ManualStart : PolicyEvent
    data class Delay(val seconds: Long) : PolicyEvent
    data object Recover : PolicyEvent
}

/** Pure serialized policy; Android effects occur only after the resulting state is persisted. */
object Policy {
    fun reduce(s: PolicySnapshot, e: PolicyEvent, nowElapsed: Long): PolicySnapshot = when (e) {
        is PolicyEvent.SelectMode -> if (e.mode == s.mode || (s.mode == OperatingMode.FORCE_REAL && e.mode != OperatingMode.AUTO)) s else s.copy(
            mode = e.mode, desired = false, generation = s.generation + 1,
            pendingAtElapsed = null, vehicle = VehicleState.UNKNOWN, result = "MODE_SELECTED"
        )
        is PolicyEvent.Vehicle -> {
            if (e.sequence < 0 || e.sequence <= s.lastSequence ||
                (e.state == VehicleState.DISCONNECTED && (e.eventTime < s.lastEventTime ||
                    (e.eventTime == s.lastEventTime && s.vehicle != VehicleState.DISCONNECTED)))) s else {
                val next = s.copy(lastEventTime = maxOf(s.lastEventTime, e.eventTime), lastSequence = e.sequence, vehicle = e.state)
                // A fresh safety observation ends even an explicitly started manual session.
                // Return to AUTO, never FORCE_REAL, so a later disconnect can request a new
                // delayed confirmation without resurrecting the terminated manual session.
                if (e.state != VehicleState.DISCONNECTED) next.copy(
                    mode = if (s.mode == OperatingMode.FORCE_REAL) s.mode else OperatingMode.AUTO,
                    desired = false, generation = s.generation + 1,
                    pendingAtElapsed = null, result = "VEHICLE_RECONCILED"
                )
                else if (s.mode != OperatingMode.AUTO) next
                else if (e.state == s.vehicle) next // duplicate snapshots do not postpone an existing delay
                else next.copy(
                    desired = false, generation = s.generation + 1,
                    pendingAtElapsed = if (e.state == VehicleState.DISCONNECTED) nowElapsed + s.delaySeconds * 1000 else null,
                    result = if (e.state == VehicleState.DISCONNECTED) "PENDING" else "VEHICLE_RECONCILED"
                )
            }
        }
        is PolicyEvent.Deadline -> if (
            e.generation == s.generation && s.mode == OperatingMode.AUTO &&
            s.vehicle == VehicleState.DISCONNECTED && s.pendingAtElapsed != null && nowElapsed >= s.pendingAtElapsed
        ) s.copy(pendingAtElapsed = null, result = "CONFIRMATION_REQUIRED") else s
        is PolicyEvent.Confirm -> if (e.generation == s.generation && s.mode == OperatingMode.AUTO &&
            s.vehicle == VehicleState.DISCONNECTED && s.needsConfirmation
        ) s.copy(desired = true, service = ServiceState.STARTING, result = "STARTING") else s
        PolicyEvent.ManualStart -> if (s.mode == OperatingMode.FORCE_REAL || s.vehicle == VehicleState.CONNECTED ||
            (s.mode == OperatingMode.MANUAL_MOCK && s.desired)) s else s.copy(
            mode = OperatingMode.MANUAL_MOCK, desired = true, service = ServiceState.STARTING,
            generation = s.generation + 1, pendingAtElapsed = null, result = "STARTING"
        )
        is PolicyEvent.Delay -> {
            require(e.seconds in 0..86400)
            s.copy(delaySeconds = e.seconds, desired = false, generation = s.generation + 1,
                pendingAtElapsed = null, vehicle = VehicleState.UNKNOWN, result = "RECONCILE_REQUIRED")
        }
        PolicyEvent.Recover -> s.copy(
            desired = false, service = ServiceState.UNKNOWN, providers = ProviderState.UNKNOWN,
            injection = InjectionState.UNKNOWN, generation = s.generation + 1,
            pendingAtElapsed = null, vehicle = VehicleState.UNKNOWN, result = "RECOVERY_REQUIRED"
        )
    }
}
