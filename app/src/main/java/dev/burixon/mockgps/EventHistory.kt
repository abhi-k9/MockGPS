package dev.burixon.mockgps

import android.os.SystemClock

/** Bounded, process-local diagnostics. Only fixed codes; never caller input, labels or coordinates. */
internal object EventHistory {
    private val events = ArrayDeque<Pair<Long, String>>()
    fun record(code: String) {
        if (!code.matches(Regex("[A-Z0-9_]{1,100}"))) return
        if (events.lastOrNull()?.let { it.second == code && SystemClock.elapsedRealtime() - it.first < 2000 } == true) return
        if (events.size == 48) events.removeFirst()
        events.addLast(SystemClock.elapsedRealtime() to code)
    }
    fun report(): String {
        val now = SystemClock.elapsedRealtime()
        return events.joinToString("\n") { (time, code) -> "${(now - time) / 1000}s ago: $code" }
    }
}
