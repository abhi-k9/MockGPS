package dev.burixon.mockgps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationOrderingTest {
    private val token = "a".repeat(64)

    @Test fun higherCounterCannotMakeAnOldDisconnectOverrideSafety() {
        val connected =
            Policy.reduce(
                PolicySnapshot(mode = OperatingMode.AUTO),
                PolicyEvent.Vehicle(VehicleState.CONNECTED, 100, 1),
                0,
            )
        for (time in listOf(99L, 100L)) {
            val request = ExternalRequest(2, token, "vehicle_disconnected", time, 2)
            assertEquals(
                AuthResult(false, "STALE_VEHICLE_OBSERVATION"),
                CommandAuth.authorize(true, token, request, 101, 1, 100, VehicleState.CONNECTED),
            )
            assertEquals(connected, Policy.reduce(connected, PolicyEvent.Vehicle(VehicleState.DISCONNECTED, time, 2), 1))
        }
        val fresh = Policy.reduce(connected, PolicyEvent.Vehicle(VehicleState.DISCONNECTED, 101, 2), 1)
        assertNotNull(fresh.pendingAtElapsed)
        assertFalse(fresh.desired)
    }

    @Test fun lateSafetyStopsAndCannotRegressTheTimestampWatermark() {
        var s = PolicySnapshot(mode = OperatingMode.AUTO)
        s = Policy.reduce(s, PolicyEvent.Vehicle(VehicleState.DISCONNECTED, 100, 1), 0)
        val generation = s.generation
        s = Policy.reduce(s, PolicyEvent.Vehicle(VehicleState.UNKNOWN, 99, 2), 1)
        assertEquals(100L, s.lastEventTime)
        assertNull(s.pendingAtElapsed)
        assertTrue(s.generation > generation)
        assertEquals(s, Policy.reduce(s, PolicyEvent.Vehicle(VehicleState.DISCONNECTED, 100, 3), 2))
        assertEquals(s, Policy.reduce(s, PolicyEvent.Deadline(generation), 999999))
    }

    @Test fun duplicatesAndReadOnlyStatusKeepTheirExistingSemantics() {
        val s =
            Policy.reduce(
                PolicySnapshot(mode = OperatingMode.AUTO),
                PolicyEvent.Vehicle(VehicleState.DISCONNECTED, 100, 1),
                0,
            )
        val duplicate = Policy.reduce(s, PolicyEvent.Vehicle(VehicleState.DISCONNECTED, 100, 2), 1)
        assertEquals(s.generation, duplicate.generation)
        assertEquals(s.pendingAtElapsed, duplicate.pendingAtElapsed)
        assertTrue(
            CommandAuth
                .authorize(
                    true,
                    token,
                    ExternalRequest(2, token, "status", 99, 0),
                    101,
                    2,
                    100,
                    VehicleState.UNKNOWN,
                ).accepted,
        )
        assertEquals(
            OperatingMode.FORCE_REAL,
            Policy
                .reduce(
                    s.copy(mode = OperatingMode.FORCE_REAL),
                    PolicyEvent.Vehicle(VehicleState.DISCONNECTED, 101, 3),
                    2,
                ).mode,
        )
    }
}
