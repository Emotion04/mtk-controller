package magicau.mtkcontroller.data.diag

import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.exec.DaemonExec

/**
 * 只读主机指标：温度、GPU 频率、DDR 频率、内存/Zram 详情。
 *
 * **为什么值得单独做**：本机的 `scaling_min_freq`/`scaling_max_freq` 读不出来，
 * 所以"系统压的频率上限"成因一直没能定位。这些指标全是**读**，不写任何东西，但能直接
 * 观察上限被压时温度、GPU 频率和内存带宽在做什么 —— 那是归因唯一能用的外部证据。
 *
 * **所有命令都经 [DaemonExec] 送到 Shizuku 用户服务里跑**，见 [DaemonExec] 的说明：
 * `/sys` 下的厂商节点对普通应用不可读，在 App 进程里跑只会全部读空。
 *
 * ## 数据来源的实机结论（vivo V2430A / PD2430A，2026-09-23）
 *
 * - **温度**：`/sys/class/thermal/` 在 shell 下被 SELinux 整片拒绝。改用
 *   `dumpsys thermalservice` —— 8 个探头（CPU/GPU/NPU/SOC/TPU/SKIN/BATTERY/PA），
 *   shell 可读取 Android Thermal HAL 的命名温度与状态；这是框架观测值，不是内核原始传感器或厂商限频因果证明。
 * - **GPU/DDR 频率**：`/sys/class/devfreq/13000000.mali/cur_freq` 与
 *   `mtk-dvfsrc-devfreq/cur_freq` 都被 SELinux 拒（`ls -Z` 连标签都取不到，是策略拒绝
 *   而非文件权限位）。设备无 root，所以这两个指标**在这一权限级别拿不到**。路径保留：
 *   少数机型 shell 可读，且加了 root 的 Shizuku 能读。
 */
object HostMetrics {

    /** 四项指标的固定 key，界面按它把读不到的原因对到具体卡片。 */
    const val TEMPERATURE = "temperature"
    const val GPU = "gpu"
    const val DDR = "ddr"
    const val MEMORY = "memory"

    /**
     * 一路温度。值来自 Android Thermal HAL，**单位是摄氏度**（不是 sysfs 的毫摄氏度）。
     *
     * [status] 是 Android 的 [android.os.TemperatureType] 节流档：0 正常，1 轻度，
     * 2 中度，3 重度，4 严重。非 0 说明这一路正在被限 —— 那本身就是"上限被压"的直接证据。
     */
    data class Temperature(val name: String, val celsius: Double, val status: Int)

    /** [source] 是这条读数来自哪个节点；读不到时它是原因说明。 */
    data class Gpu(val busyPercent: Int?, val freqKhz: Long?, val source: Message)

    data class Memory(val totalKb: Long?, val availableKb: Long?, val swapTotalKb: Long?, val swapFreeKb: Long?,
                      val zramOrigKb: Long?, val zramComprKb: Long?)

    /** 一条指标：值 + 它来自哪个节点 + 读不到时为什么读不到。 */
    data class Reading(val value: String?, val node: String?, val note: Message? = null)

    /** 各指标最近一次读不到的原因；读到了就把那一项删掉。 */
    @Volatile
    var unavailableReasons: Map<String, Message> = emptyMap()
        private set

    private val reasons = mutableMapOf<String, Message>()

    private fun start(key: String) { synchronized(reasons) { reasons.remove(key) } }

    private fun note(key: String, reason: Message) {
        synchronized(reasons) { reasons[key] = reason }
        publish()
    }

    private fun publish() {
        unavailableReasons = synchronized(reasons) { reasons.toMap() }
    }

    /** 读一个节点；把失败原因摊开，不让它变成笼统的"不可读"。 */
    private suspend fun readNode(key: String, path: String): String? {
        val out = DaemonExec.runArgv(listOf("/system/bin/cat", path))
        if (out == null) {
            note(key, Message(R.string.metric_no_service))
            return null
        }
        if (out.exit != 0 || out.stdout.isBlank()) {
            val denied = out.stderr.contains("Permission denied", true) ||
                out.stderr.contains("Permission Denial", true)
            note(key, when {
                denied -> Message(R.string.metric_denied, listOf(path))
                out.stderr.isNotBlank() -> Message(
                    R.string.metric_read_failed,
                    listOf(path, out.stderr.lineSequence().first().take(120)),
                )
                else -> Message(R.string.metric_missing, listOf(path))
            })
            return null
        }
        return out.stdout
    }

    /**
     * 温度。来源固定为 `dumpsys thermalservice`。
     *
     * 输出里有两段同格式的温度：`Cached temperatures` 和 `Current temperatures from HAL`。
     * 两段都有同样的探头，按名字去重、后一段覆盖前一段（HAL 段更新）。
     */
    suspend fun temperatures(): List<Temperature> = withContext(Dispatchers.IO) {
        start(TEMPERATURE)
        val out = DaemonExec.runArgv(listOf("/system/bin/dumpsys", "thermalservice"))
        if (out == null) {
            note(TEMPERATURE, Message(R.string.metric_no_service))
            return@withContext emptyList()
        }
        if (out.exit != 0) {
            note(
                TEMPERATURE,
                Message(
                    R.string.metric_dumpsys_failed,
                    listOf(out.stderr.ifBlank { "退出码 ${out.exit}" }),
                ),
            )
            return@withContext emptyList()
        }
        // Temperature{mValue=..., mType=0, mName=CPU, mStatus=...}
        val parsed = linkedMapOf<String, Temperature>()
        for (line in out.stdout.lineSequence()) {
            val match = Regex("Temperature\\{mValue=([-0-9.]+),\\s*mType=(\\d+),\\s*mName=([^,}]+),\\s*mStatus=(\\d+)\\}").find(line)
                ?: continue
            val value = match.groupValues[1].toDoubleOrNull() ?: continue
            val name = match.groupValues[3].trim()
            parsed[name] = Temperature(name, value, match.groupValues[4].toIntOrNull() ?: 0)
        }
        val result = parsed.values.toList()
        if (result.isEmpty()) note(TEMPERATURE, Message(R.string.metric_no_probe))
        result
    }

