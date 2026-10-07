package dev.kapil.healthcal

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to sync the last 7 days without opening the app. */
class SyncTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Sync health"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        SyncWorker.runNow(applicationContext, SyncWorker.MODE_RECENT)
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "Syncing…"
            updateTile()
        }
    }
}
