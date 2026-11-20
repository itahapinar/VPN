package com.taha.vpn

import android.app.Application
import android.os.Build
import de.blinkt.openvpn.core.ICSOpenVPNApplication
import de.blinkt.openvpn.core.VpnStatus
import java.io.File

/**
 * ICSOpenVPNApplication (durum aktarımı, bildirim kanalları) + uygulama dışı göstergeler.
 * Hem arayüz sürecinde hem VPN servis sürecinde (":openvpn") çalışır.
 */
class VpnApp : ICSOpenVPNApplication() {

    override fun onCreate() {
        super.onCreate()
        if (processName().endsWith(":openvpn")) {
            VpnStatus.addStateListener(ServiceProcessRelay(this))
        } else {
            // Widget ve kutucuk, ekran açılmadan da motora erişebilsin
            VpnEngine.init(this)
        }
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            try {
                File("/proc/self/cmdline").readText().trimEnd('\u0000')
            } catch (_: Exception) {
                ""
            }
        }
}
