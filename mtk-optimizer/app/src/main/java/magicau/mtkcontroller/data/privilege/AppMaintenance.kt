package magicau.mtkcontroller.data.privilege

import java.io.File
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.probe.ProbeProcess

/**
 * 有界的一次性任务；不执行用户输入的 shell 文本。
 *
 * ## 为什么返回 [Outcome] 而不是一个 `"OK:…"` 字符串
 *
 * 这个类跑在 Shizuku 拉起的用户服务进程里，结果要跨 AIDL 回到 App。
 * 以前它返回一句**拼好的中文**，前面挂 `OK:` / `ERROR:` 前缀当成败标记 ——
 * 于是"人看的文案"和"机器判的状态"焊在同一个字符串里，而调用方只能靠
 * `result.startsWith("OK:")` 和 `removePrefix` 去猜。（`PhaseOffset` 就是因为
 * 同一种写法坏掉的，见那个类的注释。）
 *
 * 现在拆开：
 *
 *  - [success] 是状态，机器判
 *  - [message] 是 [Message]，**构造它不需要 `Context`** —— 这正是它存在的理由
 *    （见 `Message.kt` 类头）。解析留给展示边界
 *  - [detail] 是原始输出（`ProbeProcess.render()` 那一坨），给排错用
 *
 * ## 跨进程时传的是资源 id，不是中文
 *
 * 这个类跑在 daemon 进程里，拿不到 App 侧的 `Context`，也不能把 `Message` 直接
 * 送过去。所以 [RuntimeUserService] 把 `message` 编成 `"<resId>"` 放在 AIDL 上，
 * App 侧再用**自己的** `R` 解析 —— 两边的资源表由同一个 APK 产出，id 对得上。
 *
 * `OK:` / `ERROR:` 前缀留在 AIDL 那一层，它是传输格式，不是内容。
 */
object AppMaintenance {

    /** 结果。失败时 [detail] 里带上服务的原始输出与退出码。 */
    data class Outcome(
        val success: Boolean,
        val message: Message,
        val detail: String? = null,
    )

    fun run(kind: String, pkg: String): Outcome {
        if (!Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(pkg)) {
            return Outcome(false, Message(R.string.maintenance_bad_package))
        }
        val path = ProbeProcess.run(listOf("/system/bin/pm", "path", pkg), 5000, 16000)
        if (path.exit != 0 || path.truncated || path.error != null || path.timedOut) {
            return Outcome(false, Message(R.string.maintenance_no_install_path), path.render())
        }
        val files = path.output.lineSequence()
            .filter { it.startsWith("package:") }
            .map { File(it.removePrefix("package:")) }
            .toList()
        if (files.isEmpty() || files.any { !it.isFile || !it.path.endsWith(".apk") }) {
            return Outcome(false, Message(R.string.maintenance_apk_unreadable))
        }
        return when (kind) {
            "compile" -> compile(pkg)
            "warm" -> warm(files)
            else -> Outcome(false, Message(R.string.maintenance_unknown_kind))
        }
    }

    private fun compile(pkg: String): Outcome {
        val result = ProbeProcess.run(
            listOf("/system/bin/cmd", "package", "compile", "-m", "speed-profile", pkg),
            120000,
            12000,
        )
        return if (result.exit == 0 && !result.timedOut && result.error == null &&
            result.output.contains("Success", true)
        ) {
            Outcome(true, Message(R.string.maintenance_compile_ok))
        } else {
            Outcome(false, Message(R.string.maintenance_compile_unconfirmed), result.render())
        }
    }

    private fun warm(files: List<File>): Outcome {
        // `dumpsys battery set` 能让框架报告"已插电"，而实际没有。此时读 APK
        // 是白费 192 MiB / 60 秒，所以先交叉核对内核电源节点再决定。
        val battery = ProbeProcess.run(listOf("/system/bin/dumpsys", "battery"), 2500, 12000)
        val plugged = battery.exit == 0 &&
            Regex("(?m)^\\s*(AC|USB|Wireless|Dock) powered: true\\s*$").containsMatchIn(battery.output)
        if (!plugged) return Outcome(false, Message(R.string.maintenance_need_power))
        if (FakeBattery.isFaked()) return Outcome(false, Message(R.string.maintenance_fake_charge))

        var remaining = 192L * 1024 * 1024
        var loaded = 0L
        val deadline = System.nanoTime() + 60_000_000_000L
        for (file in files) {
            if (remaining <= 0) break
            val bytes = minOf(file.length(), remaining)
            val count = (bytes + 4095) / 4096
            val ms = ((deadline - System.nanoTime()) / 1_000_000).coerceAtMost(15000)
            if (ms <= 0) return Outcome(false, Message(R.string.maintenance_warm_timeout))
            val result = ProbeProcess.run(
                listOf("/system/bin/dd", "if=${file.path}", "of=/dev/null", "bs=4096", "count=$count"),
                ms,
                2048,
            )
            if (result.exit != 0 || result.timedOut || result.error != null) {
                return Outcome(false, Message(R.string.maintenance_warm_read_failed), result.render())
            }
            remaining -= count * 4096
            loaded += bytes
        }
        return Outcome(true, Message(R.string.maintenance_warm_ok, listOf(loaded / 1024 / 1024)))
    }
}
