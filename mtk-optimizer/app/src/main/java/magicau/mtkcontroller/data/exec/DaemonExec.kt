package magicau.mtkcontroller.data.exec

import magicau.mtkcontroller.data.privilege.PrivilegeManager

/**
 * 在 Shizuku 用户服务（uid 2000 / shell）里执行命令。
 *
 * **为什么必须有这一层**：`ProbeProcess.run` 用 ProcessBuilder 起进程，跑在 App 自己的
 * uid 上。普通应用读不到 `/sys` 下的厂商节点 —— SELinux 直接拒，表现就是"读不出来"而不是
 * 报错。所以一切"探测系统状态"的命令都要送到用户服务里跑，和 `VivoTweaks` 读写 settings
 * 是同一条路（`PrivilegeManager.exec`）。
 *
 * 之所以必须如此：跑在 shell/root 下的进程才读得到这些厂商节点。早期把读指标放在本地进程，
 * 于是温度、GPU、DDR 全读不到，而 `/proc/meminfo` 因为对所有应用可读反倒正常。
 */
object DaemonExec {

    data class Output(val stdout: String, val stderr: String, val exit: Int?)

    /**
     * 执行 shell 命令。没能送到用户服务（未连接、连接失败、守护进程异常）时返回 null ——
     * 这时候不能和"节点不存在"混为一谈。
     */
    suspend fun run(command: String): Output? = parse(PrivilegeManager.exec(command))

    /** argv 形式；每个参数单独加引号，避免路径里的特殊字符被 shell 解释。 */
    suspend fun runArgv(argv: List<String>): Output? =
        run(argv.joinToString(" ") { quote(it) })

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * 解析用户服务的输出包装：`<stdout>\n[stderr] <stderr>\n[exit] <code>`。
     *
     * 没有 `[exit]` 标记说明走的是异常分支（`ERROR: ...`），返回 null。
     * stderr 里可能带换行，所以结束标记必须从后往前找。
     */
    internal fun parse(raw: String?): Output? {
        if (raw == null) return null
        val exitMarker = "\n[exit] "
        val exitIndex = raw.lastIndexOf(exitMarker)
        if (exitIndex < 0) return null
        val exit = raw.substring(exitIndex + exitMarker.length).trim().toIntOrNull()
        var body = raw.substring(0, exitIndex)
        var stderr = ""
        val stderrMarker = "\n[stderr] "
        val stderrIndex = body.lastIndexOf(stderrMarker)
        if (stderrIndex >= 0) {
            stderr = body.substring(stderrIndex + stderrMarker.length).trim()
            body = body.substring(0, stderrIndex)
        }
        return Output(body, stderr, exit)
    }
}
