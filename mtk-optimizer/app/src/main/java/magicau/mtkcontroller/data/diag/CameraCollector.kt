package magicau.mtkcontroller.data.diag
import magicau.mtkcontroller.data.probe.ProbeProcess

/** Runs in Shizuku, independent of Activity scheduling. Each command has its own deadline. */
object CameraCollector {
    fun collect(
        run: (List<String>, Long, Int) -> ProbeProcess.Result = ProbeProcess::run,
        nanoTime: () -> Long = System::nanoTime,
        sleep: (Long) -> Unit = Thread::sleep,
    ): String {
        val report = StringBuilder()
        val start = nanoTime()
        var blocks = 0
        var failures = 0
        var truncated = 0
        var dropped = 0
        var samples = 0
        val healthy = mutableMapOf<String, Int>()
        fun elapsed() = (nanoTime() - start) / 1_000_000
        fun capture(label: String, command: List<String>, limit: Int = 10000) {
            report.appendLine("BEGIN $label elapsed=${elapsed()}ms wall=${System.currentTimeMillis()}")
            val result = run(command, 1800, limit)
            blocks++
            if (result.status !in listOf("OK", "TRUNCATED")) failures++
            if (result.truncated) truncated++
            if (result.status == "OK") {
                val source = label.substringBefore(" round=")
                healthy[source] = (healthy[source] ?: 0) + 1
            }
            if (report.length + result.output.length < 220_000) report.appendLine(result.render())
            else { dropped++; report.appendLine("REPORT_BUDGET_EXHAUSTED status=${result.status} bytes=${result.bytes}") }
            report.appendLine("END $label elapsed=${elapsed()}ms")
        }
        return try {
            report.appendLine("CAMERA_COLLECTOR_V2 time segments are not camera state; no frequency writes")
            capture("identity", listOf("/system/bin/id"))
            capture("services", listOf("/system/bin/dumpsys", "-l"))
            capture("vendor-endpoints", listOf("/system/bin/sh", "-c",
                "for p in /proc/ppm /proc/cpufreq /sys/kernel/ged/hal; do echo NODE=\$p; ls -l \"\$p\" 2>&1; done"))
            var nextSample = 0L
            var round = 0
            while (elapsed() < 60_000) {
                if (elapsed() >= nextSample) {
                    capture("frequencies-and-busy", listOf("/system/bin/sh", "-c",
                        "for p in /sys/devices/system/cpu/cpufreq/policy*; do echo POLICY=\$p; for n in scaling_min_freq scaling_max_freq scaling_cur_freq; do echo \$n; cat \"\$p/\$n\" 2>&1; done; done; head -n 9 /proc/stat"), 6000)
                    samples++
                    nextSample = elapsed() + 2000
                }
                if (elapsed() >= round * 15000L && round < 4) {
                    for (service in listOf("media.camera", "power", "thermalservice", "power_hal_mgr_service")) {
                        if (elapsed() >= 60_000) break
                        capture("service:$service round=$round", listOf("/system/bin/dumpsys", "-t", "1", service))
                    }
                    round++
                }
                sleep(100)
            }
            report.appendLine("CAMERA_PROBE_COMPLETE elapsed=${elapsed()}ms")
            val missing = listOf("service:media.camera", "service:power", "service:thermalservice").filter { (healthy[it] ?: 0) < 4 }
            report.appendLine("SELF_CHECK blocks=$blocks samples=$samples incompleteOrUnavailable=$failures truncated=$truncated budgetDropped=$dropped criticalNotFullyRead=$missing; camera transitions not automatically verified")
            report.toString()
        } catch (e: Exception) {
            report.appendLine("CAMERA_PROBE_FAILED ${e.javaClass.simpleName}: ${e.message}").toString()
        }
    }
}
