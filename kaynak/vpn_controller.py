import os
import re
import shutil
import socket
import subprocess
import tempfile
import time

import psutil
from PyQt6.QtCore import QObject, QTimer, pyqtSignal
from PyQt6.QtNetwork import QAbstractSocket, QTcpSocket

# OpenVPN yönetim arayüzünün bildirdiği aşamalar -> (halka ilerlemesi, açıklama)
STAGES = {
    "CONNECTING": (0.10, "Sunucuya bağlanılıyor"),
    "RESOLVE": (0.12, "Sunucu adresi çözümleniyor"),
    "TCP_CONNECT": (0.22, "TCP bağlantısı kuruluyor"),
    "WAIT": (0.32, "Sunucu yanıtı bekleniyor"),
    "AUTH": (0.48, "Kimlik doğrulanıyor"),
    "AUTH_PENDING": (0.52, "Kimlik doğrulama bekleniyor"),
    "GET_CONFIG": (0.64, "Yapılandırma alınıyor"),
    "ASSIGN_IP": (0.78, "IP adresi atanıyor"),
    "ADD_ROUTES": (0.90, "Yönlendirmeler ekleniyor"),
    "CONNECTED": (1.00, "Bağlandı"),
    "RECONNECTING": (0.10, "Yeniden bağlanılıyor"),
    "EXITING": (0.00, "Bağlantı kapatılıyor"),
}

VPN_NIC_KEYWORDS = ("openvpn", "wintun", "tap", "dco", "tun")

# RECONNECTING aşamasında OpenVPN'in bildirdiği sebep -> kullanıcıya gösterilecek açıklama
RECONNECT_REASONS = {
    "dco-connect-error": "sunucu bağlantıyı reddetti",
    "connection-reset": "bağlantı sıfırlandı",
    "tls-error": "TLS el sıkışması başarısız",
    "ping-restart": "sunucudan yanıt gelmiyor",
    "auth-failure": "kimlik doğrulama başarısız",
    "server-pushed-connection-reset": "sunucu bağlantıyı sıfırladı",
}


def explain_error(msg):
    """OpenVPN log satırını anlaşılır Türkçe açıklamaya çevirir."""
    low = msg.lower()
    if "refused" in low:
        return ("Sunucu bağlantıyı reddetti — sunucu kapalı olabilir ya da "
                "yapılandırmadaki port/protokol (TCP/UDP) yanlış")
    if "tls key negotiation failed" in low or "tls handshake failed" in low:
        return "Sunucu yanıt vermiyor (TLS el sıkışması zaman aşımına uğradı)"
    if "auth_failed" in low or "auth-failure" in low:
        return "Kimlik doğrulama başarısız (sertifika geçersiz veya iptal edilmiş olabilir)"
    if "access is denied" in low or "erişim engellendi" in low:
        return "Erişim engellendi — uygulamayı yönetici olarak çalıştırın"
    return msg


def find_openvpn():
    for path in (r"C:\Program Files\OpenVPN\bin\openvpn.exe",
                 r"C:\Program Files (x86)\OpenVPN\bin\openvpn.exe"):
        if os.path.exists(path):
            return path
    return shutil.which("openvpn")


def read_server_info(config_path):
    """ovpn dosyasından sunucu adresi, port ve protokolü okur."""
    info = {"host": "", "port": "1194", "proto": "udp"}
    try:
        with open(config_path, encoding="utf-8", errors="replace") as f:
            for line in f:
                parts = line.split()
                if not parts or parts[0].startswith(("#", ";")):
                    continue
                if parts[0] == "remote" and len(parts) >= 2 and not info["host"]:
                    info["host"] = parts[1]
                    if len(parts) >= 3:
                        info["port"] = parts[2]
                    if len(parts) >= 4:
                        info["proto"] = parts[3]
                elif parts[0] == "proto" and len(parts) >= 2:
                    info["proto"] = parts[1]
    except OSError:
        pass
    info["proto"] = info["proto"].replace("-client", "").upper()
    return info


