package dev.burixon.mockgps.core

import org.junit.Assert.*
import org.junit.Test

class VehicleSafetyTest {
    private fun event(s: PolicySnapshot, vehicle: VehicleState, seq: Long, time: Long = 1000 + seq) =
        Policy.reduce(s, PolicyEvent.Vehicle(vehicle, time, seq), 2000)
    private fun active(mode: OperatingMode) = PolicySnapshot(mode = mode, desired = true,
        service = ServiceState.INJECTING, providers = ProviderState.READY,
        injection = InjectionState.CONFIRMED, vehicle = VehicleState.DISCONNECTED, generation = 9)

    @Test fun autoAndManualSafetyStopsInvalidateIntentWithoutInventingCleanupEvidence() {
        for (mode in listOf(OperatingMode.AUTO, OperatingMode.MANUAL_MOCK)) {
            for (vehicle in listOf(VehicleState.CONNECTED, VehicleState.UNKNOWN)) {
                val before = active(mode)
                val after = event(before, vehicle, 1)
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

    @Test fun manualStartImmediatelyFollowedByConnectionInvalidatesTheStartGeneration() {
        val manual = Policy.reduce(PolicySnapshot(mode = OperatingMode.AUTO), PolicyEvent.ManualStart, 0)
        val connected = event(manual, VehicleState.CONNECTED, 1)
        assertFalse(connected.desired)
        assertTrue(connected.generation > manual.generation)
        assertEquals(connected, Policy.reduce(connected, PolicyEvent.Confirm(manual.generation), 3000))
        assertEquals(connected, Policy.reduce(connected, PolicyEvent.Deadline(manual.generation), 3000))
    }

    @Test fun repeatedUnknownAlsoStopsManualTestingThatBeganWithUnknownVehicle() {
        val manual = active(OperatingMode.MANUAL_MOCK).copy(vehicle = VehicleState.UNKNOWN)
        assertFalse(event(manual, VehicleState.UNKNOWN, 1).desired)
    }

    @Test fun forceRealSurvivesAllFreshVehicleEventsAndManualStart() {
        var s = PolicySnapshot(generation = 5)
        for ((i, vehicle) in VehicleState.entries.withIndex()) {
            s = event(s, vehicle, i.toLong())
            assertEquals(OperatingMode.FORCE_REAL, s.mode)
            assertFalse(s.desired)
            assertNull(s.pendingAtElapsed)
            assertEquals(s, Policy.reduce(s, PolicyEvent.ManualStart, 3000))
        }
    }

    @Test fun unknownCancelsBothWaitingAndReadyConfirmation() {
        val waiting = event(PolicySnapshot(mode = OperatingMode.AUTO), VehicleState.DISCONNECTED, 1)
        val ready = Policy.reduce(waiting, PolicyEvent.Deadline(waiting.generation), waiting.pendingAtElapsed!!)
        for (before in listOf(waiting, ready)) {
            val stopped = event(before, VehicleState.UNKNOWN, 2)
            assertNull(stopped.pendingAtElapsed)
            assertFalse(stopped.needsConfirmation)
            assertFalse(stopped.desired)
            assertEquals(stopped, Policy.reduce(stopped, PolicyEvent.Confirm(before.generation), 500_000))
        }
    }

    @Test fun disconnectedAfterManualSafetyStopRequestsANewDelayAndConfirmationOnly() {
        val manual = active(OperatingMode.MANUAL_MOCK)
        val connected = event(manual, VehicleState.CONNECTED, 1)
        val disconnected = event(connected, VehicleState.DISCONNECTED, 2)
        assertEquals(OperatingMode.AUTO, disconnected.mode)
        assertFalse(disconnected.desired)
        assertEquals(122_000L, disconnected.pendingAtElapsed)
        val ready = Policy.reduce(disconnected, PolicyEvent.Deadline(disconnected.generation), 122_000)
        assertTrue(ready.needsConfirmation)
        assertFalse(ready.desired)
        assertEquals(ready, Policy.reduce(ready, PolicyEvent.Confirm(manual.generation), 122_000))
        assertTrue(Policy.reduce(ready, PolicyEvent.Confirm(ready.generation), 122_000).desired)
    }

    @Test fun repeatedConnectedSnapshotsAreSafeAndReplayDoesNothing() {
        var s = event(active(OperatingMode.MANUAL_MOCK), VehicleState.CONNECTED, 1)
        repeat(10) {
            val old = s
            s = event(old, VehicleState.CONNECTED, old.lastSequence + 1)
            assertFalse(s.desired)
            assertNull(s.pendingAtElapsed)
            assertEquals(OperatingMode.AUTO, s.mode)
            assertEquals(s, event(s, VehicleState.CONNECTED, s.lastSequence))
        }
    }

    @Test fun sameMillisecondOrderingRejectsOlderDisconnectAfterManualSafetyStop() {
        val connected = event(active(OperatingMode.MANUAL_MOCK), VehicleState.CONNECTED, 20, time = 1000)
        assertEquals(connected, event(connected, VehicleState.DISCONNECTED, 19, time = 1000))
        val disconnected = event(connected, VehicleState.DISCONNECTED, 21, time = 1000)
        assertEquals(connected, disconnected) // safety wins a same-millisecond contradiction
        val reconnected = event(disconnected, VehicleState.CONNECTED, 22, time = 1000)
        assertFalse(reconnected.desired)
        assertNull(reconnected.pendingAtElapsed)
        assertEquals(1000L, reconnected.lastEventTime)
    }

    @Test fun recoveryAfterVehicleShutdownNeverRestoresManualInjection() {
        val stopped = event(active(OperatingMode.MANUAL_MOCK), VehicleState.CONNECTED, 3)
        val recovered = Policy.reduce(stopped, PolicyEvent.Recover, 0)
        assertEquals(OperatingMode.AUTO, recovered.mode)
        assertFalse(recovered.desired)
        assertNull(recovered.pendingAtElapsed)
        assertEquals(VehicleState.UNKNOWN, recovered.vehicle)
        assertEquals(ProviderState.UNKNOWN, recovered.providers)
    }

    @Test fun tileForceRealStopsManualAndCannotBeDefeatedByVehicleEvents() {
        val manual = active(OperatingMode.MANUAL_MOCK)
        assertEquals(TileAction.FORCE_REAL, TilePresentation.from(manual).action)
        val stopped = Policy.reduce(manual, PolicyEvent.SelectMode(OperatingMode.FORCE_REAL), 1000)
        val disconnected = event(stopped, VehicleState.DISCONNECTED, 1)
        assertFalse(disconnected.desired)
        assertEquals(OperatingMode.FORCE_REAL, disconnected.mode)
        assertEquals("FORCE_REAL", TilePresentation.from(disconnected).label)
    }

    @Test fun knownConnectedVehicleBlocksANewManualStart() {
        val connected = event(PolicySnapshot(mode = OperatingMode.AUTO), VehicleState.CONNECTED, 1)
        assertEquals(connected, Policy.reduce(connected, PolicyEvent.ManualStart, 3000))
        val disconnected = event(connected, VehicleState.DISCONNECTED, 2)
        assertTrue(Policy.reduce(disconnected, PolicyEvent.ManualStart, 3000).desired)
    }
}
