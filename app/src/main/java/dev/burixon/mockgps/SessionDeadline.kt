package dev.burixon.mockgps

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.burixon.mockgps.core.PolicySnapshot

/** The persisted policy is authoritative; every callback rechecks its current elapsed deadline. */
internal object SessionDeadline {
    private val handler = Handler(Looper.getMainLooper())
    fun precise(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private var scheduled: Long? = null
    private var exactAllowed: Boolean? = null
    fun schedule(context: Context, state: PolicySnapshot) {
        val target = listOfNotNull(state.temporary?.endsAtElapsed, state.sessionEndsAtElapsed).minOrNull()
        val exact = precise(context)
        if (target == scheduled && exactAllowed == exact) return
        scheduled = target
        exactAllowed = exact
        runCatching { update(context, target) }.onFailure { EventHistory.record("TIMER_ALARM_UNAVAILABLE") }
    }
    fun delivered(context: Context) {
        scheduled = null
        ControlCenter.expireSessions(context)
        schedule(context, ControlCenter.snapshot(context))
    }
    private fun update(context: Context, target: Long?) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val callback = PendingIntent.getBroadcast(context, 2010, Intent(context, SessionDeadlineReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(callback)
        handler.removeCallbacksAndMessages(null)
        val deadline = target ?: return
        val app = context.applicationContext
        handler.postDelayed({ delivered(app) }, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        try {
            if (precise(context)) alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, callback)
            else alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, callback)
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, callback)
        } catch (_: RuntimeException) {
            EventHistory.record("TIMER_ALARM_UNAVAILABLE")
        }
    }
}

class SessionDeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EventHistory.record("TIMER_ALARM_DELIVERED")
        SessionDeadline.delivered(context)
    }
}

class OverrideActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("session", -1)
        when (intent.action) {
            "extend" -> ControlCenter.adjustOverride(context, id, 300_000)
            "reduce" -> ControlCenter.adjustOverride(context, id, -300_000)
        }
    }
}
