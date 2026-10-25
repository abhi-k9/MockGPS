package dev.burixon.mockgps

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.burixon.mockgps.core.OperatingMode
import dev.burixon.mockgps.core.OverrideKind

/** Visible local consent for a timed override; no automatic action is taken on opening. */
class OverrideActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var duration: TextInputEditText
    private lateinit var simulate: MaterialButton
    private lateinit var allow: MaterialButton
    private lateinit var adjust: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.temporary_override)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        status = TextView(this).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge) }
        column.addView(status)
        column.addView(TextView(this).apply { setText(R.string.override_description); setPadding(0, 16, 0, 16) })
        val input = TextInputLayout(this).apply { hint = getString(R.string.duration_minutes) }
        duration = TextInputEditText(input.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("15"); setSelectAllOnFocus(true)
        }
        input.addView(duration)
        column.addView(input)
        val presets = LinearLayout(this)
        listOf(5, 15, 30, 60).forEach { minutes ->
            presets.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.minutes_short, minutes)
                setOnClickListener { duration.setText(minutes.toString()) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        column.addView(presets)
        fun button(label: Int, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
            setText(label); setOnClickListener { action() }; column.addView(this)
        }
        allow = button(R.string.allow_simulation) { ControlCenter.selectAuto(this); render() }
        simulate = button(R.string.simulate_temporarily) { start(OverrideKind.SIMULATE) }
        button(R.string.stop_temporarily) { start(OverrideKind.STOP) }
        adjust = LinearLayout(this)
        fun adjustment(label: Int, delta: Long) {
            adjust.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(label); setOnClickListener {
                    ControlCenter.snapshot(this@OverrideActivity).temporary?.let {
                        ControlCenter.adjustOverride(this@OverrideActivity, it.id, delta)
                    }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        adjustment(R.string.reduce_five_minutes, -300_000)
        adjustment(R.string.add_five_minutes, 300_000)
        column.addView(adjust)
        button(R.string.end_override) {
            ControlCenter.snapshot(this).temporary?.let { ControlCenter.finishOverride(this, it.id) }
        }
        button(R.string.stop_and_force_real) { ControlCenter.forceReal(this) }
        button(R.string.open_mockgps) { startActivity(Intent(this, MainActivity::class.java)); finish() }
        button(android.R.string.ok) { finish() }
    }

    private fun start(kind: OverrideKind) {
        val minutes = duration.text?.toString()?.toIntOrNull()
        if (minutes == null || minutes !in 1..1440) {
            duration.error = getString(R.string.invalid_duration); return
        }
        if (!ControlCenter.startOverride(this, kind, minutes)) {
            Toast.makeText(this, R.string.override_start_failed, Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun render() {
        val s = ControlCenter.snapshot(this)
        val timer = s.temporary
        status.text = if (timer == null) s.displayStatus() else {
            val seconds = ((timer.endsAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
            getString(if (timer.kind == OverrideKind.SIMULATE) R.string.timed_simulation_status else R.string.timed_stop_status,
                seconds / 60, seconds % 60)
        }
        if (!SessionDeadline.precise(this)) status.append("\n${getString(R.string.timer_precision_hint)}")
        allow.visibility = if (s.mode == OperatingMode.FORCE_REAL) android.view.View.VISIBLE else android.view.View.GONE
        simulate.isEnabled = s.mode != OperatingMode.FORCE_REAL
        adjust.visibility = if (timer != null) android.view.View.VISIBLE else android.view.View.GONE
    }
    override fun onResume() { super.onResume(); ControlCenter.addListener(listener); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); ControlCenter.removeListener(listener); super.onPause() }
}
