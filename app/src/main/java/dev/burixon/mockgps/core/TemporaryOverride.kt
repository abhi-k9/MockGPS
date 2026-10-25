package dev.burixon.mockgps.core

enum class OverrideKind { SIMULATE, STOP }

/** A local, elapsed-time overlay. Source evidence keeps evolving beneath it. */
data class TemporaryOverride(
    val id: Long,
    val kind: OverrideKind,
    val endsAtElapsed: Long,
    val resumeMode: OperatingMode,
    val resumeDesired: Boolean,
    val resumePending: Long?,
    val resumeResult: String,
) {
    fun restore(s: PolicySnapshot) = s.copy(temporary = null, mode = resumeMode,
        desired = resumeDesired, pendingAtElapsed = resumePending, result = resumeResult,
        service = if (resumeDesired) ServiceState.STARTING else ServiceState.STOPPED)

    fun capture(s: PolicySnapshot) = copy(resumeMode = s.mode, resumeDesired = s.desired,
        resumePending = s.pendingAtElapsed, resumeResult = s.result)
}

object OverridePolicy {
    const val MAX_DURATION = 24 * 60 * 60 * 1000L
    fun start(s: PolicySnapshot, kind: OverrideKind, durationMillis: Long, now: Long): PolicySnapshot {
        require(durationMillis in 60_000..MAX_DURATION)
        // Persistent Keep stopped can only be cleared by an explicit local mode change.
        if (kind == OverrideKind.SIMULATE && s.mode == OperatingMode.FORCE_REAL) return s
        val base = s.temporary?.restore(s) ?: s
        val timer = TemporaryOverride(s.generation + 1, kind, now + durationMillis,
            base.mode, base.desired, base.pendingAtElapsed, base.result)
        return s.copy(temporary = timer, desired = kind == OverrideKind.SIMULATE,
            generation = s.generation + 1, pendingAtElapsed = null,
            service = if (kind == OverrideKind.SIMULATE) ServiceState.STARTING else ServiceState.STOPPED,
            result = "TEMPORARY_OVERRIDE")
    }

    fun adjust(s: PolicySnapshot, id: Long, deltaMillis: Long, now: Long): PolicySnapshot {
        val timer = s.temporary ?: return s
        if (timer.id != id || now >= timer.endsAtElapsed || deltaMillis !in -MAX_DURATION..MAX_DURATION) return s
        return s.copy(temporary = timer.copy(endsAtElapsed =
            (timer.endsAtElapsed + deltaMillis).coerceIn(now, now + MAX_DURATION)))
    }

    fun finish(s: PolicySnapshot, id: Long, now: Long): PolicySnapshot {
        val timer = s.temporary ?: return s
        if (timer.id != id) return s
        var base = timer.restore(s).copy(generation = s.generation + 1,
            providers = ProviderState.NONE, injection = InjectionState.NONE)
        if (base.mode == OperatingMode.AUTO && base.pendingAtElapsed != null && now >= base.pendingAtElapsed) {
            base = Policy.reduce(base, PolicyEvent.Deadline(base.generation), now)
        }
        return base.copy(service = if (base.desired) ServiceState.STARTING else ServiceState.STOPPED)
    }
}

enum class SessionExpiry { NONE, OVERRIDE, CEILING;
    companion object {
        fun at(s: PolicySnapshot, now: Long): SessionExpiry = when {
            s.sessionEndsAtElapsed?.let { now >= it } == true -> CEILING
            s.temporary?.let { now >= it.endsAtElapsed } == true -> OVERRIDE
            else -> NONE
        }
    }
}
