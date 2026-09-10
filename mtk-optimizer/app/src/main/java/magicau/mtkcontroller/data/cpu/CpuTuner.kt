package magicau.mtkcontroller.data.cpu

import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.domain.model.ClusterSetting
import magicau.mtkcontroller.domain.model.CpuCluster

/**
 * Applies CPU frequency limits.
 *
 * Frequencies go through MTK PowerHAL, which works without root and — unlike
 * the reference app, which sent one value for all four commands — lets us send
 * a genuine min/max range per cluster.
 *
 * The governor is a kernel cpufreq knob with no PowerHAL equivalent, so it is
 * written through sysfs and only works when the node is writable (root).
 */
class CpuTuner {

    data class Outcome(
        val success: Boolean,
        val message: String,
        val handler: Int? = null,
        val governorResults: Map<String, Boolean> = emptyMap(),
    )

    /**
     * @param clusters scanned clusters, used to map policy name -> command index
     * @param settings the requested limits; entries whose policy is absent are skipped
     */
    suspend fun apply(
        clusters: List<CpuCluster>,
        settings: List<ClusterSetting>,
    ): Outcome {
        if (clusters.isEmpty()) return Outcome(false, "未检测到 CPU 簇")
        if (settings.isEmpty()) return Outcome(false, "未选择任何簇")

        val byPolicy = clusters.associateBy { it.policy }
        val pairs = mutableListOf<Pair<String, String>>()

        for (setting in settings) {
            val cluster = byPolicy[setting.policy] ?: continue
            val min = cluster.nearest(setting.minFreqKhz)
            val max = cluster.nearest(setting.maxFreqKhz)
            val low = min.coerceAtMost(max)
            val high = min.coerceAtLeast(max)

            // Order matches the reference app: min, max, thermal-min, thermal-max.
            pairs += PowerHal.commandId(PowerHal.BASE_MIN, cluster.index) to low.toString()
            pairs += PowerHal.commandId(PowerHal.BASE_MAX, cluster.index) to high.toString()
            pairs += PowerHal.commandId(PowerHal.BASE_THERMAL_MIN, cluster.index) to low.toString()
            pairs += PowerHal.commandId(PowerHal.BASE_THERMAL_MAX, cluster.index) to high.toString()
        }

        if (pairs.isEmpty()) return Outcome(false, "没有匹配到可用的簇")

        val result = PowerHal.acquire(pairs)
        if (result is PowerHal.Result.Failure) {
            return Outcome(false, result.message)
        }
        val handler = (result as PowerHal.Result.Success).handler

        // Governor is best-effort: it needs a writable node, so failures are reported
        // per-cluster rather than failing the whole apply.
        val governorResults = mutableMapOf<String, Boolean>()
        for (setting in settings) {
            val governor = setting.governor ?: continue
            val cluster = byPolicy[setting.policy] ?: continue
            val node = "${CpuScanner.CPUFREQ_ROOT}/${cluster.policy}/scaling_governor"
            governorResults[cluster.policy] = Sysfs.write(node, governor)
        }

        return Outcome(
            success = true,
            message = result.message,
            handler = handler,
            governorResults = governorResults,
        )
    }

    suspend fun release(handler: Int): Outcome {
        return when (val result = PowerHal.release(handler)) {
            is PowerHal.Result.Success -> Outcome(true, result.message)
            is PowerHal.Result.Failure -> Outcome(false, result.message)
        }
    }
}
