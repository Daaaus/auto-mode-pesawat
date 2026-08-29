package id.autoair.app.tweak

import id.autoair.app.config.ConfigStore
import id.autoair.app.monitor.Logger
import id.autoair.app.shizuku.ShizukuBridge

/**
 * Kumpulan optimasi perangkat yang bisa dilakukan TANPA root, cukup lewat
 * identitas shell Shizuku (setara `adb shell`).
 *
 * Soal "JIT": JIT pada ART tidak bisa dinyalakan/dimatikan dari luar - ia
 * bagian internal runtime. Yang bisa dikendalikan adalah KOMPILASI: memaksa
 * AOT (`cmd package compile`) membuat kode dikompilasi di depan sehingga JIT
 * nyaris tidak perlu bekerja. Itulah yang diekspos di sini.
 *
 * Semua perintah gagal dengan aman: hasil perintah dilog, tidak ada yang
 * mengubah sesuatu yang tidak bisa dikembalikan.
 */
object Tweaks {

    /**
     * Mode kompilasi ART (sesuai `cmd package compile -m`).
     * Urutan dipakai juga untuk spinner di UI.
     */
    val COMPILE_MODES = listOf(
        // mode, label UI, catatan jujur
        Triple(
            "speed-profile",
            "speed-profile (bawaan Android)",
            "AOT hanya untuk kode yang sering dipakai, sisanya JIT. " +
                "Paling seimbang - inilah yang dilakukan Android tiap malam saat ngecas."
        ),
        Triple(
            "speed",
            "speed (AOT penuh)",
            "Semua kode dikompilasi di depan: aplikasi terasa paling responsif " +
                "dan JIT nyaris tidak bekerja. Bayarannya: ukuran aplikasi di " +
                "penyimpanan bisa 2-3x lebih besar."
        ),
        Triple(
            "everything",
            "everything (maksimal)",
            "Seperti speed tapi lebih agresif lagi. Hampir tidak ada bedanya di " +
                "pemakai nyata, penyimpanan paling boros."
        ),
        Triple(
            "quicken",
            "quicken (hemat)",
            "Kompilasi minimal, mengandalkan JIT. Dipakai untuk mengembalikan " +
                "aplikasi bila penyimpanan penuh setelah mode speed/everything."
        ),
    )

    // ------------------------------------------------------------- kompilasi

    /**
     * Kompilasi paket sekarang. [allPackages]=true mengompilasi SEMUA aplikasi
     * - bisa makan waktu puluhan menit dan menguras baterai, jadi timeout
     * dibesarkan dan pemanggil wajib menjalankannya di thread IO.
     */
    fun compile(mode: String, allPackages: Boolean, selfPkg: String): Boolean {
        if (!ShizukuBridge.isReady()) {
            Logger.error("kompilasi dibatalkan: Shizuku belum siap")
            return false
        }
        val target = if (allPackages) "-a" else selfPkg
        val label = if (allPackages) "semua aplikasi" else selfPkg
        Logger.warn("kompilasi ART: mode=$mode target=$label - ini bisa lama")
        val timeout = if (allPackages) 3600L else 300L
        val r = ShizukuBridge.exec("cmd package compile -m $mode -f $target", timeoutSec = timeout)
        if (r.ok) {
            // Output per-paket bisa ribuan baris; ringkas saja.
            val failures = r.stdout.lines().count { it.contains("Failure") }
            if (failures > 0) Logger.warn("kompilasi selesai, $failures paket gagal (wajar: ada paket terkunci)")
            else Logger.info("kompilasi selesai tanpa kegagalan")
        } else {
            Logger.error("kompilasi gagal: ${r.output.take(300)}")
        }
        return r.ok
    }

    /** Jalankan dexopt latar belakang (yang biasanya berjalan saat ngecas malam). */
    fun runBackgroundDexopt(): Boolean {
        if (!ShizukuBridge.isReady()) {
            Logger.error("dexopt dibatalkan: Shizuku belum siap")
            return false
        }
        Logger.info("menjalankan dexopt latar belakang (speed-profile)...")
        val r = ShizukuBridge.exec("cmd package bg-dexopt-job", timeoutSec = 3600)
        if (r.ok) Logger.info("dexopt latar belakang selesai")
        else Logger.error("dexopt gagal: ${r.output.take(300)}")
        return r.ok
    }

    /** Hapus hasil kompilasi paksa, kembali ke perilaku default Android. */
    fun resetCompilation(allPackages: Boolean, selfPkg: String): Boolean {
        val target = if (allPackages) "-a" else selfPkg
        val r = ShizukuBridge.exec("cmd package compile --reset $target", timeoutSec = 600)
        if (r.ok) Logger.info("profil kompilasi dikembalikan ke default")
        else Logger.error("reset kompilasi gagal: ${r.output.take(300)}")
        return r.ok
    }

    // --------------------------------------------------------------- setelan

    /** Animasi UI: true = 0.5x (lebih gesit), false = 1.0x standar. */
    fun setFastAnimations(on: Boolean): Boolean {
        val v = if (on) "0.5" else "1.0"
        val ok = listOf(
            "window_animation_scale",
            "transition_animation_scale",
            "animator_duration_scale"
        ).all { key ->
            ShizukuBridge.exec("settings put global $key $v").ok
        }
        if (ok) Logger.info("animasi UI diatur ke ${v}x")
        else Logger.error("gagal mengatur skala animasi")
        return ok
    }

