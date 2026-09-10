package magicau.mtkcontroller.feature.cpu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import magicau.mtkcontroller.MainActivity
import magicau.mtkcontroller.MtkApp
import magicau.mtkcontroller.data.cpu.CpuControl
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.domain.model.CpuCluster
import magicau.mtkcontroller.data.settings.SettingsRepository

/**
 * Re-sends the CPU limits on a timer while the app is in the background.
 *
 * MTK's powerhal and FPSGO rewrite `scaling_max_freq` on their own schedule, so
 * a limit set once does not necessarily survive — and nothing else puts it
 * back. This holds the user's chosen range in place for as long as it runs,
 * which is the reason it is a foreground service rather than a loop in the
 * ViewModel: the whole point is to keep working while another app is in front.
 *
 * It runs only when [ApplyMode.POLLING] is selected and the user has actually
 * applied something; releasing stops it.
 */
class CpuReapplyService : Service() {

    companion object {
        private const val CHANNEL_ID = "cpu_reapply"
        private const val NOTIFICATION_ID = 1002
        private const val TAG = "CpuReapply"

        const val EXTRA_INTERVAL_MS = "interval_ms"

        fun start(context: Context, intervalMs: Long) {
            val intent = Intent(context, CpuReapplyService::class.java)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CpuReapplyService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var intervalMs = SettingsRepository.DEFAULT_REAPPLY_INTERVAL_MS

    /** Resolved once: the policy -> command-index mapping is fixed per boot. */
    private var clusters: List<CpuCluster> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(intervalMs))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, SettingsRepository.DEFAULT_REAPPLY_INTERVAL_MS)
            .coerceIn(SettingsRepository.MIN_REAPPLY_INTERVAL_MS, SettingsRepository.MAX_REAPPLY_INTERVAL_MS)

        startLoop()
        // Not sticky: if the process dies there is no handler left to release,
        // so a silent restart would only re-apply against stale state.
        return START_NOT_STICKY
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            val container = (application as MtkApp).container
            clusters = runCatching { container.cpuScanner.scan(deep = false) }.getOrDefault(emptyList())
            if (clusters.isEmpty()) {
                AppLog.w(TAG, "未检测到 CPU 簇,轮询停止")
                stopSelf()
                return@launch
            }

            AppLog.i(TAG, "开始轮询,间隔 ${intervalMs}ms,${clusters.size} 个簇")
            while (isActive) {
                val settings = container.cpuControl.lastApplied()
                if (settings.isEmpty()) {
                    AppLog.w(TAG, "没有可重放的设置,轮询停止")
                    stopSelf()
                    return@launch
                }

                val outcome = runCatching {
                    container.cpuControl.apply(clusters, settings)
                }.getOrElse { CpuControl.Outcome(false, "轮询异常: ${it.javaClass.simpleName}: ${it.message}") }

                if (!outcome.success) AppLog.w(TAG, "轮询下发失败: ${outcome.message}")

                delay(intervalMs)
            }
        }
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CPU 频率轮询",
            NotificationManager.IMPORTANCE_MIN,
        ).apply { description = "按设定间隔反复下发 CPU 频率上下限" }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(intervalMs: Long): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val seconds = intervalMs / 1000.0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在维持 CPU 频率限制")
            .setContentText("每 ${"%.1f".format(seconds)} 秒重新下发一次")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(intent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
