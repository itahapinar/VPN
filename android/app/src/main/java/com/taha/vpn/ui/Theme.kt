package com.taha.vpn.ui

import androidx.compose.ui.graphics.Color
import com.taha.vpn.Phase
import java.util.Locale

// Renk paleti: masaüstü sürümüyle (kaynak/widgets.py) aynı, logodaki mor/turkuaz tonları
object VpnColors {
    val BgTop = Color(0xFF17163A)
    val BgBottom = Color(0xFF0B0B1A)
    val Text = Color(0xFFECEEFF)
    val Muted = Color(0xFF8A8FB8)
    val Cyan = Color(0xFF35E6D6)
    val Purple = Color(0xFF8B5CF6)
    val Green = Color(0xFF2EE5A0)
    val Amber = Color(0xFFFFB547)
    val Red = Color(0xFFFF5C7A)
    val Idle = Color(0xFF7D80B0)
    val Card = Color.White.copy(alpha = 11 / 255f)
    val CardBorder = Color.White.copy(alpha = 18 / 255f)
    val DiscTop = Color(0xFF2C2A63)
    val DiscBottom = Color(0xFF121230)
}

/** Arka plan parıltısının rengi */
fun Phase.accent(): Color = when (this) {
    Phase.OFF -> VpnColors.Purple
    Phase.CONNECTING, Phase.PAUSED, Phase.DISCONNECTING -> VpnColors.Amber
    Phase.ON -> VpnColors.Green
}

/** Güç butonu ve durum çipinin rengi */
fun Phase.color(): Color = when (this) {
    Phase.OFF -> VpnColors.Idle
    Phase.CONNECTING, Phase.PAUSED, Phase.DISCONNECTING -> VpnColors.Amber
    Phase.ON -> VpnColors.Green
}

private val TR = Locale.forLanguageTag("tr")

/** Bayt/saniye değerini (sayı, birim) olarak döndürür. */
fun formatSpeed(bps: Double): Pair<String, String> = when {
    bps < 1024 -> String.format(TR, "%.0f", bps) to "B/s"
    bps < 1024.0 * 1024 -> String.format(TR, "%.1f", bps / 1024) to "KB/s"
    else -> String.format(TR, "%.2f", bps / (1024.0 * 1024)) to "MB/s"
}

fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024L * 1024 -> String.format(TR, "%.1f KB", n / 1024.0)
    n < 1024L * 1024 * 1024 -> String.format(TR, "%.1f MB", n / (1024.0 * 1024))
    else -> String.format(TR, "%.2f GB", n / (1024.0 * 1024 * 1024))
}

fun formatDuration(seconds: Long): String =
    String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
