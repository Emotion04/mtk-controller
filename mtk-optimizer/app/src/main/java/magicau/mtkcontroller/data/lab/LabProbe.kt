package magicau.mtkcontroller.data.lab

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.cpu.CpuFreqNodes
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
 * ids and node names between BSP revisions (see `docs/cpu/protocol.md` §11). A
 * missing node is therefore normal and reported as such; its presence is itself
 * the capability answer.
 */
object LabProbe {

    data class Reading(
        @StringRes val labelRes: Int,
        val node: String,
        /** Trimmed contents, or null when the node could not be read at all. */
        val value: String?,
        /** Set when the value needs interpreting, e.g. units differ per platform. 0 = 无。 */
        @StringRes val noteRes: Int = 0,
        /** [labelRes] 的占位符参数。 */
        val labelArgs: List<Any> = emptyList(),
        /** [noteRes] 的占位符参数。 */
        val noteArgs: List<Any> = emptyList(),
    ) {
        val exists: Boolean get() = value != null
    }

    data class Section(
        @StringRes val titleRes: Int,
        /** 0 = 无。 */
        @StringRes val noteRes: Int = 0,
        val readings: List<Reading>,
    )

    // 2026-10-14 真机核对：这台机器没有 /proc/ppm 整个目录（`ls` 是 ENOENT，
    // 不是权限拒绝）。原来这里读四条 PPM 节点、界面还有一行「PPM 软上限」，
    // 全是死行。删掉，不再每轮读四个不存在的路径。
    private const val PERFMGR = "/proc/perfmgr"
    private const val CPUCTL = "/dev/cpuctl"
    private const val FPSGO = "/sys/kernel/fpsgo/fbt"

