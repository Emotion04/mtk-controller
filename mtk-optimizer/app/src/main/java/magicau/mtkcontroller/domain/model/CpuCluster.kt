package magicau.mtkcontroller.domain.model

import kotlinx.serialization.Serializable

/**
 * One cpufreq policy directory — i.e. one cluster on a big.LITTLE SoC.
 *
 * [index] is the enumeration order over the sorted policy list; it is what the
 * PowerHAL command ids are derived from, so it must stay stable for a given
 * device.
 *
 * **不带界面文案。** 界面上显示的簇名（「核心 0–3」）由界面层从 [cpus] 拼
 * （见 `ui/OptionLabels.kt`）—— `domain` 拿不到字符串资源，这里只给数据。
 * [policy] 是内核的设备节点名，属于设备原文，原样保留。
 */
@Serializable
data class CpuCluster(
    val policy: String,
    val index: Int,
    val cpus: List<Int> = emptyList(),
    /** Available frequencies in kHz, ascending. */
    val availableFreqs: List<Long> = emptyList(),
    val currentFreqKhz: Long? = null,
    /**
     * **硬件**下限（`cpuinfo_min_freq`），不是"当前被限制到的下限"。
     * 当前限制在 `CpuControl` 的 caps 里，两者不同：硬件边界固定，限制会随温控变。
     */
    val minFreqKhz: Long = 0L,
    /** **硬件**上限（`cpuinfo_max_freq`）。仪表拿它当量程，因为它不随限制变化。 */
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
    /**
     * Closest available frequency to [targetKhz].
     */
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
    /**
     * 温控策略索引。null = 方案不管档位，应用时保持当前值。
     *
     * 之所以放进方案：档位和频率区间共同决定实际能跑多高 —— 用户实测过索引 01 把
     * policy7 从 2.3G 放到 3.4G。只存频率的方案在换了档位的机器上会跑出不同结果。
     *
     * 旧方案没有这个字段，反序列化时默认 null，行为不变（不动档位）。
     */
    val thermalIndex: Int? = null,
    val createdAtEpochMs: Long = 0L,
)
