package com.taha.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Bildirim panelindeki hızlı ayarlar kutucuğu: dokununca bağlan / bağlantıyı kes. */
class VpnTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var shown = Phase.OFF

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = MainScope().apply {
            launch { VpnEngine.status.collect { render(it.phase) } }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Karar, panelde görünen duruma göre verilir
        if (shown == Phase.PAUSED) {
            VpnEngine.resume(this)
        } else if (qsTile?.state == Tile.STATE_ACTIVE) {
            VpnEngine.disconnect(this, force = true)
        } else if (VpnService.prepare(this) != null) {
            openAppAndConnect()   // Android'in VPN onay penceresi gerekiyor
        } else {
            VpnEngine.connect(this) { openAppAndConnect() }
        }
    }

    private fun render(phase: Phase) {
        shown = phase
        val tile = qsTile ?: return
        tile.label = getString(R.string.app_name)
        tile.state = when (phase) {
            Phase.OFF, Phase.PAUSED -> Tile.STATE_INACTIVE
            Phase.CONNECTING, Phase.ON -> Tile.STATE_ACTIVE
            Phase.DISCONNECTING -> Tile.STATE_UNAVAILABLE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when (phase) {
                    Phase.OFF -> R.string.tile_off
                    Phase.CONNECTING -> R.string.tile_connecting
                    Phase.PAUSED -> R.string.tile_paused
                    Phase.DISCONNECTING -> R.string.tile_disconnecting
                    Phase.ON -> R.string.tile_on
                }
            )
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAppAndConnect() {
        val intent = StatusSurfaces.openAppAndConnect(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