    suspend fun gpu(): Gpu = withContext(Dispatchers.IO) {
        start(GPU)
        // MTK 的 GED 直接给占用百分比。真机实测该目录在 shell 下被拒，但保留：
        // 少数机型 shell 可读，加了 root 的 Shizuku 一定能读。
        val ged = readNode(GPU, "/sys/kernel/ged/hal/gpu_utilization")
        if (ged != null) {
            // GED 的输出形如 "43"，部分 BSP 带额外字段；只取第一段整数。
            val busy = ged.trim().split(Regex("[^0-9]")).firstOrNull { it.isNotBlank() }?.toIntOrNull()
            if (busy != null) return@withContext Gpu(busy, devfreqFreq(), Message(R.string.metric_source_ged))
        }
        val freq = devfreqFreq()
        if (freq != null) return@withContext Gpu(null, freq, Message(R.string.metric_source_devfreq))
        val kgsl = readNode(GPU, "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
        if (kgsl != null) {
            val busy = kgsl.trim().toIntOrNull()
            if (busy != null) return@withContext Gpu(busy, null, Message(R.string.metric_source_kgsl))
        }
        Gpu(null, null, Message(R.string.metric_gpu_unread))
    }

    /**
     * GPU 频率节点。
     *
     * 真机（PD2430A）上 Mali 的 devfreq 目录名是 **`13000000.mali`**，不是 `mtk-mali`；
     * 早先照抄的 `mtk-mali`/`gpufreq` 在这台机器上根本不存在。两个名字都留着，别的机型
     * 可能还是旧命名。
     */
    private suspend fun devfreqFreq(): Long? =
        readNode(GPU, "/sys/class/devfreq/13000000.mali/cur_freq")?.trim()?.toLongOrNull()
            ?: readNode(GPU, "/sys/class/devfreq/mtk-mali/cur_freq")?.trim()?.toLongOrNull()
            ?: readNode(GPU, "/sys/class/devfreq/gpufreq/cur_freq")?.trim()?.toLongOrNull()

    suspend fun memory(): Memory = withContext(Dispatchers.IO) {
        start(MEMORY)
        val meminfo = readNode(MEMORY, "/proc/meminfo").orEmpty()
        fun field(name: String) = Regex("(?m)^$name:\\s+(\\d+)").find(meminfo)?.groupValues?.get(1)?.toLongOrNull()

        // zram 的 mm_stat 列序（内核文档）：orig_data_size compr_data_size mem_used_total ...
        // 第 0 列是原始大小，第 1 列是压缩后大小。早先两者写反了，压缩率因此是倒过来的。
        val zram = readNode(MEMORY, "/sys/block/zram0/mm_stat")?.trim()?.split(Regex("\\s+"))
        Memory(
            totalKb = field("MemTotal"),
            availableKb = field("MemAvailable"),
            swapTotalKb = field("SwapTotal"),
            swapFreeKb = field("SwapFree"),
            zramOrigKb = zram?.getOrNull(0)?.toLongOrNull(),
            zramComprKb = zram?.getOrNull(1)?.toLongOrNull(),
        )
    }

    suspend fun ddrFreqKhz(): Reading = withContext(Dispatchers.IO) {
        start(DDR)
        // 真机（PD2430A）上 MTK 的 DDR/DVFSRC devfreq 目录名是 `mtk-dvfsrc-devfreq`，
        // 既不是 `ddrfreq` 也不是 `/sys/kernel/dvfsrc/dvfsrc_level`。三个都留着试。
        val candidates = listOf(
            "/sys/class/devfreq/mtk-dvfsrc-devfreq/cur_freq",
            "/sys/class/devfreq/ddrfreq/cur_freq",
            "/sys/class/devfreq/memlat/cur_freq",
            "/sys/kernel/dvfsrc/dvfsrc_level",
        )
        for (node in candidates) {
            val value = readNode(DDR, node)?.trim()?.toLongOrNull()
            if (value != null) return@withContext Reading(value.toString(), node)
        }
        // devfreq 下名字含 ddr 的目录
        val listing = DaemonExec.run("for d in /sys/class/devfreq/*ddr*; do [ -r \"\$d/cur_freq\" ] && cat \"\$d/cur_freq\" && break; done")
        val value = listing?.stdout?.trim()?.toLongOrNull()
        if (value != null) return@withContext Reading(value.toString(), "devfreq/*ddr*/cur_freq")
        val why = unavailableReasons[DDR] ?: Message(R.string.metric_hint_root)
        Reading(null, null, Message(R.string.metric_ddr_caveat, listOf(why)))
    }
}
