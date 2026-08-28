package id.autoair.app.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ring buffer log, padanan LOG_FILE + rotate_log pada skrip.
 */
object Logger {

    private const val MAX_LINES = 200
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val buffer = ArrayDeque<String>()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status

    @Synchronized
    fun log(level: String, message: String) {
        val line = "${fmt.format(Date())} [$level] $message"
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        _lines.value = buffer.toList()
        android.util.Log.d("AutoAirplane", line)
    }

    fun info(message: String) = log("INFO", message)
    fun warn(message: String) = log("WARN", message)
    fun error(message: String) = log("ERROR", message)

    fun setStatus(text: String) {
        _status.value = text
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _lines.value = emptyList()
    }

    fun snapshot(): String = buffer.joinToString("\n")
}
