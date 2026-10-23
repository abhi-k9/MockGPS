package dev.burixon.mockgps.core

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    private val now = 10_000L
    private fun reduce(s: PolicySnapshot, e: PolicyEvent, at: Long = now) = Policy.reduce(s, e, at)
    private fun auto() = reduce(PolicySnapshot(), PolicyEvent.SelectMode(OperatingMode.AUTO))
    private fun disconnected(s: PolicySnapshot = auto(), eventTime: Long = 1) =
        reduce(s, PolicyEvent.Vehicle(VehicleState.DISCONNECTED, eventTime))
    private fun ready(s: PolicySnapshot = disconnected()) =
        reduce(s, PolicyEvent.Deadline(s.generation), requireNotNull(s.pendingAtElapsed))

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
                pendingAtElapsed = now + 500, vehicle = VehicleState.DISCONNECTED)
            val next = reduce(s, PolicyEvent.SelectMode(to))
            assertEquals(to, next.mode)
            assertFalse(next.desired)
            assertEquals(5, next.generation)
            assertNull(next.pendingAtElapsed)
            assertEquals(VehicleState.UNKNOWN, next.vehicle)
            assertEquals(s.lastEventTime, next.lastEventTime)
        }
    }

    @Test fun selectingSameModeIsIdempotent() {
        for (mode in OperatingMode.entries) {
            val s = PolicySnapshot(mode = mode)
            assertEquals(s, reduce(s, PolicyEvent.SelectMode(mode)))
        }
    }

    @Test fun autoDisconnectedUsesDefaultTwoMinuteMonotonicDeadline() {
        val s = disconnected()
        assertEquals(now + 120_000L, s.pendingAtElapsed)
        assertFalse(s.desired)
        assertEquals("PENDING", s.result)
        assertEquals("Waiting after vehicle disconnect", s.displayStatus())
    }

    @Test fun earlyOrWrongGenerationDeadlineCannotRequestStart() {
        val s = disconnected()
        val deadline = requireNotNull(s.pendingAtElapsed)
        assertEquals(s, reduce(s, PolicyEvent.Deadline(s.generation), deadline - 1))
        assertEquals(s, reduce(s, PolicyEvent.Deadline(s.generation - 1), deadline))
        assertEquals(s, reduce(s, PolicyEvent.Deadline(s.generation + 1), deadline))
    }

    @Test fun deadlineRequestsVisibleConfirmationRatherThanStartingInBackground() {
        val s = ready()
        assertNull(s.pendingAtElapsed)
        assertTrue(s.needsConfirmation)
        assertFalse(s.desired)
        assertEquals("Open app and confirm simulation", s.displayStatus())
    }

    @Test fun blockedNotificationsStillAllowVisibleUserConfirmation() {
        val pending = ready().copy(result = "CONFIRMATION_REQUIRED_NOTIFICATIONS_BLOCKED")
        assertTrue(pending.needsConfirmation)
        assertEquals("Open app and confirm simulation", pending.displayStatus())
        val confirmed = reduce(pending, PolicyEvent.Confirm(pending.generation))
        assertTrue(confirmed.desired)
        assertEquals(ServiceState.STARTING, confirmed.service)
    }

    @Test fun explicitCurrentConfirmationStartsOnce() {
        val s = ready()
        val next = reduce(s, PolicyEvent.Confirm(s.generation))
        assertTrue(next.desired)
        assertEquals(ServiceState.STARTING, next.service)
        assertFalse(next.needsConfirmation)
        assertEquals("Starting — injection not yet confirmed", next.displayStatus())
        assertEquals(next, reduce(next, PolicyEvent.Confirm(s.generation)))
    }

    @Test fun confirmationBeforeDeadlineOrWithStaleGenerationIsRejected() {
        val waiting = disconnected()
        assertEquals(waiting, reduce(waiting, PolicyEvent.Confirm(waiting.generation)))
        val ready = ready(waiting)
        assertEquals(ready, reduce(ready, PolicyEvent.Confirm(ready.generation - 1)))
    }

    @Test fun reconnectCancelsDelayAndOldConfirmation() {
        for (before in listOf(disconnected(), ready())) {
            val connected = reduce(before, PolicyEvent.Vehicle(VehicleState.CONNECTED, 2))
            assertFalse(connected.desired)
            assertNull(connected.pendingAtElapsed)
            assertFalse(connected.needsConfirmation)
            assertEquals(before.generation + 1, connected.generation)
            assertEquals(connected, reduce(connected, PolicyEvent.Deadline(before.generation), now + 1_000_000))
            assertEquals(connected, reduce(connected, PolicyEvent.Confirm(before.generation)))
        }
    }

    @Test fun unknownConnectionCancelsDelayAndCannotRequestSimulation() {
        val pending = disconnected()
        val unknown = reduce(pending, PolicyEvent.Vehicle(VehicleState.UNKNOWN, 2))
        assertNull(unknown.pendingAtElapsed)
        assertFalse(unknown.desired)
        assertFalse(unknown.needsConfirmation)
        assertEquals(VehicleState.UNKNOWN, unknown.vehicle)
        assertTrue(unknown.copy(service = ServiceState.STOPPED).displayStatus().contains("reconciliation"))
    }

    @Test fun forceRealBlocksAllAutomaticEventsIncludingStaleConfirmation() {
        val pending = ready()
        var s = reduce(pending, PolicyEvent.SelectMode(OperatingMode.FORCE_REAL))
        for (event in listOf(
            PolicyEvent.Vehicle(VehicleState.DISCONNECTED, 2),
            PolicyEvent.Vehicle(VehicleState.CONNECTED, 3),
            PolicyEvent.Vehicle(VehicleState.UNKNOWN, 4),
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

    @Test fun returningToAutoRequiresFreshVehicleReconciliation() {
        var s = disconnected()
        s = reduce(s, PolicyEvent.SelectMode(OperatingMode.FORCE_REAL))
        s = reduce(s, PolicyEvent.Vehicle(VehicleState.DISCONNECTED, 2))
        s = reduce(s, PolicyEvent.SelectMode(OperatingMode.AUTO))
        assertEquals(VehicleState.UNKNOWN, s.vehicle)
        assertNull(s.pendingAtElapsed)
        assertFalse(s.desired)
        assertEquals(s, reduce(s, PolicyEvent.Vehicle(VehicleState.DISCONNECTED, 2)))
        assertNotNull(reduce(s, PolicyEvent.Vehicle(VehicleState.DISCONNECTED, 3)).pendingAtElapsed)
    }

    @Test fun duplicateSnapshotsDoNotPostponeExistingDelay() {
        val first = disconnected()
        val duplicate = reduce(first, PolicyEvent.Vehicle(VehicleState.DISCONNECTED, 2), now + 20_000)
        assertEquals(first.pendingAtElapsed, duplicate.pendingAtElapsed)
        assertEquals(first.generation, duplicate.generation)
        assertEquals(2, duplicate.lastEventTime)
    }

    @Test fun duplicateOrOutOfOrderEventsDoNotOverrideNewerConnection() {
        val s = reduce(disconnected(), PolicyEvent.Vehicle(VehicleState.CONNECTED, 3))
        for (eventTime in listOf(-10L, 0L, 1L, 2L, 3L)) {
            assertEquals(s, reduce(s, PolicyEvent.Vehicle(VehicleState.DISCONNECTED, eventTime)))
        }
    }

    @Test fun processRecoveryCancelsIntentionsAndOldDelayedWork() {
        for (before in listOf(disconnected(), ready(), reduce(ready(), PolicyEvent.Confirm(ready().generation)),
            reduce(PolicySnapshot(mode = OperatingMode.AUTO), PolicyEvent.ManualStart))) {
            val after = reduce(before, PolicyEvent.Recover)
            assertEquals(before.mode, after.mode)
            assertFalse(after.desired)
            assertNull(after.pendingAtElapsed)
            assertEquals(VehicleState.UNKNOWN, after.vehicle)
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
        val old = disconnected()
        val recovered = reduce(old, PolicyEvent.Recover, 0)
        assertEquals(recovered, reduce(recovered, PolicyEvent.Deadline(old.generation), 500))
        val fresh = reduce(recovered, PolicyEvent.Vehicle(VehicleState.DISCONNECTED, 2), 500)
        assertEquals(120_500L, fresh.pendingAtElapsed)
    }

    @Test fun delayChangesInvalidatePendingActivationAndNeedFreshInput() {
        val s = disconnected()
        for (seconds in listOf(0L, 1L, 120L, 86_400L)) {
            val updated = reduce(s, PolicyEvent.Delay(seconds))
            assertEquals(seconds, updated.delaySeconds)
            assertFalse(updated.desired)
            assertNull(updated.pendingAtElapsed)
            assertEquals(VehicleState.UNKNOWN, updated.vehicle)
            assertEquals(s.generation + 1, updated.generation)
            val fresh = disconnected(updated, 2)
            assertEquals(now + seconds * 1000L, fresh.pendingAtElapsed)
        }
    }

    @Test fun invalidDelayRejectedWithoutMutation() {
        val original = disconnected()
        for (seconds in listOf(-1L, Long.MIN_VALUE, 86_401L, Long.MAX_VALUE)) {
            try {
                reduce(original, PolicyEvent.Delay(seconds))
                fail("Expected invalid delay $seconds to be rejected")
            } catch (_: IllegalArgumentException) {
                assertEquals(120L, original.delaySeconds)
            }
        }
    }

    @Test fun explicitManualStartCannotBeProducedByVehicleEvents() {
        val manual = reduce(PolicySnapshot(mode = OperatingMode.AUTO), PolicyEvent.ManualStart)
        assertEquals(OperatingMode.MANUAL_MOCK, manual.mode)
        assertTrue(manual.desired)
        assertEquals(ServiceState.STARTING, manual.service)
        assertNull(manual.pendingAtElapsed)
        for (vehicle in VehicleState.entries) {
            val next = reduce(manual, PolicyEvent.Vehicle(vehicle, 1))
            assertEquals(OperatingMode.MANUAL_MOCK, next.mode)
            assertTrue(next.desired)
            assertNull(next.pendingAtElapsed)
        }
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
        assertEquals("Error: MISSING_MOCK_APP", error.displayStatus())
    }
}
