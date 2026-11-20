# VPN kurulumu: OpenVPN'i kurar ve masaüstüne "VPN" kısayolu oluşturur.
# kurulum.bat'a çift tıklayarak çalıştırın.

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# Yönetici değilse kendini yönetici olarak yeniden başlat
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$($MyInvocation.MyCommand.Path)`""
    exit
}

Write-Host "=== VPN Kurulumu ===" -ForegroundColor Cyan

# 1. OpenVPN
$openvpn = "C:\Program Files\OpenVPN\bin\openvpn.exe"
if (Test-Path $openvpn) {
    Write-Host "[OK] OpenVPN zaten kurulu." -ForegroundColor Green
} else {
    $msi = Get-ChildItem -Path $here -Filter "OpenVPN-*.msi" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($msi) {
        Write-Host "OpenVPN klasördeki kurulum dosyasından kuruluyor: $($msi.Name)"
        Start-Process msiexec.exe -ArgumentList "/i `"$($msi.FullName)`" /passive /norestart" -Wait
    } else {
        Write-Host "OpenVPN internetten kuruluyor (winget)..."
        winget install --id OpenVPNTechnologies.OpenVPN --exact --silent --accept-source-agreements --accept-package-agreements
    }
    if (Test-Path $openvpn) {
        Write-Host "[OK] OpenVPN kuruldu." -ForegroundColor Green
    } else {
        Write-Host "[HATA] OpenVPN kurulamadı. https://openvpn.net/community-downloads/ adresinden elle kurun." -ForegroundColor Red
    }
}

# 2. Masaüstü kısayolu
$exe = Join-Path $here "main.exe"
$lnk = Join-Path ([Environment]::GetFolderPath("Desktop")) "VPN.lnk"
$shell = New-Object -ComObject WScript.Shell
$sc = $shell.CreateShortcut($lnk)
$sc.TargetPath = $exe
$sc.WorkingDirectory = $here
$sc.IconLocation = "$exe,0"
$sc.Description = "VPN"
$sc.Save()
Write-Host "[OK] Masaüstüne 'VPN' kısayolu oluşturuldu." -ForegroundColor Green

Write-Host ""
Write-Host "Kurulum tamamlandı. Kapatmak için Enter'a basın." -ForegroundColor Cyan
Read-Host | Out-Null
