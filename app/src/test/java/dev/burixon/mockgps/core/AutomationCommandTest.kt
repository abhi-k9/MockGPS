package dev.burixon.mockgps.core

import org.junit.Assert.*
import org.junit.Test

class AutomationCommandTest {
    @Test fun everyTransportUsesTheSameContextNeutralCommands() {
        val names = setOf("pause", "request_simulation", "unknown", "stop", "status")
        assertEquals(names, AutomationCommand.entries.map { it.wireName }.toSet())
        for (command in AutomationCommand.entries) {
            assertEquals(command, AutomationCommand.fromWire(command.wireName))
            assertEquals(command, AutomationCommand.fromShortcut(command.shortcutName))
        }
    }

    @Test fun unsupportedOrContextSpecificNamesCannotAcquireACommand() {
        for (name in listOf(null, "", "start", "unsupported", "paused", "PAUSE", "pause ")) {
            assertNull(AutomationCommand.fromWire(name))
            assertNull(AutomationCommand.fromShortcut(name))
        }
    }

    @Test fun requestOnlySuppliesReadinessAndStatusCannotMutate() {
        assertEquals(ReadinessState.READY, AutomationCommand.REQUEST_SIMULATION.readinessState)
        assertEquals(ReadinessState.BLOCKED, AutomationCommand.PAUSE.readinessState)
        assertEquals(ReadinessState.UNKNOWN, AutomationCommand.UNKNOWN.readinessState)
        assertNull(AutomationCommand.STOP.readinessState)
        assertNull(AutomationCommand.STATUS.readinessState)
        assertFalse(AutomationCommand.STATUS.mutates)
        assertTrue(AutomationCommand.entries.filter { it.mutates }.size == 4)
    }
}
