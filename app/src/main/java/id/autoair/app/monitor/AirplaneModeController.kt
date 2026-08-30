package id.autoair.app.monitor

import android.content.Context
import android.content.Intent
import android.provider.Settings
import id.autoair.app.config.ConfigStore
import id.autoair.app.shizuku.ShizukuBridge
import kotlinx.coroutines.delay

/**
 * Padanan modpes_on / modpes_off pada skrip shell.
 *
 * Dua jalur, sama seperti flag USE_CMD:
 *  - Settings.Global airplane_mode_on + broadcast  (default)
 *  - cmd connectivity airplane-mode enable|disable (fallback / opsi)
 */
class AirplaneModeController(
    private val context: Context,
    private val config: ConfigStore
) {

    fun isAirplaneModeOn(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    /**
     * Terapkan daftar radio yang ikut dimatikan mode pesawat.
     * `wifi` sengaja dikecualikan supaya hotspot/Wi-Fi tidak ikut mati.
     * Nilai asli disimpan agar bisa dipulihkan saat service berhenti.
     */
    fun applyRadioWhitelist(): Boolean {
        val desired = config.airplaneRadios
        val current = readGlobal("airplane_mode_radios")

        if (config.originalRadios == null && current != null) {
            config.originalRadios = current
            Logger.info("airplane_mode_radios asli disimpan: $current")
        }
        if (current == desired) return true

        val ok = writeGlobal("airplane_mode_radios", desired)
        // Beberapa ROM juga membaca daftar terpisah untuk keadaan pesawat.
        writeGlobal("wifi_on_when_airplane_mode", "1")

        if (ok) Logger.info("airplane_mode_radios -> $desired (wifi dikecualikan)")
        else Logger.warn("gagal menulis airplane_mode_radios")
        return ok
    }

    /** Kembalikan konfigurasi radio ke nilai sebelum aplikasi mengubahnya. */
    fun restoreRadioWhitelist() {
        val orig = config.originalRadios ?: return
        if (writeGlobal("airplane_mode_radios", orig)) {
            Logger.info("airplane_mode_radios dipulihkan: $orig")
        }
    }

    suspend fun enable(): Boolean = setAirplane(true)

    suspend fun disable(): Boolean = setAirplane(false)

    private suspend fun setAirplane(on: Boolean): Boolean {
        val label = if (on) "ON" else "OFF"

        if (config.useCmdConnectivity) {
            val verb = if (on) "enable" else "disable"
            val r = ShizukuBridge.exec("cmd connectivity airplane-mode $verb")
            if (r.ok) {
                Logger.info("mode pesawat $label (cmd connectivity)")
                return awaitState(on)
            }
            Logger.warn("cmd connectivity gagal (${r.output}), fallback ke Settings.Global")
        }

        val value = if (on) "1" else "0"
        val wrote = writeGlobal("airplane_mode_on", value)
        if (!wrote) {
            Logger.error("tidak bisa menulis airplane_mode_on")
            return false
        }
        broadcastAirplaneChanged(on)
        Logger.info("mode pesawat $label (settings global)")
        return awaitState(on)
    }

    /**
     * Broadcast agar system service bereaksi. Butuh identitas shell,
     * jadi dikirim lewat Shizuku; broadcast dari app biasa akan ditolak.
     *
     * Dikirim tanpa ditunggu: hasilnya tidak dipakai, dan menunggu spawn shell
     * selesai menambah ratusan milidetik tepat di jalur kritis toggle.
     * Broadcast dari proses aplikasi dikirim juga sebagai pelengkap, karena
     * sebagian ROM menerimanya dan biayanya nihil.
     */
    private fun broadcastAirplaneChanged(on: Boolean) {
        val state = if (on) "true" else "false"
        ShizukuBridge.execAsync(
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $state"
        )
        runCatching {
            context.sendBroadcast(
                Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", on)
            )
        }
    }

    /** Tunggu sampai sistem benar-benar melaporkan status yang diminta. */
    private suspend fun awaitState(expected: Boolean, timeoutMs: Long = 4000): Boolean {
        // Sering sudah benar seketika karena penulisan Settings.Global bersifat
        // sinkron; polling rapat membuat kasus itu tidak membayar jeda apa pun.
        if (isAirplaneModeOn() == expected) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(60)
            if (isAirplaneModeOn() == expected) return true
        }
        Logger.warn("status mode pesawat tidak berubah dalam ${timeoutMs}ms")
        return false
    }

    /**
     * Paksa data seluler attach ulang: `svc data disable` lalu `enable`.
     *
     * Dipakai sebagai eskalasi bila toggle mode pesawat berulang kali tidak
     * memulihkan koneksi - kadang radio tetap nyangkut pada sel yang sama dan
     * hanya siklus data yang melepaskannya.
     */
    suspend fun kickMobileData() {
        Logger.warn("kick data seluler (svc data off/on)")
        val off = ShizukuBridge.exec("svc data disable", timeoutSec = 5)
        if (!off.ok) {
            Logger.warn("svc data disable gagal: ${off.output}")
            return
        }
        // Data sekarang MATI. Bila proses dibunuh sebelum enable, flag ini yang
        // menyelamatkan pengguna dari kehilangan data seluler permanen.
        config.dataKickInProgress = true
        try {
            delay(600)
            var on = ShizukuBridge.exec("svc data enable", timeoutSec = 5).ok
            if (!on) {
                // Sama seperti mode pesawat: meninggalkan data mati jauh lebih
                // buruk daripada gagal refresh, jadi dicoba beberapa kali.
                for (i in 1..5) {
                    Logger.error("gagal menyalakan data, percobaan ulang $i/5")
                    delay(300)
                    if (ShizukuBridge.exec("svc data enable", timeoutSec = 5).ok) {
                        on = true
                        break
                    }
                }
            }
            if (on) Logger.info("data seluler dinyalakan ulang")
            else Logger.error("DATA SELULER MASIH MATI - nyalakan manual!")
        } finally {
            config.dataKickInProgress = false
        }
    }

    /** Pemulihan saat start: nyalakan data yang tertinggal mati. */
    fun recoverStuckData(): Boolean {
        if (!config.dataKickInProgress) return false
        Logger.warn("terdeteksi kick data terputus - menyalakan data seluler")
        val r = ShizukuBridge.exec("svc data enable", timeoutSec = 5)
        config.dataKickInProgress = false
        if (r.ok) Logger.info("data seluler dipulihkan")
        else Logger.error("gagal memulihkan data seluler - nyalakan manual")
        return true
    }

    // --- Settings.Global helpers -------------------------------------------------

    private fun readGlobal(key: String): String? {
        Settings.Global.getString(context.contentResolver, key)?.let { return it }
        val r = ShizukuBridge.exec("settings get global $key")
        val v = r.stdout.trim()
        return if (r.ok && v.isNotEmpty() && v != "null") v else null
    }

    private fun writeGlobal(key: String, value: String): Boolean {
        if (ShizukuBridge.hasSecureSettings(context)) {
            val direct = runCatching {
                Settings.Global.putString(context.contentResolver, key, value)
            }.getOrDefault(false)
            if (direct) return true
        }
        val r = ShizukuBridge.exec("settings put global $key $value")
        return r.ok
    }
}
