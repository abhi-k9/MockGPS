package dev.burixon.mockgps.engine

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build

/** Uses only normal Developer Options test-provider APIs; Android supplies mock flags. */
class AndroidProviderPort(context: Context) : ProviderPort {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val journal = context.getSharedPreferences("provider_ownership", Context.MODE_PRIVATE)

    override fun ownedProviders(): Set<MockProvider> =
        MockProvider.entries.filter { journal.getBoolean(it.platformName, false) }.toSet()

    override fun markOwned(provider: MockProvider, owned: Boolean) {
        check(journal.edit().putBoolean(provider.platformName, owned).commit()) { "Ownership persistence failed" }
    }

    @Suppress("DEPRECATION")
    override fun add(provider: MockProvider) {
        val fine = provider == MockProvider.GPS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val properties = ProviderProperties.Builder()
                .setAccuracy(if (fine) ProviderProperties.ACCURACY_FINE else ProviderProperties.ACCURACY_COARSE)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .build()
            manager.addTestProvider(provider.platformName, properties)
        } else {
            manager.addTestProvider(
                provider.platformName, false, false, false, false,
                true, true, true, ProviderProperties.POWER_USAGE_LOW,
                if (fine) ProviderProperties.ACCURACY_FINE else ProviderProperties.ACCURACY_COARSE
            )
        }
    }

    override fun enable(provider: MockProvider) {
        manager.setTestProviderEnabled(provider.platformName, true)
    }

    override fun inject(provider: MockProvider, fix: MockFix) {
        manager.setTestProviderLocation(provider.platformName, Location(provider.platformName).apply {
            latitude = fix.coordinates.latitude
            longitude = fix.coordinates.longitude
            accuracy = fix.accuracyMeters
            time = fix.wallTimeMillis
            elapsedRealtimeNanos = fix.elapsedRealtimeNanos
            altitude = fix.altitudeMeters
            speed = fix.speedMetersPerSecond
            bearing = fix.bearingDegrees
        })
    }

    override fun disable(provider: MockProvider) {
        try {
            manager.setTestProviderEnabled(provider.platformName, false)
        } catch (_: IllegalArgumentException) {
            // Android documents this when the named test provider is already absent.
        }
    }

    override fun remove(provider: MockProvider) {
        try {
            manager.removeTestProvider(provider.platformName)
        } catch (_: IllegalArgumentException) {
            // Idempotent recovery after system/provider removal. Security failures still propagate.
        }
    }
}
