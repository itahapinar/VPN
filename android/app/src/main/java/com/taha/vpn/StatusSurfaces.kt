package com.taha.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.VpnStatus

/**
 * Uygulama dışındaki durum göstergeleri: ana ekran widget'ı ve hızlı ayarlar kutucuğu.
 *
 * İki süreçten de güncellenir: arayüz süreci (VpnEngine) ayrıntılı durumu bilir ama
 * Android onu arka planda kapatabilir; VPN servis süreci (":openvpn") ise bağlantı
 * boyunca hep ayaktadır. Böylece uygulama kapalıyken de gösterge doğru kalır.
 */
object StatusSurfaces {

    fun publish(context: Context, phase: Phase, vpnIp: String) {
        VpnWidget.render(context, phase, vpnIp)
        try {
            TileService.requestListeningState(context, ComponentName(context, VpnTileService::class.java))
        } catch (_: Exception) {
            // Kutucuk eklenmemişse ya da sistem isteği reddederse sorun değil
        }
    }

    /** Uygulamayı açıp bağlanmayı orada başlatan intent (VPN izni gerekiyorsa vb.) */
    fun openAppAndConnect(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CONNECT, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}

/** VPN servis sürecinde çalışır; motorun durumunu widget/kutucuğa yansıtır. */
class ServiceProcessRelay(private val context: Context) : VpnStatus.StateListener {

    private var last: Pair<Phase, String>? = null

    override fun updateState(
        state: String?, logmessage: String?, localizedResId: Int,
        level: ConnectionStatus?, intent: Intent?,
    ) {
        val phase = when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> Phase.ON
            ConnectionStatus.LEVEL_NOTCONNECTED, ConnectionStatus.LEVEL_AUTH_FAILED -> Phase.OFF
            ConnectionStatus.LEVEL_VPNPAUSED -> Phase.PAUSED
            ConnectionStatus.UNKNOWN_LEVEL, null -> return
            else -> Phase.CONNECTING
        }
        // CONNECTED mesajı: "SUCCESS,10.8.0.2,sunucu,port,..."
        val ip = if (phase == Phase.ON) logmessage.orEmpty().split(",").getOrNull(1).orEmpty() else ""
        val now = phase to ip
        if (now == last) return
        last = now
        StatusSurfaces.publish(context, phase, ip)
    }

    override fun setConnectedVPN(uuid: String?) {}
}
