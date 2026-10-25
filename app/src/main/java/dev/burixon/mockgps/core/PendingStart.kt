package dev.burixon.mockgps.core

/** Commit/cleanup and deadline registration form one operation for every request transport. */
object PendingStart {
    fun commit(
        next: PolicySnapshot,
        persistAndStop: (PolicySnapshot) -> Boolean,
        current: () -> PolicySnapshot,
        schedule: (PolicySnapshot) -> Unit,
    ): Boolean {
        if (!persistAndStop(next)) return false
        // Persistence and cleanup may notify listeners that cancel or supersede the request.
        val committed = current()
        if (next.pendingAtElapsed != null && committed.generation == next.generation &&
            committed.pendingAtElapsed == next.pendingAtElapsed && committed.mode == OperatingMode.AUTO &&
            committed.readiness == ReadinessState.READY && !committed.desired && committed.service != ServiceState.ERROR
        ) schedule(committed)
        return true
    }
}
