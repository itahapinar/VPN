package com.taha.vpn

import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.LogItem
import de.blinkt.openvpn.core.OpenVPNManagement
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** PAUSED: oturum sürüyor ama tünel kapalı; trafik normal internetten gider */
enum class Phase { OFF, CONNECTING, ON, PAUSED, DISCONNECTING }

enum class Notice { NONE, WARNING, ERROR }

data class ServerInfo(val host: String, val port: String, val proto: String)

data class VpnStatusUi(
    val phase: Phase = Phase.OFF,
    /** Durum çipinin altındaki açıklama; null ise ekran duruma göre varsayılanı gösterir */
    val detail: String? = null,
    /** Güç butonu halkasının doluluğu (0..1), bağlantı aşamasına göre */
    val progress: Float = 0f,
    val vpnIp: String = "",
    /** Bağlantının kurulduğu an (System.currentTimeMillis) */
    val connectedAt: Long? = null,
    val notice: String? = null,
    val noticeLevel: Notice = Notice.NONE,
)

data class TrafficUi(
    val downBps: Double = 0.0,
    val upBps: Double = 0.0,
    val totalRx: Long = 0,
    val totalTx: Long = 0,
    /** Son [VpnEngine.HISTORY] saniyenin hızları (bayt/sn), eskiden yeniye */
    val downHistory: List<Float> = emptyList(),
    val upHistory: List<Float> = emptyList(),
)

/**
 * ics-openvpn motorunu başlatır, durdurur ve durumunu arayüze aktarır.
 * Masaüstü sürümündeki vpn_controller.py'nin karşılığı.
 *
 * VPN servisi ayrı bir süreçte (":openvpn") çalışır; durum, günlük ve bayt
 * sayaçları ICSOpenVPNApplication'ın StatusListener'ı üzerinden bu sürecin
 * VpnStatus'una aktarılır. Dinleyiciler binder thread'lerinden çağrıldığı için
 * tüm işlemler ana thread'e taşınır.
 */
object VpnEngine : VpnStatus.StateListener, VpnStatus.ByteCountListener, VpnStatus.LogListener {

    const val HISTORY = 60
    private const val CONNECT_TIMEOUT_MS = 60_000L
    // Derlemede kaynak/mobile.ovpn bu adla APK'ye konur (app/build.gradle.kts)
    private const val CONFIG_ASSET = "vpn.ovpn"
    // Her bağlanışta aynı profil güncellenir (her zaman açık VPN de bunu kullanır)
    private val PROFILE_UUID: UUID = UUID.fromString("6f1d5a52-3c1b-4f0e-9d2a-7a5e2b8c4d10")
    private const val PREFS = "vpn_arayuz"
    private const val PREF_CONNECTED_AT = "connected_at"

    // Motor bayt sayaçlarını bu aralıkla gönderir; daha uzun sessizlik = trafik yok
    private val STALE_MS = OpenVPNManagement.mBytecountInterval * 2000L + 500

    // OpenVPN aşamaları -> (halka ilerlemesi, açıklama)
    private val STAGES = mapOf(
        "VPN_GENERATE_CONFIG" to (0.06f to "Yapılandırma hazırlanıyor"),
        "CONNECTING" to (0.10f to "Sunucuya bağlanılıyor"),
        "RESOLVE" to (0.12f to "Sunucu adresi çözümleniyor"),
        "TCP_CONNECT" to (0.22f to "TCP bağlantısı kuruluyor"),
        "WAIT" to (0.32f to "Sunucu yanıtı bekleniyor"),
        "AUTH" to (0.48f to "Kimlik doğrulanıyor"),
        "AUTH_PENDING" to (0.52f to "Kimlik doğrulama bekleniyor"),
        "GET_CONFIG" to (0.64f to "Yapılandırma alınıyor"),
        "ASSIGN_IP" to (0.78f to "IP adresi atanıyor"),
        "ADD_ROUTES" to (0.90f to "Yönlendirmeler ekleniyor"),
        "RECONNECTING" to (0.10f to "Yeniden bağlanılıyor"),
        "NONETWORK" to (0.04f to "İnternet bağlantısı bekleniyor"),
        "SCREENOFF" to (0.04f to "Ekran kapalıyken duraklatıldı"),
        "USERPAUSE" to (0.04f to "Duraklatıldı — normal internet bağlantınız kullanılıyor"),
    )

