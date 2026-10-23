package dev.burixon.mockgps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.burixon.mockgps.core.ExternalRequest

/** Versioned explicit capability IPC. A successful response means accepted, never proven injection. */
class MockGpsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) { reject("UNSUPPORTED_ACTION"); return }
        try {
            val extras = intent.extras
            if (extras == null || extras.keySet() != setOf("api", "token", "command", "event_time")) {
                reject("MALFORMED_REQUEST"); return
            }
            val request = ExternalRequest(intent.getIntExtra("api", -1), intent.getStringExtra("token"),
                intent.getStringExtra("command"), intent.getLongExtra("event_time", -1))
            val result = ControlCenter.external(context, request)
            if (!result.accepted) { reject(result.code); return }
            if (isOrderedBroadcast) {
                resultCode = 0
                resultData = "ACCEPTED"
                val s = ControlCenter.snapshot(context)
                setResultExtras(Bundle().apply {
                    putString("mode", s.mode.name)
                    putString("service", s.service.name)
                    putString("providers", s.providers.name)
                    putString("injection", s.injection.name)
                    putString("transition", s.result)
                    putString("vehicle", s.vehicle.name)
                    putBoolean("desired", s.desired)
                    putBoolean("pending", s.pendingAtElapsed != null)
                })
            }
        } catch (_: RuntimeException) {
            reject("MALFORMED_REQUEST")
        }
    }
    private fun reject(code: String) {
        if (isOrderedBroadcast) { resultCode = 1; resultData = code; setResultExtras(null) }
    }
    companion object { const val ACTION = "dev.burixon.mockgps.CONTROL" }
}
