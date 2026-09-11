package magicau.mtkcontroller.data.lab

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.cpu.CpuScanner
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.domain.model.CpuCluster

/**
 * Reads the kernel nodes that say what the platform is *actually* enforcing.
 *
 * Every other feature in this app can only report what it asked for. This is
 * the one place that reports what the device decided, which is why it is the
 * first thing built and the thing to check whenever something looks wrong.
 *
 * Read-only by construction: nothing here writes.
 *
 * **All nodes are fetched in one batched call.** Reading them one at a time,
 * each with its own elevated fallback, spawns a shell process per unreadable
 * node — and on a device that lacks most of what we probe, that is dozens of
 * spawns per visit, expensive enough to show up as stutter across the whole
 * system.
 *
 * Node paths come from primary sources but are **not portable** — MediaTek moves
 * ids and node names between BSP revisions (see `docs/protocol.md` §11). A
 * missing node is therefore normal and reported as such; its presence is itself
 * the capability answer.
 */
object LabProbe {

    data class Reading(
        val label: String,
        val node: String,
        /** Trimmed contents, or null when the node could not be read at all. */
        val value: String?,
        /** Set when the value needs interpreting, e.g. units differ per platform. */
        val note: String? = null,
    ) {
        val exists: Boolean get() = value != null
    }

    data class Section(
        val title: String,
        val note: String? = null,
        val readings: List<Reading>,
    )

    private const val PPM = "/proc/ppm/policy"
    private const val PERFMGR = "/proc/perfmgr"
    private const val CPUCTL = "/dev/cpuctl"
    private const val FPSGO = "/sys/kernel/fpsgo/fbt"

    /** Uclamp groups worth showing, most to least privileged. */
    private val UCLAMP_GROUPS = listOf("top-app", "foreground", "background")

    private fun freqDir(policy: String) = "${CpuScanner.CPUFREQ_ROOT}/$policy"

    suspend fun readAll(context: Context, clusters: List<CpuCluster>): List<Section> =
        withContext(Dispatchers.IO) {
            // One batch, then every section is built from the resulting map.
            val values = Sysfs.readMany(allPaths(clusters))
            fun value(path: String) = values[path]

            listOf(
                limiterState(context, clusters, ::value),
                enforcedLimits(clusters, ::value),
                uclamp(::value),
                platformLimiters(::value),
                thermal(::value),
                touchBoost(::value),
            )
        }

    /** Every node any section reads. Kept beside the readers so they stay in step. */
    private fun allPaths(clusters: List<CpuCluster>): List<String> = buildList {
        clusters.forEach { c ->
            val dir = freqDir(c.policy)
            add("$dir/cpuinfo_min_freq")
            add("$dir/cpuinfo_max_freq")
            add("$dir/scaling_min_freq")
            add("$dir/scaling_max_freq")
            add("$dir/scaling_cur_freq")
        }
        add("$PPM/hard_userlimit_cpu_freq")
        add("$PPM/userlimit_cpu_freq")
        add("$PPM/thermal_limit")
        add("$PPM/thermal_cur_power")
        add("$PERFMGR/thermal_policy")
        add("$PERFMGR/syslimiter/syslimiter_limit_freq")
        add("$PERFMGR/boost_ctrl/cpu_ctrl/cpu_ctrl_enable")
        add("$PERFMGR/tchbst/user/usrtch")
        add("/proc/cpufreq/cpufreq_cci_mode")
        UCLAMP_GROUPS.forEach { group ->
            add("$CPUCTL/$group/cpu.uclamp.min")
            add("$CPUCTL/$group/cpu.uclamp.max")
        }
        add("$FPSGO/limit_cfreq")
        add("$FPSGO/limit_rfreq")
    }

