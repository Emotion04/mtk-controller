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

    /**
     * 给用户看的名字在界面层（`ui/OptionLabels.kt`）—— 这里只给身份。
     *
     * **界面文案不带节点路径。** 之前写的是 `GPUFreq v2 (/proc/gpufreqv2)` 这种 ——
     * 那是内核接口的路径，用户不认识，而且换台机器可能就不叫这个。
     * 用户需要知道的是"这个软件走的哪条路"，不是路的名字。
     */
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
    /**
     * A channel is not the same as a usable control.
     *
     * On vivo PD2430A the devfreq directory exists (`/sys/class/devfreq/mtk-mali`
     * passes `test -e`) while every file inside it is SELinux-denied to shell, so
     * the scan yields a channel with an empty frequency table. Reporting that as
     * controllable puts a working-looking slider on screen that can only ever
     * answer "failed to map" — worse than saying the device has no GPU control.
     */
    val controllable: Boolean get() = channel != GpuChannel.NONE && availableFreqs.isNotEmpty()

    /** For GPUFreq v2 the OPP index is what gets written, so expose the mapping. */
    fun oppIndexFor(freqKhz: Long): Int? =
        oppTable.minByOrNull { kotlin.math.abs(it.freqKhz - freqKhz) }?.index
}
