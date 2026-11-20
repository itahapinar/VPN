package com.taha.vpn

import android.app.Activity
import android.os.Bundle

/**
 * VPN bildirimindeki "Bağlantıyı kes" düğmesi buraya gelir (manifest'te motorun
 * DisconnectVPN adı bu ekrana yönlendirildi). Onay penceresi göstermeden bağlantıyı
 * keser; pencere hiç çizilmez (Theme.NoDisplay).
 */
class QuickDisconnectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VpnEngine.disconnect(this, force = true)
        finish()
    }
}
