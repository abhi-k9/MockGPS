package dev.burixon.mockgps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** No custom restart action or automatic location-service restore. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ControlCenter.reboot(context)
            Intent.ACTION_MY_PACKAGE_REPLACED -> ControlCenter.forceReal(context)
        }
    }
}
