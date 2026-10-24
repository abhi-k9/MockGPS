package dev.burixon.mockgps.core

import org.junit.Assert.*
import org.junit.Test

class ReadinessSafetyTest {
    private fun event(s: PolicySnapshot, readiness: ReadinessState, seq: Long, time: Long = 1000 + seq) =
        Policy.reduce(s, PolicyEvent.Readiness(readiness, time, seq), 2000)
    private fun active(mode: OperatingMode) = PolicySnapshot(mode = mode, desired = true,
        service = ServiceState.INJECTING, providers = ProviderState.READY,
        injection = InjectionState.CONFIRMED, readiness = ReadinessState.READY, generation = 9)

    @Test fun autoAndManualSafetyStopsInvalidateIntentWithoutInventingCleanupEvidence() {
        for (mode in listOf(OperatingMode.AUTO, OperatingMode.MANUAL_MOCK)) {
            for (readiness in listOf(ReadinessState.BLOCKED, ReadinessState.UNKNOWN)) {
                val before = active(mode)
                val after = event(before, readiness, 1)
                assertFalse(after.desired)
                assertEquals(OperatingMode.AUTO, after.mode)
                assertEquals(10L, after.generation)
                assertNull(after.pendingAtElapsed)
                assertFalse(after.needsConfirmation)
                // Only the controller/platform effect can establish successful removal.
                assertEquals(before.providers, after.providers)
                assertEquals(before.injection, after.injection)
            }
        }
    }

    @Test fun manualStartImmediatelyFollowedByPauseInvalidatesTheStartGeneration() {
        val manual = Policy.reduce(PolicySnapshot(mode = OperatingMode.AUTO), PolicyEvent.ManualStart, 0)
        val paused = event(manual, ReadinessState.BLOCKED, 1)
        assertFalse(paused.desired)
        assertTrue(paused.generation > manual.generation)
        assertEquals(paused, Policy.reduce(paused, PolicyEvent.Confirm(manual.generation), 3000))
        assertEquals(paused, Policy.reduce(paused, PolicyEvent.Deadline(manual.generation), 3000))
    }

    @Test fun repeatedUnknownAlsoStopsManualTestingThatBeganWithUnknownReadiness() {
        val manual = active(OperatingMode.MANUAL_MOCK).copy(readiness = ReadinessState.UNKNOWN)
        assertFalse(event(manual, ReadinessState.UNKNOWN, 1).desired)
    }

    @Test fun forceRealSurvivesAllFreshReadinessEventsAndManualStart() {
        var s = PolicySnapshot(generation = 5)
        for ((i, readiness) in ReadinessState.entries.withIndex()) {
            s = event(s, readiness, i.toLong())
            assertEquals(OperatingMode.FORCE_REAL, s.mode)
            assertFalse(s.desired)
            assertNull(s.pendingAtElapsed)
            assertEquals(s, Policy.reduce(s, PolicyEvent.ManualStart, 3000))
        }
    }

    @Test fun unknownCancelsBothWaitingAndReadyConfirmation() {
        val waiting = event(PolicySnapshot(mode = OperatingMode.AUTO), ReadinessState.READY, 1)
        val ready = Policy.reduce(waiting, PolicyEvent.Deadline(waiting.generation), waiting.pendingAtElapsed!!)
        for (before in listOf(waiting, ready)) {
            val stopped = event(before, ReadinessState.UNKNOWN, 2)
            assertNull(stopped.pendingAtElapsed)
            assertFalse(stopped.needsConfirmation)
            assertFalse(stopped.desired)
            assertEquals(stopped, Policy.reduce(stopped, PolicyEvent.Confirm(before.generation), 500_000))
        }
    }

    @Test fun readyAfterManualSafetyStopRequestsANewDelayAndConfirmationOnly() {
        val manual = active(OperatingMode.MANUAL_MOCK)
        val paused = event(manual, ReadinessState.BLOCKED, 1)
        val pending = event(paused, ReadinessState.READY, 2)
        assertEquals(OperatingMode.AUTO, pending.mode)
        assertFalse(pending.desired)
        assertEquals(122_000L, pending.pendingAtElapsed)
        val ready = Policy.reduce(pending, PolicyEvent.Deadline(pending.generation), 122_000)
        assertTrue(ready.needsConfirmation)
        assertFalse(ready.desired)
        assertEquals(ready, Policy.reduce(ready, PolicyEvent.Confirm(manual.generation), 122_000))
        assertTrue(Policy.reduce(ready, PolicyEvent.Confirm(ready.generation), 122_000).desired)
    }

    @Test fun repeatedBlockedSnapshotsAreSafeAndReplayDoesNothing() {
        var s = event(active(OperatingMode.MANUAL_MOCK), ReadinessState.BLOCKED, 1)
        repeat(10) {
            val old = s
            s = event(old, ReadinessState.BLOCKED, old.lastSequence + 1)
            assertFalse(s.desired)
            assertNull(s.pendingAtElapsed)
            assertEquals(OperatingMode.AUTO, s.mode)
            assertEquals(s, event(s, ReadinessState.BLOCKED, s.lastSequence))
        }
    }

    @Test fun sameMillisecondOrderingRejectsOlderRequestAfterManualSafetyStop() {
        val paused = event(active(OperatingMode.MANUAL_MOCK), ReadinessState.BLOCKED, 20, time = 1000)
        assertEquals(paused, event(paused, ReadinessState.READY, 19, time = 1000))
        val ready = event(paused, ReadinessState.READY, 21, time = 1000)
        assertEquals(paused, ready) // safety wins a same-millisecond contradiction
        val repaused = event(ready, ReadinessState.BLOCKED, 22, time = 1000)
        assertFalse(repaused.desired)
        assertNull(repaused.pendingAtElapsed)
        assertEquals(1000L, repaused.lastEventTime)
    }

    @Test fun recoveryAfterReadinessShutdownNeverRestoresManualInjection() {
        val stopped = event(active(OperatingMode.MANUAL_MOCK), ReadinessState.BLOCKED, 3)
        val recovered = Policy.reduce(stopped, PolicyEvent.Recover, 0)
        assertEquals(OperatingMode.AUTO, recovered.mode)
        assertFalse(recovered.desired)
        assertNull(recovered.pendingAtElapsed)
        assertEquals(ReadinessState.UNKNOWN, recovered.readiness)
        assertEquals(ProviderState.UNKNOWN, recovered.providers)
    }

    @Test fun tileForceRealStopsManualAndCannotBeDefeatedByReadinessEvents() {
        val manual = active(OperatingMode.MANUAL_MOCK)
        assertEquals(TileAction.FORCE_REAL, TilePresentation.from(manual).action)
        val stopped = Policy.reduce(manual, PolicyEvent.SelectMode(OperatingMode.FORCE_REAL), 1000)
        val ready = event(stopped, ReadinessState.READY, 1)
        assertFalse(ready.desired)
        assertEquals(OperatingMode.FORCE_REAL, ready.mode)
        assertEquals("FORCE_REAL", TilePresentation.from(ready).label)
    }

    @Test fun knownBlockedReadinessBlocksANewManualStart() {
        val paused = event(PolicySnapshot(mode = OperatingMode.AUTO), ReadinessState.BLOCKED, 1)
        assertEquals(paused, Policy.reduce(paused, PolicyEvent.ManualStart, 3000))
        val ready = event(paused, ReadinessState.READY, 2)
        assertTrue(Policy.reduce(ready, PolicyEvent.ManualStart, 3000).desired)
    }
}
