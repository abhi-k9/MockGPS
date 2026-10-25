package dev.burixon.mockgps.core

enum class ClientScope { STATUS, READINESS, STOP, SELECT_PRESET;
    fun permits(command: AutomationCommand): Boolean = when (command) {
        AutomationCommand.STATUS -> this == STATUS
        AutomationCommand.PAUSE, AutomationCommand.REQUEST_SIMULATION, AutomationCommand.UNKNOWN -> this == READINESS
        AutomationCommand.STOP -> this == STOP
        AutomationCommand.SELECT_PRESET -> this == SELECT_PRESET
    }
}
