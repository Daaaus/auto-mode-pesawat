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
     */
    private fun broadcastAirplaneChanged(on: Boolean) {
        val state = if (on) "true" else "false"
        val r = ShizukuBridge.exec(
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $state"
        )
        if (!r.ok) {
            // Upaya terakhir: kirim dari proses aplikasi (biasanya diabaikan sistem).
            runCatching {
                context.sendBroadcast(
                    Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", on)
                )
            }
        }
    }

    /** Tunggu sampai sistem benar-benar melaporkan status yang diminta. */
    private suspend fun awaitState(expected: Boolean, timeoutMs: Long = 8000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isAirplaneModeOn() == expected) return true
            delay(300)
        }
        Logger.warn("status mode pesawat tidak berubah dalam ${timeoutMs}ms")
        return false
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
