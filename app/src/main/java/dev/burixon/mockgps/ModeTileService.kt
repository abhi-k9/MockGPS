package dev.burixon.mockgps

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.burixon.mockgps.core.TileAction
import dev.burixon.mockgps.core.TilePresentation

/** A policy override. Selecting AUTO never grants permission to start a location FGS. */
class ModeTileService : TileService() {
    private var listening = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val listener: () -> Unit = {
        mainHandler.post { if (listening) updatePresentation() }
    }

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        ControlCenter.addListener(listener)
        updatePresentation()
    }

    override fun onStopListening() {
        listening = false
        ControlCenter.removeListener(listener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Stopping is a safety action and remains available while locked.
        val presentation = TilePresentation.from(ControlCenter.snapshot(this))
        if (presentation.action == TileAction.FORCE_REAL) {
            ControlCenter.forceReal(this)
            updatePresentation()
        } else {
            // Returning to automation changes future behavior, so require unlock.
            unlockAndRun {
                ControlCenter.selectAuto(this)
                updatePresentation()
            }
        }
    }

    private fun updatePresentation() {
        val presentation = TilePresentation.from(ControlCenter.snapshot(this))
        val tile = qsTile ?: return
        tile.label = presentation.label
        tile.subtitle = presentation.status
        tile.contentDescription = "${presentation.label}. ${presentation.status}"
        tile.state = if (presentation.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onDestroy() {
        listening = false
        ControlCenter.removeListener(listener)
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
