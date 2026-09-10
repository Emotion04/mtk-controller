package magicau.mtkcontroller.data.cpu

import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.domain.model.CpuCluster
import java.io.File

/**
 * Discovers CPU clusters by scanning cpufreq policy directories at runtime.
 *
 * Deliberately probe-based: the reference app worked across many SoCs because
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

    suspend fun scan(): List<CpuCluster> {
        val policies = listPolicies()
        if (policies.isEmpty()) return emptyList()

        return policies.mapIndexed { index, policy ->
            val dir = "$CPUFREQ_ROOT/$policy"
            val available = read(dir, "scaling_available_frequencies")
                ?.split(Regex("\\s+"))
                ?.mapNotNull { it.toLongOrNull() }
                ?.filter { it >= MIN_PLAUSIBLE_KHZ }
                ?.distinct()
                ?.sorted()
                .orEmpty()

            val minFreq = read(dir, "cpuinfo_min_freq")?.toLongOrNull()
                ?: available.firstOrNull() ?: 0L
            val maxFreq = read(dir, "cpuinfo_max_freq")?.toLongOrNull()
                ?: available.lastOrNull() ?: 0L

            val governorPerm = Sysfs.stat("$dir/scaling_governor")

            CpuCluster(
                policy = policy,
                index = index,
                cpus = read(dir, "related_cpus")
                    ?.split(Regex("\\s+"))
                    ?.mapNotNull { it.toIntOrNull() }
                    .orEmpty(),
                availableFreqs = available,
                currentFreqKhz = read(dir, "scaling_cur_freq")?.toLongOrNull()
                    ?: read(dir, "cpuinfo_cur_freq")?.toLongOrNull(),
                minFreqKhz = minFreq,
                maxFreqKhz = maxFreq,
                governors = read(dir, "scaling_available_governors")
                    ?.split(Regex("\\s+"))
                    ?.filter { it.isNotBlank() }
                    .orEmpty(),
                currentGovernor = read(dir, "scaling_governor"),
                driver = read(dir, "scaling_driver"),
                governorWritable = governorPerm?.writable == true,
            )
        }
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
