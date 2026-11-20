import struct, sys
from PyQt6.QtCore import Qt, QPointF, QRectF, QBuffer, QIODevice
from PyQt6.QtGui import (QGuiApplication, QImage, QPainter, QColor, QPen, QPainterPath,
                         QLinearGradient, QRadialGradient)

app = QGuiApplication(sys.argv)
CYAN = QColor("#35E6D6")

def draw_icon(n):
    img = QImage(n, n, QImage.Format.Format_ARGB32_Premultiplied)
    img.fill(Qt.GlobalColor.transparent)
    p = QPainter(img)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.scale(n, n)  # birim koordinat (0..1)
    small = n <= 24

    # Arka plan: yuvarlak kare, indigo -> mor degrade
    m = 0.0 if small else 0.03
    bg = QRectF(m, m, 1 - 2 * m, 1 - 2 * m)
    g = QLinearGradient(0, 0, 1, 1)
    g.setColorAt(0, QColor("#2A2F9C"))
    g.setColorAt(1, QColor("#6420AE"))
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(g)
    p.drawRoundedRect(bg, 0.22, 0.22)
    if not small:
        hl = QLinearGradient(0, m, 0, 0.55)
        hl.setColorAt(0, QColor(255, 255, 255, 38))
        hl.setColorAt(1, QColor(255, 255, 255, 0))
        p.setBrush(hl)
        p.drawRoundedRect(bg, 0.22, 0.22)
        # Kalkanın arkasında turkuaz parıltı
        glow = QRadialGradient(QPointF(0.5, 0.5), 0.42)
        glow.setColorAt(0, QColor(53, 230, 214, 90))
        glow.setColorAt(1, QColor(53, 230, 214, 0))
        p.setBrush(glow)
        p.drawEllipse(QPointF(0.5, 0.5), 0.42, 0.42)

    # Kalkan
    s = 1.08 if small else 1.0
    def P(x, y):
        return QPointF(0.5 + (x - 0.5) * s, 0.51 + (y - 0.51) * s)
    shield = QPainterPath(P(0.5, 0.16))
    shield.cubicTo(P(0.60, 0.235), P(0.70, 0.26), P(0.79, 0.27))
    shield.lineTo(P(0.79, 0.48))
    shield.cubicTo(P(0.79, 0.68), P(0.66, 0.79), P(0.5, 0.86))
    shield.cubicTo(P(0.34, 0.79), P(0.21, 0.68), P(0.21, 0.48))
    shield.lineTo(P(0.21, 0.27))
    shield.cubicTo(P(0.30, 0.26), P(0.40, 0.235), P(0.5, 0.16))
    fill = QLinearGradient(0, 0.16, 0, 0.86)
    fill.setColorAt(0, QColor(53, 230, 214, 70))
    fill.setColorAt(1, QColor(53, 230, 214, 20))
    pen = QPen(QColor("#D8FFFA") if not small else CYAN, 0.075 if small else 0.05)
    pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
    if not small:
        # dış parlak çizgi + turkuaz kenar
        p.setPen(QPen(CYAN, 0.085, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap, Qt.PenJoinStyle.RoundJoin))
        p.setBrush(fill)
        p.drawPath(shield)
        pen.setWidthF(0.03)
    p.setPen(pen)
    p.setBrush(fill if small else Qt.BrushStyle.NoBrush)
    p.drawPath(shield)

    # Kilit
    body_grad = QLinearGradient(0, 0.47, 0, 0.67)
    body_grad.setColorAt(0, QColor("#E6FFFC"))
    body_grad.setColorAt(1, CYAN)
    sh = QPainterPath(P(0.43, 0.49))
    sh.lineTo(P(0.43, 0.43))
    sh.arcTo(QRectF(P(0.43, 0.36), P(0.57, 0.50)), 180, -180)
    sh.lineTo(P(0.57, 0.49))
    p.setBrush(Qt.BrushStyle.NoBrush)
    p.setPen(QPen(QColor("#E6FFFC"), 0.045 if small else 0.034, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
    p.drawPath(sh)
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(body_grad)
    p.drawRoundedRect(QRectF(P(0.385, 0.475), P(0.615, 0.665)), 0.035, 0.035)
    if n >= 32:
        p.setBrush(QColor("#2A2F8F"))
        p.drawEllipse(P(0.5, 0.548), 0.022 * s, 0.022 * s)
        hole = QPainterPath(P(0.488, 0.55))
        hole.lineTo(P(0.512, 0.55)); hole.lineTo(P(0.518, 0.62)); hole.lineTo(P(0.482, 0.62)); hole.closeSubpath()
        p.drawPath(hole)
    p.end()
    return img

def png_bytes(img):
    buf = QBuffer(); buf.open(QIODevice.OpenModeFlag.WriteOnly)
    img.save(buf, "PNG")
    return bytes(buf.data())

out_dir = sys.argv[1]
sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
entries = [(n, png_bytes(draw_icon(n))) for n in sizes]
with open(out_dir + "/app_icon.ico", "wb") as f:
    f.write(struct.pack("<HHH", 0, 1, len(entries)))
    offset = 6 + 16 * len(entries)
    for n, data in entries:
        f.write(struct.pack("<BBBBHHII", n % 256, n % 256, 0, 0, 1, 32, len(data), offset))
        offset += len(data)
    for _, data in entries:
        f.write(data)
draw_icon(512).save(out_dir + "/logo.png")
# önizleme: tüm boyutlar koyu ve açık zemin üzerinde
prev = QImage(760, 300, QImage.Format.Format_ARGB32); prev.fill(QColor("#0B0B1A"))
p = QPainter(prev); p.fillRect(0, 150, 760, 150, QColor("#F3F3F3")); x = 10
for n in sizes:
    im = draw_icon(n)
    p.drawImage(x, 75 - n // 2, im); p.drawImage(x, 225 - n // 2, im); x += n + 14
p.end(); prev.save(sys.argv[2] + "/icon_preview.png")
print("ok")
