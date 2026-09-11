package magicau.mtkcontroller.data.diag

import android.os.Build
import androidx.annotation.StringRes
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.log.AppLog
import java.io.File

/**
 * 环境检测：root 管理器、Frida、Xposed/LSPosed 的痕迹。
 *
 * **只读，而且刻意去掉两类"检测"**：
 *  - 不自伤。这类工具常有"检测到调试就退出/自杀"，本软件故意不做 —— 用户自己可能
 *    装着 LSPosed 或 Frida 用于别的事，为此把整个 App 关掉没有意义。
 *  - 不查 `/system/bin/su`。用户在 root 模式下用 adb 时会误命中，而 root 模式恰恰是
 *    本软件 `emergencyRestore` 明确支持的用法。
 *
 * 结论只进诊断报告，不改变任何功能是否可用。
 */
object EnvironmentProbe {

    /**
     * [nameRes] 是类别名（用户文案），[detail] 是具体证据 —— 包名、路径、端口，
     * 按数据处理。
     */
    data class Finding(@StringRes val nameRes: Int, val detail: String, val severity: String)

    private val ROOT_MANAGERS = listOf(
        "com.topjohnwu.magisk", "me.weishu.kernelsu", "com.rifsxd.ksunext",
        "eu.chainfire.supersu", "com.koushikdutta.superuser", "com.noshufou.android.su",
        "com.thirdparty.superuser", "com.yellowes.su", "com.kingroot.kinguser",
        "com.kingo.root", "com.smedialink.oneclickroot", "com.zhiqupk.root.global",
    )

    private val FRIDA_PORTS = intArrayOf(27042, 27043)
    private val FRIDA_PATHS = listOf(
        "/data/local/tmp/frida-server", "/data/local/tmp/re.frida.server",
        "/sdcard/frida-server", "/system/lib/frida-agent.so",
    )
    private val XPOSED_MARKERS = listOf(
        "/system/framework/XposedBridge.jar", "/data/misc/riru/modules",
        "/data/adb/lspd", "/data/adb/riru", "/system/lib/libriru.so",
    )

    fun collect(packageInstalled: (String) -> Boolean): List<Finding> {
        val findings = mutableListOf<Finding>()

        for (pkg in ROOT_MANAGERS) {
            if (packageInstalled(pkg)) {
                findings += Finding(R.string.env_finding_root, pkg, "info")
                AppLog.i("EnvProbe", "检测到 root 管理器 $pkg")
            }
        }

        for (port in FRIDA_PORTS) {
            if (portOpen(port)) findings += Finding(R.string.env_finding_frida_port, "127.0.0.1:$port 有监听", "warn")
        }
        for (path in FRIDA_PATHS) {
            if (File(path).exists()) findings += Finding(R.string.env_finding_frida_file, path, "warn")
        }
        for (path in XPOSED_MARKERS) {
            if (File(path).exists()) findings += Finding(R.string.env_finding_xposed, path, "warning")
        }

        if (Build.TAGS?.contains("test-keys") == true)
            findings += Finding(R.string.env_finding_signature, "Build.TAGS=${Build.TAGS}", "info")

        return findings
    }

    private fun portOpen(port: Int): Boolean = try {
        java.net.Socket().use {
            it.connect(java.net.InetSocketAddress("127.0.0.1", port), 120)
            true
        }
    } catch (_: Exception) {
        false
    }

    /** 诊断报告里的一节。报告正文是固定格式的字段名与设备原文，按数据处理。 */
    fun section(findings: List<Finding>): String = buildString {
        appendLine("\n环境检测（只读；不自伤，不查询 su；结论不影响功能可用性）")
        appendLine("ABI=${Build.SUPPORTED_ABIS.joinToString()} SECURITY_PATCH=${Build.VERSION.SECURITY_PATCH}")
        if (findings.isEmpty()) appendLine("未检测到 root 管理器 / Frida / Xposed 痕迹")
        else for (f in findings) appendLine("  [${f.severity}] ${f.nameRes}: ${f.detail}")
    }

    /**
     * 最近一次检测结果，供诊断报告直接引用。
     *
     * 由界面进入"本机状态与工具"时刷新；没进过那页面就是空，报告里照实写"未检测"。
     */
    @Volatile
    var findings: List<Finding> = emptyList()
        private set

    fun remember(findings: List<Finding>) {
        this.findings = findings
    }
}
