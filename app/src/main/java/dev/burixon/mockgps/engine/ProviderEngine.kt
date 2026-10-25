package dev.burixon.mockgps.engine

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A selected simulation point. (0, 0) is valid only when explicitly selected. */
data class Coordinates(val latitude: Double, val longitude: Double) {
    val valid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    /** A spherical offset avoids the longitude singularity at the poles. */
    fun offset(distanceMeters: Double, bearingRadians: Double): Coordinates {
        require(valid && distanceMeters.isFinite() && distanceMeters in 0.0..15.0)
        require(bearingRadians.isFinite())
        if (distanceMeters == 0.0) return this
        val latitudeRadians = Math.toRadians(latitude)
        val angularDistance = distanceMeters / 6_371_000.0
        val nextLatitude = asin((sin(latitudeRadians) * cos(angularDistance) +
            cos(latitudeRadians) * sin(angularDistance) * cos(bearingRadians)).coerceIn(-1.0, 1.0))
        val nextLongitude = Math.toRadians(longitude) + atan2(
            sin(bearingRadians) * sin(angularDistance) * cos(latitudeRadians),
            cos(angularDistance) - sin(latitudeRadians) * sin(nextLatitude)
        )
        val wrappedLongitude = ((Math.toDegrees(nextLongitude) + 540.0) % 360.0) - 180.0
        return Coordinates(Math.toDegrees(nextLatitude), wrappedLongitude)
    }
}

enum class MockProvider(val platformName: String) { GPS("gps"), NETWORK("network") }

data class MockFix(
    val coordinates: Coordinates,
    val wallTimeMillis: Long,
    val elapsedRealtimeNanos: Long,
    val accuracyMeters: Float = 3f,
    val altitudeMeters: Double = 0.0,
    val speedMetersPerSecond: Float = 0f,
    val bearingDegrees: Float = 0f
) {
    val valid: Boolean
        get() = coordinates.valid && wallTimeMillis > 0 && elapsedRealtimeNanos > 0 &&
            accuracyMeters.isFinite() && accuracyMeters > 0f && altitudeMeters.isFinite() &&
            speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0f &&
            bearingDegrees.isFinite() && bearingDegrees >= 0f && bearingDegrees < 360f
}

/** Android-specific operations live behind this boundary. Ownership is a durable intent journal. */
interface ProviderPort {
    fun ownedProviders(): Set<MockProvider>
    fun markOwned(provider: MockProvider, owned: Boolean)
    fun add(provider: MockProvider)
    fun enable(provider: MockProvider)
    fun inject(provider: MockProvider, fix: MockFix)
    fun disable(provider: MockProvider)
    fun remove(provider: MockProvider)
}

data class EngineResult(
    val success: Boolean,
    val code: String,
    val cleanupUncertain: Boolean = false,
    val providersReady: Int = 0,
    val providersInjected: Int = 0
)

/** Called only by the serialized service/controller. It never retries or schedules work. */
class ProviderEngine(private val port: ProviderPort) {
    private val ready = mutableSetOf<MockProvider>()
    // Storage may fail after providers were created. Keep the current session's
    // ownership in memory as well so a broken journal cannot prevent cleanup.
    private val sessionOwned = mutableSetOf<MockProvider>()

    fun start(point: Coordinates): EngineResult {
        if (!point.valid) {
            val cleanup = stop()
            return EngineResult(false, "INVALID_COORDINATES", !cleanup.success)
        }
        val cleanup = stop()
        if (!cleanup.success) return EngineResult(false, "RECOVERY_CLEANUP_FAILED", true)
        for (provider in MockProvider.entries) {
            try {
                // Write before add: a process death after the binder call still leaves recovery evidence.
                port.markOwned(provider, true)
                sessionOwned.add(provider)
                port.add(provider)
                port.enable(provider)
                ready.add(provider)
            } catch (_: Exception) {
                val rollback = stop()
                return EngineResult(false, "PROVIDER_CREATE_FAILED_${provider.name}", !rollback.success)
            }
        }
        return EngineResult(true, "PROVIDERS_READY", providersReady = ready.size)
    }

    fun publish(fix: MockFix): EngineResult {
        if (!fix.valid) {
            val cleanup = stop()
            return EngineResult(false, "INVALID_LOCATION_METADATA", !cleanup.success)
        }
        if (ready.size != MockProvider.entries.size) {
            val cleanup = stop()
            return EngineResult(false, "PROVIDERS_NOT_READY", !cleanup.success)
        }
        var injected = 0
        for (provider in MockProvider.entries) {
            try {
                port.inject(provider, fix)
                injected++
            } catch (_: Exception) {
                val cleanup = stop()
                return EngineResult(
                    false, "PROVIDER_INJECTION_FAILED_${provider.name}", !cleanup.success,
                    providersInjected = injected
                )
            }
        }
        return EngineResult(true, "INJECTION_ACCEPTED", providersReady = ready.size, providersInjected = injected)
    }

    /** Removal success confirms API cleanup, never a fresh genuine location fix. */
    fun stop(): EngineResult {
        ready.clear()
        var journalUnreadable = false
        val durableOwned = try {
            port.ownedProviders()
        } catch (_: Exception) {
            journalUnreadable = true
            emptySet()
        }
        val owned = durableOwned + sessionOwned
        var uncertain = journalUnreadable
        for (provider in owned) {
            // Removal is attempted even if disable fails; successful removal is definitive cleanup.
            try { port.disable(provider) } catch (_: Exception) { /* remove still required */ }
            try {
                port.remove(provider)
                port.markOwned(provider, false)
                sessionOwned.remove(provider)
            } catch (_: Exception) {
                uncertain = true
            }
        }
        val code = when {
            journalUnreadable -> "OWNERSHIP_READ_FAILED"
            uncertain -> "PROVIDER_CLEANUP_UNCERTAIN"
            else -> "PROVIDERS_REMOVED"
        }
        return EngineResult(!uncertain, code, uncertain)
    }
}
