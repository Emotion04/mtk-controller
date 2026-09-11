package magicau.mtkcontroller.data.cpu

import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.domain.model.ClusterSetting

enum class CpuVerification { UNVERIFIED, VERIFIED, CONSTRAINED, CONFLICT, UNREADABLE }

data class CpuLimitReading(val policy: String, val min: Long?, val max: Long?, val current: Long?) {
    fun describe(): String = "$policy ${min?.div(1000) ?: "?"}-${max?.div(1000) ?: "?"}MHz" +
        " cur=${current?.div(1000) ?: "?"}MHz"

    /**
     * 只有下限-上限，不带当前频率。
     *
     * 给"要比较两个时刻的限制"用：当前频率每毫秒都在变，带上它就永远比不出相等。
     * 硬件范围（`cpuinfo_*`）和请求前的当前限制（`scaling_*`）都用它格式化，
     * 所以这两类值之间至少形状一致 —— 但**它们不是同一个量**，别互相替代。
     */
    fun limitsText(): String = "$policy ${min?.div(1000) ?: "?"}-${max?.div(1000) ?: "?"}MHz"

    fun verify(request: ClusterSetting): CpuVerification = when {
        current != null && current !in request.minFreqKhz..request.maxFreqKhz -> CpuVerification.CONFLICT
        min == null || max == null -> CpuVerification.UNREADABLE
        min > max || min < request.minFreqKhz || max > request.maxFreqKhz -> CpuVerification.CONFLICT
        min == max && request.minFreqKhz != request.maxFreqKhz -> CpuVerification.CONFLICT
        min == request.minFreqKhz && max == request.maxFreqKhz -> CpuVerification.VERIFIED
        else -> CpuVerification.CONSTRAINED
    }

    fun conflictReason(request: ClusterSetting): Message = Message(
        when {
            max != null && max < request.minFreqKhz -> R.string.cpu_conflict_max_below_min
            min != null && min > request.maxFreqKhz -> R.string.cpu_conflict_min_above_max
            min != null && min == max && request.minFreqKhz != request.maxFreqKhz -> R.string.cpu_conflict_collapsed
            min == null || max == null -> R.string.cpu_conflict_partial
            else -> R.string.cpu_conflict_out_of_range
        },
    )
}
