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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import magicau.mtkcontroller.MainActivity
import magicau.mtkcontroller.MtkApp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.AppExpiry
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.powerhal.RequestOutcome
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.settings.SettingsRepository
import magicau.mtkcontroller.domain.model.ApplyMode
import magicau.mtkcontroller.domain.model.CpuCluster

/**
 * 后台维持 CPU 频率请求。
 *
 * 只负责两件事，且都只在用户显式开启、并且确实有一套活动请求时才运行：
 *
 * 1. **轮询重发**（[ApplyMode.POLLING]）：MTK 的 powerhal / FPSGO 会按自己的节奏
 *    改写 `scaling_max_freq`，一次下发不保证一直有效。
 * 2. **开机自动应用**：`CpuBootReceiver` 启动后等 Shizuku 就绪，按上一次范围下发一次。
 *
 * 锁屏联动不在这个服务里；它由 `ScreenPolicyController` 在 App 进程里听广播，
 * 不开前台服务、不发通知。服务是前台服务而不是 ViewModel 里的循环：轮询的意义
 * 就是在别的应用在前台时继续工作。用户释放请求、或关掉轮询后，
 * [magicau.mtkcontroller.di.AppContainer.syncCpuReapply] 会停掉它。
 */
class CpuReapplyService : Service() {

    companion object {
        private const val CHANNEL_ID = "cpu_reapply"
        private const val NOTIFICATION_ID = 1002
        private const val TAG = "CpuReapply"

        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_BOOT_APPLY = "boot_apply"

        /** 开机后等待 Shizuku 的上限；超过就放弃本次自动应用，不无限占着前台服务。 */
        const val BOOT_PRIVILEGE_WAIT_MS = 5 * 60_000L

        fun start(context: Context, intervalMs: Long) {
            val intent = Intent(context, CpuReapplyService::class.java)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs)
            // 从快捷开关启动时 App 在后台，Android 12+ 可能拒绝启动前台服务。
            // 拒绝只意味着这次没有后台维持，不应该让开关崩掉。
            runCatching { context.startForegroundService(intent) }
                .onFailure { AppLog.w(TAG, "启动后台维持失败: ${it.javaClass.simpleName}: ${it.message}") }
        }

