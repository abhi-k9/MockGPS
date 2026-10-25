package dev.burixon.mockgps.core

import org.junit.Assert.*
import org.junit.Test

class TemporaryOverrideTest {
    private fun active() = PolicySnapshot(mode = OperatingMode.AUTO, desired = true,
        service = ServiceState.INJECTING, providers = ProviderState.READY, injection = InjectionState.CONFIRMED,
        readiness = ReadinessState.READY, unattendedApproval = true, delaySeconds = 5)

    @Test fun stopResumesThePreviouslyActivePolicy() {
        val stopped = OverridePolicy.start(active(), OverrideKind.STOP, 60_000, 1000)
        assertFalse(stopped.desired)
        val resumed = OverridePolicy.finish(stopped, stopped.temporary!!.id, 61_000)
        assertTrue(resumed.desired)
        assertNull(resumed.temporary)
        assertTrue(resumed.generation > stopped.generation)
    }
    @Test fun pauseDuringStopPreventsResumption() {
        val stopped = OverridePolicy.start(active(), OverrideKind.STOP, 60_000, 1000)
        val paused = Policy.reduce(stopped, PolicyEvent.SourceReadiness(ReadinessState.BLOCKED), 2000)
        val resumed = OverridePolicy.finish(paused, paused.temporary!!.id, 61_000)
        assertFalse(resumed.desired)
        assertEquals(ReadinessState.BLOCKED, resumed.readiness)
    }
    @Test fun requestDuringOverrideResumesAfterItsRemainingDelay() {
        val stopped = OverridePolicy.start(active(), OverrideKind.STOP, 60_000, 1000)
        val paused = Policy.reduce(stopped, PolicyEvent.SourceReadiness(ReadinessState.BLOCKED), 2000)
        val requested = Policy.reduce(paused, PolicyEvent.SourceReadiness(ReadinessState.READY), 60_000)
        val resumed = OverridePolicy.finish(requested, requested.temporary!!.id, 61_000)
        assertFalse(resumed.desired)
        assertEquals(65_000L, resumed.pendingAtElapsed)
        assertTrue(Policy.reduce(resumed, PolicyEvent.Deadline(resumed.generation), 65_000).desired)
    }
    @Test fun elapsedUnderlyingDelayStartsOnExpiryWithConsent() {
        val timer = OverridePolicy.start(active(), OverrideKind.SIMULATE, 60_000, 1000)
        val paused = Policy.reduce(timer, PolicyEvent.SourceReadiness(ReadinessState.BLOCKED), 2000)
        assertTrue(paused.desired) // Explicit temporary simulation stays above readiness.
        val requested = Policy.reduce(paused, PolicyEvent.SourceReadiness(ReadinessState.READY), 3000)
        assertTrue(OverridePolicy.finish(requested, requested.temporary!!.id, 61_000).desired)
    }
    @Test fun elapsedDelayDoesNotBypassManualApproval() {
        val timer = OverridePolicy.start(active().copy(unattendedApproval = false), OverrideKind.STOP, 60_000, 1000)
        val paused = Policy.reduce(timer, PolicyEvent.SourceReadiness(ReadinessState.BLOCKED), 2000)
        val requested = Policy.reduce(paused, PolicyEvent.SourceReadiness(ReadinessState.READY), 3000)
        val resumed = OverridePolicy.finish(requested, requested.temporary!!.id, 61_000)
        assertFalse(resumed.desired); assertTrue(resumed.needsConfirmation)
    }
    @Test fun manualSessionResumesUnlessReadinessChangedWhileStopped() {
        val timer = OverridePolicy.start(active().copy(mode = OperatingMode.MANUAL_MOCK, unattendedApproval = false), OverrideKind.STOP, 60_000, 0)
        assertTrue(OverridePolicy.finish(timer, timer.temporary!!.id, 60_000).desired)
        val blocked = Policy.reduce(timer, PolicyEvent.SourceReadiness(ReadinessState.BLOCKED), 1000)
        assertFalse(OverridePolicy.finish(blocked, blocked.temporary!!.id, 60_000).desired)
    }
    @Test fun keepStoppedCannotBeBypassedAndCancelsAnExistingTimer() {
        val kept = active().copy(mode = OperatingMode.FORCE_REAL, desired = false)
        assertEquals(kept, OverridePolicy.start(kept, OverrideKind.SIMULATE, 60_000, 0))
        val keptTimer = OverridePolicy.start(kept, OverrideKind.STOP, 60_000, 0)
        assertNull(Policy.reduce(keptTimer, PolicyEvent.SelectMode(OperatingMode.FORCE_REAL), 1000).temporary)
        val timer = OverridePolicy.start(active(), OverrideKind.STOP, 60_000, 0)
        val stopped = Policy.reduce(timer, PolicyEvent.SelectMode(OperatingMode.FORCE_REAL), 1000)
        assertNull(stopped.temporary)
        assertFalse(OverridePolicy.finish(stopped, timer.temporary!!.id, 60_000).desired)
    }
    @Test fun oldActionsCannotChangeNewTimerAndAdjustmentDoesNotChangeUnderlyingPolicy() {
        val timer = OverridePolicy.start(active(), OverrideKind.STOP, 60_000, 0)
        val newer = OverridePolicy.start(timer, OverrideKind.SIMULATE, 120_000, 5000)
        assertEquals(newer, OverridePolicy.adjust(newer, timer.temporary!!.id, 300_000, 6000))
        assertEquals(newer, OverridePolicy.finish(newer, timer.temporary.id, 60_000))
        val extended = OverridePolicy.adjust(newer, newer.temporary!!.id, 300_000, 6000)
        assertEquals(newer.temporary.resumeMode, extended.temporary!!.resumeMode)
        assertTrue(OverridePolicy.finish(extended, extended.temporary.id, 500_000).desired)
    }
    @Test fun sessionCeilingWinsOverTimerAndCannotBeExtendedByNotificationActions() {
        val timer = OverridePolicy.start(active().copy(sessionEndsAtElapsed = 60_000), OverrideKind.SIMULATE, 120_000, 0)
        val extended = OverridePolicy.adjust(timer, timer.temporary!!.id, 300_000, 1000)
        assertEquals(60_000L, extended.sessionEndsAtElapsed)
        assertEquals(SessionExpiry.NONE, SessionExpiry.at(extended, 59_999))
        assertEquals(SessionExpiry.CEILING, SessionExpiry.at(extended, 60_000))
        assertEquals(SessionExpiry.CEILING, SessionExpiry.at(extended, 500_000))
    }
    @Test fun timerExpiresOnlyAtItsCurrentElapsedDeadline() {
        val timer = OverridePolicy.start(active(), OverrideKind.STOP, 60_000, 1000)
        val extended = OverridePolicy.adjust(timer, timer.temporary!!.id, 300_000, 2000)
        assertEquals(SessionExpiry.NONE, SessionExpiry.at(extended, 61_000))
        assertEquals(SessionExpiry.OVERRIDE, SessionExpiry.at(extended, 361_000))
    }
    @Test fun recoveryNeverRestoresTimerOrInjection() {
        val timer = OverridePolicy.start(active(), OverrideKind.SIMULATE, 60_000, 0)
        val recovered = Policy.reduce(timer, PolicyEvent.Recover, 5000)
        assertNull(recovered.temporary); assertFalse(recovered.desired); assertNull(recovered.pendingAtElapsed)
    }
}
