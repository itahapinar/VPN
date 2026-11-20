import ctypes
import os
import sys
import time

from PyQt6.QtCore import Qt, QTimer
from PyQt6.QtGui import QColor, QFont, QIcon
from PyQt6.QtWidgets import QApplication, QFrame, QHBoxLayout, QVBoxLayout, QWidget

from vpn_controller import (RECONNECT_REASONS, STAGES, VpnController, find_openvpn,
                            read_server_info)
from widgets import (AMBER, APP_QSS, CYAN, GREEN, MUTED, PURPLE, RED, TEXT, Background,
                     InfoCard, Legend, PillButton, PowerButton, SpeedCard, SpeedGraph,
                     StatusChip, TitleBar, format_speed, make_label)


def resource_path(relative_path):
    try:
        base_path = sys._MEIPASS
    except Exception:
        if getattr(sys, 'frozen', False):
            base_path = os.path.dirname(sys.executable)
        else:
            base_path = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base_path, relative_path)


def find_config():
    # my.ovpn konfigürasyon dosyası yolu (gömülü, exe yanı, masaüstü kontrolü)
    config_path = resource_path("my.ovpn")
    if os.path.exists(config_path):
        return config_path
    exe_dir = os.path.dirname(sys.executable) if getattr(sys, 'frozen', False) else os.path.dirname(os.path.abspath(__file__))
    candidate = os.path.join(exe_dir, "my.ovpn")
    if os.path.exists(candidate):
        return candidate
    return os.path.join(os.path.expanduser("~"), "Desktop", "VPN", "my.ovpn")


def is_admin():
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def apply_rounded_corners(widget):
    # Windows 11: çerçevesiz pencereye yuvarlak köşe (Windows 10'da sessizce yok sayılır)
    try:
        pref = ctypes.c_int(2)  # DWMWCP_ROUND
        ctypes.windll.dwmapi.DwmSetWindowAttribute(
            ctypes.c_void_p(int(widget.winId())), 33, ctypes.byref(pref), ctypes.sizeof(pref))
    except Exception:
        pass


