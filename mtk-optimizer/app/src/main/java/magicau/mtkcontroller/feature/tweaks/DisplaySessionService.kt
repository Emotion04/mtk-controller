package magicau.mtkcontroller.feature.tweaks

import android.app.*
import android.content.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import magicau.mtkcontroller.data.AppExpiry
import magicau.mtkcontroller.MtkApp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.tweaks.TouchBackend

/**
 * `message` 为 null = 还没跑过。界面据此决定要不要显示这一行，
 * 不去比较某个具体的字符串。
 */
data class DisplaySessionState(val fps: Int = 0, val touch: Boolean = false, val message: Message? = null)

/** 用户主动开启的前台会话，不在开机或进程重启后自动恢复。 */
class DisplaySessionService : Service() {
    companion object {
        private var cleanup: Job? = null
        val state = MutableStateFlow(DisplaySessionState())
        fun start(context: Context, fps: Int = -1) {
            context.startForegroundService(Intent(context, DisplaySessionService::class.java).putExtra("fps", fps))
        }
        fun stopDisplay(context: Context) { context.startService(Intent(context, DisplaySessionService::class.java).putExtra("fps", 0)) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var touchRevision: Long? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ax_session", getString(R.string.display_session_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(this, 10, Intent(this, DisplaySessionService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1004, NotificationCompat.Builder(this, "ax_session")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(getString(R.string.display_session_running))
            .setContentText(getString(R.string.display_session_stop_hint))
            .addAction(0, getString(R.string.action_stop), stop).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (AppExpiry.isExpired()) {
            stopSelf()
            return START_NOT_STICKY
        }
        job?.cancel()
        val repo = (application as MtkApp).container.tweakRepository
        val fps = intent?.getIntExtra("fps", -1) ?: -1
        if (fps >= 0) state.value = state.value.copy(fps = fps)
        job = scope.launch {
            try {
                cleanup?.join()
                if (intent?.action == "stop") {
                    state.value = state.value.copy(fps = 0)
                    if (repo.touchBackend.first() == TouchBackend.VIVO) repo.setTouchOptimization(false)
                }
                // 每次配置变化先释放本会话旧高刷请求。
                PrivilegeManager.vivoDisplayRequest(-1)
                while (isActive) {
                    val touch = repo.touchBackend.first() == TouchBackend.VIVO && repo.touchEnabled.first()
                    val requestedFps = state.value.fps
                    if (!touch && requestedFps == 0) break
                    if (!PrivilegeManager.state.value.mode.canElevate) {
                        error(getString(R.string.display_session_shizuku_lost))
                    }
                    val displayResult = if (requestedFps > 0) PrivilegeManager.vivoDisplayRequest(requestedFps) else "OK:"
                    val revision = repo.touchRevision
                    val touchResult = if (touch) repo.renewVivoTouch() else null
                    if (touchResult != null) touchRevision = revision
                    check(displayResult.startsWith("OK:") && touchResult?.success != false) {
                        getString(R.string.display_session_interrupted)
                    }
                    state.value = state.value.copy(touch = touch, message = Message(R.string.display_session_active))
                    delay(8000)
                }
                state.value = DisplaySessionState(message = Message(R.string.display_session_stopped))
                stopSelf()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                state.value = DisplaySessionState(
                    message = e.message?.let { Message(R.string.raw_text, listOf(it)) }
                        ?: Message(R.string.display_session_failed),
                )
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel()
        val previous = state.value
        state.value = previous.copy(fps = 0, touch = false)
        // 清理不依赖已取消的服务作用域；高刷另有服务端 15 秒期限。
        val revision = touchRevision
        cleanup = CoroutineScope(Dispatchers.IO).launch {
            PrivilegeManager.vivoDisplayRequest(-1)
            val repo = (application as MtkApp).container.tweakRepository
            revision?.let { repo.stopVivoTouchIfUnchanged(it) }
        }
        super.onDestroy()
    }
}