    /**
     * Who is currently constraining the CPU, and how hard.
     *
     * The hardware range comes from `cpuinfo_min_freq`/`cpuinfo_max_freq` — the
     * silicon's own limits, which nothing can move. Comparing it against the
     * live `scaling_*` range shows whether something is clamping, and by how
     * much, which is the question a "my settings stopped working" report turns
     * on.
     *
     * A gap is **not** proof of a stale request. The platform's own thermal and
     * power-saving logic clamps too, and on the development device two releases
     * of the same build read back 2200 MHz and 2400 MHz with nothing of ours
     * applied. That is why the power-save, thermal and battery lines sit in the
     * same section: they are what tells the two causes apart.
     */
    private fun limiterState(
        context: Context,
        clusters: List<CpuCluster>,
        value: (String) -> String?,
    ): Section {
        val readings = mutableListOf<Reading>()

        clusters.forEach { cluster ->
            val dir = freqDir(cluster.policy)
            val hardwareMax = value("$dir/cpuinfo_max_freq")?.tooLong()?.div(1000)
            val liveMax = value("$dir/scaling_max_freq")?.tooLong()?.div(1000)
            val hardwareMin = value("$dir/cpuinfo_min_freq")?.tooLong()?.div(1000)
            val liveMin = value("$dir/scaling_min_freq")?.tooLong()?.div(1000)

            val drop = if (hardwareMax != null && liveMax != null) hardwareMax - liveMax else null
            readings += Reading(
                label = "${cluster.policy} 上限被压低",
                node = "$dir/cpuinfo_max_freq  vs  scaling_max_freq",
                value = when {
                    drop == null -> null
                    drop <= 0L -> "无(可到 ${hardwareMax}MHz)"
                    else -> "${drop}MHz(硬件 ${hardwareMax} / 当前 ${liveMax})"
                },
                note = if (drop != null && drop > 0L) "上限低于硬件 —— 有东西在压它" else null,
            )

            val lift = if (hardwareMin != null && liveMin != null) liveMin - hardwareMin else null
            readings += Reading(
                label = "${cluster.policy} 下限被抬高",
                node = "$dir/cpuinfo_min_freq  vs  scaling_min_freq",
                value = when {
                    lift == null -> null
                    lift <= 0L -> "无(可到 ${hardwareMin}MHz)"
                    else -> "+${lift}MHz(硬件 ${hardwareMin} / 当前 ${liveMin})"
                },
                note = if (lift != null && lift > 0L) "下限高于硬件 —— 有东西钉着它" else null,
            )
        }

        context.getSystemService(PowerManager::class.java)?.let { power ->
            readings += Reading(
                "省电模式", "PowerManager.isPowerSaveMode",
                if (power.isPowerSaveMode) "开 —— 系统会主动压频率" else "关",
            )
            readings += Reading(
                "热状态", "PowerManager.currentThermalStatus",
                thermalName(power.currentThermalStatus),
            )
        }

        context.getSystemService(BatteryManager::class.java)?.let { battery ->
            val pct = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct >= 0) {
                readings += Reading(
                    "电池", "BatteryManager",
                    "$pct%" + if (battery.isCharging) " · 充电中" else " · 未充电",
                    note = "低电量时系统和厂商的省电逻辑都会压频率",
                )
            }
        }

        readings += Reading(
            "PPM 软上限", "$PPM/userlimit_cpu_freq",
            value("$PPM/userlimit_cpu_freq"),
            note = "MTK 的 PPM 把软限制落在这里,应与 scaling_* 一致",
        )