    // RECONNECTING aşamasında OpenVPN'in bildirdiği sebep -> açıklama
    private val RECONNECT_REASONS = mapOf(
        "connection-reset" to "bağlantı sıfırlandı",
        "tls-error" to "TLS el sıkışması başarısız",
        "ping-restart" to "sunucudan yanıt gelmiyor",
        "auth-failure" to "kimlik doğrulama başarısız",
        "server-pushed-connection-reset" to "sunucu bağlantıyı sıfırladı",
        "network-change" to "ağ değişti",
    )

    /** OpenVPN günlük satırını anlaşılır Türkçe açıklamaya çevirir. */
    fun explainError(msg: String): String {
        val low = msg.lowercase()
        return when {
            "refused" in low ->
                "Sunucu bağlantıyı reddetti — sunucu kapalı olabilir ya da " +
                    "yapılandırmadaki port/protokol (TCP/UDP) yanlış"
            "tls key negotiation failed" in low || "tls handshake failed" in low ->
                "Sunucu yanıt vermiyor (TLS el sıkışması zaman aşımına uğradı)"
            "auth_failed" in low || "auth-failure" in low ->
                "Kimlik doğrulama başarısız (sertifika geçersiz veya iptal edilmiş olabilir)"
            "network is unreachable" in low || "no route to host" in low ->
                "Sunucuya ulaşılamıyor — internet bağlantınızı kontrol edin"
            "cannot resolve host" in low || "resolve: unknown host" in low ->
                "Sunucu adresi çözümlenemedi — internet bağlantınızı kontrol edin"
            else -> msg
        }
    }

    private val _status = MutableStateFlow(VpnStatusUi())
    val status: StateFlow<VpnStatusUi> = _status.asStateFlow()

    private val _traffic = MutableStateFlow(TrafficUi())
    val traffic: StateFlow<TrafficUi> = _traffic.asStateFlow()

    var server = ServerInfo("", "1194", "UDP")
        private set

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var initialized = false

    // Bağlantı oturumu
    private var startedAt = 0L          // elapsedRealtime: zaman aşımı sayacının başlangıcı
    private var startedWall = 0L        // currentTimeMillis: bu andan önceki günlükler eski
    private var userStop = false
    private var sawEngine = false       // bu bağlanışta motordan gerçek bir durum geldi mi
    private var fatal: String? = null
    private var lastLogError: String? = null
    private var lastState = ""

    // Hız ölçümü
    private var sample: Triple<Long, Long, Long>? = null   // (zaman, rx, tx)
    private var lastTraffic = 0L
    private val downHist = ArrayDeque<Float>()
    private val upHist = ArrayDeque<Float>()

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        server = readServerInfo()
        // Yeniden bağlan akışında motorun kendi günlük penceresini açmasın
        de.blinkt.openvpn.core.Preferences.getDefaultSharedPreferences(app)
            .edit().putBoolean("showlogwindow", false).apply()
        VpnStatus.addLogListener(this)
        VpnStatus.addByteCountListener(this)
        // Son durum hemen tekrar bildirilir: uygulama VPN açıkken açılırsa ekran senkronlanır
        VpnStatus.addStateListener(this)