        /** 开机广播专用：先等 Shizuku 就绪，再按上一次范围下发一次。 */
        fun startForBoot(context: Context) {
            val intent = Intent(context, CpuReapplyService::class.java)
                .putExtra(EXTRA_BOOT_APPLY, true)
            runCatching { context.startForegroundService(intent) }
                .onFailure { AppLog.w(TAG, "开机自动应用启动失败: ${it.javaClass.simpleName}: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CpuReapplyService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var intervalMs = SettingsRepository.DEFAULT_REAPPLY_INTERVAL_MS
    private var mode = ApplyMode.SINGLE
    /** 本次启动是开机自动应用；只生效一次，不随轮询重复。 */
    @Volatile private var bootApplyRequested = false
    /** 通知里显示“正在等 Shizuku”，而不是普通的“轮询中”。 */
    @Volatile private var waitingForPrivilege = false

    /** 策略到命令索引的映射每次启动解析一次，每次开机才变。 */
    private var clusters: List<CpuCluster> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (AppExpiry.isExpired()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) {
            // 系统重启服务时没有 intent。是否继续由持久化的“用户还需要控制”决定，
            // 循环里会再核对一次；不能在这里一律停掉，否则锁屏联动在进程被回收后就失效。
            startLoop()
            return START_STICKY
        }
        intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, SettingsRepository.DEFAULT_REAPPLY_INTERVAL_MS)
            .coerceIn(SettingsRepository.MIN_REAPPLY_INTERVAL_MS, SettingsRepository.MAX_REAPPLY_INTERVAL_MS)
        bootApplyRequested = intent.getBooleanExtra(EXTRA_BOOT_APPLY, false)

        startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            val container = (application as MtkApp).container
            val settings = container.settingsRepository
            // 系统重启服务时没有 intent，读持久值而不是留在默认间隔上。
            intervalMs = settings.reapplyIntervalMs.first()
                .coerceIn(SettingsRepository.MIN_REAPPLY_INTERVAL_MS, SettingsRepository.MAX_REAPPLY_INTERVAL_MS)
            mode = ApplyMode.fromName(settings.applyMode.first())

            // 开机自动应用：先等 Shizuku，再按上一次范围下发一次。
            // 只在这里执行一次，不进入轮询；失败就退场，别拿前台服务空等。
            if (bootApplyRequested) {
                bootApplyRequested = false
                waitingForPrivilege = true
                refreshNotification()
                val ready = withTimeoutOrNull(BOOT_PRIVILEGE_WAIT_MS) {
                    PrivilegeManager.state.first { it.permissionGranted && it.mode.canElevate }
                } != null
                waitingForPrivilege = false
                refreshNotification()
                if (!ready) {
                    AppLog.w(TAG, "开机自动应用：等待 Shizuku 超时，放弃本次")
                    stopSelf()
                    return@launch
                }
                if (container.cpuControl.lastRequested().isEmpty()) {
                    AppLog.i(TAG, "开机自动应用：没有上一次范围")
                    stopSelf()
                    return@launch
                }
                clusters = runCatching { container.cpuScanner.scan(deep = false) }
                    .getOrDefault(emptyList())
                if (clusters.isEmpty()) {
                    AppLog.w(TAG, "开机自动应用：未检测到 CPU 簇")
                    stopSelf()
                    return@launch
                }
                val outcome = runCatching { container.cpuControl.applyLastRequested(clusters) }
                    .getOrElse { failure("boot", it) }
                AppLog.i(TAG, "开机自动应用: ${outcome.success} ${AppLog.text(outcome.message)}")
                if (!outcome.success) {
                    stopSelf()
                    return@launch
                }
            }

            // 这个服务只负责轮询。锁屏联动在 App 进程里听广播，不占用前台服务。
            if (!container.cpuControl.controlEnabled() || mode != ApplyMode.POLLING) {
                stopSelf()
                return@launch
            }

            clusters = runCatching { container.cpuScanner.scan(deep = false) }.getOrDefault(emptyList())
            if (clusters.isEmpty()) {
                AppLog.w(TAG, "未检测到 CPU 簇，后台维持停止")
                stopSelf()
                return@launch
            }

            AppLog.i(TAG, "轮询开始: 间隔=${intervalMs}ms ${clusters.size} 个簇")
            refreshNotification()
            pollLoop(container)
        }
    }

    /** 旧行为：只轮询，熄屏时把间隔拉到最多 15 秒。 */
    private suspend fun pollLoop(container: magicau.mtkcontroller.di.AppContainer) {
        while (currentCoroutineContext().isActive) {
            if (!container.cpuControl.controlEnabled()) {
                stopSelf()
                return
            }
            val screenOn = getSystemService(PowerManager::class.java)?.isInteractive == true
            delay(if (screenOn) intervalMs else maxOf(intervalMs, 15_000L))
            if (container.cpuControl.lastApplied().isEmpty()) {
                AppLog.w(TAG, "没有可重放的设置，后台维持停止")
                stopSelf()
                return
            }
            reapply(container)
        }
    }

    private suspend fun reapply(container: magicau.mtkcontroller.di.AppContainer) {
        val outcome = runCatching { container.cpuControl.reapply(clusters) }
            .getOrElse { failure("reapply", it) }
        if (!outcome.success) AppLog.w(TAG, "重发失败: ${AppLog.text(outcome.message)}")
    }

    private fun failure(phase: String, error: Throwable): RequestOutcome {
        if (error is CancellationException) throw error
        return RequestOutcome(
            false,
            Message(R.string.cpu_poll_exception, listOf("$phase ${error.javaClass.simpleName}: ${error.message}")),
        )
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
            getString(R.string.reapply_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply { description = getString(R.string.reapply_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val seconds = intervalMs / 1000.0
        val text = if (waitingForPrivilege) {
            getString(R.string.reapply_boot_waiting)
        } else {
            getString(R.string.reapply_every, "%.1f".format(seconds))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.reapply_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(intent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }
}
