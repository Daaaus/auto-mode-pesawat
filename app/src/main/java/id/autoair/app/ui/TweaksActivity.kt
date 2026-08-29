package id.autoair.app.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import id.autoair.app.config.ConfigStore
import id.autoair.app.databinding.ActivityTweaksBinding
import id.autoair.app.shizuku.ShizukuBridge
import id.autoair.app.tweak.Tweaks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layar Optimasi: tweak non-root via Shizuku.
 *
 * Tweak bersistem langsung diterapkan saat switch berubah (tidak menunggu
 * tombol simpan), karena tidak ada yang lebih membingungkan daripada switch
 * yang terlihat aktif padahal belum berlaku.
 *
 * Pola pengikatan: nilai dimuat DULU, listener dipasang SETELAHNYA, supaya
 * memuat preferensi tidak dianggap perubahan oleh pengguna.
 */
class TweaksActivity : AppCompatActivity() {

    private lateinit var b: ActivityTweaksBinding
    private lateinit var config: ConfigStore

    /** Pilihan batas proses latar: label ke nilai (-1 = standar sistem). */
    private val bgLimits = listOf(
        "Standar sistem" to -1,
        "16 proses" to 16,
        "8 proses" to 8,
        "4 proses (ketat)" to 4
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTweaksBinding.inflate(layoutInflater)
        setContentView(b.root)
        config = ConfigStore(this)

        b.btnBack.setOnClickListener { finish() }

        setupCompileSection()
        setupPerformanceSection()
        setupKeepAliveSection()
    }

    // ------------------------------------------------------------- kompilasi

