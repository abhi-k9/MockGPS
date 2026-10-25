package dev.burixon.mockgps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import dev.burixon.mockgps.core.InjectionState
import dev.burixon.mockgps.core.PolicySnapshot
import dev.burixon.mockgps.core.ServiceState

/** Optional coordinate-free feedback; never drives policy or foreground-service lifetime. */
object AutomationAlerts {
    private const val CHANNEL = "simulation_changes"
    private const val NOTIFICATION_ID = 2003
    const val ERROR_CHANNEL = "simulation_errors"

    fun onTransition(context: Context, previous: PolicySnapshot?, current: PolicySnapshot) {
        if (previous == null) return
        if (current.service != ServiceState.ERROR && !current.result.startsWith("CONFIRMATION_REQUIRED_UNATTENDED"))
            context.getSystemService(NotificationManager::class.java).cancel(2004)
        val message = when {
            current.service == ServiceState.ERROR &&
                (previous.service != ServiceState.ERROR || previous.result != current.result) ->
                if (current.result == "CLEANUP_UNCERTAIN") R.string.alert_cleanup_uncertain
                else R.string.alert_simulation_failed
            current.service == ServiceState.INJECTING && current.injection == InjectionState.CONFIRMED &&
                previous.injection != InjectionState.CONFIRMED -> R.string.alert_simulation_started
            previous.injection == InjectionState.CONFIRMED && current.injection == InjectionState.NONE ->
                R.string.alert_simulation_stopped
            else -> return
        }
        show(context, message, current.service == ServiceState.ERROR)
    }

    fun unavailable(context: Context) = show(context, R.string.alert_unattended_unavailable, true)

    private fun show(context: Context, @StringRes message: Int, error: Boolean = false) {
        // Presentation failures must not affect cleanup, command acceptance or injection state.
        val mode = if (error) {
            if (!AutomationSettings.errorAlerts(context)) return
            AlertMode.NOTIFICATION
        } else runCatching { AutomationSettings.alertMode(context) }.getOrDefault(AlertMode.TOAST)
        val channel = if (error) ERROR_CHANNEL else CHANNEL
        val id = if (error) 2004 else NOTIFICATION_ID
        if (mode == AlertMode.TOAST || mode == AlertMode.BOTH) {
            runCatching { Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show() }
        }
        if (mode == AlertMode.NOTIFICATION || mode == AlertMode.BOTH) {
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel(
                    channel, if (error) "Action needed" else context.getString(R.string.change_alerts_channel),
                    if (error) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW
                ).apply { description = context.getString(R.string.change_alerts_channel_description) })
                if (!manager.areNotificationsEnabled()) return
                val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(id, Notification.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_mock_location)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(message))
                    .setContentIntent(open)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .apply { if (!error) setTimeoutAfter(15_000) }
                    .build())
            }
        }
    }
}
