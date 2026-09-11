package magicau.mtkcontroller.feature.cpu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import magicau.mtkcontroller.MtkApp
import magicau.mtkcontroller.data.AppExpiry
import magicau.mtkcontroller.data.log.AppLog

/**
 * 开机自动应用：只在设置开启时启动 [CpuReapplyService] 的一次性引导。
 *
 * 不在这里直接下发频率 —— 开机时 Shizuku 往往还没起来，服务会先等它就绪。
 * 用 `goAsync` 读设置，读完立刻结束广播；后续等待在服务里完成。
 */
class CpuBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (AppExpiry.isExpired()) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? MtkApp ?: return@launch
                if (app.container.settingsRepository.applyOnBoot.first()) {
                    AppLog.i("CpuBoot", "开机自动应用已排队，等待 Shizuku")
                    CpuReapplyService.startForBoot(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
