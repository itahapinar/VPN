package com.taha.vpn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taha.vpn.VpnEngine

private val CardShape = RoundedCornerShape(16.dp)

/** Yarı saydam, ince kenarlıklı kart zemini */
@Composable
fun VpnCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(VpnColors.Card, CardShape)
            .border(1.dp, VpnColors.CardBorder, CardShape),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, align: TextAlign? = null) {
    Text(
        text, modifier,
        color = VpnColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp, textAlign = align, maxLines = 1,
    )
}

/** "● KORUNUYOR" gibi renkli durum hapı */
@Composable
fun StatusChip(text: String, target: Color) {
    val color by animateColorAsState(target, tween(450), label = "cip")
    val shape = RoundedCornerShape(50)
    Text(
        "●  $text",
        modifier = Modifier
            .background(color.copy(alpha = 34 / 255f), shape)
            .border(1.dp, color.copy(alpha = 90 / 255f), shape)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
    )
}

/** Küçük, çerçeveli hap düğme (Duraklat / Devam et) */
@Composable
fun PillButton(text: String, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        modifier = Modifier
            .clip(shape)
            .background(color.copy(alpha = 24 / 255f))
            .border(1.dp, color.copy(alpha = 80 / 255f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
        color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
    )
}

/** Yan yana başlık/değer sütunları (Sunucu, Protokol, VPN IP) */
@Composable
fun InfoCard(fields: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    VpnCard(modifier) {
        Row(
            Modifier
                .height(IntrinsicSize.Min)
                .padding(horizontal = 4.dp, vertical = 12.dp),
        ) {
            fields.forEachIndexed { i, (caption, value) ->
                if (i > 0) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(VpnColors.CardBorder),
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SectionLabel(caption, align = TextAlign.Center)
                    Text(
                        value.ifEmpty { "—" },
                        color = VpnColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Renkli daire içinde aşağı/yukarı ok */
@Composable
private fun ArrowIcon(color: Color, down: Boolean) {
    Canvas(
        Modifier
            .size(30.dp)
            .background(color.copy(alpha = 40 / 255f), CircleShape),
    ) {
        val u = size.minDimension / 30f
        val stroke = 2.2f * u
        if (down) {
            drawLine(color, Offset(15 * u, 8.5f * u), Offset(15 * u, 21 * u), stroke, StrokeCap.Round)
            val p = Path().apply {
                moveTo(10 * u, 16.5f * u); lineTo(15 * u, 21.5f * u); lineTo(20 * u, 16.5f * u)
            }
            drawPath(p, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawLine(color, Offset(15 * u, 9 * u), Offset(15 * u, 21.5f * u), stroke, StrokeCap.Round)
            val p = Path().apply {
                moveTo(10 * u, 13.5f * u); lineTo(15 * u, 8.5f * u); lineTo(20 * u, 13.5f * u)
            }
            drawPath(p, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun SpeedCard(
    title: String,
    color: Color,
    down: Boolean,
    bps: Double,
    total: Long,
    modifier: Modifier = Modifier,
) {
    val (value, unit) = formatSpeed(bps)
    VpnCard(modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArrowIcon(color, down)
                Spacer(Modifier.width(8.dp))
                Text(title, color = VpnColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value, color = VpnColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    unit, Modifier.padding(bottom = 3.dp),
                    color = VpnColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text("Toplam: ${formatBytes(total)}", color = VpnColors.Muted, fontSize = 11.sp)
        }
    }
}

@Composable
fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (text, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(color, CircleShape),
                )
                Spacer(Modifier.width(5.dp))
                Text(text, color = VpnColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Son 60 saniyenin indirme/yükleme hızını gösteren yumuşak çizgi grafik */
@Composable
fun SpeedGraph(down: List<Float>, up: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = VpnEngine.HISTORY
        val left = 1.dp.toPx()
        val top = 6.dp.toPx()
        val right = size.width - 6.dp.toPx()
        val bottom = size.height - 2.dp.toPx()
        val w = right - left
        val h = bottom - top
        val peak = maxOf(down.maxOrNull() ?: 0f, up.maxOrNull() ?: 0f)
        val scale = maxOf(peak, 32f * 1024) * 1.2f

        // Kesikli yatay kılavuz çizgileri
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        for (i in 1..3) {
            val y = top + h * i / 4
            drawLine(
                Color.White.copy(alpha = 16 / 255f), Offset(left, y), Offset(right, y),
                1.dp.toPx(), pathEffect = dash,
            )
        }

        // Geçmiş sağa yaslanır; eksik başlangıç sıfırla doldurulur
        fun points(values: List<Float>): List<Offset> {
            val padded = List(n - values.size.coerceAtMost(n)) { 0f } + values.takeLast(n)
            val step = w / (n - 1)
            return padded.mapIndexed { i, v -> Offset(left + i * step, bottom - (v / scale) * h) }
        }

        for ((values, color) in listOf(up to VpnColors.Purple, down to VpnColors.Cyan)) {
            val pts = points(values)
            val line = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]
                    val b = pts[i]
                    val mx = (a.x + b.x) / 2
                    cubicTo(mx, a.y, mx, b.y, b.x, b.y)
                }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(right, bottom)
                lineTo(left, bottom)
                close()
            }
            drawPath(
                fill,
                Brush.verticalGradient(
                    listOf(color.copy(alpha = 85 / 255f), color.copy(alpha = 0f)),
                    startY = top, endY = bottom,
                ),
            )
            drawPath(line, color, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
            drawCircle(color, 3.2.dp.toPx(), pts.last())
        }
    }
}

@Composable
fun Header(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.taha.vpn.R.drawable.logo),
            contentDescription = null,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text("VPN", color = VpnColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Güvenli Tünel", color = VpnColors.Muted, fontSize = 11.sp)
        }
    }
}

/** Oturum süresi; eşit genişlikli rakamlar sayesinde her saniye yerinden oynamaz */
@Composable
fun TimerText(text: String, color: Color) {
    val animated by animateColorAsState(color, tween(450), label = "sure")
    Text(
        text, color = animated, fontSize = 34.sp, fontWeight = FontWeight.Light,
        letterSpacing = 1.5.sp, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"),
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
    )
}
