package id.autoair.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import id.autoair.app.config.ConfigStore
import id.autoair.app.databinding.ActivitySettingsBinding
import id.autoair.app.service.NetMonitorService
import id.autoair.app.shizuku.ShizukuBridge

/**
 * Layar pengaturan. Seluruh konfigurasi dipindah ke sini agar layar utama
 * bersih: tinggal status, statistik, prasyarat, dan log.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var config: ConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        config = ConfigStore(this)

        b.btnBack.setOnClickListener { finish() }

        b.btnToggleAdvanced.setOnClickListener {
            val show = b.boxAdvanced.visibility != View.VISIBLE
            b.boxAdvanced.visibility = if (show) View.VISIBLE else View.GONE
            b.btnToggleAdvanced.text =
                if (show) "Sembunyikan lanjutan" else "Pengaturan lanjutan"
        }

        b.btnSave.setOnClickListener {
            saveConfig()
        }

        b.btnFastProfile.setOnClickListener {
            b.etTimeout.setText(ConfigStore.DEF_TIMEOUT.toString())
            b.etRetry.setText(ConfigStore.DEF_MAX_RETRY.toString())
            b.etRetryGap.setText(ConfigStore.DEF_RETRY_GAP_MS.toString())
            b.etHold.setText(ConfigStore.DEF_HOLD.toString())
            b.etCooldown.setText(ConfigStore.DEF_COOLDOWN.toString())
            b.etUnhealthyInterval.setText(ConfigStore.DEF_UNHEALTHY_INTERVAL.toString())
            b.switchAggressive.isChecked = true
            saveConfig(silent = true)
            toast("Profil cepat dipulihkan")
        }

        b.btnTestRefresh.setOnClickListener { confirmTestRefresh() }
    }

    override fun onResume() {
        super.onResume()
        loadConfigIntoUi()
    }

    private fun loadConfigIntoUi() {
        b.etInterval.setText(config.intervalSec.toString())
        b.etUnhealthyInterval.setText(config.unhealthyIntervalSec.toString())
        b.switchAggressive.isChecked = config.aggressive
        b.etTimeout.setText(config.timeoutSec.toString())
        b.etRetry.setText(config.maxRetry.toString())
        b.etRetryGap.setText(config.retryGapMs.toString())
        b.etHold.setText(config.airplaneHoldSec.toString())
        b.etCooldown.setText(config.cooldownSec.toString())
        b.etTargets.setText(config.pingTargets)
        b.etExpectedIp.setText(config.expectedIp)
        b.etIpEcho.setText(config.ipEchoUrl)
        b.etRadios.setText(config.airplaneRadios)
        b.switchHotspot.isChecked = config.keepHotspot
        b.switchCallGuard.isChecked = config.skipWhenInCall
        b.switchUseCmd.isChecked = config.useCmdConnectivity
        b.switchIpOnCellular.isChecked = config.checkIpOnCellular
    }

    private fun saveConfig(silent: Boolean = false) {
        config.intervalSec = b.etInterval.text.toString().toIntOrNull() ?: 60
        config.unhealthyIntervalSec = b.etUnhealthyInterval.text.toString().toIntOrNull()
            ?: ConfigStore.DEF_UNHEALTHY_INTERVAL
        config.aggressive = b.switchAggressive.isChecked
        config.timeoutSec = b.etTimeout.text.toString().toIntOrNull() ?: ConfigStore.DEF_TIMEOUT
        config.maxRetry = b.etRetry.text.toString().toIntOrNull() ?: ConfigStore.DEF_MAX_RETRY
        config.retryGapMs =
            b.etRetryGap.text.toString().toIntOrNull() ?: ConfigStore.DEF_RETRY_GAP_MS
        config.airplaneHoldSec = b.etHold.text.toString().toIntOrNull() ?: ConfigStore.DEF_HOLD
        config.cooldownSec = b.etCooldown.text.toString().toIntOrNull() ?: ConfigStore.DEF_COOLDOWN
        config.pingTargets =
            b.etTargets.text.toString().ifBlank { "www.gstatic.com connectivitycheck.gstatic.com" }
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
                NetMonitorService.start(this)
                NetMonitorService.testRefresh(this)
                toast("Uji dijalankan, lihat log di layar utama")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
