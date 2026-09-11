package magicau.mtkcontroller.data.diag
import magicau.mtkcontroller.data.probe.ProbeProcess

/** Static, read-only evidence for locating ROM-specific control definitions. */
object RomControlProbe {
    private val roots = listOf("/vendor/etc/", "/odm/etc/", "/system_ext/etc/")
    fun configPaths(listing: String): List<String> = listing.lineSequence().map { it.trim() }
        .filter { path -> roots.any(path::startsWith) && !path.contains("..") &&
            path.matches(Regex("/[A-Za-z0-9_./-]+\\.(xml|conf|json)")) }
        .distinct().take(12).toList()

    fun collect(run: (List<String>, Long, Int) -> ProbeProcess.Result = ProbeProcess::run): String {
        val report = StringBuilder("ROM_CONTROL_PROBE_V2 read-only; no service calls by guessed transaction number\n")
        val start = System.nanoTime()
        var problems = 0
        fun capture(label: String, command: List<String>, limit: Int = 10000): ProbeProcess.Result {
            report.appendLine("BEGIN $label wall=${System.currentTimeMillis()}")
            val result = try { run(command, 1800, limit) }
                catch (e: Exception) { ProbeProcess.Result("", 0, false, null, false, e.toString()) }
            if (result.status != "OK") problems++
            report.appendLine("command=${command.joinToString(" ")}")
            report.appendLine(result.render())
            report.appendLine("END $label elapsed=${(System.nanoTime() - start) / 1_000_000}ms")
            return result
        }
        return try {
            capture("identity", listOf("/system/bin/id"))
            // service list queries registered names/descriptors; it does not invoke vendor controls.
            capture("service-identities", listOf("/system/bin/service", "list"), 24000)
            for (name in listOf("vendor.vivoperfservice", "vendor.vivosymphonyservice", "power_hal_mgr_service"))
                capture("dump:$name", listOf("/system/bin/dumpsys", "-t", "1", name), 20000)
            capture("known-properties", listOf("/system/bin/sh", "-c",
                "for p in ro.build.display.id ro.board.platform vendor.thermal.manager.data.perf vendor.powerhal.init; do echo PROPERTY=\$p; getprop \"\$p\"; done"))
            capture("node-permissions", listOf("/system/bin/sh", "-c",
                "for p in /proc/ppm /proc/cpufreq /proc/perfmgr /proc/perfmgr/boost_ctrl /proc/perfmgr/boost_ctrl/cpu_ctrl /sys/module/mtk_fpsgo/parameters /vendor/etc/.tp; do echo NODE=\$p; ls -ld \"\$p\" 2>&1; ls \"\$p\" 2>&1; done"))
            // 定向读取 ROM 已引用的配置；拒绝、空文件、截断分别保留，不当作成功。
            capture("thermal-summary", listOf("/system/bin/cat", "/dev/thermal/thermal_data_summary.conf"), 180000)
            capture("policy-state", listOf("/system/bin/sh", "-c",
                "getprop persist.vivo.qos.dump; getprop persist.vivo.qos.appstart.cpufreq.support; settings get system power_save_type; settings get system peak_profile; dumpsys battery"))
            // 外部 QoS 入口尚未证实：仅在 activity 帮助明确列出 qos 时查询，不猜 Binder 事务。
            val help = capture("activity-help", listOf("/system/bin/dumpsys", "-t", "1", "activity", "-h"), 24000)
            if (help.status == "OK" && Regex("\\bqos\\b", RegexOption.IGNORE_CASE).containsMatchIn(help.output)) {
                val qos = capture("qos-freq-config", listOf("/system/bin/dumpsys", "-t", "1", "activity", "qos", "c", "5"), 100000)
                report.appendLine("QOS_CONTENT_CONFIRMED=${qos.status == "OK" && qos.output.contains("MAX_FREQ:")}")
            } else report.appendLine("QOS_SKIPPED 外部入口未由帮助确认；不代表策略不存在")
            val listing = capture("config-candidates", listOf("/system/bin/sh", "-c",
                "for d in /vendor/etc /odm/etc /system_ext/etc; do for p in \"\$d\"/*power* \"\$d\"/*perf* \"\$d\"/*/*power* \"\$d\"/*/*perf*; do [ -f \"\$p\" ] && echo \"\$p\"; done; done; exit 0"))
            val paths = configPaths(listing.output)
            report.appendLine("CONFIG_SELECTION count=${paths.size} max=12; xml/conf/json only; unmatched or inaccessible files not inferred absent")
            for (path in paths) capture("config:$path", listOf("/system/bin/cat", path), 12000)
            report.appendLine("ROM_PROBE_COMPLETE issues=$problems configs=${paths.size}; EMPTY/DENIED/TRUNCATED are evidence limits, not successful access")
            report.toString()
        } catch (e: Exception) {
            report.appendLine("ROM_PROBE_FAILED ${e.javaClass.simpleName}: ${e.message}").toString()
        }
    }
}