        // Widget ve hızlı ayarlar kutucuğu durum değiştikçe güncellenir. İlk değer atlanır:
        // süreç yeni açıldığında gerçek durum servisten birkaç ms sonra gelir.
        MainScope().launch {
            status.map { it.phase to it.vpnIp }.distinctUntilChanged().drop(1).collect { (phase, ip) ->
                StatusSurfaces.publish(app, phase, ip)
            }
        }
    }

    // ---------------------------------------------------------------- Bağlan / Kes

    /**
     * VpnService izni alınmış olmalı (MainActivity halleder).
     * [onBackgroundBlocked]: Android arka plandan servis başlatmayı engellerse (widget/kutucuk)
     * hata göstermek yerine çağrılır; çağıran uygulamayı açarak bağlanmayı orada tamamlar.
     */
    fun connect(context: Context, onBackgroundBlocked: (() -> Unit)? = null) {
        if (_status.value.phase == Phase.ON || _status.value.phase == Phase.DISCONNECTING) return
        startedAt = SystemClock.elapsedRealtime()
        startedWall = System.currentTimeMillis()
        userStop = false
        sawEngine = false
        fatal = null
        lastLogError = null
        lastState = ""
        resetTraffic()
        _status.value = VpnStatusUi(phase = Phase.CONNECTING, progress = 0.04f)
        startTicker()

        val profile = try {
            loadProfile(context)
        } catch (e: Exception) {
            fail("Yapılandırma okunamadı: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        try {
            VPNLaunchHelper.startOpenVpn(profile, context.applicationContext, "Uygulama", true)
        } catch (e: Exception) {
            if (onBackgroundBlocked != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                stopTicker()
                _status.value = VpnStatusUi()
                onBackgroundBlocked()
                return
            }
            fail("VPN servisi başlatılamadı: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * [force]: widget/kutucuk ekranda "bağlı" gördüğü için kes diyor; bu süreç yeni açılmış
     * ve durumu henüz öğrenmemiş olabilir, yine de servise durdurma komutu gönderilir.
     */
    fun disconnect(context: Context, force: Boolean = false) {
        val phase = _status.value.phase
        if (phase == Phase.DISCONNECTING) return
        if (phase == Phase.OFF) {
            if (force) stopService(context)
            return
        }
        userStop = true
        _status.update { it.copy(phase = Phase.DISCONNECTING, detail = null) }
        stopService(context)
        // Motor hiç başlamadıysa durum bildirimi gelmez; ekran takılı kalmasın
        main.postDelayed({
            if (_status.value.phase == Phase.DISCONNECTING) finish(null)
        }, 6000)
    }

    /** Kullanıcı Android'in VPN izin penceresini reddetti. */
    fun permissionDenied() {
        _status.value = VpnStatusUi(
            detail = "VPN izni verilmedi",
            notice = "Bağlanmak için Android'in VPN bağlantı isteğini onaylamanız gerekiyor",
            noticeLevel = Notice.WARNING,
        )
    }

    /** Duraklat: tünel kapanır, trafik normal internetten gider; oturum açık kalır. */
    fun pause(context: Context) = withService(context) { it.userPause(true) }

    /** Devam et: aynı oturum yeni tünel açar (sıfırdan bağlanmaz). */
    fun resume(context: Context) = withService(context) { it.userPause(false) }

    private fun stopService(context: Context) {
        ProfileManager.setConntectedVpnProfileDisconnected(context.applicationContext)
        withService(context) { it.stopVPN(false) }
    }

    /** VPN servisine bağlanıp tek bir komut gönderir. */
    private fun withService(context: Context, command: (IOpenVPNServiceInternal) -> Unit) {
        val ctx = context.applicationContext
        val intent = Intent(ctx, OpenVPNService::class.java).setAction(OpenVPNService.START_SERVICE)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    command(IOpenVPNServiceInternal.Stub.asInterface(binder))
                } catch (e: Exception) {
                    VpnStatus.logException(e)
                }
                try {
                    ctx.unbindService(this)
                } catch (_: IllegalArgumentException) {
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    private fun loadProfile(context: Context): VpnProfile {
        val parser = ConfigParser()
        context.assets.open(CONFIG_ASSET).reader().use { parser.parseConfig(it) }
        val profile = parser.convertProfile()
        profile.mName = "VPN"
        profile.setUUID(PROFILE_UUID)
        // persist-tun açıkken duraklatma tüneli açık bırakır ve tüm trafik boşa düşer.
        // Kapalıyken duraklatınca tünel kapanır (normal internet), devam edince aynı
        // OpenVPN süreci yeni tünel açar.
        profile.mPersistTun = false
        val check = profile.checkProfile(context)
        if (check != R.string.no_error_found) error(context.getString(check))

        val pm = ProfileManager.getInstance(context)
        pm.addProfile(profile)
        ProfileManager.saveProfile(context, profile)
        pm.saveProfileList(context)
        return profile
    }

    private fun readServerInfo(): ServerInfo {
        var host = ""
        var port = "1194"
        var proto = "udp"
        try {
            app.assets.open(CONFIG_ASSET).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.isEmpty() || parts[0].startsWith("#") || parts[0].startsWith(";")) continue
                    when {
                        parts[0] == "remote" && parts.size >= 2 && host.isEmpty() -> {
                            host = parts[1]
                            if (parts.size >= 3) port = parts[2]
                            if (parts.size >= 4) proto = parts[3]
                        }
                        parts[0] == "proto" && parts.size >= 2 -> proto = parts[1]
                    }
                }
            }
        } catch (_: Exception) {
        }
        return ServerInfo(host, port, proto.replace("-client", "").uppercase())
    }

    // ------------------------------------------------------------- Motor olayları

    override fun updateState(
        state: String?, logmessage: String?, localizedResId: Int,
        level: ConnectionStatus?, intent: Intent?,
    ) {
        val s = state ?: return
        val msg = logmessage ?: ""
        val localized = try {
            if (localizedResId != 0) app.getString(localizedResId) else null
        } catch (_: Exception) {
            null
        }
        main.post { onState(s, msg, level ?: ConnectionStatus.UNKNOWN_LEVEL, localized) }
    }

    override fun setConnectedVPN(uuid: String?) {}

    override fun updateByteCount(rx: Long, tx: Long, diffIn: Long, diffOut: Long) {
        main.post { onBytes(rx, tx) }
    }

    override fun newLog(item: LogItem?) {
        if (item == null || item.logLevel != VpnStatus.LogLevel.ERROR) return
        val text = try {
            item.getString(app)
        } catch (_: Exception) {
            return
        }
        val time = item.logtime
        main.post {
            if (time >= startedWall && _status.value.phase != Phase.OFF) lastLogError = text.take(200)
        }
    }

    private fun onState(state: String, msg: String, level: ConnectionStatus, localized: String?) {
        val cur = _status.value
        when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> {
                sawEngine = true
                // msg: "SUCCESS,10.8.0.2,sunucu,port,..." ya da yönlendirme hatasında "ERROR,..."
                val fields = msg.split(",")
                val ip = fields.getOrNull(1).orEmpty().ifEmpty { cur.vpnIp }
                val since = cur.connectedAt ?: restoreConnectedAt()
                if (cur.phase != Phase.ON) resetSample()
                _status.value = cur.copy(
                    phase = Phase.ON,
                    detail = null,
                    progress = 1f,
                    vpnIp = ip,
                    connectedAt = since,
                    notice = if (fields.firstOrNull() == "ERROR")
                        "Bağlandı ancak bazı yönlendirmeler eklenemedi" else null,
                    noticeLevel = if (fields.firstOrNull() == "ERROR") Notice.WARNING else Notice.NONE,
                )
                startTicker()
            }

            ConnectionStatus.LEVEL_NOTCONNECTED -> {
                // Bağlan'a basıldıktan hemen sonra önceki oturumun "kapandı" bildirimi
                // gelebilir; motor yeni oturumu başlatmadan gelen kapanışları yok say.
                if (cur.phase == Phase.CONNECTING && !sawEngine && !userStop) return
                if (cur.phase != Phase.OFF) finish(reasonForStop())
            }

            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                sawEngine = true
                abort("Kimlik doğrulama başarısız (sertifika geçersiz veya iptal edilmiş olabilir)")
            }

            ConnectionStatus.UNKNOWN_LEVEL -> Unit

            else -> {
                // Bağlanıyor / sunucu yanıt verdi / ağ yok / duraklatıldı / kullanıcı girdisi
                sawEngine = true
                if (cur.phase == Phase.DISCONNECTING) return
                if (cur.phase == Phase.OFF) {
                    // VPN uygulama dışından başlatıldı (bildirim, her zaman açık VPN)
                    startedAt = SystemClock.elapsedRealtime()
                    startedWall = System.currentTimeMillis()
                    userStop = false
                    fatal = null
                    lastLogError = null
                    resetTraffic()
                }
                if (cur.phase == Phase.ON || cur.phase == Phase.PAUSED) {
                    // Kurulu bağlantı koptu ya da duraklatmadan dönülüyor: zaman aşımı
                    // sayacı baştan başlar. Oturum süresi (connectedAt) ise sürer.
                    startedAt = SystemClock.elapsedRealtime()
                }
                lastState = state
                val stage = STAGES[state]
                var text = stage?.second ?: localized ?: state.lowercase().replaceFirstChar { it.uppercase() }

                if (level == ConnectionStatus.LEVEL_VPNPAUSED) {
                    _status.value = cur.copy(phase = Phase.PAUSED, detail = text, notice = null, noticeLevel = Notice.NONE)
                    _traffic.update { it.copy(downBps = 0.0, upBps = 0.0) }
                    return
                }

                val reason = msg.substringBefore(",")
                // SIGUSR1 gibi sinyaller duraklatma/ağ değişimi kaynaklı; kullanıcıya anlamsız
                if (state == "RECONNECTING" && reason.isNotEmpty() && !reason.startsWith("SIG")) {
                    text += " (${RECONNECT_REASONS[reason] ?: reason})"
                }
                _status.value = cur.copy(
                    phase = Phase.CONNECTING,
                    detail = "$text…",
                    progress = stage?.first ?: cur.progress,
                    notice = null,
                    noticeLevel = Notice.NONE,
                )
                startTicker()
            }
        }
    }

    private fun reasonForStop(): String? = when {
        userStop -> null
        fatal != null -> fatal
        lastLogError != null -> explainError(lastLogError!!)
        else -> "OpenVPN beklenmedik şekilde kapandı"
    }

    /** Bağlantıyı hata mesajıyla sonlandırır (motor kapanınca finish çağrılır). */
    private fun abort(message: String) {
        if (fatal == null) fatal = message
        val phase = _status.value.phase
        if (phase == Phase.OFF || phase == Phase.DISCONNECTING) return
        _status.update { it.copy(phase = Phase.DISCONNECTING, detail = null) }
        stopService(app)
        main.postDelayed({
            if (_status.value.phase == Phase.DISCONNECTING) finish(fatal)
        }, 6000)
    }

    /** Motor hiç çalışmadan oluşan hata (yapılandırma, servis başlatma). */
    private fun fail(message: String) {
        finish(message)
    }

    private fun finish(error: String?) {
        stopTicker()
        clearConnectedAt()
        _status.value = VpnStatusUi(
            detail = if (error != null) "Bağlantı kurulamadı" else null,
            notice = error,
            noticeLevel = if (error != null) Notice.ERROR else Notice.NONE,
        )
        pushHistory(0f, 0f)
        _traffic.update { it.copy(downBps = 0.0, upBps = 0.0, downHistory = downHist.toList(), upHistory = upHist.toList()) }
        userStop = false
        fatal = null
        lastState = ""
    }

    // ------------------------------------------------------------------- Hız

    private fun resetSample() {
        sample = null
        lastTraffic = 0L
    }

    private fun resetTraffic() {
        resetSample()
        downHist.clear()
        upHist.clear()
        _traffic.value = TrafficUi()
    }

    private fun onBytes(rx: Long, tx: Long) {
        val now = SystemClock.elapsedRealtime()
        if (_status.value.phase != Phase.ON) {
            sample = Triple(now, rx, tx)
            return
        }
        val s = sample
        if (s == null || rx < s.second || tx < s.third) {
            // İlk örnek ya da sayaç sıfırlandı: yalnızca referans al
            sample = Triple(now, rx, tx)
            _traffic.update { it.copy(totalRx = rx, totalTx = tx) }
            return
        }
        val dt = (now - s.first) / 1000.0
        if (dt < 0.25) return
        sample = Triple(now, rx, tx)
        lastTraffic = now
        // Gerçek geçen süreye böl (bildirimler tam aralıkla gelmeyebilir)
        _traffic.update {
            it.copy(
                downBps = (rx - s.second) / dt,
                upBps = (tx - s.third) / dt,
                totalRx = rx,
                totalTx = tx,
            )
        }
    }

    private fun pushHistory(down: Float, up: Float) {
        downHist.addLast(down)
        upHist.addLast(up)
        while (downHist.size > HISTORY) downHist.removeFirst()
        while (upHist.size > HISTORY) upHist.removeFirst()
    }

    // ------------------------------------------------------- Saniyelik izleme

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, 1000)
        }
    }
    private var tickerRunning = false

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        main.postDelayed(ticker, 1000)
    }

    private fun stopTicker() {
        tickerRunning = false
        main.removeCallbacks(ticker)
    }

    private fun tick() {
        val st = _status.value
        val now = SystemClock.elapsedRealtime()
        // İnternet yokken beklemek hata değil; sayaç ağ gelince başlasın
        if (lastState == "NONETWORK") startedAt = now
        if (st.phase == Phase.CONNECTING && !userStop && fatal == null &&
            now - startedAt > CONNECT_TIMEOUT_MS
        ) {
            val reason = lastLogError
            abort(
                "Bağlantı zaman aşımına uğradı: " +
                    (reason?.let(::explainError) ?: "sunucu yanıt vermiyor")
            )
            return
        }
        if (st.phase != Phase.ON && st.phase != Phase.PAUSED) return

        var t = _traffic.value
        if (st.phase == Phase.PAUSED || now - lastTraffic > STALE_MS) {
            // Bir süredir sayaç değişmediyse trafik yok demektir
            t = t.copy(downBps = 0.0, upBps = 0.0)
        }
        pushHistory(t.downBps.toFloat(), t.upBps.toFloat())
        _traffic.value = t.copy(downHistory = downHist.toList(), upHistory = upHist.toList())
    }

    // Uygulama kapanıp açılınca oturum süresi kaldığı yerden devam etsin
    private fun restoreConnectedAt(): Long {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getLong(PREF_CONNECTED_AT, 0L)
        if (saved > 0) return saved
        val now = System.currentTimeMillis()
        prefs.edit().putLong(PREF_CONNECTED_AT, now).apply()
        return now
    }

    private fun clearConnectedAt() {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_CONNECTED_AT).apply()
    }
}
