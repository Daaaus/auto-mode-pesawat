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
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

        bindButtons()
        observeState()
        requestNotificationPermission()
    }

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

        b.btnToggleAdvanced.setOnClickListener {
            val show = b.boxAdvanced.visibility != View.VISIBLE
            b.boxAdvanced.visibility = if (show) View.VISIBLE else View.GONE
            b.btnToggleAdvanced.text =
                if (show) "Sembunyikan lanjutan" else "Pengaturan lanjutan"
        }

        b.btnSave.setOnClickListener { saveConfig() }

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

        b.btnTestRefresh.setOnClickListener { confirmTestRefresh() }
    }

    /**
     * Uji refresh benar-benar mematikan radio sesaat, jadi konfirmasi dulu.
     * Tanpa ini pengguna bisa tidak sengaja memutus panggilan atau unduhan.
     */
    private fun confirmTestRefresh() {
        if (!ShizukuBridge.isReady()) {
            toast("Shizuku belum siap")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Uji refresh sekarang?")
            .setMessage(
                "Mode pesawat akan dinyalakan ${config.airplaneHoldSec} detik lalu " +
                    "dimatikan lagi. Koneksi akan terputus sebentar."
            )
            .setPositiveButton("Jalankan") { _, _ ->
                saveConfig(silent = true)
                config.enabled = true
                b.switchEnabled.isChecked = true
                NetMonitorService.start(this)
                NetMonitorService.testRefresh(this)
                toast("Uji dijalankan, lihat log")
            }
            .setNegativeButton("Batal", null)
            .show()
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

        b.etInterval.setText(config.intervalSec.toString())
        b.etTimeout.setText(config.timeoutSec.toString())
        b.etRetry.setText(config.maxRetry.toString())
        b.etTargets.setText(config.pingTargets)
        b.etExpectedIp.setText(config.expectedIp)
        b.etIpEcho.setText(config.ipEchoUrl)
        b.etRadios.setText(config.airplaneRadios)
        b.switchHotspot.isChecked = config.keepHotspot
        b.switchCallGuard.isChecked = config.skipWhenInCall
        b.switchUseCmd.isChecked = config.useCmdConnectivity
        b.switchIpOnCellular.isChecked = config.checkIpOnCellular

        b.tvTotalRefresh.text = config.totalRefresh.toString()
    }

    private fun saveConfig(silent: Boolean = false) {
        config.intervalSec = b.etInterval.text.toString().toIntOrNull() ?: 60
        config.timeoutSec = b.etTimeout.text.toString().toIntOrNull() ?: 10
        config.maxRetry = b.etRetry.text.toString().toIntOrNull() ?: 3
        config.pingTargets = b.etTargets.text.toString().ifBlank { "www.gstatic.com" }
        config.expectedIp = b.etExpectedIp.text.toString()
        config.ipEchoUrl = b.etIpEcho.text.toString().ifBlank { "https://api.ipify.org" }
        config.airplaneRadios = b.etRadios.text.toString().ifBlank { "cell,bluetooth,nfc,wimax" }
        config.keepHotspot = b.switchHotspot.isChecked
        config.skipWhenInCall = b.switchCallGuard.isChecked
        config.useCmdConnectivity = b.switchUseCmd.isChecked
        config.checkIpOnCellular = b.switchIpOnCellular.isChecked
        if (!silent) toast("Tersimpan")
        loadConfigIntoUi()
    }

    // -------------------------------------------------------------------- state

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Logger.lines.collect { lines ->
                        b.tvLog.text =
                            if (lines.isEmpty()) "Belum ada aktivitas."
                            else lines.takeLast(80).joinToString("\n")
                        b.scrollLog.post { b.scrollLog.fullScroll(View.FOCUS_DOWN) }
                    }
                }
                launch {
                    MonitorState.state.collect { st ->
                        b.tvStatus.text = st.statusText
                        b.tvStatusDetail.text = buildDetail(st)
                        b.tvTotalRefresh.text = config.totalRefresh.toString()
                        b.tvLastRefresh.text = st.lastRefresh ?: "—"
                        b.dotStatus.setBackgroundResource(
                            when {
                                !config.enabled -> R.drawable.dot_grey
                                st.waitingForShizuku -> R.drawable.dot_amber
                                st.healthy == true -> R.drawable.dot_green
                                st.healthy == false -> R.drawable.dot_red
                                else -> R.drawable.dot_grey
                            }
                        )
                    }
                }
            }
        }
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
        b.tvShizuku.text = "Shizuku: ${ShizukuBridge.statusText(this)}"
        b.btnShizuku.text = if (ready) "Cek ulang" else "Hubungkan"

        val pm = getSystemService(PowerManager::class.java)
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        b.tvBattery.text =
            if (ignoring) "Baterai: sudah dikecualikan" else "Baterai: perlu dikecualikan"
        b.btnBattery.visibility = if (ignoring) View.GONE else View.VISIBLE

        if (!config.enabled) {
            b.tvStatus.text = "Berhenti"
            b.tvStatusDetail.text = "Belum aktif"
            b.dotStatus.setBackgroundResource(R.drawable.dot_grey)
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
