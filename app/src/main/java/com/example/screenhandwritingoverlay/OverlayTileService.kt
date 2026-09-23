package com.example.screenhandwritingoverlay

import android.app.PendingIntent
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class OverlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_SHOW_PERMISSION_DIALOG, true)
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
            return
        }

        if (OverlayService.isRunning) {
            OverlayService.stopService(this)
            qsTile?.state = Tile.STATE_INACTIVE
        } else {
            OverlayService.startService(this)
            qsTile?.state = Tile.STATE_ACTIVE
        }
        qsTile?.updateTile()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        if (OverlayService.isRunning) {
            tile.state = Tile.STATE_ACTIVE
        } else {
            tile.state = Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}