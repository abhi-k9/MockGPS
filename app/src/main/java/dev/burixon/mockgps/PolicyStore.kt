package dev.burixon.mockgps

import android.content.Context
import dev.burixon.mockgps.core.*

/** Credential-protected, backup-excluded policy snapshot; one atomic preference transaction. */
internal class PolicyStore(context: Context) {
    private val prefs = context.getSharedPreferences("control_policy", Context.MODE_PRIVATE)
    fun load(): PolicySnapshot {
        val mode = runCatching { OperatingMode.valueOf(prefs.getString("mode", "FORCE_REAL")!!) }
            .getOrDefault(OperatingMode.FORCE_REAL)
        return PolicySnapshot(mode = if (prefs.getBoolean("temporary_active", false)) OperatingMode.FORCE_REAL else mode,
            result = if (prefs.getBoolean("temporary_active", false)) "TIMER_INTERRUPTED" else "RECOVERY_REQUIRED", generation = prefs.getLong("generation", 0),
            lastEventTime = prefs.getLong("last_event", -1),
            lastSequence = prefs.getLong("last_sequence", -1),
            delaySeconds = prefs.getLong("delay", 120).coerceIn(0, 86400))
    }
    fun save(s: PolicySnapshot) {
        check(prefs.edit().putInt("schema", 2).putString("mode", s.mode.name)
            .putBoolean("temporary_active", s.temporary != null).putBoolean("desired", s.desired).putString("service", s.service.name)
            .putString("providers", s.providers.name).putString("injection", s.injection.name)
            .putString("result", s.result).putLong("generation", s.generation)
            .putLong("pending", s.pendingAtElapsed ?: -1).putString("readiness", s.readiness.name)
            .putLong("last_sequence", s.lastSequence).putLong("last_event", s.lastEventTime).putLong("delay", s.delaySeconds).commit()) {
            "Policy storage unavailable"
        }
    }
}
