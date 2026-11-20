package com.taha.vpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews

/** Ana ekran widget'ı: durum + tek dokunuşla bağlan / bağlantıyı kes. */
class VpnWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val st = VpnEngine.status.value
        render(context, st.phase, st.vpnIp)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_CONNECT -> VpnEngine.connect(context) {
                context.startActivity(StatusSurfaces.openAppAndConnect(context))
            }
            ACTION_DISCONNECT -> VpnEngine.disconnect(context, force = true)
            ACTION_RESUME -> VpnEngine.resume(context)
        }
    }

    companion object {
        private const val ACTION_CONNECT = "com.taha.vpn.widget.CONNECT"
        private const val ACTION_DISCONNECT = "com.taha.vpn.widget.DISCONNECT"
        private const val ACTION_RESUME = "com.taha.vpn.widget.RESUME"

        private const val GREEN = 0xFF2EE5A0.toInt()
        private const val AMBER = 0xFFFFB547.toInt()
        private const val MUTED = 0xFF8A8FB8.toInt()
        private const val IDLE = 0xFFB4B8DC.toInt()

        fun render(context: Context, phase: Phase, vpnIp: String) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, VpnWidget::class.java)
            if (manager.getAppWidgetIds(component).isEmpty()) return

            val views = RemoteViews(context.packageName, R.layout.widget_vpn)
            val (status, color, button) = when (phase) {
                Phase.OFF -> Triple(context.getString(R.string.widget_off), MUTED, R.drawable.widget_btn_off)
                Phase.CONNECTING -> Triple(context.getString(R.string.widget_connecting), AMBER, R.drawable.widget_btn_connecting)
                Phase.DISCONNECTING -> Triple(context.getString(R.string.widget_disconnecting), AMBER, R.drawable.widget_btn_connecting)
                Phase.PAUSED -> Triple(context.getString(R.string.widget_paused), AMBER, R.drawable.widget_btn_connecting)
                Phase.ON -> Triple(
                    context.getString(R.string.widget_on) + if (vpnIp.isNotEmpty()) " · $vpnIp" else "",
                    GREEN, R.drawable.widget_btn_on,
                )
            }
            views.setTextViewText(R.id.widget_status, status)
            views.setTextColor(R.id.widget_status, color)
            views.setInt(R.id.widget_button, "setBackgroundResource", button)
            views.setInt(R.id.widget_button, "setColorFilter", if (phase == Phase.OFF) IDLE else color)

            // Düğme, ekranda görünen duruma göre ne yapacağını taşır
            val buttonIntent = when {
                // Duraklatılmışken düğme korumayı geri açar (oturum sürer)
                phase == Phase.PAUSED -> broadcast(context, ACTION_RESUME)
                phase != Phase.OFF -> broadcast(context, ACTION_DISCONNECT)
                // VPN izni henüz verilmemişse Android'in onay penceresi için uygulama açılır
                VpnService.prepare(context) != null -> PendingIntent.getActivity(
                    context, 1, StatusSurfaces.openAppAndConnect(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                else -> broadcast(context, ACTION_CONNECT)
            }
            views.setOnClickPendingIntent(R.id.widget_button, buttonIntent)
            views.setContentDescription(
                R.id.widget_button,
                context.getString(
                    when (phase) {
                        Phase.OFF -> R.string.widget_connect
                        Phase.PAUSED -> R.string.widget_resume
                        else -> R.string.widget_disconnect
                    }
                ),
            )

            // Widget'ın geri kalanı uygulamayı açar
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE),
            )

            manager.updateAppWidget(component, views)
        }

        private fun broadcast(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, action.hashCode(),
                Intent(context, VpnWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
