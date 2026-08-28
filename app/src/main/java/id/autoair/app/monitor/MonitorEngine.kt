package id.autoair.app.monitor

import android.content.Context
import id.autoair.app.config.ConfigStore
import id.autoair.app.shizuku.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

    /**
     * Berapa kali refresh berturut-turut gagal memulihkan koneksi.
     * Dipakai untuk backoff: bila jaringan operator memang sedang down,
     * toggle tiap 60 detik hanya menguras baterai tanpa menolong.
     */
    private var consecutiveFailures = 0

    private fun currentInterval(): Int {
        val base = config.intervalSec
        return when {
            consecutiveFailures >= 10 -> base * 10
            consecutiveFailures >= 5 -> base * 5
            consecutiveFailures >= 3 -> base * 2
            else -> base
        }.coerceAtMost(1800)
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

            runCycle()

            delay(currentInterval() * 1000L)
        }

        update { it.copy(running = false, statusText = "Berhenti") }
    }

    private suspend fun runCycle() {
        if (config.skipWhenInCall && callGuard.isInCall()) {
            Logger.info("panggilan aktif, refresh ditunda")
            update { it.copy(statusText = "Ditunda (panggilan aktif)") }
            return
        }

        var problem: ConnectivityProbe.Result? = null

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

            if (attempt < config.maxRetry) {
                Logger.warn("percobaan ulang ${attempt + 1}/${config.maxRetry}")
                delay(2000)
            }
        }

        val reason = when (val p = problem) {
            is ConnectivityProbe.Result.IpMismatch -> "IP tidak cocok (${p.actual})"
            is ConnectivityProbe.Result.NoInternet -> "tidak ada internet"
            else -> "tidak diketahui"
        }

        consecutiveFailures++
        update { it.copy(healthy = false) }
        if (consecutiveFailures >= 3) {
            Logger.warn(
                "gagal $consecutiveFailures kali berturut-turut, " +
                    "jeda diperpanjang jadi ${currentInterval()}s untuk hemat baterai"
            )
        }
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
        Logger.warn("refresh koneksi - alasan: $reason")
        update { it.copy(statusText = "Refresh: $reason") }

        val hotspotWasActive = if (config.keepHotspot) hotspot.capture() else false

        if (!airplane.enable()) {
            Logger.error("gagal menyalakan mode pesawat, refresh dibatalkan")
            update { it.copy(statusText = "Gagal menyalakan mode pesawat") }
            return@withContext
        }

        // Tandai bahwa kita sedang mematikan radio; dipakai untuk pemulihan
        // otomatis bila proses mati sebelum sempat mengembalikannya.
        config.airplaneToggleInProgress = true

        delay(config.airplaneHoldSec * 1000L)

        var off = airplane.disable()
        if (!off) {
            // Percobaan ulang: membiarkan mode pesawat menyala jauh lebih buruk
            // daripada gagal refresh, jadi coba beberapa kali sebelum menyerah.
            for (i in 1..3) {
                Logger.error("gagal mematikan mode pesawat, percobaan ulang $i/3")
                delay(1500)
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
            return@withContext
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
        Logger.info("refresh selesai pada $stamp")

        delay(config.cooldownSec * 1000L)
    }

    /**
     * Jaring pengaman saat service start: jika proses sebelumnya mati di tengah
     * toggle, mode pesawat bisa tertinggal menyala. Matikan segera.
     */
    private suspend fun recoverStuckAirplaneMode() {
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

    private suspend fun awaitDataReady(timeoutMs: Long = 30000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe.hasUsableTransport()) {
                Logger.info("data tersambung kembali - ${probe.dataStateSummary()}")
                return
            }
            delay(1000)
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
}
