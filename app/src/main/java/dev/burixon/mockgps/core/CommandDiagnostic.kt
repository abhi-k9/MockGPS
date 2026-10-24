package dev.burixon.mockgps.core

/** One in-memory record per kind, never persisted and never includes a supplied value. */
data class CommandDiagnostic(
    val lastAccepted: String = "None",
    val lastRejected: String = "None",
) {
    fun accepted(command: String): CommandDiagnostic =
        copy(
            lastAccepted =
                when (command) {
                    "pause", "request_simulation", "unknown", "stop", "status" -> command
                    else -> "UNSUPPORTED_COMMAND"
                },
        )

    fun rejected(code: String): CommandDiagnostic =
        copy(
            lastRejected =
                when (code) {
                    "UNSUPPORTED_ACTION", "MALFORMED_REQUEST", "DISABLED", "UNSUPPORTED_API", "UNAUTHORIZED",
                    "UNSUPPORTED_COMMAND", "EXPIRED_EVENT", "INVALID_SEQUENCE", "REPLAYED_EVENT",
                    "STORAGE_UNAVAILABLE", "STALE_OBSERVATION",
                    -> code
                    else -> "REJECTED"
                },
        )
}
