import math
from collections import deque

from PyQt6.QtCore import Qt, QTimer, QPointF, QRectF, QElapsedTimer, QVariantAnimation, QEasingCurve
from PyQt6.QtGui import (QPainter, QColor, QPen, QFont, QFontMetricsF, QPixmap, QPainterPath,
                         QRadialGradient, QLinearGradient)
from PyQt6.QtWidgets import (QWidget, QAbstractButton, QLabel, QFrame, QHBoxLayout,
                             QVBoxLayout, QPushButton)

# --- Renk Paleti (logodaki mor/turkuaz tonlarından) ---
BG_TOP = QColor("#17163A")
BG_BOTTOM = QColor("#0B0B1A")
TEXT = "#ECEEFF"
MUTED = "#8A8FB8"
CYAN = QColor("#35E6D6")
PURPLE = QColor("#8B5CF6")
GREEN = QColor("#2EE5A0")
AMBER = QColor("#FFB547")
RED = QColor("#FF5C7A")
IDLE = QColor("#7D80B0")

APP_QSS = """
QFrame#card {
    background-color: rgba(255, 255, 255, 11);
    border: 1px solid rgba(255, 255, 255, 18);
    border-radius: 16px;
}
QPushButton#titleBtn, QPushButton#closeBtn {
    border: none;
    border-radius: 8px;
    color: #A7AACD;
    font-size: 13px;
}
QPushButton#titleBtn:hover { background-color: rgba(255, 255, 255, 20); color: white; }
QPushButton#closeBtn:hover { background-color: #E5484D; color: white; }
QToolTip {
    background-color: #1F1E45;
    color: #ECEEFF;
    border: 1px solid #3A3870;
    padding: 4px 8px;
}
"""


def make_label(text="", size=13, weight=QFont.Weight.Normal, color=TEXT, spacing=0.0, align=None):
    lbl = QLabel(text)
    font = QFont("Segoe UI")
    font.setPixelSize(size)
    font.setWeight(weight)
    if spacing:
        font.setLetterSpacing(QFont.SpacingType.AbsoluteSpacing, spacing)
    lbl.setFont(font)
    lbl.setStyleSheet(f"color: {color};")
    if align is not None:
        lbl.setAlignment(align)
    return lbl


def format_speed(bps):
    """Byte/saniye değerini (sayı, birim) olarak döndürür."""
    if bps < 1024:
        return f"{bps:.0f}", "B/s"
    if bps < 1024 ** 2:
        return f"{bps / 1024:.1f}", "KB/s"
    return f"{bps / 1024 ** 2:.2f}", "MB/s"


def format_bytes(n):
    if n < 1024:
        return f"{n:.0f} B"
    if n < 1024 ** 2:
        return f"{n / 1024:.1f} KB"
    if n < 1024 ** 3:
        return f"{n / 1024 ** 2:.1f} MB"
    return f"{n / 1024 ** 3:.2f} GB"


def with_alpha(color, alpha):
    c = QColor(color)
    c.setAlpha(max(0, min(255, int(alpha))))
    return c