    /**
     * Batas proses latar belakang. -1 mengembalikan ke standar sistem
     * (dengan menghapus kunci, bukan menulis angka, supaya benar-benar default).
     */
    fun setBackgroundProcessLimit(limit: Int): Boolean {
        val ok = if (limit < 0) {
            ShizukuBridge.exec("settings delete global background_process_limit").ok
        } else {
            ShizukuBridge.exec("settings put global background_process_limit $limit").ok
        }
        if (ok) {
            Logger.info(
                if (limit < 0) "batas proses latar: standar sistem"
                else "batas proses latar: maks $limit"
            )
        } else Logger.error("gagal mengatur batas proses latar")
        return ok
    }

    /** Freezer aplikasi cache: menghentikan aplikasi cached memakai CPU sama sekali. */
    fun setFreezer(on: Boolean): Boolean {
        val v = if (on) "enabled" else "device_default"
        val ok = ShizukuBridge.exec("settings put global cached_apps_freezer $v").ok
        if (ok) Logger.info("freezer aplikasi cache: $v")
        else Logger.error("gagal mengatur freezer (butuh Android 11+)")
        return ok
    }

    /** true = throttle pemindaian WiFi DIMATIKAN (scan lebih agresif, lebih boros). */
    fun setWifiScanThrottleOff(off: Boolean): Boolean {
        val v = if (off) "0" else "1"
        val ok = ShizukuBridge.exec("settings put global wifi_scan_throttle_enabled $v").ok
        if (ok) Logger.info("throttle scan WiFi: ${if (off) "mati" else "aktif (standar)"}")
        else Logger.error("gagal mengatur throttle scan WiFi")
        return ok
    }

    /**
     * Kunci layar ke satu refresh rate.
     *
     * Min DAN peak harus dipatok ke nilai yang sama: menaikkan peak saja tidak
     * mengubah apa-apa karena sistem tetap bebas memilih mode lain. Nilainya
     * bebas di antara mode yang didukung layar - Hz tertinggi untuk paling
     * mulus, 60 Hz untuk paling hemat baterai.
     *
     * Mematikan tweak = menghapus kedua kunci (bukan menulis 60), supaya
     * perangkat kembali ke perilaku adaptif bawaannya persis seperti semula.
     */
    fun setLockRefreshRate(on: Boolean, hz: Float): Boolean {
        val ok = if (on) {
            if (hz <= 0f) {
                Logger.error("refresh rate tidak diatur: nilai Hz tidak valid")
                return false
            }
            val v = String.format(java.util.Locale.US, "%.1f", hz)
            ShizukuBridge.exec("settings put system peak_refresh_rate $v").ok &&
                ShizukuBridge.exec("settings put system min_refresh_rate $v").ok
        } else {
            ShizukuBridge.exec("settings delete system peak_refresh_rate").ok &&
                ShizukuBridge.exec("settings delete system min_refresh_rate").ok
        }
        if (ok) {
            Logger.info(
                if (on) "refresh rate dikunci ke ${hz.toInt()} Hz"
                else "refresh rate kembali adaptif"
            )
        } else Logger.error("gagal mengatur refresh rate")
        return ok
    }

    /** Bunuh semua aplikasi latar/cached untuk mengosongkan RAM seketika. */
    fun killBackgroundApps(): Boolean {
        val r = ShizukuBridge.exec("am kill-all", timeoutSec = 15)
        if (r.ok) Logger.info("aplikasi latar dibunuh (am kill-all)")
        else Logger.error("am kill-all gagal: ${r.output.take(200)}")
        return r.ok
    }

    /**
     * Masukkan aplikasi ini ke whitelist doze. Melengkapi pengecualian baterai
     * biasa: saat Doze dalam, whitelist tetap mengizinkan jaringan & partial
     * wake lock, jadi pemantauan tidak ikut tertidur.
     */
    fun whitelistSelfFromDoze(selfPkg: String): Boolean {
        val r = ShizukuBridge.exec("dumpsys deviceidle whitelist +$selfPkg")
        val ok = r.ok
        if (ok) Logger.info("aplikasi masuk whitelist doze")
        else Logger.error("whitelist doze gagal: ${r.output.take(200)}")
        return ok
    }

    // ------------------------------------------------------------ persistence

    /**
     * Terapkan ulang tweak yang berstatus AKTIF. Dipanggil sekali saat service
     * monitor mulai: Settings.Global umumnya bertahan setelah reboot, tapi ada
     * ROM yang meresetnya (terutama background_process_limit), jadi kita
     * pastikan ulang.
     *
     * Sengaja hanya menerapkan nilai "aktif" dan tidak pernah menulis nilai
     * default - supaya setelan manual pengguna di luar aplikasi tidak ditimpa.
     */
    fun applyPersistent(config: ConfigStore): Boolean {
        if (!ShizukuBridge.isReady()) return false
        var applied = 0
        if (config.tweakFastAnim) {
            if (setFastAnimations(true)) applied++
        }
        if (config.tweakBgProcessLimit >= 0) {
            if (setBackgroundProcessLimit(config.tweakBgProcessLimit)) applied++
        }
        if (config.tweakFreezerOn) {
            if (setFreezer(true)) applied++
        }
        if (config.tweakWifiScanThrottleOff) {
            if (setWifiScanThrottleOff(true)) applied++
        }
        if (config.tweakLockRefreshRate && config.tweakLockRefreshRateHz > 0f) {
            if (setLockRefreshRate(true, config.tweakLockRefreshRateHz)) applied++
        }
        if (applied > 0) Logger.info("tweak optimasi diterapkan ulang ($applied)")
        return true
    }
}
