package magicau.mtkcontroller.data.privilege

import magicau.mtkcontroller.data.probe.ProbeProcess

/**
 * `dumpsys battery set ...` 的假电量 / 假充电。
 *
 * **边界，必须先说清楚**：这只改 BatteryService 报告给框架的那一层 —— UI 的百分比、
 * 闪电图标、App 通过 BatteryManager 读到的状态。它**改变不了实际充电电流**，因为那是
 * 内核的充电驱动与电量计按真实硬件电压决定的。所以"高电量也想保持快充"做不到。
 *
 * 唯一确定能改到的是**软件层的省电策略**：`low_power=0` 之类。想缓解充电变慢，从那里
 * 入手，不要指望假电量。
 *
 * 副作用：假充电会让 `AppMaintenance` 的充电预热误判为已插电，所以 [isFaked] 会交叉
 * 核对内核电源节点，读不到时才退回"无法分辨"并在调用方提示。
 */
object FakeBattery {

    private val POWER_SUPPLY_ONLINE = listOf(
        "/sys/class/power_supply/usb/online",
        "/sys/class/power_supply/ac/online",
        "/sys/class/power_supply/dc/online",
        "/sys/class/power_supply/wireless/online",
    )

    /** 内核侧有一个电源在线为 1，就认为真的接着电源。 */
    private fun kernelSaysCharging(): Boolean? {
        for (path in POWER_SUPPLY_ONLINE) {
            val result = ProbeProcess.run(listOf("/system/bin/cat", path), 1500, 64)
            if (result.exit != 0 || result.truncated) continue
            if (result.output.trim() == "1") return true
        }
        // 一个都读不到 → 无法判断；读到了但全是 0 → 明确没有。
        return POWER_SUPPLY_ONLINE.any { path ->
            ProbeProcess.run(listOf("/system/bin/cat", path), 1500, 64).exit == 0
        }.takeIf { it }?.let { false }
    }

    /**
     * 框架说在充电、但内核说没有 → 视为假充电生效。
     * 返回 null 表示"无法分辨"，调用方应把这个不确定性告诉用户。
     */
    fun isFaked(): Boolean {
        val battery = ProbeProcess.run(listOf("/system/bin/dumpsys", "battery"), 2500, 12000)
        val frameworkCharging = Regex("(?m)^\\s*(AC|USB|Wireless|Dock) powered: true\\s*$")
            .containsMatchIn(battery.output)
        if (!frameworkCharging) return false
        return kernelSaysCharging() == false
    }

    /** null = 内核节点读不到，无法确认是真插电还是假充电。 */
    fun fakedOrUnknown(): Boolean? =
        if (!Regex("(?m)^\\s*(AC|USB|Wireless|Dock) powered: true\\s*$").containsMatchIn(
                ProbeProcess.run(listOf("/system/bin/dumpsys", "battery"), 2500, 12000).output)
        ) false
        else kernelSaysCharging()?.not()

    fun setLevel(level: Int): String = run("dumpsys battery set level $level")
    fun fakeCharging(): String = run("dumpsys battery set status 2; dumpsys battery set usb 1")
    fun fakeDischarging(): String = run("dumpsys battery set status 4; dumpsys battery set usb 0")
    fun reset(): String = run("dumpsys battery reset")

    /**
     * daemon 侧入口。command 只接受这四个固定形态，自由文本不进来 ——
     * `level:N` 里的 N 必须是 1..100 的整数。
     */
    fun dispatch(command: String): String = when {
        command == "charge" -> fakeCharging()
        command == "discharge" -> fakeDischarging()
        command == "reset" -> reset()
        command.startsWith("level:") -> {
            val level = command.removePrefix("level:").toIntOrNull()
            if (level == null || level !in 1..100) "ERROR:电量需为 1-100 的整数"
            else setLevel(level)
        }
        else -> "ERROR:未知假电池命令"
    }

    private fun run(command: String): String {
        val result = ProbeProcess.run(listOf("/system/bin/sh", "-c", command), 5000, 2048)
        return if (result.exit == 0 && result.error == null && !result.timedOut) "OK:$command"
        else "ERROR:${result.render()}"
    }
}