class main(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("VPN")
        self.setWindowFlags(Qt.WindowType.Window | Qt.WindowType.FramelessWindowHint
                            | Qt.WindowType.WindowMinimizeButtonHint)
        self.setStyleSheet(APP_QSS)

        icon_file = resource_path("app_icon.ico")
        if os.path.exists(icon_file):
            self.setWindowIcon(QIcon(icon_file))

        self.openvpn_path = find_openvpn()
        self.config_path = find_config()
        self.server = read_server_info(self.config_path)

        self.vpn = VpnController(self)
        self.vpn.stage.connect(self.on_stage)
        self.vpn.traffic.connect(self.on_traffic)
        self.vpn.traffic_reset.connect(self.reset_sample)
        self.vpn.paused.connect(self.on_paused)
        self.vpn.finished.connect(self.on_finished)

        self.state = "off"
        self.connected_since = None
        self.reset_sample()

        self._build_ui()
        self.apply_state("off")
        self.footer_default()

        # Saniyelik arayüz güncellemesi (süre, grafik, bayat hız kontrolü)
        self.ui_timer = QTimer(self)
        self.ui_timer.timeout.connect(self.tick)
        self.ui_timer.start(1000)

    # ------------------------------------------------------------------ UI
    def _build_ui(self):
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        self.bg = Background()
        root.addWidget(self.bg)

        outer = QVBoxLayout(self.bg)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.setSpacing(0)
        logo = resource_path("logo.png")
        outer.addWidget(TitleBar("VPN", "Güvenli Tünel", logo if os.path.exists(logo) else None))

        body = QVBoxLayout()
        body.setContentsMargins(22, 8, 22, 16)
        body.setSpacing(0)
        outer.addLayout(body)

        # Durum
        self.chip = StatusChip()
        chip_row = QHBoxLayout()
        chip_row.addStretch()
        chip_row.addWidget(self.chip)
        chip_row.addStretch()
        body.addLayout(chip_row)
        body.addSpacing(8)
        self.lbl_detail = make_label("", 12, QFont.Weight.Normal, MUTED, 0,
                                     Qt.AlignmentFlag.AlignCenter)
        self.lbl_detail.setWordWrap(True)
        body.addWidget(self.lbl_detail)

        # Güç butonu
        self.btn = PowerButton()
        self.btn.clicked.connect(self.toggle_connection)
        body.addWidget(self.btn, alignment=Qt.AlignmentFlag.AlignHCenter)

        # Duraklat / devam et (yer hep ayrılı; düğme belirince sayfa kaymasın)
        pill_box = QWidget()
        pill_box.setFixedHeight(40)
        pill_row = QHBoxLayout(pill_box)
        pill_row.setContentsMargins(0, 0, 0, 0)
        self.btn_pause = PillButton()
        self.btn_pause.clicked.connect(self.toggle_pause)
        pill_row.addWidget(self.btn_pause, alignment=Qt.AlignmentFlag.AlignCenter)
        body.addWidget(pill_box)

        # Oturum süresi
        self.lbl_time = make_label("00:00:00", 30, QFont.Weight.Light, TEXT, 1.5,
                                   Qt.AlignmentFlag.AlignCenter)
        body.addWidget(self.lbl_time)
        body.addWidget(make_label("OTURUM SÜRESİ", 10, QFont.Weight.Bold, MUTED, 2,
                                  Qt.AlignmentFlag.AlignCenter))
        body.addSpacing(12)

        # Sunucu bilgileri
        self.info = InfoCard([("server", "SUNUCU"), ("proto", "PROTOKOL"), ("ip", "VPN IP")])
        self.info.set_value("server", self.server["host"])
        self.info.set_value("proto", f"{self.server['proto']} · {self.server['port']}")
        body.addWidget(self.info)
        body.addSpacing(12)

        # Hız kartları
        speeds = QHBoxLayout()
        speeds.setSpacing(12)
        self.card_down = SpeedCard("İndirme", CYAN, down=True)
        self.card_up = SpeedCard("Yükleme", PURPLE, down=False)
        speeds.addWidget(self.card_down)
        speeds.addWidget(self.card_up)
        body.addLayout(speeds)
        body.addSpacing(12)

        # Canlı grafik
        graph_card = QFrame()
        graph_card.setObjectName("card")
        g = QVBoxLayout(graph_card)
        g.setContentsMargins(14, 10, 12, 10)
        g.setSpacing(4)
        head = QHBoxLayout()
        head.addWidget(make_label("CANLI TRAFİK", 10, QFont.Weight.Bold, MUTED, 1.2))
        head.addStretch()
        head.addWidget(Legend([("İndirme", CYAN), ("Yükleme", PURPLE)]))
        g.addLayout(head)
        self.graph = SpeedGraph(60)
        self.graph.setMinimumHeight(62)
        g.addWidget(self.graph)
        self.lbl_peak = make_label("Son 60 sn · tepe 0 B/s", 10, QFont.Weight.Normal, MUTED)
        g.addWidget(self.lbl_peak)
        body.addWidget(graph_card)
        body.addSpacing(10)

        self.lbl_footer = make_label("", 10, QFont.Weight.Normal, MUTED, 0,
                                     Qt.AlignmentFlag.AlignCenter)
        self.lbl_footer.setWordWrap(True)
        body.addWidget(self.lbl_footer)

        self.setFixedWidth(392)
        self.setFixedHeight(self.sizeHint().height())

    def showEvent(self, event):
        super().showEvent(event)
        apply_rounded_corners(self)

    def footer_default(self):
        if not self.openvpn_path:
            self.set_footer("OpenVPN bulunamadı — lütfen OpenVPN'i kurun", RED)
        elif not os.path.exists(self.config_path):
            self.set_footer(f"Yapılandırma dosyası bulunamadı: {self.config_path}", RED)
        elif not is_admin():
            self.set_footer("⚠ Yönetici olarak çalışmıyor — yönlendirmeler eklenemeyebilir", AMBER)
        else:
            self.set_footer(f"OpenVPN · {os.path.basename(self.config_path)}", None)

    def set_footer(self, text, color):
        self.lbl_footer.setText(text)
        self.lbl_footer.setStyleSheet(f"color: {color.name() if color else MUTED};")

    def apply_state(self, state, detail=None):
        self.state = state
        self.lbl_time.setStyleSheet(f"color: {TEXT if state == 'on' else MUTED};")
        if state == "on":
            self.btn_pause.set_look("Duraklat", RED, "pause")
        elif state == "paused":
            self.btn_pause.set_look("Devam et", GREEN, "play")
        self.btn_pause.setVisible(state in ("on", "paused", "pausing"))
        self.btn_pause.setEnabled(state in ("on", "paused"))
        if state in ("pausing", "paused"):
            self.info.set_value("ip", "")
            self.card_down.set_speed(0)
            self.card_up.set_speed(0)

        if state == "off":
            self.chip.set_status("BAĞLI DEĞİL", RED)
            self.lbl_detail.setText(detail or "Bağlantınız şu an korunmuyor")
            self.btn.set_mode("off")
            self.btn.setEnabled(True)
            self.bg.set_accent(PURPLE)
            self.info.set_value("ip", "")
            self.connected_since = None
            self.card_down.set_speed(0)
            self.card_up.set_speed(0)
        elif state == "connecting":
            self.chip.set_status("BAĞLANIYOR", AMBER)
            self.lbl_detail.setText(detail or "Sunucuya bağlanılıyor…")
            self.btn.set_mode("connecting")
            self.btn.setEnabled(True)
            self.bg.set_accent(AMBER)
        elif state == "disconnecting":
            self.chip.set_status("KESİLİYOR", AMBER)
            self.lbl_detail.setText("Bağlantı güvenli şekilde kapatılıyor…")
            self.btn.setEnabled(False)
        elif state == "on":
            self.chip.set_status("KORUNUYOR", GREEN)
            self.lbl_detail.setText(detail or "Trafiğiniz şifreli tünelden geçiyor")
            self.btn.set_mode("on")
            self.btn.setEnabled(True)
            self.bg.set_accent(GREEN)
        elif state == "pausing":
            self.chip.set_status("DURAKLATILIYOR", AMBER)
            self.lbl_detail.setText("Tünel kapatılıyor…")
            self.btn.set_mode("paused")
            self.bg.set_accent(AMBER)
        elif state == "paused":
            self.chip.set_status("DURAKLATILDI", AMBER)
            self.lbl_detail.setText("Normal internet bağlantınız kullanılıyor")
            self.btn.set_mode("paused")
            self.btn.setEnabled(True)
            self.bg.set_accent(AMBER)

    # ------------------------------------------------------------- Bağlantı
    def toggle_connection(self):
        if self.state == "off":
            self.connect_vpn()
        elif self.state in ("connecting", "on", "pausing", "paused"):
            self.disconnect_vpn()

    def toggle_pause(self):
        if self.state == "on":
            if self.vpn.pause():
                self.apply_state("pausing")
        elif self.state == "paused":
            # Aynı oturum devam eder: süre sıfırlanmaz, tünel yeniden açılır
            self.apply_state("connecting", "Devam ediliyor…")
            self.btn.set_progress(0.04)
            self.vpn.resume()

    def on_paused(self):
        if self.state in ("pausing", "paused"):
            self.apply_state("paused")

    def connect_vpn(self):
        if not self.openvpn_path:
            self.apply_state("off", "OpenVPN bulunamadı")
            return
        if not os.path.exists(self.config_path):
            self.apply_state("off", "my.ovpn yapılandırma dosyası bulunamadı")
            return
        self.footer_default()
        self.reset_sample()
        self.total_rx = self.total_tx = 0
        self.card_down.set_total(0)
        self.card_up.set_total(0)
        self.graph.clear()
        self.lbl_time.setText("00:00:00")
        self.apply_state("connecting")
        self.btn.set_progress(0.04)
        self.vpn.start(self.openvpn_path, self.config_path)

    def disconnect_vpn(self):
        self.apply_state("disconnecting")
        self.vpn.stop()

    def on_stage(self, name, desc, local_ip):
        progress, text = STAGES.get(name, (None, name.title()))
        if self.state in ("pausing", "paused"):
            # Duraklatma kaynaklı yeniden başlatma aşamaları; bekleme durumu bozulmasın
            return
        if local_ip:
            self.info.set_value("ip", local_ip)
        if name == "CONNECTED":
            if self.state != "on":
                if self.connected_since is None:  # devam edince oturum süresi sürer
                    self.connected_since = time.monotonic()
                self.reset_sample()
            self.apply_state("on")
            self.btn.set_progress(1.0)
            if desc == "ERROR":
                self.set_footer("⚠ Bağlandı ancak bazı yönlendirmeler eklenemedi — "
                                "uygulamayı yönetici olarak çalıştırın", AMBER)
        elif self.state in ("connecting", "on") and name != "EXITING":
            # SIGUSR1 gibi sinyaller duraklatma/ağ değişimi kaynaklı; kullanıcıya anlamsız
            if name == "RECONNECTING" and desc and not desc.startswith("SIG"):
                text += f" ({RECONNECT_REASONS.get(desc, desc)})"
            self.apply_state("connecting", f"{text}…")
            if progress is not None:
                self.btn.set_progress(progress)

    def on_finished(self, error):
        if error:
            self.apply_state("off", "Bağlantı kurulamadı")
            self.set_footer(error, RED)
        else:
            self.apply_state("off")
        self.graph.push(0, 0)

    # ----------------------------------------------------------------- Hız
    def reset_sample(self):
        self._sample = None
        self._last_traffic = 0.0
        self.down_bps = self.up_bps = 0.0
        if not hasattr(self, "total_rx"):
            self.total_rx = self.total_tx = 0

    def on_traffic(self, rx, tx):
        now = time.monotonic()
        if self.state != "on":
            self._sample = (now, rx, tx)
            return
        if self._sample is None or rx < self._sample[1] or tx < self._sample[2]:
            # İlk örnek ya da sayaç sıfırlandı: yalnızca referans al
            self._sample = (now, rx, tx)
            return
        t0, rx0, tx0 = self._sample
        dt = now - t0
        if dt < 0.25:
            return
        drx, dtx = rx - rx0, tx - tx0
        self._sample = (now, rx, tx)
        self._last_traffic = now
        # Gerçek geçen süreye böl (zamanlayıcı tam 1 sn çalışmayabilir)
        self.down_bps = drx / dt
        self.up_bps = dtx / dt
        self.total_rx += drx
        self.total_tx += dtx
        self.card_down.set_speed(self.down_bps)
        self.card_up.set_speed(self.up_bps)
        self.card_down.set_total(self.total_rx)
        self.card_up.set_total(self.total_tx)

    def tick(self):
        # Oturum açıkken (bağlı, duraklatılmış ya da devam ediliyor) süre işler
        if self.connected_since is None or self.state in ("off", "disconnecting"):
            return
        now = time.monotonic()
        secs = int(now - self.connected_since)
        self.lbl_time.setText(f"{secs // 3600:02d}:{secs % 3600 // 60:02d}:{secs % 60:02d}")
        if self.state != "on" or now - self._last_traffic > 2.5:
            # Duraklatıldıysa ya da bir süredir veri gelmediyse trafik yok demektir
            self.down_bps = self.up_bps = 0.0
            self.card_down.set_speed(0)
            self.card_up.set_speed(0)
        self.graph.push(self.down_bps, self.up_bps)
        value, unit = format_speed(self.graph.peak())
        self.lbl_peak.setText(f"Son 60 sn · tepe {value} {unit}")

    def closeEvent(self, event):
        # Uygulama kapanırken arka planda açık OpenVPN bırakma
        self.vpn.stop_blocking()
        super().closeEvent(event)


if __name__ == "__main__":
    try:
        # 1. Görev Çubuğu İkonu Ayarı
        try:
            myappid = 'taha.vpn.arayuz.2'
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(myappid)
        except Exception:
            pass

        # 2. Uygulamayı Başlat
        app = QApplication(sys.argv)
        app.setFont(QFont("Segoe UI", 10))

        # 3. İkon Yükleme
        icon_path = resource_path("app_icon.ico")
        app.setWindowIcon(QIcon(icon_path))

        # 4. Pencereyi Göster
        window = main()
        window.show()

        sys.exit(app.exec())

    except Exception as e:
        # Eğer bir hata olursa bunu "hata_logu.txt" dosyasına yaz
        with open("hata_logu.txt", "w") as f:
            f.write(f"Hata oluştu: {str(e)}\n")
            import traceback
            traceback.print_exc(file=f)
