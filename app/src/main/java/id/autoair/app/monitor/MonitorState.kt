package id.autoair.app.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Status pemantauan yang dibagikan antara service dan UI.
 *
 * Sebelumnya UI membaca StateFlow milik instance MonitorEngine, padahal engine
 * hidup di dalam service dan Activity tidak pernah memegang instance yang sama,
 * sehingga status di layar tidak pernah ikut berubah. Holder proses-wide ini
 * membuat keduanya melihat sumber yang sama.
 */
object MonitorState {

    data class Snapshot(
        val running: Boolean = false,
        val statusText: String = "Berhenti",
        val lastRefresh: String? = null,
        val lastReason: String? = null,
        val refreshCount: Int = 0,
        val waitingForShizuku: Boolean = false,
        /** null = belum diketahui, true = sehat, false = bermasalah */
        val healthy: Boolean? = null
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    fun update(block: (Snapshot) -> Snapshot) {
        val next = block(_state.value)
        _state.value = next
        Logger.setStatus(next.statusText)
    }

    fun reset() {
        _state.value = Snapshot()
    }
}
