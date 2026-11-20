package com.october.fixer

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class FixTileService : TileService() {

    override fun onStartListening() {
        instance = this
        render()
    }

    override fun onStopListening() {
        if (instance === this) instance = null
    }

    override fun onTileAdded() = render()

    private fun render() {
        val tile = qsTile ?: return
        val on = FixVpnService.running
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "Включено" else "Выключено"
        tile.updateTile()
    }

    // shown right after the tap: `running` flips only ~1 s later
    private fun showPending(starting: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (starting) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (starting) "Включение…" else "Выключение…"
        tile.updateTile()
    }

    override fun onClick() {
        val vpn = Intent(this, FixVpnService::class.java)
        when {
            FixVpnService.running -> {
                showPending(false)
                startService(vpn.setAction(FixVpnService.ACTION_STOP))
            }
            VpnService.prepare(this) != null -> openApp()
            else -> try {
                showPending(true)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(vpn) else startService(vpn)
            } catch (_: Exception) {
                render()
                openApp()
            }
        }
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // the Intent overload only below 34
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    companion object {
        @Volatile private var instance: FixTileService? = null
        private val main = Handler(Looper.getMainLooper())

        // called from the VPN worker thread: the tile is touched on the main thread only
        fun refresh() {
            main.post { instance?.render() }
        }
    }
}
