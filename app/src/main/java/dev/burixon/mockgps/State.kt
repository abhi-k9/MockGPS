package dev.burixon.mockgps

import android.content.Context

/** Selected mock configuration only. Genuine location is ephemeral and never backed up. */
object State {
    private fun prefs(context: Context) = context.getSharedPreferences("mock_configuration", Context.MODE_PRIVATE)
    private var realLat: Double? = null
    private var realLon: Double? = null

    fun migrate(context: Context) {
        val p = prefs(context)
        val legacy = context.createDeviceProtectedStorageContext().getSharedPreferences("mockgps", Context.MODE_PRIVATE)
        if (p.getBoolean("migrated", false)) {
            // Retry privacy cleanup even if an earlier commit succeeded but legacy clearing failed.
            if (legacy.all.isNotEmpty()) check(legacy.edit().clear().commit())
            return
        }
        val lat = legacy.getString("lat", null)?.toDoubleOrNull()
        val lon = legacy.getString("lon", null)?.toDoubleOrNull()
        val editor = p.edit().putBoolean("migrated", true)
            .putBoolean("legacy_unverified", legacy.all.isNotEmpty())
        // Old zero/zero may have been a silent fallback. Require explicit reselection after upgrade.
        if (lat != null && lon != null && validLat(lat) && validLon(lon) && !(lat == 0.0 && lon == 0.0)) {
            editor.putString("lat", lat.toString()).putString("lon", lon.toString())
        }
        check(editor.commit())
        check(legacy.edit().clear().commit()) // clear genuine coordinates, open-control and boot flags
    }
    fun legacyProviderStateUnverified(context: Context): Boolean = prefs(context).getBoolean("legacy_unverified", false)
    fun clearLegacyUncertainty(context: Context) { check(prefs(context).edit().putBoolean("legacy_unverified", false).commit()) }
    fun setLocation(context: Context, lat: Double, lon: Double) {
        require(validLat(lat) && validLon(lon))
        check(prefs(context).edit().putString("lat", lat.toString()).putString("lon", lon.toString()).commit())
        ControlCenter.changed(context)
    }
    fun getLat(context: Context): Double? = prefs(context).getString("lat", null)?.toDoubleOrNull()?.takeIf(::validLat)
    fun getLon(context: Context): Double? = prefs(context).getString("lon", null)?.toDoubleOrNull()?.takeIf(::validLon)
    fun setRealLocation(@Suppress("UNUSED_PARAMETER") context: Context, lat: Double, lon: Double) {
        if (validLat(lat) && validLon(lon)) { realLat = lat; realLon = lon }
    }
    fun getRealLat(@Suppress("UNUSED_PARAMETER") context: Context): Double? = realLat
    fun getRealLon(@Suppress("UNUSED_PARAMETER") context: Context): Double? = realLon
    fun isServiceRunning(context: Context): Boolean = ControlCenter.snapshot(context).service == dev.burixon.mockgps.core.ServiceState.INJECTING
    fun isBroadcastEnabled(context: Context): Boolean = AutomationAccess.isEnabled(context)
    fun setBroadcastEnabled(context: Context, enabled: Boolean) = ControlCenter.setIntegrationEnabled(context, enabled)
    fun setDrifting(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean("drifting", enabled).commit())
        ControlCenter.changed(context)
    }
    fun isDrifting(context: Context): Boolean = prefs(context).getBoolean("drifting", false)
    fun validLat(value: Double): Boolean = value.isFinite() && value in -90.0..90.0
    fun validLon(value: Double): Boolean = value.isFinite() && value in -180.0..180.0
}