    /** Uclamp groups worth showing, most to least privileged. */
    private val UCLAMP_GROUPS = listOf("top-app", "foreground", "background")

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
            // 五个节点的路径全部来自 CpuFreqNodes。这里是"一次性把该读的都读回来"，
            // 和逐条直读不是同一件事，但节点名必须只有一份。
            add(CpuFreqNodes.hardwareMin(c.policy))
            add(CpuFreqNodes.hardwareMax(c.policy))
            add(CpuFreqNodes.min(c.policy))
            add(CpuFreqNodes.max(c.policy))
            add(CpuFreqNodes.cur(c.policy))
        }
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
            val hardwareMax = value(CpuFreqNodes.hardwareMax(cluster.policy))?.tooLong()?.div(1000)
            val liveMax = value(CpuFreqNodes.max(cluster.policy))?.tooLong()?.div(1000)
            val hardwareMin = value(CpuFreqNodes.hardwareMin(cluster.policy))?.tooLong()?.div(1000)
            val liveMin = value(CpuFreqNodes.min(cluster.policy))?.tooLong()?.div(1000)

            val drop = if (hardwareMax != null && liveMax != null) hardwareMax - liveMax else null
            readings += Reading(
                labelRes = R.string.lab_probe_label_limit_lowered,
                labelArgs = listOf(cluster.policy),
                node = "${CpuFreqNodes.hardwareMax(cluster.policy)}  vs  ${CpuFreqNodes.max(cluster.policy)}",
                value = when {
                    drop == null -> null
                    drop <= 0L -> context.getString(R.string.lab_probe_value_limit_none, hardwareMax.toString())
                    else -> context.getString(R.string.lab_probe_value_limit_drop, drop.toString(), hardwareMax.toString(), liveMax.toString())
                },
                noteRes = if (drop != null && drop > 0L) R.string.lab_probe_note_lowered else 0,
            )

            val lift = if (hardwareMin != null && liveMin != null) liveMin - hardwareMin else null
            readings += Reading(
                labelRes = R.string.lab_probe_label_limit_lifted,
                labelArgs = listOf(cluster.policy),
                node = "${CpuFreqNodes.hardwareMin(cluster.policy)}  vs  ${CpuFreqNodes.min(cluster.policy)}",
                value = when {
                    lift == null -> null
                    lift <= 0L -> context.getString(R.string.lab_probe_value_limit_none, hardwareMin.toString())
                    else -> context.getString(R.string.lab_probe_value_limit_lift, lift.toString(), hardwareMin.toString(), liveMin.toString())
                },
                noteRes = if (lift != null && lift > 0L) R.string.lab_probe_note_lifted else 0,
            )
        }

        context.getSystemService(PowerManager::class.java)?.let { power ->
            readings += Reading(
                R.string.lab_probe_label_power_save, "PowerManager.isPowerSaveMode",
                if (power.isPowerSaveMode) context.getString(R.string.lab_probe_value_power_save_on)
                else context.getString(R.string.lab_probe_value_off),
            )
            readings += Reading(
                R.string.lab_probe_label_thermal_status, "PowerManager.currentThermalStatus",
                thermalName(context, power.currentThermalStatus),
            )
        }

        context.getSystemService(BatteryManager::class.java)?.let { battery ->
            val pct = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct >= 0) {
                readings += Reading(
                    R.string.lab_probe_label_battery, "BatteryManager",
                    context.getString(
                        if (battery.isCharging) R.string.lab_probe_value_battery_charging
                        else R.string.lab_probe_value_battery_idle,
                        pct,
                    ),
                    noteRes = R.string.lab_probe_note_battery,
                )
            }
        }

        return Section(
            titleRes = R.string.lab_probe_section_limiter,
            noteRes = R.string.lab_probe_section_limiter_note,
            readings = readings,
        )
    }

    private fun thermalName(context: Context, status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> context.getString(R.string.lab_probe_thermal_none)
        PowerManager.THERMAL_STATUS_LIGHT -> context.getString(R.string.lab_probe_thermal_light)
        PowerManager.THERMAL_STATUS_MODERATE -> context.getString(R.string.lab_probe_thermal_moderate)
        PowerManager.THERMAL_STATUS_SEVERE -> context.getString(R.string.lab_probe_thermal_severe)
        PowerManager.THERMAL_STATUS_CRITICAL -> context.getString(R.string.lab_probe_thermal_critical)
        PowerManager.THERMAL_STATUS_EMERGENCY -> context.getString(R.string.lab_probe_thermal_emergency)
        PowerManager.THERMAL_STATUS_SHUTDOWN -> context.getString(R.string.lab_probe_thermal_shutdown)
        else -> context.getString(R.string.lab_probe_thermal_unknown, status)
    }

    /** What the kernel is holding right now, per cluster and globally. */
    private fun enforcedLimits(clusters: List<CpuCluster>, value: (String) -> String?): Section {
        val readings = mutableListOf<Reading>()
        clusters.forEach { cluster ->
            readings += freqReading(R.string.lab_probe_label_cluster_min, listOf(cluster.policy), CpuFreqNodes.min(cluster.policy), value)
            readings += freqReading(R.string.lab_probe_label_cluster_max, listOf(cluster.policy), CpuFreqNodes.max(cluster.policy), value)
            readings += freqReading(R.string.lab_probe_label_cluster_cur, listOf(cluster.policy), CpuFreqNodes.cur(cluster.policy), value)
        }
        return Section(
            titleRes = R.string.lab_probe_section_enforced,
            noteRes = R.string.lab_probe_section_enforced_note,
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
        titleRes = R.string.lab_probe_section_uclamp,
        noteRes = R.string.lab_probe_section_uclamp_note,
        readings = UCLAMP_GROUPS.flatMap { group ->
            listOf(
                Reading(R.string.lab_probe_label_uclamp_min, "$CPUCTL/$group/cpu.uclamp.min", value("$CPUCTL/$group/cpu.uclamp.min"), labelArgs = listOf(group)),
                Reading(R.string.lab_probe_label_uclamp_max, "$CPUCTL/$group/cpu.uclamp.max", value("$CPUCTL/$group/cpu.uclamp.max"), labelArgs = listOf(group)),
            )
        },
    )

    private fun platformLimiters(value: (String) -> String?): Section = Section(
        titleRes = R.string.lab_probe_section_platform,
        noteRes = R.string.lab_probe_section_platform_note,
        readings = listOf(
            Reading(R.string.lab_probe_label_syslimiter, "$PERFMGR/syslimiter/syslimiter_limit_freq", value("$PERFMGR/syslimiter/syslimiter_limit_freq")),
            Reading(R.string.lab_probe_label_boost_ctrl, "$PERFMGR/boost_ctrl/cpu_ctrl/cpu_ctrl_enable", value("$PERFMGR/boost_ctrl/cpu_ctrl/cpu_ctrl_enable")),
            Reading(R.string.lab_probe_label_fpsgo_max, "$FPSGO/limit_cfreq", value("$FPSGO/limit_cfreq")),
            Reading(R.string.lab_probe_label_fpsgo_min, "$FPSGO/limit_rfreq", value("$FPSGO/limit_rfreq")),
        ),
    )

    private fun thermal(value: (String) -> String?): Section = Section(
        titleRes = R.string.lab_probe_section_thermal,
        noteRes = R.string.lab_probe_section_thermal_note,
        readings = listOf(
            Reading(R.string.lab_probe_label_thermal_policy, "$PERFMGR/thermal_policy", value("$PERFMGR/thermal_policy")),
        ),
    )

    private fun touchBoost(value: (String) -> String?): Section = Section(
        titleRes = R.string.lab_probe_section_touch,
        noteRes = R.string.lab_probe_section_touch_note,
        readings = listOf(
            Reading(R.string.lab_probe_label_touch_state, "$PERFMGR/tchbst/user/usrtch", value("$PERFMGR/tchbst/user/usrtch")),
            Reading(R.string.lab_probe_label_cci_mode, "/proc/cpufreq/cpufreq_cci_mode", value("/proc/cpufreq/cpufreq_cci_mode")),
        ),
    )

    private fun freqReading(@StringRes labelRes: Int, labelArgs: List<Any>, node: String, value: (String) -> String?): Reading {
        val raw = value(node)
        return Reading(labelRes, node, raw?.let { "${it.tooLong()?.div(1000) ?: it} MHz" }, labelArgs = labelArgs)
    }

    /** These nodes report kHz; parse without throwing on anything unexpected. */
    private fun String.tooLong(): Long? = trim().toLongOrNull()
}

