package dev.burixon.mockgps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BetaSafetyTest {
    @Test fun repeatedManualStartDoesNotRestartAnAdmittedSession() {
        val started = Policy.reduce(PolicySnapshot(mode = OperatingMode.AUTO), PolicyEvent.ManualStart, 0)
        assertTrue(started.desired)
        assertEquals(started, Policy.reduce(started, PolicyEvent.ManualStart, 1))
        val injecting =
            started.copy(
                service = ServiceState.INJECTING,
                providers = ProviderState.READY,
                injection = InjectionState.CONFIRMED,
            )
        assertEquals(injecting, Policy.reduce(injecting, PolicyEvent.ManualStart, 2))
    }

    @Test fun randomizedSafetyEventsCannotResurrectCanceledGenerations() {
        val random = Random(427)
        var s = PolicySnapshot(mode = OperatingMode.AUTO)
        repeat(1000) { seq ->
            s = Policy.reduce(s, PolicyEvent.ManualStart, seq.toLong())
            val old = s.generation
            val vehicle = if (random.nextBoolean()) VehicleState.CONNECTED else VehicleState.UNKNOWN
            s = Policy.reduce(s, PolicyEvent.Vehicle(vehicle, 1000, seq.toLong()), 2000)
            assertFalse(s.desired)
            assertNull(s.pendingAtElapsed)
            val stopped = s
            assertEquals(stopped, Policy.reduce(s, PolicyEvent.Confirm(old), 5000))
            assertEquals(stopped, Policy.reduce(s, PolicyEvent.Deadline(old), 5000))
            assertEquals(stopped, Policy.reduce(s, PolicyEvent.Vehicle(VehicleState.DISCONNECTED, 1000, seq.toLong()), 5000))
        }
    }

    @Test fun diagnosticsCannotRetainUntrustedValues() {
        val supplied = "secret-or-location-supplied-by-sender"
        assertEquals("UNSUPPORTED_COMMAND", CommandDiagnostic().accepted(supplied).lastAccepted)
        assertEquals("REJECTED", CommandDiagnostic().rejected(supplied).lastRejected)
        assertEquals("REPLAYED_EVENT", CommandDiagnostic().rejected("REPLAYED_EVENT").lastRejected)
    }
}
