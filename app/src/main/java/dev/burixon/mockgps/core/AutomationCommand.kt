package dev.burixon.mockgps.core

/** The transport-independent command surface. No external command can start simulation. */
enum class AutomationCommand(
    val wireName: String,
    val shortcutName: String,
    val readinessState: ReadinessState? = null,
) {
    PAUSE("pause", "pause", ReadinessState.BLOCKED),
    REQUEST_SIMULATION("request_simulation", "request_simulation", ReadinessState.READY),
    UNKNOWN("unknown", "unknown", ReadinessState.UNKNOWN),
    STOP("stop", "stop"),
    STATUS("status", "status");

    val mutates: Boolean get() = this != STATUS

    companion object {
        const val API_VERSION = 3

        fun fromWire(value: String?): AutomationCommand? = entries.firstOrNull { it.wireName == value }
        fun fromShortcut(value: String?): AutomationCommand? = entries.firstOrNull { it.shortcutName == value }
    }
}
