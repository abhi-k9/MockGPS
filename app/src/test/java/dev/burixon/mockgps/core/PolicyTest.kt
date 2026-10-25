package dev.burixon.mockgps.core

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    private val now = 10_000L
    private fun reduce(s: PolicySnapshot, e: PolicyEvent, at: Long = now) = Policy.reduce(s, e, at)
    private fun auto() = reduce(PolicySnapshot(), PolicyEvent.SelectMode(OperatingMode.AUTO))
    private fun requestPending(s: PolicySnapshot = auto(), eventTime: Long = 1) =
        reduce(s, PolicyEvent.Readiness(ReadinessState.READY, eventTime))
    private fun confirmationReady(s: PolicySnapshot = requestPending()) =
        reduce(s, PolicyEvent.Deadline(s.generation), requireNotNull(s.pendingAtElapsed))

    @Test fun manualSessionIsStoppedByBlockedOrUnknownReadiness() {
        for (readiness in listOf(ReadinessState.BLOCKED, ReadinessState.UNKNOWN)) {
            val manual = reduce(auto(), PolicyEvent.ManualStart)
            val stopped = reduce(manual, PolicyEvent.Readiness(readiness, 1))
            assertFalse("$readiness must stop manual simulation", stopped.desired)
            assertEquals(OperatingMode.AUTO, stopped.mode)
            assertEquals(manual.generation + 1, stopped.generation)
            assertNull(stopped.pendingAtElapsed)
        }
    }

    @Test fun initialStateIsSafeAndDoesNotClaimFreshGenuineLocation() {
        val s = PolicySnapshot()
        assertEquals(OperatingMode.FORCE_REAL, s.mode)
        assertFalse(s.desired)
        assertNull(s.pendingAtElapsed)
        assertEquals(ServiceState.UNKNOWN, s.service)
        assertEquals(InjectionState.UNKNOWN, s.injection)
        assertEquals("Previous provider state unverified", s.displayStatus())
    }

    @Test fun everyModeChangeStopsAndInvalidatesPriorActivation() {
        for (from in OperatingMode.entries) for (to in OperatingMode.entries) {
            if (from == to || (from == OperatingMode.FORCE_REAL && to == OperatingMode.MANUAL_MOCK)) continue
            val s = PolicySnapshot(mode = from, desired = true, generation = 4,
                pendingAtElapsed = now + 500, readiness = ReadinessState.READY)
            val next = reduce(s, PolicyEvent.SelectMode(to))
            assertEquals(to, next.mode)
            assertFalse(next.desired)
            assertEquals(5, next.generation)
            assertNull(next.pendingAtElapsed)
            assertEquals(ReadinessState.UNKNOWN, next.readiness)
            assertEquals(s.lastEventTime, next.lastEventTime)
        }
    }

    @Test fun selectingSameModeIsIdempotent() {
        for (mode in OperatingMode.entries) {
            val s = PolicySnapshot(mode = mode)
            assertEquals(s, reduce(s, PolicyEvent.SelectMode(mode)))
        }
    }

    @Test fun autoRequestUsesDefaultTwoMinuteMonotonicDeadline() {
        val s = requestPending()
        assertEquals(now + 120_000L, s.pendingAtElapsed)
        assertFalse(s.desired)
        assertEquals("PENDING", s.result)
        assertEquals("Waiting for automation delay", s.displayStatus())
    }

    @Test fun earlyOrWrongGenerationDeadlineCannotRequestStart() {
        val s = requestPending()
        val deadline = requireNotNull(s.pendingAtElapsed)
        assertEquals(s, reduce(s, PolicyEvent.Deadline(s.generation), deadline - 1))
        assertEquals(s, reduce(s, PolicyEvent.Deadline(s.generation - 1), deadline))
        assertEquals(s, reduce(s, PolicyEvent.Deadline(s.generation + 1), deadline))
    }

    @Test fun deadlineRequestsVisibleConfirmationRatherThanStartingInBackground() {
        val s = confirmationReady()
        assertNull(s.pendingAtElapsed)
        assertTrue(s.needsConfirmation)
        assertFalse(s.desired)
        assertEquals("Open app and confirm simulation", s.displayStatus())
    }

    @Test fun blockedNotificationsStillAllowVisibleUserConfirmation() {
        val pending = confirmationReady().copy(result = "CONFIRMATION_REQUIRED_NOTIFICATIONS_BLOCKED")
        assertTrue(pending.needsConfirmation)
        assertEquals("Open app and confirm simulation", pending.displayStatus())
        val confirmed = reduce(pending, PolicyEvent.Confirm(pending.generation))
        assertTrue(confirmed.desired)
        assertEquals(ServiceState.STARTING, confirmed.service)
    }

    @Test fun explicitCurrentConfirmationStartsOnce() {
        val s = confirmationReady()
        val next = reduce(s, PolicyEvent.Confirm(s.generation))
        assertTrue(next.desired)
        assertEquals(ServiceState.STARTING, next.service)
        assertFalse(next.needsConfirmation)
        assertEquals("Starting — injection not yet confirmed", next.displayStatus())
        assertEquals(next, reduce(next, PolicyEvent.Confirm(s.generation)))
    }

    @Test fun confirmationBeforeDeadlineOrWithStaleGenerationIsRejected() {
        val waiting = requestPending()
        assertEquals(waiting, reduce(waiting, PolicyEvent.Confirm(waiting.generation)))
        val ready = confirmationReady(waiting)
        assertEquals(ready, reduce(ready, PolicyEvent.Confirm(ready.generation - 1)))
    }

    @Test fun pauseCancelsDelayAndOldConfirmation() {
        for (before in listOf(requestPending(), confirmationReady())) {
            val paused = reduce(before, PolicyEvent.Readiness(ReadinessState.BLOCKED, 2))
            assertFalse(paused.desired)
            assertNull(paused.pendingAtElapsed)
            assertFalse(paused.needsConfirmation)
            assertEquals(before.generation + 1, paused.generation)
            assertEquals(paused, reduce(paused, PolicyEvent.Deadline(before.generation), now + 1_000_000))
            assertEquals(paused, reduce(paused, PolicyEvent.Confirm(before.generation)))
        }
    }

    @Test fun unknownPauseCancelsDelayAndCannotRequestSimulation() {
        val pending = requestPending()
        val unknown = reduce(pending, PolicyEvent.Readiness(ReadinessState.UNKNOWN, 2))
        assertNull(unknown.pendingAtElapsed)
        assertFalse(unknown.desired)
        assertFalse(unknown.needsConfirmation)
        assertEquals(ReadinessState.UNKNOWN, unknown.readiness)
        assertTrue(unknown.copy(service = ServiceState.STOPPED).displayStatus().contains("automation readiness"))
    }

    @Test fun forceRealBlocksAllAutomaticEventsIncludingStaleConfirmation() {
        val pending = requestPending()
        var s = reduce(pending, PolicyEvent.SelectMode(OperatingMode.FORCE_REAL))
        for (event in listOf(
            PolicyEvent.Readiness(ReadinessState.READY, 2),
            PolicyEvent.Readiness(ReadinessState.BLOCKED, 3),
            PolicyEvent.Readiness(ReadinessState.UNKNOWN, 4),
            PolicyEvent.Deadline(pending.generation),
            PolicyEvent.Confirm(pending.generation),
            PolicyEvent.Deadline(s.generation),
            PolicyEvent.Confirm(s.generation)
        )) {
            s = reduce(s, event, now + 1_000_000)
            assertEquals(OperatingMode.FORCE_REAL, s.mode)
            assertFalse(s.desired)
            assertNull(s.pendingAtElapsed)
        }
    }

    @Test fun returningToAutoRequiresFreshReadinessReconciliation() {
        var s = requestPending()
        s = reduce(s, PolicyEvent.SelectMode(OperatingMode.FORCE_REAL))
        s = reduce(s, PolicyEvent.Readiness(ReadinessState.READY, 2))
        s = reduce(s, PolicyEvent.SelectMode(OperatingMode.AUTO))
        assertEquals(ReadinessState.UNKNOWN, s.readiness)
        assertNull(s.pendingAtElapsed)
        assertFalse(s.desired)
        assertEquals(s, reduce(s, PolicyEvent.Readiness(ReadinessState.READY, 2)))
        assertNotNull(reduce(s, PolicyEvent.Readiness(ReadinessState.READY, 3)).pendingAtElapsed)
    }

    @Test fun duplicateSnapshotsDoNotPostponeExistingDelay() {
        val first = requestPending()
        val duplicate = reduce(first, PolicyEvent.Readiness(ReadinessState.READY, 2), now + 20_000)
        assertEquals(first.pendingAtElapsed, duplicate.pendingAtElapsed)
        assertEquals(first.generation, duplicate.generation)
        assertEquals(2, duplicate.lastEventTime)
    }

    @Test fun duplicateOrOutOfOrderEventsDoNotOverrideNewerPause() {
        val s = reduce(requestPending(), PolicyEvent.Readiness(ReadinessState.BLOCKED, 3))
        for (eventTime in listOf(-10L, 0L, 1L, 2L, 3L)) {
            assertEquals(s, reduce(s, PolicyEvent.Readiness(ReadinessState.READY, eventTime)))
        }
    }

    @Test fun processRecoveryCancelsIntentionsAndOldDelayedWork() {
        for (before in listOf(requestPending(), confirmationReady(), reduce(confirmationReady(), PolicyEvent.Confirm(confirmationReady().generation)),
            reduce(PolicySnapshot(mode = OperatingMode.AUTO), PolicyEvent.ManualStart))) {
            val after = reduce(before, PolicyEvent.Recover)
            assertEquals(before.mode, after.mode)
            assertFalse(after.desired)
            assertNull(after.pendingAtElapsed)
            assertEquals(ReadinessState.UNKNOWN, after.readiness)
            assertEquals(ServiceState.UNKNOWN, after.service)
            assertEquals(ProviderState.UNKNOWN, after.providers)
            assertEquals(InjectionState.UNKNOWN, after.injection)
            assertEquals("RECOVERY_REQUIRED", after.result)
            assertEquals(before.generation + 1, after.generation)
            assertEquals(after, reduce(after, PolicyEvent.Deadline(before.generation), now + 1_000_000))
            assertEquals(after, reduce(after, PolicyEvent.Confirm(before.generation)))
        }
    }

    @Test fun rebootElapsedClockResetDoesNotResurrectOldDeadline() {
        val old = requestPending()
        val recovered = reduce(old, PolicyEvent.Recover, 0)
        assertEquals(recovered, reduce(recovered, PolicyEvent.Deadline(old.generation), 500))
        val fresh = reduce(recovered, PolicyEvent.Readiness(ReadinessState.READY, 2), 500)
        assertEquals(120_500L, fresh.pendingAtElapsed)
    }

    @Test fun delayChangesInvalidatePendingActivationAndNeedFreshInput() {
        val s = requestPending()
        for (seconds in listOf(0L, 1L, 120L, 86_400L)) {
            val updated = reduce(s, PolicyEvent.Delay(seconds))
            assertEquals(seconds, updated.delaySeconds)
            assertFalse(updated.desired)
            assertNull(updated.pendingAtElapsed)
            assertEquals(ReadinessState.UNKNOWN, updated.readiness)
            assertEquals(s.generation + 1, updated.generation)
            val fresh = requestPending(updated, 2)
            assertEquals(now + seconds * 1000L, fresh.pendingAtElapsed)
        }
    }

    @Test fun invalidDelayRejectedWithoutMutation() {
        val original = requestPending()
        for (seconds in listOf(-1L, Long.MIN_VALUE, 86_401L, Long.MAX_VALUE)) {
            try {
                reduce(original, PolicyEvent.Delay(seconds))
                fail("Expected invalid delay $seconds to be rejected")
            } catch (_: IllegalArgumentException) {
                assertEquals(120L, original.delaySeconds)
            }
        }
    }

    @Test fun explicitManualStartCannotBeProducedByReadinessEvents() {
        val manual = reduce(PolicySnapshot(mode = OperatingMode.AUTO), PolicyEvent.ManualStart)
        assertEquals(OperatingMode.MANUAL_MOCK, manual.mode)
        assertTrue(manual.desired)
        assertEquals(ServiceState.STARTING, manual.service)
        assertNull(manual.pendingAtElapsed)
        val ready = reduce(manual, PolicyEvent.Readiness(ReadinessState.READY, 1))
        assertEquals(OperatingMode.MANUAL_MOCK, ready.mode)
        assertTrue(ready.desired)
        assertNull(ready.pendingAtElapsed) // No second automatic session is requested.
    }

    @Test fun manualStartCannotBypassForceRealWithoutExplicitReturnToAuto() {
        val forceReal = PolicySnapshot(mode = OperatingMode.FORCE_REAL, generation = 9)
        assertEquals(forceReal, reduce(forceReal, PolicyEvent.ManualStart))
        assertEquals(forceReal, reduce(forceReal, PolicyEvent.SelectMode(OperatingMode.MANUAL_MOCK)))
        val automatic = reduce(forceReal, PolicyEvent.SelectMode(OperatingMode.AUTO))
        assertEquals(OperatingMode.AUTO, automatic.mode)
        assertFalse(automatic.desired)
        val manual = reduce(automatic, PolicyEvent.ManualStart)
        assertEquals(OperatingMode.MANUAL_MOCK, manual.mode)
        assertTrue(manual.desired)
        val forcedAgain = reduce(manual, PolicyEvent.SelectMode(OperatingMode.FORCE_REAL))
        assertFalse(forcedAgain.desired)
        assertEquals(forcedAgain, reduce(forcedAgain, PolicyEvent.ManualStart))
    }

    @Test fun rapidManualStartStopSequenceEndsStoppedWithStaleStartsInvalidated() {
        var s = PolicySnapshot()
        repeat(100) {
            s = reduce(s, PolicyEvent.SelectMode(OperatingMode.AUTO))
            s = reduce(s, PolicyEvent.ManualStart)
            val runningGeneration = s.generation
            assertTrue(s.desired)
            s = reduce(s, PolicyEvent.SelectMode(OperatingMode.FORCE_REAL))
            assertFalse(s.desired)
            assertEquals(s, reduce(s, PolicyEvent.Confirm(runningGeneration)))
            assertEquals(s, reduce(s, PolicyEvent.Deadline(runningGeneration)))
        }
        assertEquals(OperatingMode.FORCE_REAL, s.mode)
        assertFalse(s.desired)
    }

    @Test fun truthfulStatusDistinguishesProviderAndInjectionObservations() {
        val starting = PolicySnapshot(service = ServiceState.STARTING, providers = ProviderState.READY,
            injection = InjectionState.NONE)
        assertTrue(starting.displayStatus().contains("not yet confirmed"))
        assertFalse(starting.displayStatus().startsWith("Injecting"))
        val running = starting.copy(service = ServiceState.INJECTING, injection = InjectionState.CONFIRMED)
        assertEquals("Injecting simulated location", running.displayStatus())
        val uncertain = running.copy(result = "CLEANUP_UNCERTAIN")
        assertTrue(uncertain.displayStatus().contains("uncertain"))
        val error = starting.copy(service = ServiceState.ERROR, result = "MISSING_MOCK_APP")
        assertEquals("Check the mock-location app in Developer options, then retry", error.displayStatus())
    }
    @Test fun sameMillisecondPauseCancelsPendingStart() {
        val initial = auto()
        val ready = reduce(initial, PolicyEvent.Readiness(ReadinessState.READY, 1000, 1))
        val paused = reduce(ready, PolicyEvent.Readiness(ReadinessState.BLOCKED, 1000, 2))
        assertEquals(ReadinessState.BLOCKED, paused.readiness)
        assertNull(paused.pendingAtElapsed)
        assertFalse(paused.desired)
        assertEquals(paused, reduce(paused, PolicyEvent.Deadline(ready.generation), now + 200_000))
    }

    @Test fun newerSafetySequenceSurvivesWallClockRollbackWithoutRegressingWatermark() {
        val first = reduce(auto(), PolicyEvent.Readiness(ReadinessState.READY, 100_000, 1))
        val second = reduce(first, PolicyEvent.Readiness(ReadinessState.BLOCKED, 1_000, 2))
        assertEquals(ReadinessState.BLOCKED, second.readiness)
        assertEquals(100_000, second.lastEventTime)
        assertEquals(2, second.lastSequence)
    }

    @Test fun newerWallTimeCannotReplayAnOldControlSequence() {
        val first = reduce(auto(), PolicyEvent.Readiness(ReadinessState.BLOCKED, 1000, 10))
        assertEquals(first, reduce(first, PolicyEvent.Readiness(ReadinessState.READY, 2000, 9)))
    }
}
