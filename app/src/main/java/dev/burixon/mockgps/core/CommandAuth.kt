package dev.burixon.mockgps.core

import java.security.MessageDigest

/** A capability authenticates possession, not a caller-supplied package identity. */
data class ExternalRequest(val api: Int, val token: String?, val command: String?, val eventTime: Long, val sequence: Long = eventTime)
data class AuthResult(val accepted: Boolean, val code: String)
object CommandAuth {
    /** Read raw Bundle values without coercion or Android type-warning logs containing supplied values. */
    fun parse(extras: Map<String, Any?>): ExternalRequest? {
        if (extras.keys != setOf("api", "token", "command", "event_time", "seq")) return null
        return ExternalRequest(extras["api"] as? Int ?: return null,
            extras["token"] as? String ?: return null, extras["command"] as? String ?: return null,
            extras["event_time"] as? Long ?: return null, extras["seq"] as? Long ?: return null)
    }

    private val commands = setOf("vehicle_connected", "vehicle_disconnected", "vehicle_unknown", "stop", "status")
    fun authorize(enabled: Boolean, expectedToken: String?, request: ExternalRequest, nowWall: Long, lastSequence: Long,
        lastEventTime: Long = -1, lastVehicle: VehicleState = VehicleState.UNKNOWN): AuthResult {
        fun reject(code: String) = AuthResult(false, code)
        if (!enabled) return reject("DISABLED")
        if (request.api != 2) return reject("UNSUPPORTED_API")
        val supplied = request.token
        if (supplied == null || supplied.length != 64 || expectedToken == null || expectedToken.length != 64 ||
            !supplied.all { it in '0'..'9' || it in 'a'..'f' } ||
            !MessageDigest.isEqual(expectedToken.toByteArray(Charsets.US_ASCII), supplied.toByteArray(Charsets.US_ASCII))) {
            return reject("UNAUTHORIZED")
        }
        if (request.command !in commands) return reject("UNSUPPORTED_COMMAND")
        // Compare before subtraction so malicious Long.MIN_VALUE cannot overflow into freshness.
        if (request.eventTime < 0 || request.eventTime > nowWall || nowWall - request.eventTime > 60_000) return reject("EXPIRED_EVENT")
        if (request.sequence < 0 || request.sequence == Long.MAX_VALUE) return reject("INVALID_SEQUENCE")
        if (request.command != "status" && request.sequence <= lastSequence) return reject("REPLAYED_EVENT")
        // A late sender must not turn an older observation into permission to resume by
        // allocating a newer counter. Safety wins timestamp ties; retry a fresh query.
        if (request.command == "vehicle_disconnected" && (request.eventTime < lastEventTime ||
            (request.eventTime == lastEventTime && lastVehicle != VehicleState.DISCONNECTED))) {
            return reject("STALE_VEHICLE_OBSERVATION")
        }
        return AuthResult(true, "ACCEPTED")
    }
}
