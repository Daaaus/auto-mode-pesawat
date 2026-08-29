package id.autoair.app.ui

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import id.autoair.app.R
import id.autoair.app.databinding.ActivityDebloatBinding
import id.autoair.app.shizuku.ShizukuBridge
import id.autoair.app.tweak.Debloat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layar Bloatware: pindai aplikasi bawaan, lalu nonaktifkan / hapus (user 0)
 * / pulihkan.
 *
 * Seleksi disimpan sebagai Set nama paket, BUKAN referensi view - daftar bisa
 * dibangun ulang (filter, rescan) tanpa kehilangan pilihan pengguna.
 */
class DebloatActivity : AppCompatActivity() {

    private lateinit var b: ActivityDebloatBinding

    private var entries: List<Debloat.Entry> = emptyList()
    private val selected = mutableSetOf<String>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDebloatBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnScan.setOnClickListener { startScan() }
        b.switchOnlySuspects.setOnCheckedChangeListener { _, _ -> renderList() }

        b.btnDisable.setOnClickListener { confirmBatch(Action.DISABLE) }
        b.btnRemove.setOnClickListener { confirmBatch(Action.REMOVE) }
        b.btnRestore.setOnClickListener { confirmBatch(Action.RESTORE) }
        b.btnRestoreAll.setOnClickListener { confirmRestoreAll() }
    }

    // ------------------------------------------------------------------ scan

    private fun startScan() {
        if (!requireShizuku()) return
        if (busy) return
        busy = true
        setUiBusy(true, "Memindai paket...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                Debloat.scan(this@DebloatActivity, b.switchIncludeUser.isChecked)
            }
            entries = result
            selected.retainAll(result.map { it.pkg }.toSet())
            busy = false
            setUiBusy(false, null)
            if (result.isEmpty()) {
                b.tvScanStatus.visibility = View.VISIBLE
                b.tvScanStatus.text = "Pemindaian gagal atau kosong - lihat log"
            } else {
                b.boxActions.visibility = View.VISIBLE
                b.tvScanStatus.visibility = View.VISIBLE
                b.tvScanStatus.text =
                    "${result.size} paket · " +
                        "${result.count { it.isSuspect && !it.isProtected }} kandidat bloat · " +
                        "${result.count { it.isRemovedForUser }} terhapus · " +
                        "${result.count { it.isDisabled }} nonaktif"
                renderList()
            }
        }
    }

    // ------------------------------------------------------------------ list

    private fun visibleEntries(): List<Debloat.Entry> =
        if (b.switchOnlySuspects.isChecked) {
            entries.filter { it.isSuspect || it.isRemovedForUser || it.isDisabled }
        } else {
            entries
        }

    private fun renderList() {
        b.listContainer.removeAllViews()
        val pad = (8 * resources.displayMetrics.density).toInt()
        visibleEntries().forEach { e -> b.listContainer.addView(buildRow(e, pad)) }
        updateActionButtons()
    }

    private fun buildRow(e: Debloat.Entry, pad: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        val cb = CheckBox(this).apply {
            isEnabled = e.selectable
            isChecked = e.pkg in selected
            setOnCheckedChangeListener { _, checked ->
                if (checked) selected += e.pkg else selected -= e.pkg
                updateActionButtons()
            }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(pad, 0, 0, 0)
        }
        val tvLabel = TextView(this).apply {
            text = e.label
            setTextColor(
                ContextCompat.getColor(
                    this@DebloatActivity,
                    if (e.isProtected) R.color.text_tertiary else R.color.text_primary
                )
            )
            textSize = 14f
        }
        val tvPkg = TextView(this).apply {
            val status = e.statusText()
            text = if (status.isBlank()) e.pkg else "${e.pkg}  [$status]"
            setTextColor(ContextCompat.getColor(this@DebloatActivity, R.color.text_tertiary))
            textSize = 11f
        }
        texts.addView(tvLabel)
        texts.addView(tvPkg)
        row.addView(cb)
        row.addView(texts)
        if (!e.selectable) row.alpha = 0.55f
        // Ketuk baris = toggle checkbox (area sentuh lebih besar).
        row.setOnClickListener { if (cb.isEnabled) cb.isChecked = !cb.isChecked }
        return row
    }

    // ------------------------------------------------------------------ aksi

    private enum class Action { DISABLE, REMOVE, RESTORE }

    /** Entri terpilih yang relevan untuk aksi tertentu. */
    private fun targetsFor(action: Action): List<Debloat.Entry> {
        val sel = entries.filter { it.pkg in selected && it.selectable }
        return when (action) {
            Action.DISABLE -> sel.filter { !it.isDisabled && !it.isRemovedForUser }
            Action.REMOVE -> sel.filter { !it.isRemovedForUser }
            Action.RESTORE -> sel.filter { it.isRemovedForUser || it.isDisabled }
        }
    }

    private fun updateActionButtons() {
        if (b.boxActions.visibility != View.VISIBLE) return
        b.btnDisable.text = "Nonaktifkan (${targetsFor(Action.DISABLE).size})"
        b.btnRemove.text = "Hapus (${targetsFor(Action.REMOVE).size})"
        b.btnRestore.text = "Pulihkan (${targetsFor(Action.RESTORE).size})"
    }

    private fun confirmBatch(action: Action) {
        if (!requireShizuku() || busy) return
        val targets = targetsFor(action)
        if (targets.isEmpty()) {
            toast("Tidak ada yang dipilih untuk aksi ini")
            return
        }

        if (action != Action.REMOVE) {
            // Nonaktifkan & pulihkan aman dan reversible - langsung jalan.
            runBatch(action, targets)
            return
        }

        val userApps = targets.count { it.isUserApp }
        val msg = buildString {
            append("${targets.size} aplikasi akan dihapus.\n\n")
            append("Aplikasi bawaan bisa dipulihkan lewat tombol Pulihkan.")
            if (userApps > 0) {
                append("\n\nPERINGATAN: $userApps di antaranya aplikasi user - ")
                append("penghapusannya PERMANEN dan tidak bisa dipulihkan.")
            }
            append("\n\nJangan hapus aplikasi yang tidak Anda kenal.")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus ${targets.size} aplikasi?")
            .setMessage(msg)
            .setPositiveButton("Hapus") { _, _ -> runBatch(action, targets) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun runBatch(action: Action, targets: List<Debloat.Entry>) {
        busy = true
        setUiBusy(true, "Memproses ${targets.size} aplikasi...")
        lifecycleScope.launch {
            val (ok, fail) = withContext(Dispatchers.IO) {
                var ok = 0
                var fail = 0
                targets.forEach { e ->
                    val done = when (action) {
                        Action.DISABLE -> Debloat.disable(e.pkg)
                        Action.REMOVE -> Debloat.uninstall(e.pkg)
                        Action.RESTORE -> Debloat.restore(e.pkg)
                    }
                    if (done) ok++ else fail++
                }
                ok to fail
            }
            busy = false
            setUiBusy(false, null)
            selected.clear()
            toast(
                if (fail == 0) "Selesai: $ok aplikasi"
                else "Selesai: $ok berhasil, $fail gagal - lihat log"
            )
            startScan() // segarkan status
        }
    }

    private fun confirmRestoreAll() {
        if (!requireShizuku() || busy) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Pulihkan semua?")
            .setMessage(
                "Semua aplikasi bawaan yang pernah dihapus (oleh aplikasi ini " +
                    "maupun cara lain) akan dikembalikan."
            )
            .setPositiveButton("Pulihkan") { _, _ ->
                busy = true
                setUiBusy(true, "Memulihkan...")
                lifecycleScope.launch {
                    val (ok, fail) = withContext(Dispatchers.IO) { Debloat.restoreAllRemoved() }
                    busy = false
                    setUiBusy(false, null)
                    toast("Dipulihkan: $ok berhasil, $fail gagal")
                    startScan()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------------------------------------------------------------- helpers

    private fun setUiBusy(isBusy: Boolean, status: String?) {
        b.btnScan.isEnabled = !isBusy
        b.btnDisable.isEnabled = !isBusy
        b.btnRemove.isEnabled = !isBusy
        b.btnRestore.isEnabled = !isBusy
        b.btnRestoreAll.isEnabled = !isBusy
        if (status != null) {
            b.tvScanStatus.visibility = View.VISIBLE
            b.tvScanStatus.text = status
        }
    }

    private fun requireShizuku(): Boolean {
        if (ShizukuBridge.isReady()) return true
        toast("Shizuku belum siap - hubungkan dulu di layar utama")
        return false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