class Background(QWidget):
    """Koyu degrade arka plan + duruma göre renk değiştiren yumuşak parıltı."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self._accent = QColor(PURPLE)
        self._anim = QVariantAnimation(self)
        self._anim.setDuration(700)
        self._anim.setEasingCurve(QEasingCurve.Type.OutCubic)
        self._anim.valueChanged.connect(self._set_accent)

    def _set_accent(self, color):
        self._accent = QColor(color)
        self.update()

    def set_accent(self, color):
        self._anim.stop()
        self._anim.setStartValue(QColor(self._accent))
        self._anim.setEndValue(QColor(color))
        self._anim.start()

    def paintEvent(self, event):
        p = QPainter(self)
        r = self.rect()
        grad = QLinearGradient(0, 0, 0, r.height())
        grad.setColorAt(0, BG_TOP)
        grad.setColorAt(1, BG_BOTTOM)
        p.fillRect(r, grad)

        glow = QRadialGradient(QPointF(r.width() / 2, r.height() * 0.30), r.width() * 0.9)
        glow.setColorAt(0, with_alpha(self._accent, 60))
        glow.setColorAt(1, with_alpha(self._accent, 0))
        p.fillRect(r, glow)


class TitleBar(QWidget):
    """Çerçevesiz pencere için sürüklenebilir başlık çubuğu."""

    def __init__(self, title, subtitle, logo_path=None, parent=None):
        super().__init__(parent)
        self.setFixedHeight(50)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(16, 10, 10, 0)
        lay.setSpacing(10)

        self.logo = QLabel()
        self.logo.setFixedSize(30, 30)
        self._logo_src = QPixmap(logo_path) if logo_path else QPixmap()
        self._update_logo()
        lay.addWidget(self.logo)

        texts = QVBoxLayout()
        texts.setSpacing(0)
        texts.addWidget(make_label(title, 14, QFont.Weight.Bold))
        texts.addWidget(make_label(subtitle, 10, QFont.Weight.Medium, MUTED))
        lay.addLayout(texts)
        lay.addStretch()

        self.btn_min = QPushButton("—")
        self.btn_min.setObjectName("titleBtn")
        self.btn_close = QPushButton("✕")
        self.btn_close.setObjectName("closeBtn")
        for btn, tip in ((self.btn_min, "Simge durumuna küçült"), (self.btn_close, "Kapat")):
            btn.setFixedSize(34, 30)
            btn.setToolTip(tip)
            btn.setCursor(Qt.CursorShape.PointingHandCursor)
            lay.addWidget(btn, alignment=Qt.AlignmentFlag.AlignTop)
        self.btn_min.clicked.connect(lambda: self.window().showMinimized())
        self.btn_close.clicked.connect(lambda: self.window().close())

    def _update_logo(self):
        # Ekran ölçeğine (%125, %150...) göre keskin çizim
        if self._logo_src.isNull():
            return
        dpr = self.devicePixelRatioF()
        side = round(30 * dpr)
        pix = self._logo_src.scaled(side, side, Qt.AspectRatioMode.KeepAspectRatio,
                                    Qt.TransformationMode.SmoothTransformation)
        pix.setDevicePixelRatio(dpr)
        self.logo.setPixmap(pix)

    def changeEvent(self, event):
        if event.type() == event.Type.DevicePixelRatioChange:
            self._update_logo()
        super().changeEvent(event)

    def showEvent(self, event):
        self._update_logo()
        super().showEvent(event)

    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            handle = self.window().windowHandle()
            if handle is not None:
                handle.startSystemMove()


class StatusChip(QLabel):
    def __init__(self, parent=None):
        super().__init__(parent)
        font = QFont("Segoe UI")
        font.setPixelSize(12)
        font.setWeight(QFont.Weight.Bold)
        font.setLetterSpacing(QFont.SpacingType.AbsoluteSpacing, 1.5)
        self.setFont(font)
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)

    def set_status(self, text, color):
        r, g, b = color.red(), color.green(), color.blue()
        self.setText(f"●  {text}")
        self.setStyleSheet(
            f"color: rgb({r},{g},{b});"
            f"background-color: rgba({r},{g},{b},34);"
            f"border: 1px solid rgba({r},{g},{b},90);"
            "border-radius: 14px; padding: 5px 16px;"
        )


class PowerButton(QAbstractButton):
    """Animasyonlu, yuvarlak bağlan/kes butonu.

    Modlar: "off", "connecting", "on", "paused". Bağlanırken halka gerçek
    bağlantı aşamasına göre dolar (set_progress).
    """

    COLORS = {"off": IDLE, "connecting": AMBER, "on": GREEN, "paused": AMBER}
    GLOW = {"off": 0.0, "connecting": 0.55, "on": 1.0, "paused": 0.3}
    CAPTIONS = {"off": "BAĞLAN", "connecting": "İPTAL", "on": "BAĞLANTIYI KES",
                "paused": "BAĞLANTIYI KES"}

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFixedSize(236, 236)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setFocusPolicy(Qt.FocusPolicy.NoFocus)

        self._mode = "off"
        self._color = QColor(IDLE)
        self._progress = 0.0
        self._target = 0.0
        self._glow = 0.0
        self._hover = 0.0
        self._hovered = False
        self._spin = 0.0
        self._phase = 0.0

        self._clock = QElapsedTimer()
        self._timer = QTimer(self)
        self._timer.setInterval(16)
        self._timer.timeout.connect(self._tick)

        self._color_anim = QVariantAnimation(self)
        self._color_anim.setDuration(450)
        self._color_anim.setEasingCurve(QEasingCurve.Type.OutCubic)
        self._color_anim.valueChanged.connect(self._set_color)

    # --- Durum ---
    def mode(self):
        return self._mode

    def set_mode(self, mode):
        if mode == self._mode:
            return
        self._mode = mode
        self._color_anim.stop()
        self._color_anim.setStartValue(QColor(self._color))
        self._color_anim.setEndValue(QColor(self.COLORS[mode]))
        self._color_anim.start()
        if mode == "off":
            self._target = 0.0
        elif mode in ("on", "paused"):
            self._target = 1.0
        else:
            self._target = 0.04
        self._wake()

    def set_progress(self, value):
        self._target = max(0.0, min(1.0, value))
        self._wake()

    def _set_color(self, color):
        self._color = QColor(color)
        self.update()

    # --- Animasyon döngüsü ---
    def _wake(self):
        if not self._timer.isActive():
            self._clock.restart()
            self._timer.start()

    def _tick(self):
        dt = min(self._clock.restart() / 1000.0, 0.05)
        self._spin = (self._spin + dt * 220) % 360
        self._phase += dt
        self._progress += (self._target - self._progress) * min(1.0, dt * 5)
        self._glow += (self.GLOW[self._mode] - self._glow) * min(1.0, dt * 3)
        self._hover += ((1.0 if self._hovered else 0.0) - self._hover) * min(1.0, dt * 12)

        settled = (abs(self._target - self._progress) < 0.002
                   and abs(self.GLOW[self._mode] - self._glow) < 0.002
                   and abs((1.0 if self._hovered else 0.0) - self._hover) < 0.01)
        if self._mode == "off" and settled:
            self._timer.stop()
        self.update()

    def enterEvent(self, event):
        self._hovered = True
        self._wake()
        super().enterEvent(event)

    def leaveEvent(self, event):
        self._hovered = False
        self._wake()
        super().leaveEvent(event)

    def sizeHint(self):
        return self.minimumSize()

    # --- Çizim ---
    def paintEvent(self, event):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        w, h = self.width(), self.height()
        c = QPointF(w / 2, h / 2)
        outer = min(w, h) / 2 - 2
        ring_r = outer - 26
        disc_r = ring_r - 13
        col = QColor(self._color)

        # Dış parıltı
        if self._glow > 0.01:
            g = QRadialGradient(c, outer)
            g.setColorAt(0, with_alpha(col, 110 * self._glow))
            g.setColorAt(disc_r / outer, with_alpha(col, 70 * self._glow))
            g.setColorAt(1, with_alpha(col, 0))
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(g)
            p.drawEllipse(c, outer, outer)

        # Bağlıyken dışa doğru yayılan dalgalar
        if self._mode == "on":
            p.setBrush(Qt.BrushStyle.NoBrush)
            for k in range(2):
                t = (self._phase / 2.6 + k * 0.5) % 1.0
                r = ring_r + t * (outer - ring_r)
                p.setPen(QPen(with_alpha(col, 140 * (1 - t) * self._glow), 2))
                p.drawEllipse(c, r, r)

        # Halka zemini + ilerleme yayı
        ring_rect = QRectF(c.x() - ring_r, c.y() - ring_r, 2 * ring_r, 2 * ring_r)
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.setPen(QPen(QColor(255, 255, 255, 22), 6))
        p.drawEllipse(ring_rect)
        if self._progress > 0.003:
            pen = QPen(col, 6)
            pen.setCapStyle(Qt.PenCapStyle.RoundCap)
            p.setPen(pen)
            p.drawArc(ring_rect, 90 * 16, -int(self._progress * 360 * 16))

        # Bağlanırken halka üzerinde dönen kuyruklu yıldız
        if self._mode == "connecting":
            for i in range(10):
                ang = math.radians(90 - (self._spin - i * 4))
                pt = QPointF(c.x() + ring_r * math.cos(ang), c.y() - ring_r * math.sin(ang))
                p.setPen(Qt.PenStyle.NoPen)
                p.setBrush(with_alpha(QColor("#FFFFFF") if i == 0 else col, 255 * (1 - i / 10)))
                p.drawEllipse(pt, 4.5 - i * 0.3, 4.5 - i * 0.3)

        # İç disk
        dr = disc_r * (0.96 if self.isDown() else 1.0)
        dg = QRadialGradient(QPointF(c.x(), c.y() - dr * 0.55), dr * 1.6)
        dg.setColorAt(0, QColor("#2C2A63").lighter(100 + int(18 * self._hover)))
        dg.setColorAt(1, QColor("#121230"))
        p.setPen(QPen(QColor(255, 255, 255, 28 + int(34 * self._hover)), 1.2))
        p.setBrush(dg)
        p.drawEllipse(c, dr, dr)
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.setPen(QPen(with_alpha(col, 40 + 90 * self._glow), 2))
        p.drawEllipse(c, dr - 8, dr - 8)

        # Güç simgesi
        if self._mode == "off":
            base = QColor(180, 184, 220)
            icon_col = QColor(
                int(base.red() + (255 - base.red()) * self._hover),
                int(base.green() + (255 - base.green()) * self._hover),
                int(base.blue() + (255 - base.blue()) * self._hover),
            )
        else:
            icon_col = col
        ir = dr * 0.30
        cy = c.y() - dr * 0.10
        pen = QPen(icon_col, 6.5)
        pen.setCapStyle(Qt.PenCapStyle.RoundCap)
        p.setPen(pen)
        p.drawArc(QRectF(c.x() - ir, cy - ir, 2 * ir, 2 * ir), 120 * 16, 300 * 16)
        p.drawLine(QPointF(c.x(), cy - ir - 5), QPointF(c.x(), cy - ir * 0.1))

        # Alt yazı: iç çemberin o yükseklikteki genişliğine sığacak şekilde
        text = self.CAPTIONS[self._mode]
        text_rect = QRectF(c.x() - dr, cy + ir + 14, 2 * dr, 18)
        inner_r = dr - 8
        dy = text_rect.center().y() - c.y()
        avail = 2 * math.sqrt(max(inner_r ** 2 - dy ** 2, 0)) - 24
        font = QFont("Segoe UI")
        font.setWeight(QFont.Weight.Bold)
        size, spacing = 10, 2.0
        while True:
            font.setPixelSize(size)
            font.setLetterSpacing(QFont.SpacingType.AbsoluteSpacing, spacing)
            if QFontMetricsF(font).horizontalAdvance(text) <= avail or size <= 8:
                break
            if spacing > 0.8:
                spacing -= 0.4
            else:
                size -= 1
        p.setFont(font)
        p.setPen(with_alpha(icon_col, 200))
        p.drawText(text_rect, Qt.AlignmentFlag.AlignCenter, text)


class PillButton(QAbstractButton):
    """Küçük hap düğme (Duraklat / Devam et); simgeler yazı tipine bağlı kalmadan çizilir."""

    HEIGHT = 34
    ICON = 12

    def __init__(self, parent=None):
        super().__init__(parent)
        self._font = QFont("Segoe UI")
        self._font.setPixelSize(13)
        self._font.setWeight(QFont.Weight.DemiBold)
        self._font.setLetterSpacing(QFont.SpacingType.AbsoluteSpacing, 0.3)
        self._color = QColor(TEXT)
        self._icon = "pause"
        self._hover = 0.0
        self._hovered = False
        self._anim = QTimer(self)
        self._anim.setInterval(16)
        self._anim.timeout.connect(self._step)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        self.setFixedHeight(self.HEIGHT)

    def set_look(self, text, color, icon):
        self.setText(text)
        self._color = QColor(color)
        self._icon = icon
        text_w = QFontMetricsF(self._font).horizontalAdvance(text)
        self.setFixedWidth(int(16 + self.ICON + 9 + text_w + 20))
        self.update()

    def _step(self):
        target = 1.0 if self._hovered else 0.0
        self._hover += (target - self._hover) * 0.25
        if abs(target - self._hover) < 0.01:
            self._hover = target
            self._anim.stop()
        self.update()

    def enterEvent(self, event):
        self._hovered = True
        self._anim.start()
        super().enterEvent(event)

    def leaveEvent(self, event):
        self._hovered = False
        self._anim.start()
        super().leaveEvent(event)

    def sizeHint(self):
        return self.size()

    def paintEvent(self, event):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        col = QColor(self._color)
        if not self.isEnabled():
            col.setAlpha(110)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        radius = r.height() / 2

        # Zemin: hafif dikey degrade, üzerine gelince ve basınca koyulaşır
        strength = 26 + 22 * self._hover + (26 if self.isDown() else 0)
        bg = QLinearGradient(0, r.top(), 0, r.bottom())
        bg.setColorAt(0, with_alpha(col, strength + 10))
        bg.setColorAt(1, with_alpha(col, strength - 6))
        p.setPen(QPen(with_alpha(col, 70 + 50 * self._hover), 1))
        p.setBrush(bg)
        p.drawRoundedRect(r, radius, radius)

        # Simge
        s = self.ICON
        x0 = 16.0
        y0 = (self.height() - s) / 2
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(col)
        if self._icon == "pause":
            bar = s * 0.32
            p.drawRoundedRect(QRectF(x0 + s * 0.08, y0, bar, s), 1.6, 1.6)
            p.drawRoundedRect(QRectF(x0 + s * 0.92 - bar, y0, bar, s), 1.6, 1.6)
        else:
            tri = QPainterPath(QPointF(x0 + s * 0.12, y0))
            tri.lineTo(QPointF(x0 + s, y0 + s / 2))
            tri.lineTo(QPointF(x0 + s * 0.12, y0 + s))
            tri.closeSubpath()
            pen = QPen(col, 2.2)
            pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
            p.setPen(pen)
            p.drawPath(tri)

        # Yazı
        p.setPen(col)
        p.setFont(self._font)
        p.drawText(QRectF(x0 + s + 9, 0, self.width() - (x0 + s + 9), self.height()),
                   Qt.AlignmentFlag.AlignVCenter | Qt.AlignmentFlag.AlignLeft, self.text())


class ArrowIcon(QWidget):
    def __init__(self, color, down=True, parent=None):
        super().__init__(parent)
        self.setFixedSize(30, 30)
        self._color = QColor(color)
        self._down = down

    def paintEvent(self, event):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(with_alpha(self._color, 40))
        p.drawEllipse(QRectF(0, 0, 30, 30))
        pen = QPen(self._color, 2.2)
        pen.setCapStyle(Qt.PenCapStyle.RoundCap)
        pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
        p.setPen(pen)
        if self._down:
            p.drawLine(QPointF(15, 8.5), QPointF(15, 21))
            p.drawPolyline([QPointF(10, 16.5), QPointF(15, 21.5), QPointF(20, 16.5)])
        else:
            p.drawLine(QPointF(15, 9), QPointF(15, 21.5))
            p.drawPolyline([QPointF(10, 13.5), QPointF(15, 8.5), QPointF(20, 13.5)])


class SpeedCard(QFrame):
    def __init__(self, title, color, down=True, parent=None):
        super().__init__(parent)
        self.setObjectName("card")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(14, 12, 14, 12)
        lay.setSpacing(6)

        top = QHBoxLayout()
        top.setSpacing(8)
        top.addWidget(ArrowIcon(color, down))
        top.addWidget(make_label(title, 12, QFont.Weight.DemiBold, MUTED))
        top.addStretch()
        lay.addLayout(top)

        row = QHBoxLayout()
        row.setSpacing(5)
        self.value = make_label("0", 26, QFont.Weight.Bold)
        self.unit = make_label("B/s", 12, QFont.Weight.DemiBold, MUTED)
        row.addWidget(self.value, alignment=Qt.AlignmentFlag.AlignBottom)
        row.addWidget(self.unit, alignment=Qt.AlignmentFlag.AlignBottom)
        row.addStretch()
        lay.addLayout(row)

        self.total = make_label("Toplam: 0 B", 11, QFont.Weight.Normal, MUTED)
        lay.addWidget(self.total)

    def set_speed(self, bps):
        value, unit = format_speed(bps)
        self.value.setText(value)
        self.unit.setText(unit)

    def set_total(self, n):
        self.total.setText(f"Toplam: {format_bytes(n)}")


class InfoCard(QFrame):
    """Yan yana başlık/değer sütunları (Sunucu, Protokol, VPN IP...)."""

    def __init__(self, fields, parent=None):
        super().__init__(parent)
        self.setObjectName("card")
        lay = QHBoxLayout(self)
        lay.setContentsMargins(6, 12, 6, 12)
        lay.setSpacing(0)
        self.values = {}
        for i, (key, caption) in enumerate(fields):
            if i:
                line = QFrame()
                line.setFixedWidth(1)
                line.setStyleSheet("background-color: rgba(255,255,255,18);")
                lay.addWidget(line)
            col = QVBoxLayout()
            col.setSpacing(4)
            col.addWidget(make_label(caption, 10, QFont.Weight.Bold, MUTED, 1.2,
                                     Qt.AlignmentFlag.AlignCenter))
            value = make_label("—", 13, QFont.Weight.DemiBold, TEXT, 0,
                               Qt.AlignmentFlag.AlignCenter)
            col.addWidget(value)
            lay.addLayout(col, 1)
            self.values[key] = value

    def set_value(self, key, text):
        self.values[key].setText(text or "—")


class SpeedGraph(QWidget):
    """Son N saniyenin indirme/yükleme hızını gösteren yumuşak çizgi grafik."""

    def __init__(self, points=60, parent=None):
        super().__init__(parent)
        self.setMinimumHeight(78)
        self._n = points
        self.down = deque([0.0] * points, maxlen=points)
        self.up = deque([0.0] * points, maxlen=points)

    def push(self, down, up):
        self.down.append(float(down))
        self.up.append(float(up))
        self.update()

    def clear(self):
        self.down.extend([0.0] * self._n)
        self.up.extend([0.0] * self._n)
        self.update()

    def peak(self):
        return max(max(self.down), max(self.up))

    def _path(self, values, rect, top):
        step = rect.width() / (self._n - 1)
        pts = [QPointF(rect.left() + i * step, rect.bottom() - (v / top) * rect.height())
               for i, v in enumerate(values)]
        path = QPainterPath(pts[0])
        for a, b in zip(pts, pts[1:]):
            mx = (a.x() + b.x()) / 2
            path.cubicTo(QPointF(mx, a.y()), QPointF(mx, b.y()), b)
        return path, pts[-1]

    def paintEvent(self, event):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        rect = QRectF(self.rect()).adjusted(1, 6, -6, -2)
        top = max(self.peak(), 32 * 1024) * 1.2

        grid = QPen(QColor(255, 255, 255, 16), 1, Qt.PenStyle.DashLine)
        p.setPen(grid)
        for i in range(1, 4):
            y = rect.top() + rect.height() * i / 4
            p.drawLine(QPointF(rect.left(), y), QPointF(rect.right(), y))

        for values, color in ((self.up, PURPLE), (self.down, CYAN)):
            path, last = self._path(values, rect, top)
            fill = QPainterPath(path)
            fill.lineTo(rect.right(), rect.bottom())
            fill.lineTo(rect.left(), rect.bottom())
            fill.closeSubpath()
            grad = QLinearGradient(0, rect.top(), 0, rect.bottom())
            grad.setColorAt(0, with_alpha(color, 85))
            grad.setColorAt(1, with_alpha(color, 0))
            p.fillPath(fill, grad)
            pen = QPen(color, 2)
            pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
            p.strokePath(path, pen)
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(color)
            p.drawEllipse(last, 3.2, 3.2)


class Legend(QWidget):
    def __init__(self, items, parent=None):
        super().__init__(parent)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(12)
        for text, color in items:
            lbl = make_label("", 11, QFont.Weight.DemiBold, MUTED)
            lbl.setText(f'<span style="color:{color.name()}">●</span> {text}')
            lay.addWidget(lbl)
