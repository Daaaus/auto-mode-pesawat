package id.autoair.app.monitor

import android.content.Context
import id.autoair.app.shizuku.ShizukuBridge
import kotlinx.coroutines.delay
import java.net.NetworkInterface

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

    /**
     * Deteksi hotspot tanpa spawn shell.
     *
     * Interface tether Wi-Fi (`ap0`, `softap0`, `wlan1`, ...) hanya muncul dan
     * berstatus up saat hotspot menyala, jadi ini penanda yang cukup andal dan
     * biayanya mikrodetik. `wlan0` adalah Wi-Fi klien biasa, tidak dihitung.
     *
     * @return true bila terdeteksi aktif, null bila belum pasti (perlu shell).
     */
    private fun hotspotViaInterfaces(): Boolean? = runCatching {
        val up = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .map { it.name.lowercase() }
        val ap = up.any { n ->
            n.startsWith("ap") || n.startsWith("softap") || n.startsWith("wlan1") ||
                n.startsWith("swlan")
        }
        if (ap) true else null
    }.getOrNull()

    /** true jika ada tether Wi-Fi yang sedang aktif. */
    fun isHotspotActive(): Boolean {
        hotspotViaInterfaces()?.let { return it }

        // `cmd wifi` tersedia luas sejak Android 11. Dipakai hanya sebagai
        // konfirmasi bila pembacaan interface tidak menemukan apa pun.
        val wifi = ShizukuBridge.exec("cmd wifi is-softap-enabled", timeoutSec = 3)
        if (wifi.ok) {
            val out = wifi.output.lowercase()
            if (out.contains("enabled") && !out.contains("disabled")) return true
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

        // Cek langsung dulu: dalam kasus normal wifi memang tidak ikut mati
        // karena sudah dikecualikan dari airplane_mode_radios, jadi tidak perlu
        // menunggu sama sekali. Versi lama selalu delay(1500) lebih dulu.
        if (isHotspotActive()) {
            Logger.info("hotspot bertahan melewati toggle")
            return Outcome.PRESERVED
        }
        delay(400)
        if (isHotspotActive()) {
            Logger.info("hotspot bertahan melewati toggle")
            return Outcome.PRESERVED
        }

        Logger.warn("hotspot mati setelah toggle, mencoba menyalakan kembali")
        val start = ShizukuBridge.exec("cmd wifi start-softap", timeoutSec = 5)
        if (!start.ok) {
            // Sebagian ROM memakai sintaks lama tanpa argumen tambahan.
            val alt = ShizukuBridge.exec("svc wifi enable; cmd wifi start-softap", timeoutSec = 5)
            if (!alt.ok) {
                Logger.error("tidak dapat menyalakan hotspot: ${start.output}")
                return Outcome.UNSUPPORTED
            }
        }

        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            if (isHotspotActive()) {
                Logger.info("hotspot berhasil dinyalakan kembali")
                return Outcome.RESTORED
            }
            delay(250)
        }
        Logger.error("hotspot gagal pulih")
        return Outcome.FAILED
    }
}
