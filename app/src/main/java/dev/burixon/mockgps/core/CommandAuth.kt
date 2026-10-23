package dev.burixon.mockgps.core

import java.security.MessageDigest

/** A capability authenticates possession, not a caller-supplied package identity. */
data class ExternalRequest(val api: Int, val token: String?, val command: String?, val eventTime: Long)
data class AuthResult(val accepted: Boolean, val code: String)
object CommandAuth {
    private val commands = setOf("vehicle_connected", "vehicle_disconnected", "vehicle_unknown", "stop", "status")
    fun authorize(enabled: Boolean, expectedToken: String?, request: ExternalRequest, nowWall: Long, lastEventTime: Long): AuthResult {
        fun reject(code: String) = AuthResult(false, code)
        if (!enabled) return reject("DISABLED")
        if (request.api != 1) return reject("UNSUPPORTED_API")
        val supplied = request.token
        if (supplied == null || supplied.length != 64 || expectedToken == null || expectedToken.length != 64 ||
            !supplied.all { it in '0'..'9' || it in 'a'..'f' } ||
            !MessageDigest.isEqual(expectedToken.toByteArray(Charsets.US_ASCII), supplied.toByteArray(Charsets.US_ASCII))) {
            return reject("UNAUTHORIZED")
        }
        if (request.command !in commands) return reject("UNSUPPORTED_COMMAND")
        // Compare before subtraction so malicious Long.MIN_VALUE cannot overflow into freshness.
        if (request.eventTime < 0 || request.eventTime > nowWall || nowWall - request.eventTime > 60_000) return reject("EXPIRED_EVENT")
        if (request.eventTime <= lastEventTime) return reject("REPLAYED_EVENT")
        return AuthResult(true, "ACCEPTED")
    }
}
