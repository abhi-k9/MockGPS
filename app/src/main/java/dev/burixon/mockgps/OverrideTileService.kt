package dev.burixon.mockgps

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Opens the duration controls after unlock, including when no timer is currently running. */
class OverrideTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val timer = ControlCenter.snapshot(this).temporary
        qsTile?.apply {
            icon = Icon.createWithResource(this@OverrideTileService, R.drawable.ic_location_timer)
            label = getString(R.string.timer_tile)
            subtitle = if (timer == null) getString(R.string.set_temporary_override) else {
                val minutes = ((timer.endsAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 59_999) / 60_000
                getString(R.string.timer_minutes_remaining, minutes)
            }
            state = if (timer == null) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            contentDescription = "$label. $subtitle"
            updateTile()
        }
    }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // Required legacy API only below 34.
    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val open = Intent(this, OverrideActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 2011, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(open)
            }
        }
    }
}
