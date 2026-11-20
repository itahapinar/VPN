package com.taha.vpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.taha.vpn.ui.VpnScreen

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CONNECT = "com.taha.vpn.CONNECT"
    }

    // Android'in "Bu uygulama VPN bağlantısı kurmak istiyor" onayı
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) VpnEngine.connect(this) else VpnEngine.permissionDenied()
        }

    // VPN bildirimi (hız + bağlantıyı kes düğmesi) için; reddedilse de VPN çalışır
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        VpnEngine.init(applicationContext)
        askNotificationPermissionOnce()

        setContent {
            val status by VpnEngine.status.collectAsState()
            val traffic by VpnEngine.traffic.collectAsState()
            VpnScreen(
                status, traffic, VpnEngine.server,
                onToggle = ::toggle,
                onPauseToggle = {
                    when (VpnEngine.status.value.phase) {
                        Phase.ON -> VpnEngine.pause(this)
                        Phase.PAUSED -> VpnEngine.resume(this)
                        else -> Unit
                    }
                },
            )
        }
        if (savedInstanceState == null) handleConnectRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConnectRequest(intent)
    }

    /** Widget / hızlı ayarlar kutucuğu bağlanmak için uygulamayı açtıysa */
    private fun handleConnectRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CONNECT, false) != true) return
        intent.removeExtra(EXTRA_CONNECT)
        if (VpnEngine.status.value.phase == Phase.OFF) connect()
    }

    private fun toggle() {
        when (VpnEngine.status.value.phase) {
            Phase.OFF -> connect()
            Phase.CONNECTING, Phase.ON, Phase.PAUSED -> VpnEngine.disconnect(this)
            Phase.DISCONNECTING -> Unit
        }
    }

    private fun connect() {
        val consent = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            null
        }
        if (consent == null) VpnEngine.connect(this) else vpnPermission.launch(consent)
    }

    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences("vpn_arayuz", MODE_PRIVATE)
        if (prefs.getBoolean("notif_asked", false)) return
        prefs.edit().putBoolean("notif_asked", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
