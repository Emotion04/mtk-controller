package magicau.mtkcontroller.feature.tweaks

import android.app.*
import android.content.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.privilege.PrivilegeManager

/**
 * [ok] 只在任务真的跑完时有意义：true = 服务回了 `OK:`，false = 回了 `ERROR:`。
 * 由服务在这层判定 —— 界面不该去认协议前缀。
 */
data class MaintenanceState(
    val running: Boolean = false,
    val ok: Boolean? = null,
    val message: Message = Message(R.string.maintenance_idle),
)

class MaintenanceService : Service() {
    companion object {
        val state = MutableStateFlow(MaintenanceState())
        fun start(context: Context, kind: String, pkg: String) {
            context.startForegroundService(Intent(context, MaintenanceService::class.java).putExtra("kind", kind).putExtra("pkg", pkg))
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("ax_task", getString(R.string.maintenance_channel), NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(1005, NotificationCompat.Builder(this, "ax_task").setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.maintenance_running))
            .setContentText(getString(R.string.maintenance_hint)).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true
        state.value = MaintenanceState(running = true, message = Message(R.string.maintenance_working))
        scope.launch {
            val result = PrivilegeManager.appMaintenance(
                intent?.getStringExtra("kind").orEmpty(),
                intent?.getStringExtra("pkg").orEmpty(),
            )
            // 回包是 `OK:<resId>` / `ERROR:<resId>`，可带一行参数和若干行原始输出。
            // **解码放在这里**（展示边界）—— 数据层只把协议原样带上来，
            // 它拿不到 App 的 R，也不该知道怎么把 id 变成句子。
            val (ok, message) = decodeMaintenance(result, this@MaintenanceService)
            state.value = MaintenanceState(running = false, ok = ok, message = message)
            if (ok) AppLog.i("Maintenance", result) else AppLog.w("Maintenance", result)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (state.value.running) {
            state.value = MaintenanceState(running = false, message = Message(R.string.maintenance_interrupted))
        }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 解析 daemon 回来的 `OK:<resId>` / `ERROR:<resId>`（可带参数行与原始输出行）。
     *
     * 认不出来就原样当文本显示 —— 协议对不上时，**把服务端原话给用户看**
     * 比编一句"解析失败"有用得多。
     */
    private fun decodeMaintenance(raw: String, context: android.content.Context): Pair<Boolean, Message> {
        val lines = raw.lines()
        val head = lines.firstOrNull().orEmpty()
        val ok = head.startsWith("OK:")
        val body = head.substringAfter(':', head)
        val resId = body.trim().toIntOrNull()
            ?: return ok to Message(R.string.raw_text, listOf(raw))
        val args = lines.getOrNull(1).orEmpty().split(',').filter { it.isNotBlank() }
            .mapNotNull { it.trim().toIntOrNull() }
        return ok to Message(resId, args)
    }
}
