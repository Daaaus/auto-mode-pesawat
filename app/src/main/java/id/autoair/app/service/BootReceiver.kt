package id.autoair.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import id.autoair.app.config.ConfigStore
import id.autoair.app.monitor.Logger

/**
 * Setelah reboot, Shizuku (non-root) belum hidup. Service tetap dimulai supaya
 * konfigurasi dipulihkan dan notifikasi meminta pengguna menjalankan Shizuku;
 * MonitorEngine akan menunggu sampai binder tersedia lalu lanjut sendiri.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val config = ConfigStore(context)
        if (!config.enabled) {
            Logger.info("boot: pemantauan dinonaktifkan, service tidak dimulai")
            return
        }

        Logger.info("boot: memulai service, menunggu Shizuku")
        runCatching { NetMonitorService.start(context) }
            .onFailure { Logger.error("boot: gagal memulai service - ${it.message}") }
    }
}
