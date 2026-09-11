package magicau.mtkcontroller.data.privilege

import magicau.mtkcontroller.data.probe.ProbeProcess

/**
 * 当前前台应用的包名，通过 dumpsys 解析得到。
 *
 * 不走反射 `IActivityTaskManager`：那是 `@hide` API，签名随 ROM 变，而本项目的
 * 事务码已经因为 AIDL 声明顺序在 ROM 之间漂移吃过亏。`dumpsys activity activities`
 * 在 uid 2000 下可读，读不到就明确报读不到。
 *
 * **这一份 dump 不便宜**：整棵活动栈的转储，输出上限 40 KB，几十毫秒的 CPU。
 * 调用方要控制频率 —— 按应用温控每 2 秒一次，见 `AppThermalService` 的说明。
 *
 * 只读，不持有任何句柄、不改变任何系统状态。
 */
object ForegroundApp {

    private val RESUMED = Regex("(?:topResumedActivity|mResumedActivity)\\s*[:=]\\s*u?[a-z0-9]*\\s*\\{?[^}]*?\\s+([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)/")
    private val FOCUS = Regex("m(?:CurrentFocus|FocusedApp)\\s*=\\s*.*?\\s([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)/")

    /** 返回 `包名`，或 `ERROR:原因`。 */
    fun current(): String {
        // activities 落在 uncompressed 的 dump 里；窗口作为第二来源。
        val activities = ProbeProcess.run(listOf("/system/bin/dumpsys", "activity", "activities"), 4000, 40000)
        val fromActivities = activities.output.lineSequence()
            .mapNotNull { RESUMED.find(it)?.groupValues?.get(1) }
            .firstOrNull()
        if (fromActivities != null) return fromActivities

        val window = ProbeProcess.run(listOf("/system/bin/dumpsys", "window"), 4000, 40000)
        val fromWindow = window.output.lineSequence()
            .mapNotNull { FOCUS.find(it)?.groupValues?.get(1) }
            .firstOrNull()
        if (fromWindow != null) return fromWindow

        val reason = when {
            activities.timedOut || window.timedOut -> "dumpsys 超时"
            activities.error != null || window.error != null -> "dumpsys 执行失败"
            activities.output.isBlank() && window.output.isBlank() -> "dumpsys 无输出"
            else -> "未解析到前台应用"
        }
        return "ERROR:$reason"
    }
}
