package id.autoair.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import id.autoair.app.R
import id.autoair.app.config.ConfigStore
import id.autoair.app.databinding.ActivityMainBinding
import id.autoair.app.monitor.Logger
import id.autoair.app.monitor.MonitorState
import id.autoair.app.service.NetMonitorService
import id.autoair.app.shizuku.ShizukuBridge
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var config: ConfigStore

    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, result ->
            runOnUiThread {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    ShizukuBridge.grantSecureSettings(this)
                    toast("Shizuku terhubung")
                } else {
                    toast("Izin Shizuku ditolak")
                }
                refreshStatus()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        config = ConfigStore(this)

        runCatching { Shizuku.addRequestPermissionResultListener(permListener) }

        b.tvVersion.text = "v${appVersion()}"
        bindButtons()
        observeState()
        requestNotificationPermission()
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permListener) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        loadConfigIntoUi()
        refreshStatus()
    }

    // ------------------------------------------------------------------ actions

    private fun bindButtons() {
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        b.btnTweaks.setOnClickListener {
            startActivity(Intent(this, TweaksActivity::class.java))
        }

        b.btnShizuku.setOnClickListener {
            when {
                !ShizukuBridge.isAvailable() -> showShizukuHelp()
                !ShizukuBridge.hasPermission() -> ShizukuBridge.requestPermission(REQ_SHIZUKU)
                else -> {
                    ShizukuBridge.grantSecureSettings(this)
                    toast("Shizuku sudah siap")
                }
            }
            refreshStatus()
        }

        b.btnBattery.setOnClickListener { requestIgnoreBattery() }

        b.btnClearLog.setOnClickListener {
            Logger.clear()
            toast("Log dihapus")
        }

        b.btnCopyLog.setOnClickListener {
            val text = Logger.snapshot().ifBlank { "(log kosong)" }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", text))
            toast("Log disalin")
        }
    }

    private fun showShizukuHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Shizuku belum berjalan")
            .setMessage(
                "Aplikasi ini butuh Shizuku untuk mengubah mode pesawat.\n\n" +
                    "1. Pasang aplikasi Shizuku\n" +
                    "2. Aktifkan Wireless debugging di Opsi pengembang\n" +
                    "3. Tekan Start di Shizuku\n" +
                    "4. Kembali ke sini dan tekan Hubungkan\n\n" +
                    "Catatan: Shizuku berhenti setiap kali HP dimatikan, " +
                    "jadi perlu dijalankan lagi setelah reboot."
            )
            .setPositiveButton("Buka Shizuku") { _, _ ->
                val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (i != null) startActivity(i) else toast("Shizuku belum terpasang")
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    // ------------------------------------------------------------------- config

    private fun loadConfigIntoUi() {
        b.switchEnabled.setOnCheckedChangeListener(null)
        b.switchEnabled.isChecked = config.enabled
        b.switchEnabled.setOnCheckedChangeListener { _, checked ->
            config.enabled = checked
            if (checked) {
                if (!ShizukuBridge.isReady()) toast("Shizuku belum siap, service menunggu")
                NetMonitorService.start(this)
            } else {
                NetMonitorService.stop(this)
            }
            refreshStatus()
        }

        b.tvTotalRefresh.text = config.totalRefresh.toString()
    }

    // -------------------------------------------------------------------- state

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Logger.lines.collect { lines ->
                        // Tangkap posisi SEBELUM teks berubah: kalau pengguna sedang
                        // membaca log lama (tidak di dasar), jangan ditarik ke bawah.
                        val stickToBottom = isLogNearBottom()
                        renderLog(lines)
                        if (stickToBottom) {
                            b.scrollLog.post {
                                val child = b.scrollLog.getChildAt(0)
                                if (child != null) {
                                    b.scrollLog.smoothScrollTo(0, child.bottom)
                                }
                            }
                        }
                    }
                }
                launch {
                    MonitorState.state.collect { st ->
                        b.tvStatus.text = st.statusText
                        b.tvStatusDetail.text = buildDetail(st)
                        b.tvTotalRefresh.text = config.totalRefresh.toString()
                        b.tvLastRefresh.text = st.lastRefresh ?: "—"
                        b.pulseStatus.setMode(pulseModeFor(st))
                    }
                }
            }
        }
    }

    private fun pulseModeFor(st: MonitorState.Snapshot): StatusPulseView.Mode = when {
        !config.enabled -> StatusPulseView.Mode.OFF
        st.waitingForShizuku -> StatusPulseView.Mode.WARN
        st.healthy == true -> StatusPulseView.Mode.OK
        st.healthy == false -> StatusPulseView.Mode.BAD
        else -> StatusPulseView.Mode.WARN
    }

    /** True bila log sedang berada (mendekati) dasar, sehingga aman auto-scroll. */
    private fun isLogNearBottom(): Boolean {
        val sv = b.scrollLog
        val child = sv.getChildAt(0) ?: return true
        val threshold = (48 * resources.displayMetrics.density).toInt()
        return child.bottom - (sv.height + sv.scrollY) <= threshold
    }

    /** Warnai tiap baris log berdasarkan levelnya: [INFO]/[WARN]/[ERROR]. */
    private fun renderLog(lines: List<String>) {
        if (lines.isEmpty()) {
            b.tvLog.text = "Belum ada aktivitas."
            b.tvLog.setTextColor(ContextCompat.getColor(this, R.color.log_info))
            return
        }
        val timeColor = ContextCompat.getColor(this, R.color.log_time)
        val infoColor = ContextCompat.getColor(this, R.color.log_info)
        val warnColor = ContextCompat.getColor(this, R.color.log_warn)
        val errorColor = ContextCompat.getColor(this, R.color.log_error)

        val sb = SpannableStringBuilder()
        lines.takeLast(120).forEachIndexed { i, raw ->
            val levelColor = when {
                raw.contains("[ERROR]") -> errorColor
                raw.contains("[WARN]") -> warnColor
                else -> infoColor
            }
            val start = sb.length
            sb.append(raw)

            // Timestamp (8 char "HH:mm:ss") lebih redup.
            if (raw.length >= 8) {
                sb.setSpan(
                    ForegroundColorSpan(timeColor),
                    start, start + 8,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            // Sisa baris mengikuti warna level.
            val msgStart = (start + 8).coerceAtMost(sb.length)
            sb.setSpan(
                ForegroundColorSpan(levelColor),
                msgStart, sb.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (i != lines.size - 1) sb.append('\n')
        }
        b.tvLog.text = sb
    }

    private fun buildDetail(st: MonitorState.Snapshot): String = when {
        !config.enabled -> "Belum aktif"
        st.waitingForShizuku -> "Jalankan Shizuku untuk melanjutkan"
        st.lastReason != null -> "Terakhir: ${st.lastReason}"
        st.healthy == true -> "Internet normal"
        else -> "Memantau..."
    }

    private fun refreshStatus() {
        val ready = ShizukuBridge.isReady()
        b.tvShizuku.text = ShizukuBridge.statusText(this)
        b.btnShizuku.text = if (ready) "Cek ulang" else "Hubungkan"
        b.icShizuku.setImageResource(if (ready) R.drawable.ic_check else R.drawable.ic_alert)

        val pm = getSystemService(PowerManager::class.java)
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        b.tvBattery.text =
            if (ignoring) "Sudah dikecualikan dari optimasi" else "Perlu dikecualikan agar tidak dimatikan"
        b.btnBattery.visibility = if (ignoring) View.GONE else View.VISIBLE
        b.icBattery.setImageResource(if (ignoring) R.drawable.ic_check else R.drawable.ic_alert)

        if (!config.enabled) {
            b.tvStatus.text = "Berhenti"
            b.tvStatusDetail.text = "Belum aktif"
            b.pulseStatus.setMode(StatusPulseView.Mode.OFF)
        }
        b.tvTotalRefresh.text = config.totalRefresh.toString()
    }

    // -------------------------------------------------------------- permissions

    private fun requestNotificationPermission() {
        val want = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) want += Manifest.permission.POST_NOTIFICATIONS

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) want += Manifest.permission.READ_PHONE_STATE

        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), REQ_PERMS)
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Sudah dikecualikan")
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure { toast("Buka Setelan > Baterai secara manual") }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_SHIZUKU = 100
        private const val REQ_PERMS = 101
    }
}
