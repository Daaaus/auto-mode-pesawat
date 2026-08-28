package id.autoair.app.monitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import id.autoair.app.shizuku.ShizukuBridge

/**
 * Padanan cek `mCallState=2` pada skrip.
 * Mode pesawat memutus panggilan, jadi refresh ditunda selama telepon aktif.
 */
class CallGuard(private val context: Context) {

    private val tm: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    fun isInCall(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            // callStateForSubscription baru ada di API 31; di API 30 pakai callState.
            val state = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    tm.callStateForSubscription
                } else {
                    @Suppress("DEPRECATION")
                    tm.callState
                }
            }.getOrNull()
            if (state != null) {
                return state != TelephonyManager.CALL_STATE_IDLE
            }
        }
        // Tanpa izin runtime, baca lewat Shizuku seperti skrip aslinya.
        val r = ShizukuBridge.exec("dumpsys telephony.registry | grep -m4 mCallState")
        if (r.ok && r.output.isNotBlank()) {
            return r.output.lines().any { line ->
                val v = line.substringAfter("mCallState=", "").trim().takeWhile { it.isDigit() }
                v.isNotEmpty() && v != "0"
            }
        }
        return false
    }
}
