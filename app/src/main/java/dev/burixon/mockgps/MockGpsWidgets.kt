package dev.burixon.mockgps

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import dev.burixon.mockgps.core.*

class CompactWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = MockGpsWidgets.updateNow(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = MockGpsWidgets.updateNow(context)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in MockGpsWidgets.configurationActions) MockGpsWidgets.updateNow(context)
    }
}

class DetailedWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = MockGpsWidgets.updateNow(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = MockGpsWidgets.updateNow(context)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in MockGpsWidgets.configurationActions) MockGpsWidgets.updateNow(context)
    }
}

/** Event-driven RemoteViews: no polling, services, extra permissions or dependencies. */
internal object MockGpsWidgets {
    val configurationActions = setOf(Intent.ACTION_CONFIGURATION_CHANGED, Intent.ACTION_LOCALE_CHANGED,
        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
    private val handler = Handler(Looper.getMainLooper())
    private var queued = false

    fun requestUpdate(context: Context) {
        if (queued) return
        queued = true
        val app = context.applicationContext
        handler.post {
            queued = false
            // Launcher failures must never interrupt persistence or provider cleanup.
            runCatching { update(app) }
        }
    }

    // Provider callbacks must finish rendering before their broadcast lifetime ends.
    fun updateNow(context: Context) {
        handler.removeCallbacksAndMessages(null)
        queued = false
        runCatching { update(context.applicationContext) }
    }

    private fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val providers = listOf(CompactWidgetProvider::class.java to R.layout.widget_compact,
            DetailedWidgetProvider::class.java to R.layout.widget_detailed)
        val bound = providers.map { (provider, layout) -> manager.getAppWidgetIds(ComponentName(context, provider)) to layout }
        if (bound.all { it.first.isEmpty() }) return
        val s = ControlCenter.snapshot(context)
        val model = WidgetPresentation.from(s)
        val updated = context.getString(R.string.widget_updated, DateUtils.formatDateTime(context,
            System.currentTimeMillis(), DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE))
        for ((ids, layout) in bound) {
            if (ids.isEmpty()) continue
            val views = RemoteViews(context.packageName, layout)
            val status = context.getString(when (model.status) {
                WidgetStatus.ACTIVE -> R.string.widget_active
                WidgetStatus.STARTING -> R.string.widget_starting
                WidgetStatus.ERROR -> R.string.widget_error
                WidgetStatus.UNVERIFIED -> R.string.widget_unverified
                WidgetStatus.CONFIRMATION -> R.string.widget_confirmation
                WidgetStatus.WAITING -> R.string.widget_waiting
                WidgetStatus.TEMPORARY_STOP -> R.string.widget_temporary_stop
                WidgetStatus.KEEP_STOPPED -> R.string.widget_keep_stopped
                WidgetStatus.STOPPED -> R.string.widget_stopped
            })
            views.setTextViewText(R.id.widgetStatus, status)
            views.setTextViewText(R.id.widgetUpdated, updated)
            views.setContentDescription(R.id.widgetHeader, context.getString(R.string.widget_accessible_status, status, updated))
            val open = PendingIntent.getActivity(context, 3100, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val timer = PendingIntent.getActivity(context, 3101, Intent(context, OverrideActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getBroadcast(context, 3102, Intent(context, AutomationStopReceiver::class.java)
                .setAction(AutomationStopReceiver.ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widgetHeader, open)
            views.setOnClickPendingIntent(R.id.widgetStop, stop)
            views.setOnClickPendingIntent(R.id.widgetTimer, timer)
            if (layout == R.layout.widget_detailed) {
                views.setOnClickPendingIntent(R.id.widgetOpen, open)
                views.setTextViewText(R.id.widgetMode, context.getString(R.string.widget_mode, context.getString(when (s.mode) {
                    OperatingMode.AUTO -> R.string.widget_mode_auto
                    OperatingMode.FORCE_REAL -> R.string.widget_keep_stopped
                    OperatingMode.MANUAL_MOCK -> R.string.widget_mode_manual
                })))
                views.setTextViewText(R.id.widgetAutomation, context.getString(when {
                    !s.unattendedApproval -> R.string.widget_manual_approval
                    MockLocationService.isAutomationArmed() -> R.string.widget_armed
                    else -> R.string.widget_unarmed
                }))
                views.setTextViewText(R.id.widgetReadiness, context.getString(when (s.readiness) {
                    ReadinessState.READY -> R.string.widget_ready
                    ReadinessState.BLOCKED -> R.string.widget_blocked
                    ReadinessState.UNKNOWN -> R.string.widget_unknown
                }))
                views.setTextViewText(R.id.widgetDeadlineLabel, context.getString(when (model.deadlineKind) {
                    WidgetDeadline.OVERRIDE -> R.string.widget_override_deadline
                    WidgetDeadline.SESSION -> R.string.widget_session_deadline
                    WidgetDeadline.REQUEST -> R.string.widget_request_deadline
                    null -> R.string.widget_no_deadline
                }))
                views.setViewVisibility(R.id.widgetCountdown, if (model.deadline != null) View.VISIBLE else View.GONE)
                views.setChronometerCountDown(R.id.widgetCountdown, true)
                views.setChronometer(R.id.widgetCountdown, model.deadline ?: SystemClock.elapsedRealtime(), null, model.deadline != null)
                val adjustable = s.temporary != null && model.deadlineKind == WidgetDeadline.OVERRIDE
                for ((id, action, request) in listOf(Triple(R.id.widgetReduce, "reduce", 3103), Triple(R.id.widgetExtend, "extend", 3104))) {
                    views.setViewVisibility(id, if (adjustable) View.VISIBLE else View.GONE)
                    // Timer-specific identity means an old launcher view cannot adjust a replacement.
                    val intent = Intent(context, OverrideActionReceiver::class.java).setAction(action)
                        .setData(android.net.Uri.parse("mockgps://widget/${s.temporary?.id}/$action"))
                        .putExtra("session", s.temporary?.id ?: -1)
                    views.setOnClickPendingIntent(id, PendingIntent.getBroadcast(context, request, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            }
            runCatching { manager.updateAppWidget(ids, views) }
        }
    }
}