        return Section(
            title = "谁在限制",
            note = "硬件范围 vs 当前范围。差值不为零 = 有东西在压,不一定是我们的请求",
            readings = readings,
        )
    }

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE · 正常"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT · 轻微"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE · 中度"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE · 严重"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL · 临界"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY · 紧急"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN · 即将关机"
        else -> "未知($status)"
    }

    /** What the kernel is holding right now, per cluster and globally. */
    private fun enforcedLimits(clusters: List<CpuCluster>, value: (String) -> String?): Section {
        val readings = mutableListOf<Reading>()
        clusters.forEach { cluster ->
            val dir = freqDir(cluster.policy)
            readings += freqReading("${cluster.policy} 下限", "$dir/scaling_min_freq", value)
            readings += freqReading("${cluster.policy} 上限", "$dir/scaling_max_freq", value)
            readings += freqReading("${cluster.policy} 当前", "$dir/scaling_cur_freq", value)
        }
        readings += Reading(
            "硬限制 (全局)", "$PPM/hard_userlimit_cpu_freq",
            value("$PPM/hard_userlimit_cpu_freq"),
            note = "有值就说明有客户端写了硬限制;节点不存在时这类写入是空操作",
        )
        return Section(
            title = "当前生效的限制",
            note = "内核实际在执行的,不是我们请求的",
            readings = readings,
        )
    }

    /**
     * Scheduler utilization clamping, per task group.
     *
     * The modern responsiveness knob: raising `top-app`'s minimum makes the
     * scheduler treat the focused app as heavier, lifting both the frequency it
     * picks and how readily it uses the big cores.
     */
    private fun uclamp(value: (String) -> String?): Section = Section(
        title = "调度 (uclamp)",
        note = "0-100。作用于整个任务组,不是单个应用",
        readings = UCLAMP_GROUPS.flatMap { group ->
            listOf(
                Reading("$group · 下限", "$CPUCTL/$group/cpu.uclamp.min", value("$CPUCTL/$group/cpu.uclamp.min")),
                Reading("$group · 上限", "$CPUCTL/$group/cpu.uclamp.max", value("$CPUCTL/$group/cpu.uclamp.max")),
            )
        },
    )

    private fun platformLimiters(value: (String) -> String?): Section = Section(
        title = "平台限制器",
        note = "厂商自己的上限,叠在我们的限制之上",
        readings = listOf(
            Reading("系统总频率上限", "$PERFMGR/syslimiter/syslimiter_limit_freq", value("$PERFMGR/syslimiter/syslimiter_limit_freq")),
            Reading("Boost 控制", "$PERFMGR/boost_ctrl/cpu_ctrl/cpu_ctrl_enable", value("$PERFMGR/boost_ctrl/cpu_ctrl/cpu_ctrl_enable")),
            Reading("FPSGO 帧率上限", "$FPSGO/limit_cfreq", value("$FPSGO/limit_cfreq")),
            Reading("FPSGO 帧率下限", "$FPSGO/limit_rfreq", value("$FPSGO/limit_rfreq")),
        ),
    )

    private fun thermal(value: (String) -> String?): Section = Section(
        title = "温控",
        note = "只读;写温控策略需要替换加密的厂商策略文件,本应用不做",
        readings = listOf(
            Reading("温控频率上限", "$PPM/thermal_limit", value("$PPM/thermal_limit")),
            Reading("当前热功耗", "$PPM/thermal_cur_power", value("$PPM/thermal_cur_power")),
            Reading("温控策略索引", "$PERFMGR/thermal_policy", value("$PERFMGR/thermal_policy")),
        ),
    )

    private fun touchBoost(value: (String) -> String?): Section = Section(
        title = "触控加速",
        note = "共享节点,MTK 自己也在写",
        readings = listOf(
            Reading("触控加速状态", "$PERFMGR/tchbst/user/usrtch", value("$PERFMGR/tchbst/user/usrtch")),
            Reading("CCI 模式", "/proc/cpufreq/cpufreq_cci_mode", value("/proc/cpufreq/cpufreq_cci_mode")),
        ),
    )

    private fun freqReading(label: String, node: String, value: (String) -> String?): Reading {
        val raw = value(node)
        return Reading(label, node, raw?.let { "${it.tooLong()?.div(1000) ?: it} MHz" })
    }

    /** These nodes report kHz; parse without throwing on anything unexpected. */
    private fun String.tooLong(): Long? = trim().toLongOrNull()
}
