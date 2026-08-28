package id.autoair.app.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Padanan `config.ini` pada skrip shell asli.
 */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("autoair_config", Context.MODE_PRIVATE)

    init {
        // Migrasi ke profil cepat. Instalasi lama menyimpan timeout 10s /
        // cooldown 15s / hold 3s yang membuat satu siklus refresh bisa lebih
        // dari satu menit. Tanpa migrasi paksa, pengguna lama tidak akan pernah
        // merasakan perbaikan karena nilai lambat itu sudah ada di prefs.
        if (prefs.getInt(KEY_SPEED_REV, 0) < SPEED_REV) {
            prefs.edit()
                .putInt(KEY_TIMEOUT, DEF_TIMEOUT)
                .putInt(KEY_COOLDOWN, DEF_COOLDOWN)
                .putInt(KEY_HOLD, DEF_HOLD)
                .putInt(KEY_MAX_RETRY, DEF_MAX_RETRY)
                .putInt(KEY_RETRY_GAP_MS, DEF_RETRY_GAP_MS)
                .putInt(KEY_UNHEALTHY_INTERVAL, DEF_UNHEALTHY_INTERVAL)
                .putBoolean(KEY_AGGRESSIVE, true)
                .putInt(KEY_SPEED_REV, SPEED_REV)
                .apply()
        }
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** detik antar siklus pemantauan */
    var intervalSec: Int
        get() = prefs.getInt(KEY_INTERVAL, 60)
        set(v) = prefs.edit().putInt(KEY_INTERVAL, v.coerceIn(5, 3600)).apply()

    /**
     * Mode agresif: saat internet terdeteksi mati, cek ulang jauh lebih cepat,
     * tahan mode pesawat lebih lama secara bertahap, dan kick data seluler.
     */
    var aggressive: Boolean
        get() = prefs.getBoolean(KEY_AGGRESSIVE, true)
        set(v) = prefs.edit().putBoolean(KEY_AGGRESSIVE, v).apply()

    /**
     * Interval saat kondisi BERMASALAH (detik). Dipakai menggantikan
     * [intervalSec] selama internet masih mati, supaya reaksi cepat.
     */
    var unhealthyIntervalSec: Int
        get() = prefs.getInt(KEY_UNHEALTHY_INTERVAL, DEF_UNHEALTHY_INTERVAL)
        set(v) = prefs.edit().putInt(KEY_UNHEALTHY_INTERVAL, v.coerceIn(2, 600)).apply()

    /** detik timeout tiap probe */
    var timeoutSec: Int
        get() = prefs.getInt(KEY_TIMEOUT, DEF_TIMEOUT)
        set(v) = prefs.edit().putInt(KEY_TIMEOUT, v.coerceIn(1, 60)).apply()

    var maxRetry: Int
        get() = prefs.getInt(KEY_MAX_RETRY, DEF_MAX_RETRY)
        set(v) = prefs.edit().putInt(KEY_MAX_RETRY, v.coerceIn(1, 10)).apply()

    /** jeda antar percobaan probe (milidetik) */
    var retryGapMs: Int
        get() = prefs.getInt(KEY_RETRY_GAP_MS, DEF_RETRY_GAP_MS)
        set(v) = prefs.edit().putInt(KEY_RETRY_GAP_MS, v.coerceIn(0, 10000)).apply()

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
        get() = prefs.getInt(KEY_HOLD, DEF_HOLD)
        set(v) = prefs.edit().putInt(KEY_HOLD, v.coerceIn(1, 30)).apply()

    /** jeda setelah refresh sebelum siklus normal dilanjutkan (detik) */
    var cooldownSec: Int
        get() = prefs.getInt(KEY_COOLDOWN, DEF_COOLDOWN)
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

    /**
     * Penanda bahwa siklus kick data seluler sedang berjalan. Sama seperti
     * [airplaneToggleInProgress]: bila proses mati antara `svc data disable`
     * dan `enable`, data akan tertinggal MATI, jadi flag ini dipakai untuk
     * memulihkannya saat start berikutnya. commit() disengaja.
     */
    var dataKickInProgress: Boolean
        get() = prefs.getBoolean(KEY_DATA_KICK, false)
        set(v) {
            prefs.edit().putBoolean(KEY_DATA_KICK, v).commit()
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
        // ---- Profil cepat (v1.8) ----------------------------------------------
        // Naikkan SPEED_REV bila default di bawah diubah lagi, supaya instalasi
        // lama ikut termigrasi.
        private const val SPEED_REV = 2
        const val DEF_TIMEOUT = 3          // 10 -> 3 detik
        const val DEF_COOLDOWN = 3         // 15 -> 3 detik
        const val DEF_HOLD = 2             // 3  -> 2 detik
        const val DEF_MAX_RETRY = 2        // 3  -> 2 percobaan
        const val DEF_RETRY_GAP_MS = 400   // 2000 -> 400 ms
        const val DEF_UNHEALTHY_INTERVAL = 8   // cek tiap 8 dtk selama internet mati

        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_TIMEOUT = "timeout"
        private const val KEY_MAX_RETRY = "max_retry"
        private const val KEY_RETRY_GAP_MS = "retry_gap_ms"
        private const val KEY_UNHEALTHY_INTERVAL = "unhealthy_interval"
        private const val KEY_AGGRESSIVE = "aggressive"
        private const val KEY_SPEED_REV = "speed_rev"
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
        private const val KEY_DATA_KICK = "data_kick_in_progress"
        private const val KEY_TOTAL_REFRESH = "total_refresh"
    }
}
