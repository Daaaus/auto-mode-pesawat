package id.autoair.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import id.autoair.app.R
import id.autoair.app.config.ConfigStore
import id.autoair.app.monitor.MonitorEngine
import id.autoair.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service persisten. Tipe `specialUse` karena pemantauan jaringan
 * berkelanjutan atas permintaan pemilik perangkat tidak cocok dengan tipe lain.
 */
class NetMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engineJob: Job? = null
    private lateinit var engine: MonitorEngine
    private lateinit var config: ConfigStore

    override fun onCreate() {
        super.onCreate()
        config = ConfigStore(this)
        engine = MonitorEngine(this, config)
        createChannel()
        startForegroundCompat(buildNotification("Memulai...", null))

        scope.launch {
            engine.state.collect { st ->
                val icon = when {
                    st.waitingForShizuku -> "\u26A0\uFE0F"
                    st.healthy == true -> "\uD83D\uDFE2"
                    st.healthy == false -> "\uD83D\uDD34"
                    else -> "\u26AA"
                }
                val sub = st.lastRefresh?.let {
                    "Refresh terakhir $it  \u2022  total ${config.totalRefresh}x"
                }
                notify(buildNotification("$icon ${st.statusText}", sub))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                config.enabled = false
                // Lepas status foreground supaya notifikasi tidak tertinggal.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (engineJob?.isActive != true) {
            engineJob = scope.launch { engine.run(this) }
        }

        if (intent?.action == ACTION_TEST) {
            scope.launch { engine.forceRefresh() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // onStop() memulihkan airplane_mode_radios lewat Shizuku. Jalankan lebih
        // dulu dan secara sinkron; membatalkan scope duluan bisa memotongnya dan
        // meninggalkan setelan radio pengguna dalam keadaan berubah.
        runCatching { engine.onStop() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- notifikasi -------------------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pemantauan Koneksi",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Status pemantauan dan refresh mode pesawat"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String, sub: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NetMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .apply { sub?.let { setSubText(it) } }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Hentikan", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val CHANNEL_ID = "autoair_monitor"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "id.autoair.app.STOP"
        const val ACTION_TEST = "id.autoair.app.TEST"

        fun start(context: Context) {
            val i = Intent(context, NetMonitorService::class.java)
            context.startForegroundService(i)
        }

        fun testRefresh(context: Context) {
            context.startForegroundService(
                Intent(context, NetMonitorService::class.java).setAction(ACTION_TEST)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NetMonitorService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