def _free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class VpnController(QObject):
    """OpenVPN sürecini başlatır ve yönetim arayüzü üzerinden izler.

    Hız ölçümü için yalnızca VPN tünelinin trafiği kullanılır: önce OpenVPN'in
    kendi bayt sayaçları (bytecount), bunlar gelmezse VPN ağ adaptörünün
    sayaçları. Böylece Wi-Fi + VPN adaptörü trafiği iki kez sayılmaz.
    """

    stage = pyqtSignal(str, str, str)   # aşama adı, açıklama (OK/ERROR), VPN IP
    traffic = pyqtSignal(int, int)      # oturum içi toplam alınan, gönderilen bayt
    traffic_reset = pyqtSignal()        # sayaç kaynağı değişti, ölçümü sıfırla
    paused = pyqtSignal()               # duraklatıldı: tünel kapandı, süreç bekliyor
    finished = pyqtSignal(str)          # "" = kullanıcı kesti, aksi halde hata mesajı

    CONNECT_TIMEOUT = 60

    def __init__(self, parent=None):
        super().__init__(parent)
        self.proc = None
        self.log_path = os.path.join(tempfile.gettempdir(), "vpn_arayuz_openvpn.log")

        self._sock = QTcpSocket(self)
        self._sock.connected.connect(self._on_sock_connected)
        self._sock.readyRead.connect(self._on_ready_read)

        self._watch = QTimer(self)
        self._watch.setInterval(400)
        self._watch.timeout.connect(self._check)

        self._nic_timer = QTimer(self)
        self._nic_timer.setInterval(1000)
        self._nic_timer.timeout.connect(self._poll_nic)

        self._reset()

    def _reset(self):
        self._buf = b""
        self._port = 0
        self._started = 0.0
        self._connected = False
        self._connected_at = 0.0
        self._user_stop = False
        self._paused = False
        self._fatal = ""
        self._last_bytecount = 0.0
        self._nic = None
        self._nic_base = (0, 0)
        self.local_ip = ""

    def is_running(self):
        return self.proc is not None and self.proc.poll() is None

    # --- Başlat / Durdur ---
    def start(self, openvpn_path, config_path):
        self._reset()
        self._port = _free_port()
        # persist-tun açıkken duraklatma tüneli açık bırakır ve tüm trafik boşa düşer.
        # Kapalıyken duraklatınca tünel kapanır (normal internet), devam edince aynı
        # OpenVPN süreci yeni tünel açar. Ayar, config diske yazılmadan stdin'den
        # verilerek kaldırılır; my.ovpn değişmez.
        try:
            with open(config_path, encoding="utf-8", errors="replace") as f:
                config = "".join(line for line in f
                                 if not re.match(r"\s*persist-tun\b", line))
        except OSError as e:
            QTimer.singleShot(0, lambda: self.finished.emit(f"Yapılandırma okunamadı: {e}"))
            return
        args = [openvpn_path, "--config", "stdin",
                "--management", "127.0.0.1", str(self._port),
                "--log", self.log_path]
        try:
            self.proc = subprocess.Popen(
                args,
                cwd=os.path.dirname(config_path) or None,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                stdin=subprocess.PIPE,
                creationflags=subprocess.CREATE_NO_WINDOW,
            )
            self.proc.stdin.write(config.encode("utf-8"))
            self.proc.stdin.close()
        except OSError as e:
            self._kill(self.proc)
            self.proc = None
            QTimer.singleShot(0, lambda: self.finished.emit(f"OpenVPN başlatılamadı: {e}"))
            return
        self._started = time.monotonic()
        self._watch.start()

    def stop(self):
        """Kullanıcı isteğiyle bağlantıyı keser (önce düzgün kapatma, sonra zorla)."""
        if not self.is_running():
            self._finish("")
            return
        self._user_stop = True
        self._terminate()

    def pause(self):
        """Duraklat: tünel kapanır, trafik normal internetten gider; süreç ve oturum açık kalır."""
        if not self.is_running() or self._sock.state() != QAbstractSocket.SocketState.ConnectedState:
            return False
        self._paused = True
        self._nic_timer.stop()
        # hold açıkken SIGUSR1 sonrası yeniden bağlanmadan önce "hold release" beklenir
        self._sock.write(b"hold on\nsignal SIGUSR1\n")
        return True

    def resume(self):
        """Devam et: aynı OpenVPN süreci tüneli yeniden açar (sıfırdan başlamaz)."""
        if not self.is_running():
            return
        self._paused = False
        self._started = time.monotonic()  # zaman aşımı sayacı baştan başlar
        self._nic = None                  # yeni tünelin adaptörü yeniden aranır
        self.traffic_reset.emit()
        self._sock.write(b"hold off\nhold release\n")

    def stop_blocking(self, timeout=4):
        """Uygulama kapanırken: bağlantıyı kes ve sürecin bitmesini bekle."""
        if not self.is_running():
            return
        self._user_stop = True
        if self._sock.state() == QAbstractSocket.SocketState.ConnectedState:
            self._sock.write(b"signal SIGTERM\n")
            self._sock.waitForBytesWritten(500)
        else:
            self.proc.kill()
        try:
            self.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self.proc.kill()

    def _terminate(self):
        # SIGTERM ile OpenVPN eklediği rotaları temizleyerek kapanır.
        proc = self.proc
        if self._sock.state() == QAbstractSocket.SocketState.ConnectedState:
            self._sock.write(b"signal SIGTERM\n")
            self._sock.flush()
            QTimer.singleShot(4000, lambda: self._kill(proc))
        else:
            self._kill(proc)

    @staticmethod
    def _kill(proc):
        if proc is not None and proc.poll() is None:
            try:
                proc.kill()
            except OSError:
                pass

    def _abort(self, message):
        self._fatal = message
        self._terminate()

    def _finish(self, reason):
        self._watch.stop()
        self._nic_timer.stop()
        self._sock.abort()
        self._buf = b""
        self.proc = None
        self.finished.emit(reason)

    # --- İzleme ---
    def _check(self):
        if self.proc is None:
            self._watch.stop()
            return
        if self.proc.poll() is not None:
            self._finish("" if self._user_stop else self._failure_reason())
            return

        if self._sock.state() == QAbstractSocket.SocketState.UnconnectedState:
            self._sock.connectToHost("127.0.0.1", self._port)

        now = time.monotonic()
        if (not self._connected and not self._user_stop and not self._fatal
                and not self._paused and now - self._started > self.CONNECT_TIMEOUT):
            reason = self._last_log_error()
            self._abort("Bağlantı zaman aşımına uğradı: "
                        + (explain_error(reason) if reason else "sunucu yanıt vermiyor"))

        # OpenVPN bayt sayacı göndermiyorsa VPN adaptörünün sayaçlarına geç
        if (self._connected and self._nic is None
                and now - max(self._last_bytecount, self._connected_at) > 3):
            self._start_nic_fallback()

    def _failure_reason(self):
        if self._fatal:
            return self._fatal
        reason = self._last_log_error()
        return explain_error(reason) if reason else "OpenVPN beklenmedik şekilde kapandı"

    def _last_log_error(self):
        try:
            with open(self.log_path, encoding="utf-8", errors="replace") as f:
                lines = f.readlines()[-60:]
        except OSError:
            return ""
        for line in reversed(lines):
            low = line.lower()
            if any(k in low for k in ("error", "fatal", "cannot", "failed", "denied", "refused")):
                msg = re.sub(r"^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\s+", "", line.strip())
                return msg[:160]
        return ""

    # --- Yönetim arayüzü ---
    def _on_sock_connected(self):
        self._sock.write(b"state on\nstate\nbytecount 1\n")

    def _on_ready_read(self):
        self._buf += bytes(self._sock.readAll())
        *lines, self._buf = self._buf.split(b"\n")
        for raw in lines:
            line = raw.decode("utf-8", "replace").strip()
            if line.startswith(">STATE:"):
                self._handle_state(line[7:])
            elif line.startswith(">BYTECOUNT:"):
                try:
                    rx, tx = (int(x) for x in line[11:].split(",")[:2])
                except ValueError:
                    continue
                self._last_bytecount = time.monotonic()
                if self._nic is None:
                    self.traffic.emit(rx, tx)
            elif line.startswith(">FATAL:"):
                self._fatal = line[7:].strip()
            elif line.startswith(">HOLD:"):
                if self._paused:
                    self.paused.emit()
                else:
                    self._sock.write(b"hold release\n")
            elif line.startswith(">PASSWORD:"):
                self._abort("Sunucu kullanıcı adı/şifre istiyor (auth-user-pass)")
            elif re.match(r"^\d+,[A-Z_]+,", line):
                # "state" komutunun anlık cevabı
                self._handle_state(line)

    def _handle_state(self, payload):
        fields = payload.split(",")
        if len(fields) < 2:
            return
        name = fields[1]
        desc = fields[2] if len(fields) > 2 else ""
        if len(fields) > 3 and fields[3]:
            self.local_ip = fields[3]
        if name == "CONNECTED":
            self._connected = True
            self._connected_at = time.monotonic()
        elif name == "RECONNECTING":
            # Yalnızca kurulu bir bağlantı koptuysa süre baştan başlar; ilk bağlanma
            # denemelerinde sıfırlanırsa zaman aşımı hiç dolmaz.
            if self._connected:
                self._started = time.monotonic()
            self._connected = False
        self.stage.emit(name, desc, self.local_ip)

    # --- Yedek hız kaynağı: VPN ağ adaptörü ---
    def _find_vpn_nic(self):
        addrs = psutil.net_if_addrs()
        if self.local_ip:
            for name, entries in addrs.items():
                if any(a.address == self.local_ip for a in entries):
                    return name
        stats = psutil.net_if_stats()
        for name in addrs:
            st = stats.get(name)
            if st and st.isup and any(k in name.lower() for k in VPN_NIC_KEYWORDS):
                return name
        return None

    def _start_nic_fallback(self):
        nic = self._find_vpn_nic()
        counters = psutil.net_io_counters(pernic=True).get(nic) if nic else None
        if counters is None:
            return
        self._nic = nic
        self._nic_base = (counters.bytes_recv, counters.bytes_sent)
        self.traffic_reset.emit()
        self._nic_timer.start()

    def _poll_nic(self):
        counters = psutil.net_io_counters(pernic=True).get(self._nic)
        if counters is not None:
            self.traffic.emit(counters.bytes_recv - self._nic_base[0],
                              counters.bytes_sent - self._nic_base[1])
