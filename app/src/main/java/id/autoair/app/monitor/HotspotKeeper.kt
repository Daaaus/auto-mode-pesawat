package id.autoair.app.monitor

import android.content.Context
import id.autoair.app.shizuku.ShizukuBridge
import kotlinx.coroutines.delay

/**
 * Menjaga hotspot tetap hidup melewati toggle mode pesawat.
 *
 * Strategi berlapis:
 *  1. Cegah matinya Wi-Fi lewat airplane_mode_radios (dilakukan AirplaneModeController).
 *  2. Rekam status tether sebelum toggle.
 *  3. Setelah toggle, jika sebelumnya aktif tapi sekarang mati, nyalakan kembali.
 *
 * Tidak pernah menyalakan hotspot yang memang sedang mati.
 */
class HotspotKeeper(private val context: Context) {

    enum class Outcome { NOT_ACTIVE, PRESERVED, RESTORED, FAILED, UNSUPPORTED }

    /** true jika ada tether Wi-Fi yang sedang aktif. */
    fun isHotspotActive(): Boolean {
        // `cmd wifi` tersedia luas sejak Android 11.
        val wifi = ShizukuBridge.exec("cmd wifi is-softap-enabled")
        if (wifi.ok) {
            val out = wifi.output.lowercase()
            if (out.contains("enabled") && !out.contains("disabled")) return true
            if (out.contains("disabled")) return false
        }
        // Cadangan: baca daftar interface tether aktif.
        val dump = ShizukuBridge.exec(
            "dumpsys connectivity tethering | grep -i -m1 'mActiveTethering\\|TetherStates\\|Tethered'"
        )
        if (dump.ok && dump.output.isNotBlank()) {
            val o = dump.output.lowercase()
            if (o.contains("wlan") || o.contains("ap0")) return true
        }
        return false
    }

    fun capture(): Boolean {
        val active = runCatching { isHotspotActive() }.getOrDefault(false)
        if (active) Logger.info("hotspot terdeteksi aktif, akan dipertahankan")
        return active
    }

    /**
     * Pulihkan hotspot bila sebelumnya aktif.
     * @param wasActive hasil [capture] sebelum toggle.
     */
    suspend fun restore(wasActive: Boolean): Outcome {
        if (!wasActive) return Outcome.NOT_ACTIVE

        // Beri sistem waktu menstabilkan radio setelah mode pesawat dimatikan.
        delay(1500)

        if (isHotspotActive()) {
            Logger.info("hotspot bertahan melewati toggle")
            return Outcome.PRESERVED
        }

        Logger.warn("hotspot mati setelah toggle, mencoba menyalakan kembali")
        val start = ShizukuBridge.exec("cmd wifi start-softap")
        if (!start.ok) {
            // Sebagian ROM memakai sintaks lama tanpa argumen tambahan.
            val alt = ShizukuBridge.exec("svc wifi enable; cmd wifi start-softap")
            if (!alt.ok) {
                Logger.error("tidak dapat menyalakan hotspot: ${start.output}")
                return Outcome.UNSUPPORTED
            }
        }

        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            if (isHotspotActive()) {
                Logger.info("hotspot berhasil dinyalakan kembali")
                return Outcome.RESTORED
            }
            delay(500)
        }
        Logger.error("hotspot gagal pulih")
        return Outcome.FAILED
    }
}
