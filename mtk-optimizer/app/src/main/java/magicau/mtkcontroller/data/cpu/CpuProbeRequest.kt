package magicau.mtkcontroller.data.cpu

import androidx.annotation.StringRes
import magicau.mtkcontroller.R

import magicau.mtkcontroller.data.powerhal.PowerHal

/**
 * 探测模式。
 *
 * 2026-10-14 删掉五个：CAMERA、VIVO_LIMIT、VIVO_POLICY_9、VIVO_POLICY_71、
 * VIVO_SOURCE_MAX。前四个（相机一个、vivo 三个）**已经测过且得不到结论** ——
 * 事务码返回成功但采样读数没有对应变化，留着一个占位置的按钮只会让人反复重跑。
 * VIVO_SOURCE_MAX 同理。
 *
 * AIDL 侧的 `cameraProbe` / `vivoCpuLimitProbe` / `vivoPolicyProbe` **没有删**：
 * AIDL 注释里写着 "Appended to preserve existing transaction IDs"，
 * 从中间抽掉一项会把后面所有事务码平移，而 Shizuku 按 version 留着旧服务，
 * 表现是"新的调用打到旧的方法上"且不报错。留着三个没人调的方法比那个安全得多。
 */
enum class CpuProbeMode(@StringRes val labelRes: Int, val loaded: Boolean = false) {
    OBSERVE(R.string.probe_mode_observe), RANGE(R.string.probe_mode_range),
    HARD_RANGE(R.string.probe_mode_hard_range), POINT(R.string.probe_mode_point),
    RANGE_LOAD(R.string.probe_mode_range_load, true),
    HARD_RANGE_LOAD(R.string.probe_mode_hard_range_load, true),
    POINT_LOAD(R.string.probe_mode_point_load, true),
    ROM(R.string.probe_mode_rom);

    fun pairs(index: Int, low: Long, high: Long): List<Pair<String, String>> {
        require(index in 0..255 && low > 0 && low <= high && high <= Int.MAX_VALUE)
        if (this == OBSERVE || this == ROM) return emptyList()
        val floor = if (this == POINT || this == POINT_LOAD) high else low
        return buildList {
            add(PowerHal.commandId(PowerHal.BASE_MIN, index) to floor.toString())
            add(PowerHal.commandId(PowerHal.BASE_MAX, index) to high.toString())
            if (this@CpuProbeMode != RANGE && this@CpuProbeMode != RANGE_LOAD)
                add(PowerHal.commandId(PowerHal.BASE_HARD_MIN, index) to floor.toString())
            add(PowerHal.commandId(PowerHal.BASE_HARD_MAX, index) to high.toString())
        }
    }
}
