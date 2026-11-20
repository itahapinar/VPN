# VPN

Kendi OpenVPN sunucum için yazdığım iki istemci:

- **Windows:** Python ve PyQt6 ile yazılmış masaüstü uygulaması
- **Android:** Kotlin ve Jetpack Compose ile yazılmış, OpenVPN motoru içine gömülü mobil uygulama

İki uygulama aynı tasarım dilini kullanır: koyu mor tema, bağlantı aşamalarına göre dolan güç butonu, canlı hız grafiği ve Türkçe hata mesajları. Bilgisayar ve telefon ayrı sertifikalarla bağlandığı için ikisi aynı anda bağlı kalabilir.

> 🔑 **Bu depo VPN anahtarı içermez.** Kendi OpenVPN sunucunun verdiği `.ovpn` dosyalarını `kaynak/my.ovpn` (Windows) ve `kaynak/mobile.ovpn` (Android) olarak koy; biçim için `kaynak/ornek.example.ovpn` dosyasına bak. Derlenen `main.exe` ve APK bu dosyaları içine gömer, yani anahtarı taşır. `.ovpn`, `.exe` ve `.apk` dosyaları `.gitignore`'da; onları paylaşma ve depoya ekleme.

## İçindekiler

- [Depo Yapısı](#depo-yapısı)
- [Hızlı Kurulum](#hızlı-kurulum)
- [Windows Uygulaması](#windows-uygulaması)
- [Android Uygulaması](#android-uygulaması)
- [Bilgisayar ve Telefon Aynı Anda](#bilgisayar-ve-telefon-aynı-anda)
- [Sorun Giderme](#sorun-giderme)
- [Lisans](#lisans)

## Depo Yapısı

```
├── kurulum.bat           # Windows kurulumu: OpenVPN + masaüstü kısayolu
├── kurulum.ps1           # kurulum.bat'ın çalıştırdığı betik
├── kaynak/               # Windows uygulamasının kaynak kodu
│   ├── main.py           # Ana pencere, arayüz düzeni ve durum yönetimi
│   ├── widgets.py        # Özel çizilen bileşenler (güç butonu, grafik, kartlar)
│   ├── vpn_controller.py # OpenVPN süreci ve yönetim arayüzü bağlantısı
│   ├── main.spec         # PyInstaller derleme ayarları
│   ├── logo.png          # Başlık logosu
│   ├── app_icon.ico      # Uygulama simgesi (16–256 px)
│   ├── simge/            # Simgeyi üreten betik
│   ├── ornek.example.ovpn # Örnek yapılandırma (anahtarsız)
│   ├── my.ovpn           # (sen eklersin) Bilgisayarın istemci yapılandırması
│   └── mobile.ovpn       # (sen eklersin) Telefonun istemci yapılandırması
└── android/              # Android uygulamasının kaynak kodu
    ├── app/              # Arayüz, widget, hızlı ayarlar kutucuğu
    ├── vpnmotor/         # ics-openvpn'i kütüphane olarak saran modül
    └── ics-openvpn/      # OpenVPN for Android kaynağı (git'te yok, ayrıca indirilir)
```

## Hızlı Kurulum

Önce kendi `.ovpn` dosyalarını `kaynak/` klasörüne koy (bkz. yukarıdaki not).

**Windows:** `kaynak` klasöründe exe'yi derle (bkz. [Windows Uygulaması](#windows-uygulaması)), sonra `kurulum.bat`'a çift tıkla. Betik yönetici izni ister, OpenVPN kurulu değilse kurar ve masaüstüne **VPN** kısayolu koyar. Uygulamayı bu kısayoldan açın.

**Android:** APK'yı derle (bkz. [Android Uygulaması](#android-uygulaması)), telefona gönderip aç. "Bilinmeyen kaynaklardan yükleme" uyarısı çıkarsa izin verin. İlk bağlantıda Android'in VPN iznini onaylayın.

Aşağıdaki bölümler kaynak koddan derlemeyi anlatır.

---

## Windows Uygulaması

### Özellikler

- **Tek tıkla bağlanma:** Animasyonlu güç butonu. Bağlanırken etrafındaki halka OpenVPN'in gerçek bağlantı aşamalarına göre dolar.
- **Gerçek bağlantı durumu:** "Kimlik doğrulanıyor", "IP adresi atanıyor", "Yönlendirmeler ekleniyor" gibi aşamalar OpenVPN'in yönetim arayüzünden canlı okunur.
- **Doğru hız ölçümü:** Sadece VPN tüneli trafiği ölçülür. Wi-Fi ve VPN adaptörünün aynı trafiği iki kez sayması engellenir.
- **Canlı trafik grafiği:** Son 60 saniyenin indirme ve yükleme hızı.
- **Oturum bilgileri:** Bağlantı süresi, sunucu, protokol, VPN IP adresi ve oturum boyunca aktarılan toplam veri.
- **Anlaşılır hata mesajları:** Bağlantı kurulamazsa sebebi Türkçe açıklanır, örneğin "Sunucu bağlantıyı reddetti — port/protokol (TCP/UDP) yanlış olabilir".
- **Güvenli kapatma:** Bağlantı kesilirken OpenVPN eklediği yönlendirmeleri temizler. Pencere kapatılınca VPN de kapanır.
- **Modern tasarım:** Çerçevesiz koyu tema, Windows 11'de yuvarlak köşeler, yüksek DPI ekranlarda keskin görüntü.

### Gereksinimler

- Windows 10 / 11
- [OpenVPN](https://openvpn.net/community-downloads/) 2.6 veya üzeri (`kurulum.bat` kurar)
- Python 3.10+ (sadece kaynak koddan çalıştırırken)

### Kaynak Koddan Çalıştırma

```powershell
git clone https://github.com/itahapinar/VPN-Dosyalar.git
cd VPN-Dosyalar\kaynak
pip install PyQt6 psutil pyinstaller
```

VPN'in ağ yönlendirmelerini ekleyebilmesi için uygulama **yönetici olarak** çalıştırılmalıdır:

```powershell
Start-Process pythonw -ArgumentList '"main.py"' -Verb RunAs
```

Yönetici olarak çalıştırılmazsa uygulama alt kısımda uyarı gösterir.

Uygulama `my.ovpn` dosyasını şu sırayla arar:

1. Exe'nin içine gömülü dosya
2. Exe'nin (veya `main.py`'nin) bulunduğu klasör

### Exe Olarak Derleme

`kaynak` klasöründe:

```powershell
pyinstaller main.spec
```

Derlenen dosya `kaynak\dist\main.exe` konumunda oluşur. Bunu depo köküne `main.exe` olarak kopyalayın. Exe açılırken Windows otomatik olarak yönetici izni ister (`uac_admin=True`).

> `main.spec`, `my.ovpn` dosyasını exe'nin içine gömer. Bu yüzden oluşan exe VPN anahtarını içerir.

### Nasıl Çalışır?

1. Uygulama OpenVPN'i `--management` parametresiyle, boş bir yerel port üzerinden başlatır.
2. `QTcpSocket` ile bu yönetim arayüzüne bağlanır ve `state on` ile `bytecount 1` komutlarını gönderir.
3. `>STATE:` mesajları bağlantı aşamalarını, `>BYTECOUNT:` mesajları ise tünelden geçen toplam veriyi verir.
4. Hız, ardışık iki sayaç değeri arasındaki farkın **gerçek geçen süreye** bölünmesiyle hesaplanır.
5. OpenVPN bayt sayacı göndermezse yedek olarak sadece VPN ağ adaptörünün sayaçları (`psutil`) kullanılır.

Arayüz Qt Designer kullanılmadan tamamen kodla yazılmıştır. Güç butonu, grafik ve arka plan `QPainter` ile çizilir.

---

## Android Uygulaması

### Özellikler

- **Tek APK:** OpenVPN motoru ([ics-openvpn](https://github.com/schwabe/ics-openvpn), OpenVPN 2.7 + OpenSSL) uygulamanın içine gömülüdür. Telefona ayrıca OpenVPN uygulaması kurmak gerekmez.
- **Masaüstüyle aynı arayüz:** Güç butonu, bağlantı aşamaları, oturum süresi, sunucu ve VPN IP bilgisi, indirme/yükleme kartları ve 60 saniyelik canlı grafik.
- **Ana ekran widget'ı:** Durumu gösterir ("Korunuyor · 10.8.0.x", "Bağlanıyor…", "Bağlı değil"). Sağdaki düğme tek dokunuşla bağlar veya keser.
- **Hızlı ayarlar kutucuğu:** Bildirim panelinde "VPN" kutucuğu. Uygulama kapalıyken de bağlanır ve keser.
- **Bildirim kontrolleri:** VPN bildiriminde **Bağlantıyı kes**, **Duraklat** ve **Devam et** düğmeleri. Bağlantı onay penceresi sormadan kesilir. Bildirim durum değişince kaybolmaz, yerinde güncellenir.
- **Gerçek duraklatma:** Duraklatınca tünel kapanır ve normal internet bağlantısı kullanılır. Devam edince aynı oturum yaklaşık 2 saniyede tüneli geri açar, sıfırdan bağlanmaz. Oturum süresi kaldığı yerden devam eder.
- **Doğru hız ölçümü:** Hız, OpenVPN'in kendi bayt sayaçlarından hesaplanır.
- **Türkçe:** Uygulama, widget, kutucuk ve VPN bildirimi tamamen Türkçedir.

### Gereksinimler

- Android 7.0 (API 24) veya üzeri
- İşlemci: arm64-v8a, armeabi-v7a veya x86_64 (emülatör)

### Kaynak Koddan Derleme

Gerekenler:

- [Android Studio](https://developer.android.com/studio)
- **NDK 30.0.14904198:** Android Studio → Settings → Languages & Frameworks → Android SDK → SDK Tools → "Show Package Details" işaretlenip seçilir
- `kaynak/mobile.ovpn` (derleme bu dosyayı APK'nın içine gömer; yoksa açık bir hata verir)

1. OpenVPN motorunun kaynağını indirin. Bu klasör 170 MB olduğu için git'e dahil değildir:

   ```powershell
   cd android
   git clone --depth 1 --branch v0.7.68 --recurse-submodules --shallow-submodules https://github.com/schwabe/ics-openvpn.git ics-openvpn
   ```

2. `android` klasörünü Android Studio ile açın ve **Run**'a basın.

   Komut satırından APK üretmek için:

   ```powershell
   cd android
   .\gradlew.bat assembleRelease
   ```

   APK `android\app\build\outputs\apk\release\app-release.apk` konumunda oluşur. Bunu telefona yükleyin.

İlk derleme OpenSSL ve OpenVPN'i üç işlemci mimarisi için derlediğinden birkaç dakika sürer. Sonraki derlemeler saniyeler içinde biter.

Farklı bir yapılandırma dosyası kullanmak için: `.\gradlew.bat assembleRelease -PovpnDosyasi=baska.ovpn` (dosya `kaynak/` içinde olmalı).

### Mimari

| Modül / Dosya | Görevi |
|---|---|
| `app/.../VpnEngine.kt` | Motoru başlatır, durdurur, duraklatır. Durum, hız ve hata bilgisini arayüze aktarır (`vpn_controller.py`'nin karşılığı). |
| `app/.../ui/` | Jetpack Compose arayüzü: güç butonu, kartlar, grafik. |
| `app/.../VpnWidget.kt` | Ana ekran widget'ı. |
| `app/.../VpnTileService.kt` | Hızlı ayarlar kutucuğu. |
| `app/.../QuickDisconnectActivity.kt` | Bildirimdeki "Bağlantıyı kes"i onay penceresi olmadan uygular. |
| `app/.../StatusSurfaces.kt` | Widget ve kutucuğu her iki süreçten de günceller. |
| `app/src/main/res/values/strings_motor.xml` | Motorun bildirim metinlerinin Türkçesi. |
| `vpnmotor/` | ics-openvpn'in arayüzsüz ("skeleton") sürümünü kütüphane olarak derler. |

Önemli noktalar:

- **İki süreç:** VPN servisi ayrı bir süreçte (`:openvpn`) çalışır. Durum ve bayt sayaçları arayüz sürecine aktarılır. Android arayüzü arka planda kapatsa bile servis süreci widget ve kutucuğu güncel tutar.
- **Upstream'e dokunulmaz:** `ics-openvpn` klasörü hiç değiştirilmez. `vpnmotor`, kaynakları derleme sırasında `build/` altına kopyalar ve küçük yamaları orada uygular (şu an tek yama: bildirimin tek kanal ve kimlikle güncellenmesi). ics-openvpn güncellenip yamanın aradığı satır değişirse derleme açık bir hatayla durur.
- **OpenVPN3 derlenmez:** Yalnızca klasik OpenVPN 2 çekirdeği kullanılır. Bu yüzden SWIG gerekmez; `vpnmotor/cmake/FindSWIG.cmake` CMake'in SWIG aramasını karşılar.
- **`persist-tun` kapalı:** Duraklatınca tünelin kapanıp normal internetin kullanılabilmesi için telefon profilinde bu ayar kapatılır. Yan etkisi: Wi-Fi ile mobil veri arasında geçerken VPN yeniden bağlanırken trafik birkaç saniye VPN dışından gidebilir.

---

## Bilgisayar ve Telefon Aynı Anda

OpenVPN sunucusu varsayılan olarak (`duplicate-cn` ayarı yoksa) bir istemci sertifikasıyla aynı anda tek bağlantıya izin verir. Aynı sertifikayla ikinci cihaz bağlanırsa birincisi düşer. Bu yüzden:

| Cihaz | Yapılandırma | Sertifika |
|---|---|---|
| Bilgisayar | `kaynak/my.ovpn` | `CN=my` |
| Telefon | `kaynak/mobile.ovpn` | `CN=mobile` |

Yeni bir cihaz eklemek için sunucuda yeni istemci oluşturun (ör. `openvpn-install.sh` → "Add a new client") ve oluşan `.ovpn` dosyasını o cihazda kullanın.

## Sorun Giderme

| Sorun | Çözüm |
|---|---|
| Windows: "Yeniden bağlanılıyor (sunucu bağlantıyı reddetti)" | `.ovpn` dosyasındaki `proto` (tcp/udp) ve port değerlerinin sunucuyla eşleştiğini kontrol edin. |
| Windows: "Bağlandı ancak bazı yönlendirmeler eklenemedi" | Uygulamayı yönetici olarak çalıştırın. |
| Windows: "OpenVPN bulunamadı" | `kurulum.bat`'ı çalıştırın ya da OpenVPN'i `C:\Program Files\OpenVPN` konumuna kurun. |
| Android: "Bağlantı zaman aşımına uğradı: sunucu yanıt vermiyor" | Telefonun internetini kontrol edin (mobil veri bitmiş olabilir). Wi-Fi'da çalışıp mobil veride çalışmıyorsa operatör UDP 443'ü engelliyor olabilir. |
| Android: Bağlan'a basınca bir şey olmuyor | Android'in VPN izin penceresinde **Tamam**'a basın. Başka bir VPN uygulaması "Her zaman açık VPN" olarak ayarlıysa kapatın. |
| İki cihazdan biri sürekli kopuyor | İki cihaz aynı `.ovpn` dosyasını kullanıyor olabilir; bkz. [Bilgisayar ve Telefon Aynı Anda](#bilgisayar-ve-telefon-aynı-anda). |
| Bağlantı zaman aşımına uğruyor (iki platform) | Sunucunun açık olduğunu ve güvenlik duvarının portu engellemediğini kontrol edin. |

Windows'ta ayrıntılı OpenVPN logu şu dosyaya yazılır: `%TEMP%\vpn_arayuz_openvpn.log`

## Kullanılan Teknolojiler

**Windows:** [Python](https://www.python.org/), [PyQt6](https://www.riverbankcomputing.com/software/pyqt/) (arayüz), [psutil](https://github.com/giampaolo/psutil) (ağ adaptörü sayaçları), [PyInstaller](https://pyinstaller.org/) (exe paketleme)

**Android:** [Kotlin](https://kotlinlang.org/), [Jetpack Compose](https://developer.android.com/compose) (arayüz), [ics-openvpn](https://github.com/schwabe/ics-openvpn) (OpenVPN for Android motoru), Android NDK / CMake (OpenVPN ve OpenSSL derlemesi)

**Ortak:** [OpenVPN](https://openvpn.net/)

## Lisans

Android uygulaması, GPLv2 lisanslı [ics-openvpn](https://github.com/schwabe/ics-openvpn)'i içerir. Kişisel kullanımda bir yükümlülük yoktur. APK başkalarına dağıtılırsa uygulamanın kaynak kodunun da GPLv2 koşullarıyla paylaşılması gerekir.
