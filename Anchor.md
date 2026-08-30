# Anchor — Auto Mode Pesawat

> Dokumen jangkar untuk melanjutkan sesi. Baca ini dulu sebelum menyentuh kode.

## Apa ini

Aplikasi Android yang menyalakan-mematikan **mode pesawat otomatis saat internet mati**, tanpa root, memakai **Shizuku**. Port dari script Magisk `net_refresh.sh`.

- Package: `id.autoair.app` · Kotlin · minSdk 30 · target/compile 35 · ViewBinding
- Repo publik: https://github.com/Daaaus/auto-mode-pesawat
- Hosting APK (VPS): `https://cmadun65-ccce303e-9878-vm.azure.gensparkclaw.com/apk/AutoModePesawat.apk?v=5` (v1.13.1)

## Status sekarang

- **v1.13.1 released & verified.** Tanpa rem: full speed selamanya selama internet mati. CI/CD hijau.
- Kedua workflow ✅ (build run 33204105340, release run 33204159684).
- APK rilis ditandatangani **release key** (bukan debug), refleksi Shizuku utuh pasca-R8.
- **Belum dites di HP fisik.** Signature beda dari v1.4 → harus uninstall dulu.

## Cara kerja inti

```
tiap interval (default 60d):
  sedang menelepon?            -> lewati (jangan putus telepon)
  cek https://<host>/generate_204   (probeAttempts x, bypass VPN)
  hanya HTTP 204 + body kosong = sehat
  gagal? -> flag=in-progress -> mode pesawat ON -> tunggu -> OFF -> flag=false
           hotspot dijaga (wifi dikeluarkan dari airplane_mode_radios)
```

## Keputusan desain (jangan dilanggar tanpa alasan)

1. **Shizuku, bukan root.** Semua aksi istimewa lewat `Shizuku.newProcess()` (refleksi, api 13.1.5).
2. **204-kosong saja = sehat.** Halaman quota-habis operator yang balas 200/30x dihitung "tidak ada internet".
3. **Jangan pernah toggle saat panggilan aktif** (`CallGuard`).
4. **Fail-closed di probe IP** — kalau IP seluler tak terukur (VPN always-on block), catat & lewati, jangan menebak.
5. **Anti-terjebak-pesawat** (bug terburuk): siklus toggle dibungkus `NonCancellable` + flag persisten `commit()` + pemulihan saat service start.
6. **Tanpa rem saat internet mati (v1.13+):** backoff LAMA dihapus - selama tidak sehat, interval 4 dtk + kick data tiap refresh gagal, selamanya. Yang tersisa hanya gerbang wake: MIN_GAP 2.5s, throttle 1.5s.
7. **`isHotspotActive()` fail-open** (return true bila status tethering tak terbaca) agar tak memicu churn radio.
8. **Ukuran:** R8 + shrinkResources + ABI split. Verifikasi refleksi Shizuku tetap utuh setelah tiap rilis (lihat bagian Verifikasi).

