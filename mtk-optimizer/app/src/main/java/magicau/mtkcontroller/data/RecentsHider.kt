package magicau.mtkcontroller.data

import android.app.ActivityManager
import android.content.Context
import magicau.mtkcontroller.data.log.AppLog

/**
 * 把本应用的任务从“最近任务”里隐藏 / 恢复显示。
 *
 * 用的是 `ActivityManager.AppTask.setExcludeFromRecents`，只影响**本应用自己的任务**，
 * 不需要 Shizuku，也不改系统设置。`android:excludeFromRecents` 是安装期固定的，
 * 做不到设置项开关，所以这里在运行时改。
 *
 * 失败不能影响 App 使用：厂商 ROM 可能收紧这个接口，只记日志。调用方在每次
 * `MainActivity.onResume` 重新应用一次，保证新建的任务也带上标记。
 */
object RecentsHider {

    private const val TAG = "Recents"

    fun apply(context: Context, hidden: Boolean) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        runCatching {
            // 可能有多个任务（分屏、文档模式），逐个改，别只改第一个。
            manager.appTasks.forEach { task -> task.setExcludeFromRecents(hidden) }
        }.onFailure {
            AppLog.w(TAG, "设置最近任务隐藏失败(hidden=$hidden): ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
