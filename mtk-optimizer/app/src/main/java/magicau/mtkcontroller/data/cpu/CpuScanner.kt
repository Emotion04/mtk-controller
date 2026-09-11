package magicau.mtkcontroller.data.cpu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.domain.model.CpuCluster
import java.io.File

/**
 * Discovers CPU clusters by scanning cpufreq policy directories at runtime.
 *
 * Deliberately probe-based: probing across SoCs works because
 * it never hardcoded policy numbers. A Dimensity 9300+ exposes policy0/4/7,
 * but 4+4 and 4+2+2 layouts exist too, so we enumerate.
 */
class CpuScanner {

    companion object {
        const val CPUFREQ_ROOT = "/sys/devices/system/cpu/cpufreq"

        /**
         * No real CPU core runs below 100 MHz. The Android emulator's goldfish
         * driver reports nonsense like `scaling_available_frequencies = "1 2"`,
         * which would otherwise render as "0 MHz" sliders.
         */
        private const val MIN_PLAUSIBLE_KHZ = 100_000L
    }

    /**
     * @param deep when true, also run the (shell round-trip) governor write
     *   probe. The home dashboard's 1 Hz sampler passes false — it only needs
     *   live frequencies and must stay cheap.
     */
    suspend fun scan(deep: Boolean = true): List<CpuCluster> {
        val policies = listPolicies()
        if (policies.isEmpty()) return emptyList()

        return policies.mapIndexed { index, policy ->
            // 目录走 CpuFreqNodes.dir()，别在这里再拼一遍根路径。
            val dir = CpuFreqNodes.dir(policy)
            val available = read(dir, "scaling_available_frequencies")
                ?.split(Regex("\\s+"))
                ?.mapNotNull { it.toLongOrNull() }
                ?.filter { it >= MIN_PLAUSIBLE_KHZ }
                ?.distinct()
                ?.sorted()
                .orEmpty()

            // cpuinfo_* = 硬件能力；scaling_cur_freq = 当前频率。两族节点分开问，
            // 路径都走 CpuFreqNodes，别在这里再拼一遍字符串。
            val minFreq = CpuFreqNodes.parseKhz(read(dir, "cpuinfo_min_freq"))
                ?: available.firstOrNull() ?: 0L
            val maxFreq = CpuFreqNodes.parseKhz(read(dir, "cpuinfo_max_freq"))
                ?: available.lastOrNull() ?: 0L

            CpuCluster(
                policy = policy,
                index = index,
                cpus = read(dir, "related_cpus")
                    ?.split(Regex("\\s+"))
                    ?.mapNotNull { it.toIntOrNull() }
                    .orEmpty(),
                availableFreqs = available,
                currentFreqKhz = CpuFreqNodes.parseKhz(read(dir, "scaling_cur_freq"))
                    ?: CpuFreqNodes.parseKhz(read(dir, "cpuinfo_cur_freq")),
                minFreqKhz = minFreq,
                maxFreqKhz = maxFreq,
                governors = read(dir, "scaling_available_governors")
                    ?.split(Regex("\\s+"))
                    ?.filter { it.isNotBlank() }
                    .orEmpty(),
                currentGovernor = read(dir, "scaling_governor"),
                driver = read(dir, "scaling_driver"),
                // The old mode-bit heuristic answered "writable" on this
                // device while the write silently failed, so it now asks the
                // kernel directly.
                governorWritable = deep && Sysfs.probeWritable("$dir/scaling_governor"),
            )
        }
    }

    /**
     * Just the live per-cluster frequencies, keyed by policy.
     *
     * One plain file read per cluster and no shell round-trips, which is what
     * makes it usable from the dashboard's 1 Hz sampler.
     */
    suspend fun currentFreqs(): Map<String, Long> = withContext(Dispatchers.IO) {
        listPolicies().associateWith { policy ->
            CpuFreqNodes.parseKhz(Sysfs.read(CpuFreqNodes.cur(policy))) ?: 0L
        }.filterValues { it > 0L }
    }

    /** Sorted numerically so policy0 < policy4 < policy7. */
    private suspend fun listPolicies(): List<String> {
        val names = File(CPUFREQ_ROOT).takeIf { it.isDirectory }
            ?.list()?.toList()
            ?: Sysfs.list(CPUFREQ_ROOT)

        return names
            .filter { it.startsWith("policy") }
            .sortedBy { it.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
    }

    /** Direct read first; fall back to the elevated shell for locked-down nodes. */
    private suspend fun read(dir: String, node: String): String? =
        Sysfs.read("$dir/$node") ?: Sysfs.readElevated("$dir/$node")
}
