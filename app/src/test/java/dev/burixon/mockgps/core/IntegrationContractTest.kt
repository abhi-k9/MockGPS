package dev.burixon.mockgps.core

import org.junit.Assert.*
import org.junit.Test

class IntegrationContractTest {
    @Test fun presetCommandRequiresAnExactTypedIdentifier() {
        val raw = mapOf("api" to 3, "token" to "a".repeat(64), "command" to "select_preset", "event_time" to 1000L,
            "seq" to 1L, "preset_id" to "00000000-0000-0000-0000-000000000000")
        assertNotNull(CommandAuth.parse(raw))
        assertNull(CommandAuth.parse(raw - "preset_id"))
        assertNull(CommandAuth.parse(raw + ("preset_id" to 5)))
        assertNull(CommandAuth.parse(raw + ("command" to "status")))
        assertNull(CommandAuth.parse(raw + ("preset_id" to "../path")))
    }
    @Test fun scopesDoNotImplicitlyGrantOtherCommands() {
        for (scope in ClientScope.entries) for (command in AutomationCommand.entries) {
            val expected = when (scope) {
                ClientScope.STATUS -> setOf(AutomationCommand.STATUS)
                ClientScope.READINESS -> setOf(AutomationCommand.PAUSE, AutomationCommand.REQUEST_SIMULATION, AutomationCommand.UNKNOWN)
                ClientScope.STOP -> setOf(AutomationCommand.STOP)
                ClientScope.SELECT_PRESET -> setOf(AutomationCommand.SELECT_PRESET)
            }
            assertEquals(command in expected, scope.permits(command))
        }
    }
    @Test fun separateReplayCountersDoNotInterfere() {
        val request = ExternalRequest(3, "a".repeat(64), "pause", 1000, 2)
        assertTrue(CommandAuth.authorize(true, request.token, request, 1000, 1).accepted)
        assertFalse(CommandAuth.authorize(true, request.token, request, 1000, 5).accepted)
    }
    @Test fun allEnabledReadinessClientsMustAgreeAndSafetyInvalidatesOldReadyEvidence() {
        var sources = SourceAgreement(clients = mapOf("one" to ReadinessState.UNKNOWN, "two" to ReadinessState.UNKNOWN))
        sources = sources.client("one", ReadinessState.READY)
        assertEquals(ReadinessState.UNKNOWN, sources.readiness)
        sources = sources.client("two", ReadinessState.READY)
        assertEquals(ReadinessState.READY, sources.readiness)
        sources = sources.client("one", ReadinessState.BLOCKED)
        assertEquals(ReadinessState.BLOCKED, sources.readiness)
        sources = sources.client("one", ReadinessState.READY)
        assertEquals(ReadinessState.UNKNOWN, sources.readiness)
        sources = sources.client("two", ReadinessState.READY)
        assertEquals(ReadinessState.READY, sources.readiness)
    }
}
