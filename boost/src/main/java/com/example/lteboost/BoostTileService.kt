package com.example.lteboost

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Кнопка «4G MAX» у шторці швидких налаштувань. */
class BoostTileService : TileService() {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (busy) return
        busy = true
        qsTile?.let {
            it.state = Tile.STATE_UNAVAILABLE
            it.subtitle = getString(R.string.tile_working)
            it.updateTile()
        }
        Thread {
            val ready = Privileged.awaitBinder(2000) && Privileged.isReady()
            val report = if (ready) BoostController.toggle(applicationContext) else null
            main.post {
                busy = false
                updateTile()
                if (report == null) {
                    openApp()
                } else {
                    Toast.makeText(applicationContext, report.toString(), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = BoostController.isActive(this)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(if (active) R.string.tile_on else R.string.tile_off)
        tile.updateTile()
    }

    /** Без доступу Shizuku нічого зробити не можна, тому відкриваємо застосунок з інструкцією. */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
