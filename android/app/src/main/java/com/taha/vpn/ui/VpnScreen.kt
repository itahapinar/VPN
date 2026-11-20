package com.taha.vpn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taha.vpn.Notice
import com.taha.vpn.Phase
import com.taha.vpn.ServerInfo
import com.taha.vpn.TrafficUi
import com.taha.vpn.VpnStatusUi
import kotlinx.coroutines.delay

@Composable
fun VpnScreen(
    status: VpnStatusUi,
    traffic: TrafficUi,
    server: ServerInfo,
    onToggle: () -> Unit,
    onPauseToggle: () -> Unit = {},
) {
    val accent by animateColorAsState(status.phase.accent(), tween(700), label = "vurgu")

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(VpnColors.BgTop, VpnColors.BgBottom)))
            .drawBehind {
                // Duruma göre renk değiştiren yumuşak parıltı
                drawRect(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 60 / 255f), accent.copy(alpha = 0f)),
                        center = Offset(size.width / 2, size.height * 0.28f),
                        radius = size.width * 0.9f,
                    ),
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .systemBarsPadding()
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp),
            )

            // Durum
            val (chipText, detail) = when (status.phase) {
                Phase.OFF -> "BAĞLI DEĞİL" to "Bağlantınız şu an korunmuyor"
                Phase.CONNECTING -> "BAĞLANIYOR" to "Sunucuya bağlanılıyor…"
                Phase.PAUSED -> "DURAKLATILDI" to "Normal internet bağlantınız kullanılıyor"
                Phase.DISCONNECTING -> "KESİLİYOR" to "Bağlantı güvenli şekilde kapatılıyor…"
                Phase.ON -> "KORUNUYOR" to "Trafiğiniz şifreli tünelden geçiyor"
            }
            StatusChip(chipText, if (status.phase == Phase.OFF) VpnColors.Red else status.phase.color())
            Spacer(Modifier.height(8.dp))
            Text(
                status.detail ?: detail,
                color = VpnColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 2,
            )

            // Güç butonu
            PowerButton(status.phase, status.progress, onToggle, Modifier.padding(vertical = 4.dp))

            // Duraklat / devam et (yer hep ayrılı; düğme belirince sayfa kaymasın)
            Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                when (status.phase) {
                    Phase.ON -> PillButton("❚❚  Duraklat", VpnColors.Muted, onPauseToggle)
                    Phase.PAUSED -> PillButton("▶  Devam et", VpnColors.Green, onPauseToggle)
                    else -> Unit
                }
            }

            // Oturum süresi
            val elapsed = rememberElapsedSeconds(status.connectedAt)
            TimerText(
                formatDuration(elapsed),
                if (status.phase == Phase.ON) VpnColors.Text else VpnColors.Muted,
            )
            SectionLabel("OTURUM SÜRESİ", align = TextAlign.Center)
            Spacer(Modifier.height(18.dp))

            InfoCard(
                listOf(
                    "SUNUCU" to server.host,
                    "PROTOKOL" to "${server.proto} · ${server.port}",
                    "VPN IP" to if (status.phase == Phase.ON) status.vpnIp else "",
                ),
                Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpeedCard(
                    "İndirme", VpnColors.Cyan, down = true,
                    bps = traffic.downBps, total = traffic.totalRx, modifier = Modifier.weight(1f),
                )
                SpeedCard(
                    "Yükleme", VpnColors.Purple, down = false,
                    bps = traffic.upBps, total = traffic.totalTx, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))

            // Canlı grafik
            VpnCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("CANLI TRAFİK")
                        Spacer(Modifier.weight(1f))
                        Legend(listOf("İndirme" to VpnColors.Cyan, "Yükleme" to VpnColors.Purple))
                    }
                    Spacer(Modifier.height(4.dp))
                    SpeedGraph(
                        traffic.downHistory, traffic.upHistory,
                        Modifier
                            .fillMaxWidth()
                            .height(90.dp),
                    )
                    val peak = maxOf(
                        traffic.downHistory.maxOrNull() ?: 0f,
                        traffic.upHistory.maxOrNull() ?: 0f,
                    )
                    val (pv, pu) = formatSpeed(peak.toDouble())
                    Text("Son 60 sn · tepe $pv $pu", color = VpnColors.Muted, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(12.dp))

            // Alt bilgi: hata / uyarı / yapılandırma
            val (footer, footerColor) = when (status.noticeLevel) {
                Notice.ERROR -> status.notice.orEmpty() to VpnColors.Red
                Notice.WARNING -> "⚠ ${status.notice.orEmpty()}" to VpnColors.Amber
                Notice.NONE -> "OpenVPN · ${com.taha.vpn.BuildConfig.OVPN_NAME}" to VpnColors.Muted
            }
            Text(
                footer, color = footerColor, fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Bağlantı kurulduğundan beri geçen saniye; bağlı değilken 0 */
@Composable
private fun rememberElapsedSeconds(since: Long?): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    return if (since == null) 0 else ((now - since) / 1000).coerceAtLeast(0)
}

@Preview(widthDp = 392, heightDp = 860)
@Composable
private fun PreviewOn() {
    val down = List(60) { i -> (200_000 + 150_000 * kotlin.math.sin(i / 6.0)).toFloat() }
    val up = List(60) { i -> (40_000 + 25_000 * kotlin.math.cos(i / 5.0)).toFloat() }
    VpnScreen(
        VpnStatusUi(
            phase = Phase.ON, progress = 1f, vpnIp = "10.8.0.2",
            connectedAt = System.currentTimeMillis() - 754_000,
        ),
        TrafficUi(312_000.0, 48_500.0, 182_000_000, 21_400_000, down, up),
        ServerInfo("203.0.113.10", "443", "UDP"),
        onToggle = {},
    )
}

@Preview(widthDp = 392, heightDp = 860)
@Composable
private fun PreviewConnecting() {
    VpnScreen(
        VpnStatusUi(phase = Phase.CONNECTING, progress = 0.48f, detail = "Kimlik doğrulanıyor…"),
        TrafficUi(),
        ServerInfo("203.0.113.10", "443", "UDP"),
        onToggle = {},
    )
}
