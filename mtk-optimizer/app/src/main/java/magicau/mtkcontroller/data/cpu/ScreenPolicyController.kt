package magicau.mtkcontroller.data.cpu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.ScreenOffPolicy

/**
 * 锁屏联动：在 App 进程里听屏幕广播，**不开前台服务、不发通知**。
 *
 * 代价是进程被系统回收后就不再听 —— 需要跨进程死亡继续生效时，用户要开「通知」，
 * 那个前台服务会把进程留住。锁屏本身不应该因为一条常驻通知而变成“多了一个需要管理的服务”。
 *
 * 只处理 CPU 频率请求：RELEASE 在熄屏时释放（保留用户意图），亮屏后恢复；
 * KEEP 只在亮屏时重发一次。温控、实验室、GPU 不跟着动。
 */
object ScreenPolicyController {

    private const val TAG = "ScreenPolicy"

    @Volatile
    private var installed = false

    fun install(context: Context, container: AppContainer) {
        if (installed) return
        installed = true

        val app = context.applicationContext
        val power = app.getSystemService(PowerManager::class.java)
        // 只在交互状态真正变化时动作，避免 SCREEN_ON 和 USER_PRESENT 连发两次。
        var lastInteractive = power?.isInteractive == true

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val interactive = power?.isInteractive == true
                if (interactive == lastInteractive) return
                lastInteractive = interactive

                container.applicationScope.launch {
                    val policy = ScreenOffPolicy.fromName(
                        container.settingsRepository.screenOffPolicy.first(),
                    )
                    if (policy == ScreenOffPolicy.OFF) return@launch
                    if (!container.cpuControl.controlEnabled()) return@launch

                    if (interactive) {
                        val outcome = runCatching { container.resumeCpuControl() }.getOrNull()
                        if (outcome?.success == true) {
                            AppLog.i(TAG, "亮屏恢复: ${AppLog.text(outcome.message)}")
                        } else if (outcome != null) {
                            AppLog.w(TAG, "亮屏恢复失败: ${AppLog.text(outcome.message)}")
                        }
                    } else if (policy == ScreenOffPolicy.RELEASE) {
                        if (container.cpuControl.lastApplied().isEmpty()) return@launch
                        val clusters = runCatching { container.cpuScanner.scan(deep = false) }
                            .getOrDefault(emptyList())
                        val outcome = runCatching {
                            container.cpuControl.release(clusters)
                        }.getOrNull()
                        AppLog.i(
                            TAG,
                            "锁屏释放: ${outcome?.success} ${outcome?.let { AppLog.text(it.message) }.orEmpty()}",
                        )
                    }
                }
            }
        }

        app.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
    }
}