## Build lokal (bertanda tangan rilis)

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export KEYSTORE_PASSWORD=$(cat /root/.autoair_keystore_pass)
export KEY_ALIAS=autoair
export KEY_PASSWORD=$(cat /root/.autoair_keystore_pass)
cd /root/AutoAirplane && ./gradlew assembleRelease
# out: app/build/outputs/apk/release/app-{arm64-v8a,armeabi-v7a,universal}-release.apk
```

Tanpa env keystore → otomatis fallback **debug key** (agar fork/CI tak rusak).

## Rilis lewat CI

Secrets repo sudah terpasang: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

```bash
git tag v1.6.0 && git push origin v1.6.0   # release.yml auto build + publish
```

- `build.yml` — tiap push/PR ke main → artifact APK + laporan ukuran.
- `release.yml` — tag `v*` → decode keystore → build → rename `AutoModePesawat-<tag>-{arm64,arm32,universal}.apk` → apksigner verify → publish GitHub Release + SHA256SUMS.

## Push pattern (PENTING — token)

`gh` diauth lewat env `GH_TOKEN`. **Jangan simpan token di remote URL.** Polanya:

```bash
git remote set-url origin "https://x-access-token:$GH_TOKEN@github.com/Daaaus/auto-mode-pesawat.git"
git push ...
git remote set-url origin "https://github.com/Daaaus/auto-mode-pesawat.git"   # bersihkan
```

Git identity: `user.name=Daaaus`, `user.email=daaaus@users.noreply.github.com`.

## Verifikasi pasca-rilis (wajib, jangan diasumsikan)

R8 mengacak nama; `ShizukuBridge` memakai refleksi — kalau tersamarkan, toggle gagal **diam-diam**. Unduh APK dari Releases, bongkar dex, pastikan ada:

`Shizuku.newProcess`, `ShizukuRemoteProcess.waitForTimeout`, `exitValue`, `pingBinder`, `checkSelfPermission`, `requestPermission`, `NonCancellable`, `toggle_in_progress`, `cmd connectivity airplane-mode`.

Signature harus cocok dengan keystore lokal: SHA-256 `35d17681546b8426358fb491793dc8129e99d6202c8938d16ff8db7e3b9f831a`.

## Kredensial & rahasia (JANGAN COMMIT — sudah di-gitignore)

- Keystore: `/root/AutoAirplane/keystore/release.jks` (RSA-4096, alias `autoair`, DN `CN=Auto Mode Pesawat, O=Daaaus, C=ID`)
- Password keystore: `/root/.autoair_keystore_pass` (mode 600). **Cadangkan di luar VPS** — tanpa ini update tak bisa menimpa versi lama.
- **Token GitHub `ghp_Fllpv...cpPlv` (user Daaaus) TEREXPOSE di chat.** Scope luas (`admin:org`, `delete_repo`, dll). **WAJIB dicabut** di https://github.com/settings/tokens setelah selesai. Repo sendiri aman (remote sudah dibersihkan).

## Default konfigurasi (`ConfigStore`, profil instan v1.13)

`intervalSec=60` (sehat) · `unhealthyIntervalSec=4` (bermasalah) · `timeout=2` · `maxRetry=1` (gagal sekali langsung toggle) · `retryGapMs=200` · `hold=1` · `cooldown=1` · `aggressive=true` · migrasi paksa `SPEED_REV=3`
Tweak (layar Optimasi): anim 0.5x, batas proses latar, freezer, scan WiFi, kunci refresh rate (min+peak), mode performa tetap, adaptive battery off, override termal (TIDAK dipersist), debloat bloatware (proteksi keras).

## Arsitektur (peta file)

| File | Peran |
|---|---|
| `service/NetMonitorService.kt` | foreground service (SPECIAL_USE), pemulihan flag saat start, NetworkCallback → `engine.requestWake` |
| `monitor/MonitorEngine.kt` | loop utama; NonCancellable; wake channel; interval tak-sehat 4 dtk tanpa rem; kick data tiap gagal |
| `monitor/ConnectivityProbe.kt` | probe 204 ketat PARALEL (raceReach), bypass VPN, fast-fail `noValidatedNetwork` |
| `monitor/AirplaneModeController.kt` | radios, cycle settings+broadcast async, `kickMobileData` (flag `data_kick_in_progress`), poll 60ms |
| `monitor/HotspotKeeper.kt` | deteksi via NetworkInterface (tanpa shell) |
| `monitor/CallGuard.kt` | cek panggilan aktif |
| `monitor/MonitorState.kt` | holder status global (singleton) — service nulis, UI baca |
| `monitor/Logger.kt` | ring buffer + StateFlow untuk log UI |
| `config/ConfigStore.kt` | SharedPreferences, flag `commit()`, profil instan, pref tweak |
| `shizuku/ShizukuBridge.kt` | refleksi `newProcess`, `waitForTimeout`, exec/execAsync, `isReady()` |
| `service/BootReceiver.kt` | restart setelah BOOT_COMPLETED / package replaced |
| `tweak/Tweaks.kt` | kompilasi ART (`cmd package compile`), settings performa, applyPersistent saat service start |
| `tweak/Debloat.kt` | scan bloatware + proteksi keras + disable/uninstall(user0)/restore |
| `ui/MainActivity.kt` | status hero + pulse, log terminal berwarna, tombol Optimasi (ic_tweaks) + Pengaturan |
| `ui/SettingsActivity.kt` | semua pengaturan monitor + profil cepat + uji refresh |
| `ui/TweaksActivity.kt` | layar Optimasi: kompilasi ART, kinerja, whitelist doze, BERBAHAYA (termal), entri debloat |
| `ui/DebloatActivity.kt` | daftar paket bawaan + aksi massal + pulihkan semua |
| `ui/StatusPulseView.kt` | custom view cincin berdenyut (ada keep rule R8) |

## Dependensi

AGP 8.5.2 · Kotlin 2.0.21 · Gradle 8.7 · `material:1.12.0` · `shizuku:api:13.1.5` (+provider) · `org.json:20231013`

## Batasan jujur (sudah di README)

- Pemulihan hotspot bergantung OEM; strategi utama = mencegah wifi ikut mati.
- VPN always-on + "blokir tanpa VPN" menggagalkan probe IP seluler (dicatat, dilewati).
- Shizuku mati tiap reboot → aplikasi menunggu & lanjut otomatis setelah Shizuku hidup lagi.

## Pekerjaan berikutnya (terpotong)

**Selesai (v1.6 & v1.7):** redesain UI 10x anti-slop (tema gelap terracotta, hero
pulse, log terminal berwarna), ikon launcher baru, pengaturan dipindah ke layar
khusus di balik ikon gear, auto-scroll log hanya saat di dasar, interval min 5 dtk.

Berikutnya: tes di HP fisik. (APK di VPS sudah v1.13.1)
