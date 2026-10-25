package dev.burixon.mockgps.core

import org.junit.Assert.*
import org.junit.Test

class WidgetPresentationTest {
    private fun active() = PolicySnapshot(mode = OperatingMode.AUTO, desired = true,
        service = ServiceState.INJECTING, providers = ProviderState.READY, injection = InjectionState.CONFIRMED)

    @Test fun activeRequiresBothProviderAndInjectionObservations() {
        assertEquals(WidgetStatus.ACTIVE, WidgetPresentation.from(active()).status)
        assertEquals(WidgetStatus.STARTING, WidgetPresentation.from(active().copy(providers = ProviderState.UNKNOWN)).status)
        assertEquals(WidgetStatus.STARTING, WidgetPresentation.from(active().copy(injection = InjectionState.UNKNOWN)).status)
        assertEquals(WidgetStatus.STOPPED, WidgetPresentation.from(active().copy(desired = false)).status)
    }
    @Test fun safetyAndErrorsTakePriorityOverOldInjectionObservations() {
        assertEquals(WidgetStatus.KEEP_STOPPED, WidgetPresentation.from(active().copy(mode = OperatingMode.FORCE_REAL)).status)
        assertEquals(WidgetStatus.ERROR, WidgetPresentation.from(active().copy(service = ServiceState.ERROR)).status)
        assertEquals(WidgetStatus.UNVERIFIED, WidgetPresentation.from(active().copy(service = ServiceState.UNKNOWN)).status)
    }
    @Test fun pendingAndConfirmationDoNotClaimActiveSimulation() {
        val waiting = PolicySnapshot(mode = OperatingMode.AUTO, service = ServiceState.STOPPED, pendingAtElapsed = 50_000)
        assertEquals(WidgetStatus.WAITING, WidgetPresentation.from(waiting).status)
        assertEquals(WidgetDeadline.REQUEST, WidgetPresentation.from(waiting).deadlineKind)
        assertEquals(WidgetStatus.CONFIRMATION, WidgetPresentation.from(waiting.copy(pendingAtElapsed = null, result = "CONFIRMATION_REQUIRED")).status)
    }
    @Test fun earliestDeadlineShowsSessionCeilingAheadOfOverride() {
        val override = OverridePolicy.start(active(), OverrideKind.SIMULATE, 120_000, 0)
        val model = WidgetPresentation.from(override.copy(sessionEndsAtElapsed = 60_000))
        assertEquals(60_000L, model.deadline)
        assertEquals(WidgetDeadline.SESSION, model.deadlineKind)
        assertEquals(WidgetDeadline.OVERRIDE, WidgetPresentation.from(override).deadlineKind)
    }
    @Test fun temporaryStopAndPlainStoppedAreDifferentReports() {
        val stopped = OverridePolicy.start(active(), OverrideKind.STOP, 60_000, 0)
        assertEquals(WidgetStatus.TEMPORARY_STOP, WidgetPresentation.from(stopped).status)
        val inactive = PolicySnapshot(mode = OperatingMode.AUTO, service = ServiceState.STOPPED)
        assertEquals(WidgetStatus.STOPPED, WidgetPresentation.from(inactive).status)
        assertNull(WidgetPresentation.from(inactive).deadline)
    }
}
