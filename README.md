# Auto Mode Pesawat

Otomatis menyalakan-mematikan **mode pesawat** saat internet mati, tanpa root — memakai [Shizuku](https://shizuku.rikka.app/).

Port dari script Magisk `net_refresh.sh` ke aplikasi Android biasa.

![Android](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
![Ukuran](https://img.shields.io/badge/APK-~2.1%20MB-blue)
![Root](https://img.shields.io/badge/root-tidak%20perlu-success)

---

## Cara kerja

```
tiap 60 detik:
  sedang menelepon?          -> lewati
  cek https://www.gstatic.com/generate_204   (3x percobaan)
  masih gagal?               -> mode pesawat ON -> tunggu 3 dtk -> OFF
                                hotspot dijaga tetap menyala
```

Hanya **HTTP 204 dengan body kosong** yang dianggap internet sehat. Halaman
pengalihan operator (kuota habis, captive portal) yang membalas `200`/`30x`
tetap dihitung sebagai tidak ada internet.

## Fitur

- **Hotspot tetap menyala** saat mode pesawat — `wifi` dikeluarkan dari `airplane_mode_radios`
- **Pengaman panggilan** — tidak pernah memutus telepon yang sedang aktif
- **Pemulihan otomatis** — jika proses mati di tengah toggle, mode pesawat yang
  tertinggal menyala akan dimatikan saat aplikasi hidup lagi
- **Backoff hemat baterai** — 3x gagal → jeda 2 menit, 5x → 5 menit, 10x → 10 menit
- **Cek IP opsional** — refresh juga saat IP operator berubah (berguna untuk inject IP),
  diukur menembus VPN
- Jalan saat layar mati, hidup lagi setelah reboot

## Pemasangan

1. Unduh APK dari [Releases](../../releases) (`arm64` untuk mayoritas HP)
2. Pasang & jalankan **Shizuku** (Wireless debugging → Start)
3. Buka aplikasi → **Hubungkan** → **Atur** baterai → nyalakan **Pemantauan otomatis**

> Shizuku berhenti setiap HP dimatikan. Setelah reboot, jalankan Shizuku lagi —
> aplikasi menunggu dan otomatis lanjut sendiri.

## Izin

| Izin | Alasan |
|---|---|
| `WRITE_SECURE_SETTINGS` | mengubah `airplane_mode_on` (diberikan lewat Shizuku) |
| `READ_PHONE_STATE` | mendeteksi panggilan aktif agar tidak diputus |
| `FOREGROUND_SERVICE_SPECIAL_USE` | pemantauan berkelanjutan |
| `RECEIVE_BOOT_COMPLETED` | lanjut memantau setelah reboot |

Aplikasi tidak mengirim data ke mana pun. Probe hanya menuju host yang Anda atur sendiri.

## Build

```bash
git clone https://github.com/Daaaus/auto-mode-pesawat.git
cd auto-mode-pesawat
./gradlew assembleRelease     # keluar di app/build/outputs/apk/release/
```

Tanpa keystore, build memakai debug key. Untuk rilis bertanda tangan, set
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` dan taruh keystore di
`keystore/release.jks`.

### Rilis lewat CI

Set secret repo `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`,
lalu dorong tag:

```bash
git tag v1.5.0 && git push origin v1.5.0
```

## Batasan yang jujur

- **Hotspot** bergantung implementasi OEM. Strategi utama adalah mencegah Wi-Fi
  ikut mati; pemulihan lewat `cmd wifi start-softap` tidak selalu didukung.
- **VPN always-on + "blokir tanpa VPN"** membuat pengukuran IP seluler gagal.
  Aplikasi mencatatnya dan melewati refresh, bukan menebak.
- Diuji terbatas. Laporkan masalah lewat Issues beserta isi log (tombol **Salin**).

## Lisensi

[MIT](LICENSE)
