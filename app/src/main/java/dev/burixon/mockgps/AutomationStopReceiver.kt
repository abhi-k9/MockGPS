package dev.burixon.mockgps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Private destination of the immutable foreground-notification Stop action. */
class AutomationStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) ControlCenter.forceReal(context)
    }

    companion object { const val ACTION = "dev.abhik.mockgps.FORCE_REAL" }
}
