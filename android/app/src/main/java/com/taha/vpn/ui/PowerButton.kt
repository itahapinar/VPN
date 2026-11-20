package com.taha.vpn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taha.vpn.Phase
import kotlin.math.cos
import kotlin.math.sin

private const val BUTTON_DP = 236f

/**
 * Animasyonlu, yuvarlak bağlan/kes butonu (masaüstündeki PowerButton'ın karşılığı).
 * Bağlanırken halka gerçek bağlantı aşamasına göre dolar.
 */
@Composable
fun PowerButton(
    phase: Phase,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(phase.color(), tween(450), label = "renk")
    val glow by animateFloatAsState(
        when (phase) {
            Phase.OFF -> 0f
            Phase.CONNECTING -> 0.55f
            Phase.PAUSED, Phase.DISCONNECTING -> 0.3f
            Phase.ON -> 1f
        },
        tween(700), label = "parilti",
    )
    val ring by animateFloatAsState(
        when (phase) {
            Phase.OFF -> 0f
            Phase.ON, Phase.PAUSED -> 1f
            else -> progress.coerceIn(0.04f, 1f)
        },
        tween(600, easing = FastOutSlowInEasing), label = "halka",
    )

    // Sürekli animasyonlar yalnızca gerektiğinde çalışır
    val spin = loopingValue(phase == Phase.CONNECTING || phase == Phase.DISCONNECTING, 360f, 1640)
    val wave = loopingValue(phase == Phase.ON, 1f, 2600)

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "basma")
    val haptic = LocalHapticFeedback.current

    val iconColor = if (phase == Phase.OFF) Color(0xFFB4B8DC) else color
    val caption = when (phase) {
        Phase.OFF -> "BAĞLAN"
        Phase.CONNECTING -> "İPTAL"
        Phase.ON, Phase.PAUSED -> "BAĞLANTIYI KES"
        Phase.DISCONNECTING -> "KESİLİYOR"
    }

    Box(
        modifier
            .size(BUTTON_DP.dp)
            .semantics { contentDescription = caption }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = phase != Phase.DISCONNECTING,
                role = Role.Button,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val k = size.minDimension / BUTTON_DP.dp.toPx() * 1.dp.toPx()  // 1 masaüstü pikseli
            val c = center
            val outer = size.minDimension / 2 - 2 * k
            val ringR = outer - 26 * k
            val discR = ringR - 13 * k

            // Dış parıltı
            if (glow > 0.01f) {
                drawCircle(
                    Brush.radialGradient(
                        0f to color.copy(alpha = 110 / 255f * glow),
                        discR / outer to color.copy(alpha = 70 / 255f * glow),
                        1f to color.copy(alpha = 0f),
                        center = c, radius = outer,
                    ),
                    radius = outer, center = c,
                )
            }

            // Bağlıyken dışa doğru yayılan dalgalar
            if (phase == Phase.ON) {
                for (i in 0..1) {
                    val t = (wave.value + i * 0.5f) % 1f
                    drawCircle(
                        color.copy(alpha = (140 / 255f * (1 - t) * glow).coerceIn(0f, 1f)),
                        radius = ringR + t * (outer - ringR), center = c,
                        style = Stroke(2 * k),
                    )
                }
            }

            // Halka zemini + ilerleme yayı
            drawCircle(Color.White.copy(alpha = 22 / 255f), ringR, c, style = Stroke(6 * k))
            if (ring > 0.003f) {
                drawArc(
                    color, startAngle = -90f, sweepAngle = ring * 360f, useCenter = false,
                    topLeft = Offset(c.x - ringR, c.y - ringR), size = Size(2 * ringR, 2 * ringR),
                    style = Stroke(6 * k, cap = StrokeCap.Round),
                )
            }

            // Bağlanırken halka üzerinde dönen kuyruklu yıldız
            if (phase == Phase.CONNECTING || phase == Phase.DISCONNECTING) {
                for (i in 0 until 10) {
                    val ang = Math.toRadians((spin.value - i * 4 - 90).toDouble())
                    val pt = Offset(c.x + ringR * cos(ang).toFloat(), c.y + ringR * sin(ang).toFloat())
                    val dot = if (i == 0) Color.White else color
                    drawCircle(dot.copy(alpha = 1f - i / 10f), (4.5f - i * 0.3f) * k, pt)
                }
            }

            // İç disk
            val dr = discR * press
            drawCircle(
                Brush.radialGradient(
                    listOf(VpnColors.DiscTop, VpnColors.DiscBottom),
                    center = Offset(c.x, c.y - dr * 0.55f), radius = dr * 1.6f,
                ),
                dr, c,
            )
            drawCircle(Color.White.copy(alpha = 28 / 255f), dr, c, style = Stroke(1.2f * k))
            drawCircle(
                color.copy(alpha = ((40 + 90 * glow) / 255f).coerceIn(0f, 1f)),
                dr - 8 * k, c, style = Stroke(2 * k),
            )

            // Güç simgesi: üstü açık yay + dikey çizgi
            val ir = dr * 0.30f
            val cy = c.y - dr * 0.10f
            val stroke = Stroke(6.5f * k, cap = StrokeCap.Round)
            drawArc(
                iconColor, startAngle = -60f, sweepAngle = 300f, useCenter = false,
                topLeft = Offset(c.x - ir, cy - ir), size = Size(2 * ir, 2 * ir), style = stroke,
            )
            drawLine(
                iconColor, Offset(c.x, cy - ir - 5 * k), Offset(c.x, cy - ir * 0.1f),
                strokeWidth = 6.5f * k, cap = StrokeCap.Round,
            )
        }

        // Alt yazı (iç diskin alt kısmında)
        Text(
            caption,
            modifier = Modifier.offset(y = 38.dp),
            color = lerp(iconColor, Color.Transparent, 0.22f),
            fontSize = if (caption.length > 10) 9.sp else 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = if (caption.length > 10) 1.sp else 2.sp,
            maxLines = 1,
        )
    }
}

/** active iken 0..target arasında sürekli dönen değer; değilken 0'da durur. */
@Composable
private fun loopingValue(active: Boolean, target: Float, durationMs: Int): State<Float> {
    if (!active) return remember { mutableFloatStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "dongu")
    return transition.animateFloat(
        0f, target, infiniteRepeatable(tween(durationMs, easing = LinearEasing)), label = "deger",
    )
}
