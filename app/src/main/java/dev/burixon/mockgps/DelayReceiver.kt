package dev.burixon.mockgps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Internal inexact alarm, never a background foreground-service launcher. */
class DelayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ControlCenter.deadline(context, intent.getLongExtra("generation", -1))
    }
}
