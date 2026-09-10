package magicau.mtkcontroller.data.diag

import android.os.Build
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.log.LogEntry
import magicau.mtkcontroller.data.powerhal.PowerHal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A plain-text snapshot of everything needed to work out how this device
 * behaves, meant to be copied or exported and sent along as-is.
 *
 * The point is that a device we have never seen can be characterised from the
 * text alone: which cpufreq policies exist and in what order (that mapping is
 * what the command ids are derived from), what the PowerHAL interface answers,
 * what the kernel limits were before and after, and what the app actually sent.
 * Free-form prose is avoided so the report stays greppable.
 */
object DiagReport {

    fun build(report: CapabilityReport?, entries: List<LogEntry>): String = buildString {
        appendLine("# MTK God 诊断报告")
        appendLine("# 生成时间: ${timestamp(System.currentTimeMillis())}")
        appendLine("# 把整份内容发回即可用于适配本机型")
        appendLine()

        section("设备")
        appendLine("manufacturer : ${Build.MANUFACTURER}")
        appendLine("model        : ${Build.MODEL}")
        appendLine("device       : ${Build.DEVICE}")
        appendLine("hardware     : ${Build.HARDWARE}")
        appendLine("soc_manu     : ${Build.SOC_MANUFACTURER}")
        appendLine("soc_model    : ${Build.SOC_MODEL}")
        appendLine("board        : ${Build.BOARD}")
        appendLine("android      : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("build_id     : ${Build.DISPLAY}")
        appendLine()

        section("应用")
        appendLine("interface    : ${PowerHal.INTERFACE_TOKEN}")
        appendLine("service      : ${PowerHal.SERVICE_NAME}")
        appendLine()

        if (report == null) {
            appendLine("(尚未探测:请先打开 设置 → 诊断信息 并点一次「重新探测」)")
            appendLine()
        } else {
            section("提权")
            appendLine("mode         : ${report.privilege.mode}")
            appendLine("uid          : ${report.privilege.uid}")
            appendLine("permission   : ${report.privilege.permissionGranted}")
            appendLine("binder_alive : ${report.privilege.binderAlive}")
            appendLine("shizuku_ver  : ${report.privilege.shizukuVersion}")
            appendLine("user_service : bound=${report.remoteBound} uid=${report.remoteUid}")
            appendLine("powerhal     : ${report.powerHalAvailable}")
            appendLine()

            section("CPU 簇(命令索引由此顺序推导)")
            if (report.cpuClusters.isEmpty()) {
                appendLine("(未检测到 policy 目录)")
            } else {
                report.cpuClusters.forEach { c ->
                    appendLine(
                        "index=${c.index} policy=${c.policy} cpus=${c.cpus.joinToString(",")} " +
                            "driver=${c.driver} governor=${c.currentGovernor} " +
                            "governorWritable=${c.governorWritable} " +
                            "freqs=${c.availableFreqs.size}档 " +
                            "range=${c.minFreqKhz / 1000}-${c.maxFreqKhz / 1000}MHz",
                    )
                }
            }
            appendLine()

            section("GPU")
            appendLine("channel      : ${report.gpu.channel}")
            appendLine("freqs        : ${report.gpu.availableFreqs.size}档")
            appendLine("governor     : ${report.gpu.governor}")
            appendLine()

            section("探测项")
            report.checks.forEach { check ->
                appendLine("[${if (check.ok) "OK " else "NG "}] ${check.category.title} / ${check.label}")
                check.detail.lineSequence().forEach { appendLine("        $it") }
            }
            appendLine()
        }

        section("运行日志(${entries.size} 条)")
        if (entries.isEmpty()) {
            appendLine("(空)")
        } else {
            entries.forEach { appendLine(it.format()) }
        }
    }

    private fun StringBuilder.section(title: String) {
        appendLine("=".repeat(60))
        appendLine("== $title")
        appendLine("=".repeat(60))
    }

    fun timestamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