    private fun setupCompileSection() {
        b.spinCompileMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Tweaks.COMPILE_MODES.map { it.second }
        )
        b.spinCompileMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                b.tvCompileModeNote.text = Tweaks.COMPILE_MODES[pos].third
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        b.btnCompileNow.setOnClickListener { confirmCompile() }
        b.btnDexoptJob.setOnClickListener { runDexoptJob() }
        b.btnResetCompile.setOnClickListener {
            if (!requireShizuku()) return@setOnClickListener
            runLongTask("Mengembalikan profil kompilasi...") {
                Tweaks.resetCompilation(b.switchCompileAll.isChecked, packageName)
            }
        }
    }

    /** Kompilasi semua aplikasi berat dan lama, jadi wajib konfirmasi dulu. */
    private fun confirmCompile() {
        if (!requireShizuku()) return
        val mode = Tweaks.COMPILE_MODES[b.spinCompileMode.selectedItemPosition].first
        val all = b.switchCompileAll.isChecked
        if (!all) {
            startCompile(mode, false)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Kompilasi semua aplikasi?")
            .setMessage(
                "Mode $mode akan diterapkan ke SEMUA aplikasi. Prosesnya bisa " +
                    "puluhan menit, HP hangat, dan baterai terkuras. Mode speed/" +
                    "everything juga menambah pemakaian penyimpanan.\n\n" +
                    "Biarkan layar tetap menyala sampai selesai."
            )
            .setPositiveButton("Mulai") { _, _ -> startCompile(mode, true) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun startCompile(mode: String, all: Boolean) {
        runLongTask("Mengompilasi ($mode)... jangan tutup layar") {
            Tweaks.compile(mode, all, packageName)
        }
    }

    private fun runDexoptJob() {
        if (!requireShizuku()) return
        runLongTask("Dexopt latar belakang berjalan...") {
            Tweaks.runBackgroundDexopt()
        }
    }

    // --------------------------------------------------------------- kinerja

    private fun setupPerformanceSection() {
        // 1. Pasang adapter spinner.
        b.spinBgLimit.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            bgLimits.map { it.first }
        )

        // 2. Muat nilai tersimpan (sebelum listener aktif).
        b.switchFastAnim.isChecked = config.tweakFastAnim
        b.switchFreezer.isChecked = config.tweakFreezerOn
        b.switchWifiScan.isChecked = config.tweakWifiScanThrottleOff
        b.switchLockRefresh.isChecked = config.tweakLockRefreshRate
        b.switchFixedPerf.isChecked = config.tweakFixedPerfMode
        b.switchAdaptiveBatteryOff.isChecked = config.tweakAdaptiveBatteryOff
        if (config.tweakLockRefreshRateHz > 0f) {
            b.tvLockRefreshNote.text =
                "Terkunci di ${config.tweakLockRefreshRateHz.toInt()} Hz. " +
                    "Matikan lalu nyalakan lagi untuk memilih Hz lain."
        }
        b.spinBgLimit.setSelection(
            bgLimits.indexOfFirst { it.second == config.tweakBgProcessLimit }.coerceAtLeast(0)
        )

        // 3. Baru pasang listener. Callback pertama spinner berasal dari langkah
        //    2 (diposting setelah layout), jadi ditelan oleh penjaga `first`.
        var firstSpinnerCallback = true
        b.spinBgLimit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (firstSpinnerCallback) {
                    firstSpinnerCallback = false
                    return
                }
                if (!requireShizuku()) {
                    revertSpinnerToConfig()
                    return
                }
                val limit = bgLimits[pos].second
                config.tweakBgProcessLimit = limit
                applyQuick("batas proses") { Tweaks.setBackgroundProcessLimit(limit) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        b.switchFastAnim.setOnCheckedChangeListener { _, on ->
            if (!requireShizukuOrRevert(b.switchFastAnim, on)) return@setOnCheckedChangeListener
            config.tweakFastAnim = on
            applyQuick("animasi") { Tweaks.setFastAnimations(on) }
        }

        b.switchFreezer.setOnCheckedChangeListener { _, on ->
            if (!requireShizukuOrRevert(b.switchFreezer, on)) return@setOnCheckedChangeListener
            config.tweakFreezerOn = on
            applyQuick("freezer") { Tweaks.setFreezer(on) }
        }

        b.switchWifiScan.setOnCheckedChangeListener { _, on ->
            if (!requireShizukuOrRevert(b.switchWifiScan, on)) return@setOnCheckedChangeListener
            config.tweakWifiScanThrottleOff = on
            applyQuick("scan WiFi") { Tweaks.setWifiScanThrottleOff(on) }
        }

        b.switchLockRefresh.setOnCheckedChangeListener { _, on ->
            if (!requireShizukuOrRevert(b.switchLockRefresh, on)) return@setOnCheckedChangeListener
            if (on) {
                chooseRefreshRate()
            } else {
                config.tweakLockRefreshRate = false
                applyQuick("refresh rate adaptif") { Tweaks.setLockRefreshRate(false, 0f) }
            }
        }

        b.btnKillBg.setOnClickListener {
            if (!requireShizuku()) return@setOnClickListener
            applyQuick("kosongkan RAM") { Tweaks.killBackgroundApps() }
        }

        b.switchFixedPerf.setOnCheckedChangeListener { _, on ->
            if (!requireShizukuOrRevert(b.switchFixedPerf, on)) return@setOnCheckedChangeListener
            config.tweakFixedPerfMode = on
            applyQuick("mode performa") { Tweaks.setFixedPerformanceMode(on) }
        }

        b.switchAdaptiveBatteryOff.setOnCheckedChangeListener { _, on ->
            if (!requireShizukuOrRevert(b.switchAdaptiveBatteryOff, on)) {
                return@setOnCheckedChangeListener
            }
            config.tweakAdaptiveBatteryOff = on
            applyQuick("adaptive battery") { Tweaks.setAdaptiveBatteryOff(on) }
        }
    }

    // ------------------------------------------------------------- ketahanan

    private fun setupKeepAliveSection() {
        b.btnWhitelistDoze.setOnClickListener {
            if (!requireShizuku()) return@setOnClickListener
            applyQuick("whitelist doze") { Tweaks.whitelistSelfFromDoze(packageName) }
        }

        b.btnDebloat.setOnClickListener {
            if (!requireShizuku()) return@setOnClickListener
            startActivity(android.content.Intent(this, DebloatActivity::class.java))
        }

        // Thermal override memakai TOMBOL, bukan switch: statusnya tidak
        // dipersist (hilang saat reboot), jadi switch akan menampilkan
        // status palsu. Konfirmasi wajib - ini satu-satunya tweak yang bisa
        // merusak perangkat bila dipakai sembarangan.
        b.btnThermalOff.setOnClickListener {
            if (!requireShizuku()) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle("Matikan throttle termal?")
                .setMessage(
                    "Sistem tidak akan lagi memperlambat CPU saat panas. " +
                        "Panas berlebih bisa merusak baterai dan komponen secara " +
                        "permanen. Aktif sampai reboot atau dipulihkan manual.\n\n" +
                        "Lanjutkan hanya bila Anda paham risikonya."
                )
                .setPositiveButton("Saya paham, matikan") { _, _ ->
                    applyQuick("throttle termal") { Tweaks.setThermalOverride(true) }
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        b.btnThermalOn.setOnClickListener {
            if (!requireShizuku()) return@setOnClickListener
            applyQuick("throttle termal") { Tweaks.setThermalOverride(false) }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun requireShizuku(): Boolean {
        if (ShizukuBridge.isReady()) return true
        toast("Shizuku belum siap - hubungkan dulu di layar utama")
        return false
    }

    /**
     * Versi untuk switch: kembalikan posisi switch ke nilai tersimpan bila
     * Shizuku belum siap, supaya UI tidak berbohong soal status tweak.
     */
    private fun requireShizukuOrRevert(sw: MaterialSwitch, attempted: Boolean): Boolean {
        if (ShizukuBridge.isReady()) return true
        toast("Shizuku belum siap - hubungkan dulu di layar utama")
        revertSwitch(sw, attempted)
        return false
    }

    /**
     * Kembalikan switch ke posisi sebelum dicoba, lalu pasang ulang listener
     * (yang harus dilepas dulu supaya perubahan programatik tidak memicu loop).
     */
    private fun revertSwitch(sw: MaterialSwitch, attempted: Boolean) {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = !attempted
        setupPerformanceSection()
    }

    /** Semua refresh rate unik yang didukung layar, diurut menurun. */
    private fun supportedRefreshRates(): List<Float> {
        val modes = display?.supportedModes ?: return emptyList()
        return modes.map { it.refreshRate }.distinct().sortedDescending()
    }

    /**
     * Saat switch kunci refresh dinyalakan, pengguna memilih Hz dari mode yang
     * benar-benar didukung layar. Mengunci ke 60 di layar 120 Hz adalah tweak
     * hemat baterai yang sah, jadi tidak ada nilai yang "tidak berguna" - satu-
     * satunya kasus yang ditolak adalah layar tanpa pilihan mode sama sekali.
     */
    private fun chooseRefreshRate() {
        val rates = supportedRefreshRates()
        if (rates.size < 2) {
            toast("Layar ini tidak punya mode refresh lain untuk dikunci")
            revertSwitch(b.switchLockRefresh, attempted = true)
            return
        }
        val max = rates.first()
        val min = rates.last()
        val items = rates.map { hz ->
            buildString {
                append("${hz.toInt()} Hz")
                if (hz == max) append(" - paling mulus")
                if (hz == min && min != max) append(" - paling hemat")
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Kunci ke refresh rate")
            .setItems(items) { _, which ->
                val hz = rates[which]
                config.tweakLockRefreshRateHz = hz
                config.tweakLockRefreshRate = true
                b.tvLockRefreshNote.text =
                    "Terkunci di ${hz.toInt()} Hz. " +
                        "Matikan lalu nyalakan lagi untuk memilih Hz lain."
                applyQuick("refresh rate ${hz.toInt()} Hz") {
                    Tweaks.setLockRefreshRate(true, hz)
                }
            }
            .setOnCancelListener {
                // Pengguna batal memilih: switch tidak boleh tertinggal ON.
                revertSwitch(b.switchLockRefresh, attempted = true)
            }
            .show()
    }

    private fun revertSpinnerToConfig() {
        b.spinBgLimit.onItemSelectedListener = null
        b.spinBgLimit.setSelection(
            bgLimits.indexOfFirst { it.second == config.tweakBgProcessLimit }.coerceAtLeast(0)
        )
        setupPerformanceSection()
    }

    /** Tweak ringan (settings put): cepat, tapi tetap di luar thread UI. */
    private fun applyQuick(label: String, block: () -> Boolean) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { block() }
            toast(if (ok) "$label diterapkan" else "$label gagal - lihat log")
        }
    }

    /** Tugas berat (kompilasi): kunci tombol dan tampilkan status berjalan. */
    private fun runLongTask(status: String, block: () -> Boolean) {
        b.tvCompileStatus.visibility = View.VISIBLE
        b.tvCompileStatus.text = status
        setCompileButtonsEnabled(false)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { block() }
            b.tvCompileStatus.text =
                if (ok) "Selesai - detail ada di log" else "Gagal - lihat log"
            setCompileButtonsEnabled(true)
        }
    }

    private fun setCompileButtonsEnabled(enabled: Boolean) {
        b.btnCompileNow.isEnabled = enabled
        b.btnDexoptJob.isEnabled = enabled
        b.btnResetCompile.isEnabled = enabled
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
