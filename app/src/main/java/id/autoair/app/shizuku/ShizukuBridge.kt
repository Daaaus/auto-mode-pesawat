package id.autoair.app.shizuku

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * Jembatan ke Shizuku. Semua perintah istimewa (setara `adb shell`) lewat sini.
 *
 * Skrip asli berjalan sebagai root di Magisk; di sini kita memakai identitas shell
 * yang disediakan Shizuku, yang sudah memiliki WRITE_SECURE_SETTINGS.
 */
object ShizukuBridge {

    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val ok: Boolean get() = exitCode == 0
        val output: String get() = if (stdout.isNotBlank()) stdout else stderr
    }

    /** Shizuku terpasang dan servicenya hidup. */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        if (!isAvailable()) false
        else if (Shizuku.isPreV11()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestPermission(requestCode: Int) {
        try {
            if (isAvailable() && !hasPermission()) Shizuku.requestPermission(requestCode)
        } catch (_: Throwable) {
        }
    }

    fun isReady(): Boolean = isAvailable() && hasPermission()

    /**
     * Jalankan perintah shell melalui Shizuku.
     * `Shizuku.newProcess` adalah API tersembunyi, diakses lewat refleksi.
     */
    fun exec(command: String, timeoutSec: Long = 15): ExecResult {
        if (!isReady()) {
            return ExecResult(-1, "", "shizuku tidak siap")
        }
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { r: BufferedReader ->
                        r.forEachLine { out.appendLine(it) }
                    }
                }
            }
            val errThread = Thread {
                runCatching {
                    process.errorStream.bufferedReader().use { r: BufferedReader ->
                        r.forEachLine { err.appendLine(it) }
                    }
                }
            }
            outThread.start()
            errThread.start()

            if (!awaitExit(process, timeoutSec)) {
                runCatching { process.destroy() }
                return ExecResult(-2, out.toString().trim(), "timeout setelah ${timeoutSec}s")
            }
            outThread.join(1000)
            errThread.join(1000)

            ExecResult(process.exitValue(), out.toString().trim(), err.toString().trim())
        } catch (t: Throwable) {
            ExecResult(-3, "", t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * ShizukuRemoteProcess mewarisi java.lang.Process tetapi tidak meng-override
     * waitFor(long, TimeUnit); implementasi bawaan Process melakukan polling yang
     * bergantung pada exitValue(). Pakai waitForTimeout() milik Shizuku bila ada.
     */
    private fun awaitExit(process: Process, timeoutSec: Long): Boolean {
        runCatching {
            val m = process.javaClass.getMethod(
                "waitForTimeout",
                Long::class.javaPrimitiveType,
                TimeUnit::class.java
            )
            return m.invoke(process, timeoutSec, TimeUnit.SECONDS) as Boolean
        }
        // Fallback: polling exitValue sampai batas waktu.
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        while (System.currentTimeMillis() < deadline) {
            val done = runCatching { process.exitValue(); true }.getOrDefault(false)
            if (done) return true
            Thread.sleep(100)
        }
        return false
    }

    /**
     * Berikan WRITE_SECURE_SETTINGS pada aplikasi sendiri, supaya penulisan
     * Settings.Global bisa dilakukan langsung tanpa spawn shell tiap kali.
     */
    fun grantSecureSettings(context: Context): Boolean {
        if (hasSecureSettings(context)) return true
        val pkg = context.packageName
        val r = exec("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        return r.ok || hasSecureSettings(context)
    }

    fun hasSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Ringkasan status untuk UI. */
    fun statusText(context: Context): String = when {
        !isAvailable() -> "Shizuku tidak berjalan"
        !hasPermission() -> "Shizuku aktif, izin belum diberikan"
        hasSecureSettings(context) -> "Siap (izin langsung)"
        else -> "Siap (via shell)"
    }
}
