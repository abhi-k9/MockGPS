package dev.burixon.mockgps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.burixon.mockgps.core.CommandAuth

/** Versioned explicit capability IPC. A successful response means accepted, never proven injection. */
class MockGpsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) { reject(context, "UNSUPPORTED_ACTION"); return }
        try {
            val extras = intent.extras
            if (extras == null || extras.size() !in 5..6) {
                reject(context, "MALFORMED_REQUEST"); return
            }
            @Suppress("DEPRECATION") // Raw values preserve exact primitive types on API29+.
            val raw = extras.keySet().associateWith { extras.get(it) }
            val request = CommandAuth.parse(raw) ?: run { reject(context, "MALFORMED_REQUEST"); return }
            val result = ControlCenter.external(context, request)
            if (!result.accepted) { reject(context, result.code); return }
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
                    putString("source_state", s.readiness.name)
                    putBoolean("desired", s.desired)
                    putBoolean("pending", s.pendingAtElapsed != null)
                    putBoolean("unattended_approval", s.unattendedApproval)
                    putBoolean("automation_armed", MockLocationService.isAutomationArmed())
                    putLong("pending_remaining_ms", s.pendingAtElapsed?.let { (it - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0) } ?: 0)
                    putBoolean("temporary_override", s.temporary != null)
                    putString("override_kind", s.temporary?.kind?.name)
                    putLong("override_remaining_ms", s.temporary?.let { (it.endsAtElapsed - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0) } ?: 0)
                    putLong("session_remaining_ms", s.sessionEndsAtElapsed?.let { (it - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0) } ?: -1)
                    putStringArrayList("capabilities", arrayListOf("readiness", "stop", "status", "select_preset"))
                })
            }
        } catch (_: RuntimeException) {
            reject(context, "MALFORMED_REQUEST")
        }
    }
    private fun reject(context: Context, code: String) {
        ControlCenter.rejected(context, code)
        if (isOrderedBroadcast) { resultCode = 1; resultData = code; setResultExtras(null) }
    }
    companion object { const val ACTION = "dev.abhik.mockgps.CONTROL" }
}
