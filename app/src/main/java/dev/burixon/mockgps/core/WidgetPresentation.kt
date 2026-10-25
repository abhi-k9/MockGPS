package dev.burixon.mockgps.core

enum class WidgetStatus { ACTIVE, STARTING, ERROR, UNVERIFIED, CONFIRMATION, WAITING, TEMPORARY_STOP, KEEP_STOPPED, STOPPED }
enum class WidgetDeadline { OVERRIDE, SESSION, REQUEST }

/** A last-reported snapshot, never a claim that a cached launcher view is live telemetry. */
data class WidgetPresentation(val status: WidgetStatus, val deadline: Long?, val deadlineKind: WidgetDeadline?) {
    companion object {
        fun from(s: PolicySnapshot): WidgetPresentation {
            val status = when {
                s.service == ServiceState.ERROR -> WidgetStatus.ERROR
                s.service == ServiceState.UNKNOWN -> WidgetStatus.UNVERIFIED
                s.mode != OperatingMode.FORCE_REAL && s.desired && s.service == ServiceState.INJECTING && s.providers == ProviderState.READY &&
                    s.injection == InjectionState.CONFIRMED -> WidgetStatus.ACTIVE
                s.desired && s.mode != OperatingMode.FORCE_REAL -> WidgetStatus.STARTING
                s.mode == OperatingMode.FORCE_REAL -> WidgetStatus.KEEP_STOPPED
                s.temporary?.kind == OverrideKind.STOP -> WidgetStatus.TEMPORARY_STOP
                s.needsConfirmation -> WidgetStatus.CONFIRMATION
                s.pendingAtElapsed != null -> WidgetStatus.WAITING
                else -> WidgetStatus.STOPPED
            }
            val deadline = listOfNotNull(s.temporary?.endsAtElapsed?.let { it to WidgetDeadline.OVERRIDE },
                s.sessionEndsAtElapsed?.let { it to WidgetDeadline.SESSION },
                s.pendingAtElapsed?.let { it to WidgetDeadline.REQUEST }).minByOrNull { it.first }
            return WidgetPresentation(status, deadline?.first, deadline?.second)
        }
    }
}
