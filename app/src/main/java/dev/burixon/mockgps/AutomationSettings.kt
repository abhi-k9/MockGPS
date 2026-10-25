package dev.burixon.mockgps

import android.content.Context

/** Extra change alerts are independent of Android's required foreground-service notification. */
enum class AlertMode { NOTIFICATION, TOAST, BOTH, NONE }

/** Explicit local consent and presentation preferences; application backup is disabled. */
object AutomationSettings {
    private var unattendedWriteFailed = false
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
            AlertMode.valueOf(preferences(context).getString("alert_mode", AlertMode.NOTIFICATION.name)!!)
        }.getOrDefault(AlertMode.NOTIFICATION)

    internal fun setAlertMode(context: Context, mode: AlertMode) {
        check(preferences(context).edit().putString("alert_mode", mode.name).commit()) {
            "Alert settings unavailable"
        }
    }
}
