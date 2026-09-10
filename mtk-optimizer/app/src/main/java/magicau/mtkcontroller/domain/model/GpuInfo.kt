package magicau.mtkcontroller.domain.model

import kotlinx.serialization.Serializable

/**
 * Which GPU frequency interface this device exposes.
 *
 * MediaTek has shipped several over time, so the UI shows which one we found
 * instead of assuming. (Paths are written without glob suffixes on purpose:
 * a literal "slash star" inside a Kotlin block comment opens a nested comment.)
 */
enum class GpuChannel {
    /** Nothing found — GPU control unavailable on this device. */
    NONE,

    /** Procfs interface under /proc/gpufreqv2 — current-generation Dimensity. */
    GPUFREQ_V2,

    /** Procfs interface under /proc/gpufreq — older MediaTek. */
    GPUFREQ_V1,

    /** Devfreq interface under /sys/class/devfreq/mtk-mali — real min/max knobs. */
    DEVFREQ,
    ;

    val displayName: String
        get() = when (this) {
            NONE -> "未检测到 GPU 调频接口"
            GPUFREQ_V2 -> "GPUFreq v2 (/proc/gpufreqv2)"
            GPUFREQ_V1 -> "GPUFreq v1 (/proc/gpufreq)"
            DEVFREQ -> "devfreq (mtk-mali)"
        }
}

@Serializable
data class GpuOpp(
    val index: Int,
    val freqKhz: Long,
    val active: Boolean = false,
)

data class GpuInfo(
    val channel: GpuChannel = GpuChannel.NONE,
    val availableFreqs: List<Long> = emptyList(),
    val currentFreqKhz: Long? = null,
    val minFreqKhz: Long? = null,
    val maxFreqKhz: Long? = null,
    val governor: String? = null,
    val oppTable: List<GpuOpp> = emptyList(),
) {
    val controllable: Boolean get() = channel != GpuChannel.NONE

    /** For GPUFreq v2 the OPP index is what gets written, so expose the mapping. */
    fun oppIndexFor(freqKhz: Long): Int? =
        oppTable.minByOrNull { kotlin.math.abs(it.freqKhz - freqKhz) }?.index
}
