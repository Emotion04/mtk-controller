package magicau.mtkcontroller.domain.model

import kotlinx.serialization.Serializable

/**
 * One cpufreq policy directory — i.e. one cluster on a big.LITTLE SoC.
 *
 * [index] is the enumeration order over the sorted policy list; it is what the
 * PowerHAL command ids are derived from, so it must stay stable for a given
 * device.
 */
@Serializable
data class CpuCluster(
    val policy: String,
    val index: Int,
    val cpus: List<Int> = emptyList(),
    /** Available frequencies in kHz, ascending. */
    val availableFreqs: List<Long> = emptyList(),
    val currentFreqKhz: Long? = null,
    val minFreqKhz: Long = 0L,
    val maxFreqKhz: Long = 0L,
    val governors: List<String> = emptyList(),
    val currentGovernor: String? = null,
    /** e.g. "mtk-cpufreq" on a real device, "goldfish" on the emulator. */
    val driver: String? = null,
    /**
     * Whether a governor write actually lands, established by writing the
     * current value back rather than by reading permission bits.
     */
    val governorWritable: Boolean = false,
) {
    /** e.g. "policy0 · CPU 0-3". */
    val label: String
        get() = when {
            cpus.isEmpty() -> policy
            cpus.size == 1 -> "$policy · CPU ${cpus.first()}"
            else -> "$policy · CPU ${cpus.first()}-${cpus.last()}"
        }

    /** Closest available frequency to [targetKhz]. */
    fun nearest(targetKhz: Long): Long =
        availableFreqs.minByOrNull { kotlin.math.abs(it - targetKhz) } ?: targetKhz
}

/** A saved, one-tap-appliable configuration. */
@Serializable
data class ClusterSetting(
    val policy: String,
    val minFreqKhz: Long,
    val maxFreqKhz: Long,
    /** null = leave the governor alone. */
    val governor: String? = null,
)

@Serializable
data class GpuSetting(
    val minKhz: Long? = null,
    val maxKhz: Long? = null,
    val lockedOppIndex: Int? = null,
)

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val clusters: List<ClusterSetting> = emptyList(),
    val gpu: GpuSetting? = null,
    val createdAtEpochMs: Long = 0L,
)
