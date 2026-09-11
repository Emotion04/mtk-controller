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
 * Node paths are taken from primary sources, but they are **not portable** —
 * MediaTek moves ids and node names between BSP revisions (see
 * `docs/protocol.md` §12). A missing node is therefore normal and is reported
 * as such rather than treated as an error; the presence or absence of the node
 * is itself the capability answer.
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

    suspend fun readAll(context: Context, clusters: List<CpuCluster>): List<Section> = withContext(Dispatchers.IO) {
        listOf(
            limiterState(context, clusters),
            enforcedLimits(clusters),
            uclamp(),
            platformLimiters(),
            thermal(),
            touchBoost(),
        )
    }

    /**
     * Who is currently constraining the CPU, and how hard.
     *
     * The hardware range comes from `cpuinfo_min_freq`/`cpuinfo_max_freq` — the
     * silicon's own limits, which nothing can move. Comparing it against the
     * live `scaling_*` range shows whether something is clamping, and by how
     * much. That gap is invisible in every other view, and it is exactly what a
     * "why did my settings stop working" question turns on.
     *
     * Note that a gap is not proof of a stale request: the platform's own
     * thermal and power-saving logic clamps too. It is a *pointer*, and the
     * power-save and thermal lines below say whether the vendor is the cause.
     */
    private suspend fun limiterState(context: Context, clusters: List<CpuCluster>): Section {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java)

        val readings = mutableListOf<Reading>()

        clusters.forEach { cluster ->
            val dir = "${CpuScanner.CPUFREQ_ROOT}/${cluster.policy}"
            val hardwareMin = Sysfs.read("$dir/cpuinfo_min_freq")?.toLongOrNull()?.div(1000)
            val hardwareMax = Sysfs.read("$dir/cpuinfo_max_freq")?.toLongOrNull()?.div(1000)
            val liveMin = Sysfs.read("$dir/scaling_min_freq")?.toLongOrNull()?.div(1000)
            val liveMax = Sysfs.read("$dir/scaling_max_freq")?.toLongOrNull()?.div(1000)

            val gap = if (hardwareMax != null && liveMax != null) hardwareMax - liveMax else null
            readings += Reading(
                label = "${cluster.policy} 被压低",
                node = "$dir/cpuinfo_max_freq vs scaling_max_freq",
                value = when {
                    gap == null -> null
                    gap <= 0L -> "无(可到 ${hardwareMax}MHz)"
                    else -> "${gap}MHz(硬件 ${hardwareMax} / 当前 ${liveMax})"
                },
                note = if (gap != null && gap > 0L) "有东西在限制这一簇" else null,
            )
            readings += Reading(
                label = "${cluster.policy} 被抬高",
                node = "$dir/cpuinfo_min_freq vs scaling_min_freq",
                value = when {
                    hardwareMin == null || liveMin == null -> null
                    liveMin <= hardwareMin -> "无(可到 ${hardwareMin}MHz)"
                    else -> "+${liveMin - hardwareMin}MHz(硬件 ${hardwareMin} / 当前 ${liveMin})"
                },
            )
        }

        power?.let {
            readings += Reading(
                "省电模式", "PowerManager.isPowerSaveMode",
                if (it.isPowerSaveMode) "开 —— 系统会主动压频率" else "关",
            )
            readings += Reading(
                "热状态", "PowerManager.currentThermalStatus",
                thermalName(it.currentThermalStatus),
            )
        }

        battery?.let {
            val pct = it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            readings += Reading(
                "电池", "BatteryManager",
                if (pct >= 0) "$pct%" + if (it.isCharging) " · 充电中" else " · 未充电" else null,
                note = "低电量时系统与厂商省电逻辑都会主动压频率",
            )
        }

        readings += read(
            "PPM 软上限",
            "$PPM/userlimit_cpu_freq",
            note = "MTK 的 PPM 把软限制落在这里,和 scaling_* 应当一致",
        )

        return Section(
            title = "谁在限制",
            note = "硬件范围 vs 当前范围。差值不为零 = 有东西在压它,不一定是我们的请求",
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
    private suspend fun enforcedLimits(clusters: List<CpuCluster>): Section {
        val readings = mutableListOf<Reading>()

        clusters.forEach { cluster ->
            val dir = "${CpuScanner.CPUFREQ_ROOT}/${cluster.policy}"
            readings += read("${cluster.policy} 下限", "$dir/scaling_min_freq").let {
                it.copy(value = it.value?.freqToMhz())
            }
            readings += read("${cluster.policy} 上限", "$dir/scaling_max_freq").let {
                it.copy(value = it.value?.freqToMhz())
            }
            readings += read("${cluster.policy} 当前", "$dir/scaling_cur_freq").let {
                it.copy(value = it.value?.freqToMhz())
            }
        }

        readings += read(
            "硬限制 (全局)",
            "$PPM/hard_userlimit_cpu_freq",
            note = "这一行有值就说明有客户端写了硬限制,它优先于软上下限",
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
     * This is the modern responsiveness knob: raising `top-app`'s minimum makes
     * the scheduler treat the focused app as heavier, which lifts both the
     * frequency it picks and how readily it uses the big cores.
     */
    private suspend fun uclamp(): Section {
        val readings = UCLAMP_GROUPS.flatMap { group ->
            listOf(
                read("$group · 下限", "$CPUCTL/$group/cpu.uclamp.min"),
                read("$group · 上限", "$CPUCTL/$group/cpu.uclamp.max"),
            )
        }
        return Section(
            title = "调度 (uclamp)",
            note = "0-100。下限抬高调度器对这个任务组的负载估计。作用于整组,不是单个应用",
            readings = readings,
        )
    }

    private suspend fun platformLimiters(): Section = Section(
        title = "平台限制器",
        note = "厂商自己的总频率上限,叠在我们的限制之上",
        readings = listOf(
            read("系统总频率上限", "$PERFMGR/syslimiter/syslimiter_limit_freq"),
            read("Boost 控制", "$PERFMGR/boost_ctrl/cpu_ctrl/cpu_ctrl_enable"),
            read("FPSGO 帧率上限", "$FPSGO/limit_cfreq"),
            read("FPSGO 帧率下限", "$FPSGO/limit_rfreq"),
        ),
    )

    private suspend fun thermal(): Section = Section(
        title = "温控",
        note = "只有读;写温控策略需要替换加密的厂商策略文件,本应用不做",
        readings = listOf(
            read("温控频率上限", "$PPM/thermal_limit"),
            read("当前热功耗", "$PPM/thermal_cur_power"),
            read("温控策略索引", "$PERFMGR/thermal_policy"),
        ),
    )

    private suspend fun touchBoost(): Section = Section(
        title = "触控加速",
        note = "共享节点,MTK 自己也在写",
        readings = listOf(
            read("触控加速状态", "$PERFMGR/tchbst/user/usrtch"),
        ),
    )

    /**
     * Read one node: direct first, then through the elevated shell, because
     * some of these are root-private and a plain app-side open returns nothing.
     */
    private suspend fun read(label: String, node: String, note: String? = null): Reading {
        val value = Sysfs.read(node) ?: Sysfs.readElevated(node)
        return Reading(label = label, node = node, value = value, note = note)
    }

    /** These nodes report kHz; show MHz the way the rest of the app does. */
    private fun String.freqToMhz(): String =
        trim().toLongOrNull()?.let { "${it / 1000} MHz" } ?: trim()
}
