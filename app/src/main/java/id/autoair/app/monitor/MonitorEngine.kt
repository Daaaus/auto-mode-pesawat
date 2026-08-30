package id.autoair.app.monitor

import android.content.Context
import id.autoair.app.config.ConfigStore
import id.autoair.app.shizuku.ShizukuBridge
import id.autoair.app.tweak.Tweaks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Loop utama, padanan `while true` pada skrip shell.
 */
class MonitorEngine(
    private val context: Context,
    private val config: ConfigStore
) {

    private val airplane = AirplaneModeController(context, config)
    private val hotspot = HotspotKeeper(context)
    private val probe = ConnectivityProbe(context, config)
    private val callGuard = CallGuard(context)

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    /** Status dibagikan lewat holder global agar UI melihat data yang sama. */
    val state: StateFlow<MonitorState.Snapshot> = MonitorState.state

    private var radiosApplied = false
    private var recoveryChecked = false
    private var tweaksApplied = false

    /**
     * Kanal pembangun loop. Tanpa ini, hilangnya internet tepat setelah satu
     * pengecekan baru disadari pada siklus berikutnya (bisa 60 detik kemudian).
     * Sistem sudah tahu lebih dulu lewat NetworkCallback, jadi loop dibangunkan
     * saat itu juga. CONFLATED: cukup satu sinyal tertunda.
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var refreshing = false
    private var lastRefreshAt = 0L
    private var lastWakeAt = 0L

    /**
     * Minta loop bangun lebih awal. Diabaikan bila kita sendiri yang sedang
     * mematikan radio (toggle kita pasti memicu onLost), atau bila baru saja
     * refresh / baru saja dibangunkan - supaya tidak jadi loop rapat.
     */
    fun requestWake(reason: String) {
        val now = System.currentTimeMillis()
        if (refreshing) return
        if (now - lastRefreshAt < MIN_GAP_AFTER_REFRESH_MS) return
        if (now - lastWakeAt < WAKE_THROTTLE_MS) return
        lastWakeAt = now
        if (wake.trySend(Unit).isSuccess) {
            Logger.info("dibangunkan: $reason")
        }
    }

    /**
     * Berapa kali refresh berturut-turut gagal memulihkan koneksi.
     * Dipakai untuk backoff: bila jaringan operator memang sedang down,
     * toggle tiap 60 detik hanya menguras baterai tanpa menolong.
     */
    private var consecutiveFailures = 0

    /**
     * Interval siklus berikutnya.
     *
     * Sebelumnya di sini ada backoff yang MELAMBAT saat gagal beruntun (3x gagal
     * -> jeda 2x, 10x -> 10x). Itu bertolak belakang dengan tujuan aplikasi:
     * justru ketika internet mati kita ingin bereaksi paling cepat. Sekarang
     * selama kondisi bermasalah dipakai interval khusus yang jauh lebih pendek.
     *
     * Rem tetap ada, tapi hanya untuk kasus ekstrem (operator benar-benar down
     * puluhan kali berturut-turut) supaya baterai tidak habis sia-sia - dan
     * batasnya jauh lebih longgar dari sebelumnya.
     */
    private fun currentInterval(): Int {
        val healthy = MonitorState.state.value.healthy
        if (!config.aggressive) return config.intervalSec

        if (healthy == false) {
            val fast = config.unhealthyIntervalSec
            // Rem sangat longgar: baru melambat setelah 20 kegagalan berturut.
            return when {
                consecutiveFailures >= 40 -> fast * 6
                consecutiveFailures >= 20 -> fast * 3
                else -> fast
            }.coerceAtMost(300)
        }
        return config.intervalSec
    }

    /**
     * Lama mode pesawat ditahan. Bila toggle singkat berulang kali tidak
     * menolong, kemungkinan radio butuh waktu lebih lama untuk benar-benar
     * lepas dari sel yang bermasalah, jadi durasinya dinaikkan bertahap.
     */
    private fun currentHoldSec(): Int {
        val base = config.airplaneHoldSec
        if (!config.aggressive) return base
        return when {
            consecutiveFailures >= 8 -> base + 4
            consecutiveFailures >= 4 -> base + 2
            else -> base
        }.coerceAtMost(15).coerceAtLeast(1)
    }

    suspend fun run(scope: CoroutineScope) {
        Logger.info("service dimulai")
        update { it.copy(running = true, statusText = "Memulai...") }

        while (scope.isActive) {
            if (!config.enabled) {
                update { it.copy(statusText = "Dinonaktifkan") }
                delay(5000)
                continue
            }

            if (!ShizukuBridge.isReady()) {
                if (!MonitorState.state.value.waitingForShizuku) {
                    Logger.warn("Shizuku belum siap, menunggu...")
                }
                update {
                    it.copy(
                        statusText = "Menunggu Shizuku",
                        waitingForShizuku = true
                    )
                }
                delay(5000)
                continue
            }

            if (MonitorState.state.value.waitingForShizuku) {
                Logger.info("Shizuku tersedia, melanjutkan pemantauan")
                update { it.copy(waitingForShizuku = false) }
            }

            // Terapkan whitelist radio sekali agar hotspot tidak ikut mati.
            if (!radiosApplied && config.keepHotspot) {
                ShizukuBridge.grantSecureSettings(context)
                radiosApplied = airplane.applyRadioWhitelist()
            }

            if (!recoveryChecked) {
                recoveryChecked = true
                recoverStuckAirplaneMode()
            }

            // Terapkan ulang tweak optimasi yang aktif. Settings.Global umumnya
            // bertahan setelah reboot, tapi ada ROM yang meresetnya.
            if (!tweaksApplied) {
                tweaksApplied = Tweaks.applyPersistent(config)
            }

            runCycle()

            // Tidur normal, tapi bisa dipotong oleh event jaringan.
            waitNextCycle(currentInterval() * 1000L)
        }

        update { it.copy(running = false, statusText = "Berhenti") }
    }

    /**
     * Tidur sampai interval habis ATAU sampai ada sinyal bangun dari
     * NetworkCallback, mana yang lebih dulu.
     */
    private suspend fun waitNextCycle(millis: Long) {
        withTimeoutOrNull(millis) { wake.receive() }
    }

    private suspend fun runCycle() {
        if (config.skipWhenInCall && callGuard.isInCall()) {
            Logger.info("panggilan aktif, refresh ditunda")
            update { it.copy(statusText = "Ditunda (panggilan aktif)") }
            return
        }

        var problem: ConnectivityProbe.Result? = null
        val started = System.currentTimeMillis()

        for (attempt in 1..config.maxRetry) {
            update { it.copy(statusText = "Memeriksa koneksi ($attempt/${config.maxRetry})") }

            when (val result = probe.check()) {
                is ConnectivityProbe.Result.Healthy -> {
                    if (consecutiveFailures > 0) {
                        Logger.info("koneksi pulih setelah $consecutiveFailures kegagalan")
                    }
                    consecutiveFailures = 0
                    Logger.info("koneksi sehat - ${probe.dataStateSummary()}")
                    update { it.copy(statusText = "Koneksi sehat", healthy = true) }
                    return
                }
                is ConnectivityProbe.Result.NoInternet -> {
                    Logger.error("tidak ada internet: ${result.detail}")
                    problem = result
                }
                is ConnectivityProbe.Result.IpMismatch -> {
                    Logger.error("IP tidak cocok: ${result.actual} (harus ${result.expected})")
                    problem = result
                }
                is ConnectivityProbe.Result.IpUnknown -> {
                    // Internet jalan tetapi IP tidak terbaca. Refresh di sini justru
                    // memutus koneksi yang sehat, jadi siklus dilewati saja.
                    Logger.warn("IP tidak terbaca: ${result.detail} - refresh dilewati")
                    update { it.copy(statusText = "IP tidak terbaca") }
                    return
                }
            }

            // Bila sistem melaporkan tidak ada jaringan tervalidasi sama sekali,
            // percobaan ulang hampir pasti gagal juga: langsung refresh.
            if (probe.noValidatedNetwork()) {
                Logger.warn("tidak ada jaringan tervalidasi, percobaan ulang dilewati")
                break
            }

            if (attempt < config.maxRetry) {
                delay(config.retryGapMs.toLong())
            }
        }

        val reason = when (val p = problem) {
            is ConnectivityProbe.Result.IpMismatch -> "IP tidak cocok (${p.actual})"
            is ConnectivityProbe.Result.NoInternet -> "tidak ada internet"
            else -> "tidak diketahui"
        }

        consecutiveFailures++
        update { it.copy(healthy = false) }
        Logger.info("deteksi selesai dalam ${System.currentTimeMillis() - started} ms")
        refresh(reason)
    }

    /**
     * Padanan blok "Refreshing internet..." pada skrip.
     *
     * Seluruh siklus ON->OFF dibungkus NonCancellable. Tanpa ini, jika Android
     * membunuh service tepat saat jeda, mode pesawat akan menyala dan TIDAK
     * pernah dimatikan sehingga perangkat terjebak tanpa sinyal.
     */
    private suspend fun refresh(reason: String) = withContext(NonCancellable) {
        val t0 = System.currentTimeMillis()
        refreshing = true
        try {
            doRefresh(reason, t0)
        } finally {
            // Wajib di finally: ada beberapa jalur keluar awal, dan flag yang
            // tertinggal true akan mematikan pembangun loop secara permanen.
            refreshing = false
            lastRefreshAt = System.currentTimeMillis()
            // Buang sinyal bangun yang dipicu oleh toggle kita sendiri.
            wake.tryReceive()
        }
    }

    private suspend fun doRefresh(reason: String, t0: Long) {
        Logger.warn("refresh koneksi - alasan: $reason")
        update { it.copy(statusText = "Refresh: $reason") }

        val hotspotWasActive = if (config.keepHotspot) hotspot.capture() else false

        if (!airplane.enable()) {
            Logger.error("gagal menyalakan mode pesawat, refresh dibatalkan")
            update { it.copy(statusText = "Gagal menyalakan mode pesawat") }
            return
        }

        // Tandai bahwa kita sedang mematikan radio; dipakai untuk pemulihan
        // otomatis bila proses mati sebelum sempat mengembalikannya.
        config.airplaneToggleInProgress = true

        val hold = currentHoldSec()
        if (hold != config.airplaneHoldSec) {
            Logger.warn("gagal $consecutiveFailures kali - mode pesawat ditahan ${hold}s")
        }
        delay(hold * 1000L)

        var off = airplane.disable()
        if (!off) {
            // Percobaan ulang: membiarkan mode pesawat menyala jauh lebih buruk
            // daripada gagal refresh, jadi coba beberapa kali sebelum menyerah.
            for (i in 1..3) {
                Logger.error("gagal mematikan mode pesawat, percobaan ulang $i/3")
                delay(400)
                if (airplane.disable()) {
                    off = true
                    break
                }
            }
        }
        config.airplaneToggleInProgress = false

        if (!off) {
            Logger.error("MODE PESAWAT MASIH AKTIF - matikan manual!")
            update { it.copy(statusText = "GAGAL: mode pesawat masih aktif") }
            return
        }

        if (config.keepHotspot) {
            when (val outcome = hotspot.restore(hotspotWasActive)) {
                HotspotKeeper.Outcome.PRESERVED,
                HotspotKeeper.Outcome.RESTORED -> Logger.info("hotspot: ${outcome.name.lowercase()}")
                HotspotKeeper.Outcome.NOT_ACTIVE -> Unit
                else -> Logger.warn("hotspot tidak dapat dipulihkan (${outcome.name.lowercase()})")
            }
        }

        // Tunggu data kembali tersambung, padanan loop mDataConnectionState=2.
        awaitDataReady()

        // Bila setelah beberapa kali toggle koneksi tetap tidak pulih, radio
        // kemungkinan nyangkut di sel yang sama. Kick data seluler memaksa
        // attach ulang - lebih keras daripada sekadar toggle mode pesawat.
        // Ambang 2 (bukan 3): dengan profil instan tiap kegagalan sudah
        // berharga, menunggu satu kegagalan lagi hanya menunda pemulihan.
        if (config.aggressive && consecutiveFailures >= 2) {
            airplane.kickMobileData()
        }

        val stamp = timeFmt.format(Date())
        config.totalRefresh = config.totalRefresh + 1
        update {
            it.copy(
                statusText = "Selesai refresh",
                lastRefresh = stamp,
                lastReason = reason,
                refreshCount = it.refreshCount + 1
            )
        }
        Logger.info("refresh selesai dalam ${System.currentTimeMillis() - t0} ms ($stamp)")

        delay(config.cooldownSec * 1000L)
    }

    /**
     * Jaring pengaman saat service start: jika proses sebelumnya mati di tengah
     * toggle, mode pesawat bisa tertinggal menyala. Matikan segera.
     */
    private suspend fun recoverStuckAirplaneMode() {
        airplane.recoverStuckData()
        if (!config.airplaneToggleInProgress) return
        Logger.warn("terdeteksi toggle terputus sebelumnya")
        if (airplane.isAirplaneModeOn()) {
            Logger.warn("mode pesawat tertinggal aktif - mematikan")
            update { it.copy(statusText = "Memulihkan mode pesawat") }
            if (airplane.disable()) Logger.info("mode pesawat berhasil dimatikan")
            else Logger.error("gagal memulihkan - matikan manual")
        }
        config.airplaneToggleInProgress = false
    }

    private suspend fun awaitDataReady(timeoutMs: Long = 15000) {
        val t0 = System.currentTimeMillis()
        val deadline = t0 + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe.hasUsableTransport()) {
                Logger.info(
                    "data tersambung kembali dalam ${System.currentTimeMillis() - t0} ms " +
                        "- ${probe.dataStateSummary()}"
                )
                return
            }
            delay(100)
        }
        Logger.warn("data belum tersambung setelah ${timeoutMs / 1000}s")
    }

    /** Dipicu tombol "Uji sekarang" di UI. */
    suspend fun forceRefresh() {
        if (!ShizukuBridge.isReady()) {
            Logger.error("uji dibatalkan: Shizuku belum siap")
            return
        }
        if (config.skipWhenInCall && callGuard.isInCall()) {
            Logger.warn("uji dibatalkan: sedang menelepon")
            return
        }
        Logger.info("uji refresh manual")
        refresh("uji manual")
    }

    /** Dipanggil saat service berhenti. */
    fun onStop() {
        if (radiosApplied) {
            airplane.restoreRadioWhitelist()
            radiosApplied = false
        }
        update { it.copy(running = false, statusText = "Berhenti") }
        Logger.info("service dihentikan")
    }

    private fun update(block: (MonitorState.Snapshot) -> MonitorState.Snapshot) =
        MonitorState.update(block)

    companion object {
        /**
         * Jangan bangun tepat setelah refresh: radio memang baru saja diputus.
         * Dipendekkan dari 8s: siklus instan (hold 1s + cooldown 1s) sudah
         * selesai jauh sebelum ini, dan toggle pasca-refresh selalu memicu
         * onLost — jeda panjang hanya menunda reaksi terhadap gangguan nyata.
         */
        private const val MIN_GAP_AFTER_REFRESH_MS = 2500L

        /**
         * Batasi frekuensi pembangun agar badai event jaringan tidak jadi loop
         * rapat. 1.5s cukup meredam burst event tanpa menahan siklus perbaikan.
         */
        private const val WAKE_THROTTLE_MS = 1500L
    }
}
