package dev.burixon.mockgps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationOrderingTest {
    private val token = "a".repeat(64)

    @Test fun higherCounterCannotMakeAnOldRequestOverrideSafety() {
        val paused =
            Policy.reduce(
                PolicySnapshot(mode = OperatingMode.AUTO),
                PolicyEvent.Readiness(ReadinessState.BLOCKED, 100, 1),
                0,
            )
        for (time in listOf(99L, 100L)) {
            val request = ExternalRequest(AutomationCommand.API_VERSION, token, "request_simulation", time, 2)
            assertEquals(
                AuthResult(false, "STALE_OBSERVATION"),
                CommandAuth.authorize(true, token, request, 101, 1, 100, ReadinessState.BLOCKED),
            )
            assertEquals(paused, Policy.reduce(paused, PolicyEvent.Readiness(ReadinessState.READY, time, 2), 1))
        }
        val fresh = Policy.reduce(paused, PolicyEvent.Readiness(ReadinessState.READY, 101, 2), 1)
        assertNotNull(fresh.pendingAtElapsed)
        assertFalse(fresh.desired)
    }

    @Test fun lateSafetyStopsAndCannotRegressTheTimestampWatermark() {
        var s = PolicySnapshot(mode = OperatingMode.AUTO)
        s = Policy.reduce(s, PolicyEvent.Readiness(ReadinessState.READY, 100, 1), 0)
        val generation = s.generation
        s = Policy.reduce(s, PolicyEvent.Readiness(ReadinessState.UNKNOWN, 99, 2), 1)
        assertEquals(100L, s.lastEventTime)
        assertNull(s.pendingAtElapsed)
        assertTrue(s.generation > generation)
        assertEquals(s, Policy.reduce(s, PolicyEvent.Readiness(ReadinessState.READY, 100, 3), 2))
        assertEquals(s, Policy.reduce(s, PolicyEvent.Deadline(generation), 999999))
    }

    @Test fun duplicatesAndReadOnlyStatusKeepTheirExistingSemantics() {
        val s =
            Policy.reduce(
                PolicySnapshot(mode = OperatingMode.AUTO),
                PolicyEvent.Readiness(ReadinessState.READY, 100, 1),
                0,
            )
        val duplicate = Policy.reduce(s, PolicyEvent.Readiness(ReadinessState.READY, 100, 2), 1)
        assertEquals(s.generation, duplicate.generation)
        assertEquals(s.pendingAtElapsed, duplicate.pendingAtElapsed)
        assertTrue(
            CommandAuth
                .authorize(
                    true,
                    token,
                    ExternalRequest(AutomationCommand.API_VERSION, token, "status", 99, 0),
                    101,
                    2,
                    100,
                    ReadinessState.UNKNOWN,
                ).accepted,
        )
        assertEquals(
            OperatingMode.FORCE_REAL,
            Policy
                .reduce(
                    s.copy(mode = OperatingMode.FORCE_REAL),
                    PolicyEvent.Readiness(ReadinessState.READY, 101, 3),
                    2,
                ).mode,
        )
    }
}
