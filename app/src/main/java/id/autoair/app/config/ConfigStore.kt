package id.autoair.app.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Padanan `config.ini` pada skrip shell asli.
 */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("autoair_config", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** detik antar siklus pemantauan */
    var intervalSec: Int
        get() = prefs.getInt(KEY_INTERVAL, 60)
        set(v) = prefs.edit().putInt(KEY_INTERVAL, v.coerceIn(5, 3600)).apply()

    /** detik timeout tiap probe */
    var timeoutSec: Int
        get() = prefs.getInt(KEY_TIMEOUT, 10)
        set(v) = prefs.edit().putInt(KEY_TIMEOUT, v.coerceIn(2, 60)).apply()

    var maxRetry: Int
        get() = prefs.getInt(KEY_MAX_RETRY, 3)
        set(v) = prefs.edit().putInt(KEY_MAX_RETRY, v.coerceIn(1, 10)).apply()

    /** host yang diprobe, dipisah spasi (seperti PING_TARGET) */
    var pingTargets: String
        get() = prefs.getString(KEY_TARGETS, "www.gstatic.com connectivitycheck.gstatic.com")!!
        set(v) = prefs.edit().putString(KEY_TARGETS, v).apply()

    /**
     * IP publik yang diharapkan (hasil inject IP). Kosong = lewati pemeriksaan IP.
     * Bisa lebih dari satu, dipisah spasi. Cocok jika IP terdeteksi ada di daftar,
     * atau jika entri berupa prefix CIDR yang memuat IP tersebut.
     */
    var expectedIp: String
        get() = prefs.getString(KEY_EXPECTED_IP, "")!!
        set(v) = prefs.edit().putString(KEY_EXPECTED_IP, v.trim()).apply()

    /** endpoint yang mengembalikan IP publik sebagai teks polos */
    var ipEchoUrl: String
        get() = prefs.getString(KEY_IP_ECHO, "https://api.ipify.org")!!
        set(v) = prefs.edit().putString(KEY_IP_ECHO, v).apply()

    /**
     * Ukur IP publik lewat network seluler langsung (menembus VPN).
     * Wajib true bila memakai VPN: tanpa ini yang terbaca hanyalah IP server VPN
     * yang tidak pernah berubah, sehingga pergantian IP operator tidak terdeteksi.
     */
    var checkIpOnCellular: Boolean
        get() = prefs.getBoolean(KEY_IP_ON_CELL, true)
        set(v) = prefs.edit().putBoolean(KEY_IP_ON_CELL, v).apply()

    /** padanan AIRPLANE_MODE_RADIOS; `wifi` sengaja tidak ada agar hotspot bertahan */
    var airplaneRadios: String
        get() = prefs.getString(KEY_RADIOS, "cell,bluetooth,nfc,wimax")!!
        set(v) = prefs.edit().putString(KEY_RADIOS, v).apply()

    /** pertahankan hotspot yang sedang aktif melewati toggle */
    var keepHotspot: Boolean
        get() = prefs.getBoolean(KEY_KEEP_HOTSPOT, true)
        set(v) = prefs.edit().putBoolean(KEY_KEEP_HOTSPOT, v).apply()

    /** jangan toggle saat ada panggilan aktif */
    var skipWhenInCall: Boolean
        get() = prefs.getBoolean(KEY_SKIP_CALL, true)
        set(v) = prefs.edit().putBoolean(KEY_SKIP_CALL, v).apply()

    /** lama mode pesawat menyala sebelum dimatikan lagi (detik) */
    var airplaneHoldSec: Int
        get() = prefs.getInt(KEY_HOLD, 3)
        set(v) = prefs.edit().putInt(KEY_HOLD, v.coerceIn(1, 30)).apply()

    /** jeda setelah refresh sebelum siklus normal dilanjutkan (detik) */
    var cooldownSec: Int
        get() = prefs.getInt(KEY_COOLDOWN, 15)
        set(v) = prefs.edit().putInt(KEY_COOLDOWN, v.coerceIn(0, 600)).apply()

    /** gunakan `cmd connectivity airplane-mode` alih-alih Settings.Global */
    var useCmdConnectivity: Boolean
        get() = prefs.getBoolean(KEY_USE_CMD, false)
        set(v) = prefs.edit().putBoolean(KEY_USE_CMD, v).apply()

    /**
     * Penanda bahwa siklus mode pesawat sedang berjalan. Bila proses mati di
     * tengah toggle, flag ini tetap true sehingga saat start berikutnya aplikasi
     * tahu harus mematikan mode pesawat yang tertinggal menyala.
     */
    var airplaneToggleInProgress: Boolean
        get() = prefs.getBoolean(KEY_TOGGLE_PROGRESS, false)
        // commit() disengaja, bukan apply(): penulisan harus selesai sebelum
        // radio dimatikan, karena apply() yang asinkron bisa hilang jika proses
        // dibunuh tepat setelahnya - justru saat flag ini paling dibutuhkan.
        set(v) {
            prefs.edit().putBoolean(KEY_TOGGLE_PROGRESS, v).commit()
        }

    /** total refresh sejak dipasang, untuk ditampilkan di UI */
    var totalRefresh: Int
        get() = prefs.getInt(KEY_TOTAL_REFRESH, 0)
        set(v) = prefs.edit().putInt(KEY_TOTAL_REFRESH, v).apply()

    /** simpan nilai airplane_mode_radios asli agar bisa dipulihkan */
    var originalRadios: String?
        get() = prefs.getString(KEY_ORIG_RADIOS, null)
        set(v) = prefs.edit().putString(KEY_ORIG_RADIOS, v).apply()

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_TIMEOUT = "timeout"
        private const val KEY_MAX_RETRY = "max_retry"
        private const val KEY_TARGETS = "ping_targets"
        private const val KEY_EXPECTED_IP = "expected_ip"
        private const val KEY_IP_ECHO = "ip_echo"
        private const val KEY_IP_ON_CELL = "ip_on_cellular"
        private const val KEY_RADIOS = "airplane_radios"
        private const val KEY_KEEP_HOTSPOT = "keep_hotspot"
        private const val KEY_SKIP_CALL = "skip_call"
        private const val KEY_HOLD = "hold_sec"
        private const val KEY_COOLDOWN = "cooldown"
        private const val KEY_USE_CMD = "use_cmd"
        private const val KEY_ORIG_RADIOS = "orig_radios"
        private const val KEY_TOGGLE_PROGRESS = "toggle_in_progress"
        private const val KEY_TOTAL_REFRESH = "total_refresh"
    }
}
