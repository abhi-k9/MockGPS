package dev.burixon.mockgps

import android.content.Context

/** Extra change alerts are independent of Android's required foreground-service notification. */
enum class AlertMode { NOTIFICATION, TOAST, BOTH, NONE }

/** Explicit local consent and presentation preferences; application backup is disabled. */
object AutomationSettings {
    private var unattendedWriteFailed = false
    private var presetAccessWriteFailed = false
    private var maxSessionFallback: Int? = null
    private fun preferences(context: Context) =
        context.getSharedPreferences("automation_settings", Context.MODE_PRIVATE)

    fun unattendedEnabled(context: Context): Boolean =
        !unattendedWriteFailed &&
            runCatching { preferences(context).getBoolean("unattended_enabled", false) }.getOrDefault(false)

    internal fun setUnattendedEnabled(context: Context, enabled: Boolean) {
        // A failed commit can still change SharedPreferences' in-memory value.
        // Do not expose an unpersisted grant to later foreground-service calls.
        val committed = try {
            preferences(context).edit().putBoolean("unattended_enabled", enabled).commit()
        } catch (failure: RuntimeException) {
            unattendedWriteFailed = true
            throw failure
        }
        unattendedWriteFailed = !committed
        check(committed) { "Automation settings unavailable" }
    }

    fun alertMode(context: Context): AlertMode =
        runCatching {
            AlertMode.valueOf(preferences(context).getString("alert_mode", AlertMode.TOAST.name)!!)
        }.getOrDefault(AlertMode.TOAST)

    fun errorAlerts(context: Context): Boolean = preferences(context).getBoolean("error_alerts", true)
    internal fun setErrorAlerts(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean("error_alerts", enabled).commit())
        if (!enabled) context.getSystemService(android.app.NotificationManager::class.java).cancel(2004)
    }
    fun maxSessionMinutes(context: Context): Int = maxSessionFallback ?: preferences(context).getInt("max_session_minutes", 0).coerceIn(0, 1440)
    internal fun setMaxSessionMinutes(context: Context, minutes: Int) {
        require(minutes in 0..1440)
        val previous = maxSessionMinutes(context)
        try {
            check(preferences(context).edit().putInt("max_session_minutes", minutes).commit())
            maxSessionFallback = null
        } catch (failure: RuntimeException) { maxSessionFallback = previous; throw failure }
    }
    fun defaultPresetAccess(context: Context): Boolean = !presetAccessWriteFailed && preferences(context).getBoolean("default_preset_access", false)
    internal fun setDefaultPresetAccess(context: Context, enabled: Boolean) {
        try {
            check(preferences(context).edit().putBoolean("default_preset_access", enabled).commit())
            presetAccessWriteFailed = false
        } catch (failure: RuntimeException) { presetAccessWriteFailed = true; throw failure }
    }

    internal fun setAlertMode(context: Context, mode: AlertMode) {
        if (mode == AlertMode.TOAST || mode == AlertMode.NONE)
            context.getSystemService(android.app.NotificationManager::class.java).cancel(2003)
        check(preferences(context).edit().putString("alert_mode", mode.name).commit()) {
            "Alert settings unavailable"
        }
    }
}
