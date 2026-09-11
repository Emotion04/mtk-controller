package magicau.mtkcontroller.data.thermal

/**
 * 把一份温控策略的参数翻成中文说明。
 *
 * 分两层，是为了让界面能只给用户看他要的：
 *  - [brief] 只有温度和频率 —— 默认显示这个
 *  - [full] 全部参数，含原文级细节 —— 留给出将来了「高级」开关，数据结构先备好
 *
 * **两层都只做翻译，不做评价** —— 每一行都对应文件里的一个真实字段，
 * 不写"这档好不好""建议用不用"。系统改配置、换机型，显示的就是当下的事实。
 */
object ThermalPolicySummary {

    /** 只有温度和频率。 */
    fun brief(policy: ThermalPolicy): String {
        if (policy.decodeError != null) return policy.decodeError

        val out = StringBuilder()

        when (policy.cpuMappingEnabled) {
            true -> out.append("有 CPU 频率表，温度升高会逐级压频\n")
            false -> out.append("CPU 频率表已关闭 —— 温度变化不会压核频率\n")
            null -> out.append("没有 CPU 频率表 —— 温度变化不会压核频率\n")
        }

        // mode=disabled 时文件里仍可能留着表。**不把那些行当生效内容列出来** ——
        // 08/09 就有一段 46°C 的表，列出来会被读成“这档只有 46°C 才压频”。
        if (policy.cpuMappingEnabled == false && policy.cpuLadders.isNotEmpty()) {
            out.append("  文件里仍有一段表，但 mode=disabled，不参与控制\n")
        }
        if (policy.cpuMappingEnabled != false) {
            policy.cpuLadders.toSortedMap().forEach { (cluster, ladder) ->
                out.append("  核簇 $cluster：")
                out.append(ladder.thresholdsC.zip(ladder.freqsMHz)
                    .joinToString("  ") { (c, mhz) -> "${c}°C→${"%.1f".format(mhz / 1000.0)}G" })
                out.append('\n')
            }
        }

        policy.steadyTargetTJC?.let { target ->
            // 把允许区间一起给出来。单说"目标温度 65°C"会被读成一条限制线，
            // 而它其实是闭环要稳到的点，上下还有一段可调范围。
            //
            // 叫"目标温度"而不是"稳态温度目标"：**稳态是控制论词汇**，
            // 用户不靠它理解任何事；闭环这层意思由"可调区间"表达得更清楚。
            out.append("目标温度 ${target}°C")
            val lo = policy.minTargetTJC
            val hi = policy.maxTargetTJC
            if (lo != null && hi != null) out.append("（可调 ${lo}–${hi}°C）")
            out.append('\n')
        }

        return out.toString().trimEnd()
    }

    /** 全部参数。默认不显示，等「高级」开关。 */
    fun full(policy: ThermalPolicy): String {
        val brief = brief(policy)
        if (policy.decodeError != null) return brief

        val out = StringBuilder(brief)

        policy.permanent?.let { out.append("\n\n重启后仍有效：${if (it) "是" else "否"}") }
        policy.sensor?.let { out.append("\n温度传感器：$it") }
        policy.isolateEnabled?.let { out.append("\n温度过高时隔离核心：${if (it) "开" else "关"}") }
        if (policy.isolateThresholdsC.isNotEmpty()) {
            out.append("\n隔离阈值：${policy.isolateThresholdsC.joinToString("/")}°C")
        }
        policy.gpuLadder?.takeIf { !it.isEmpty }?.let { ladder ->
            out.append("\nGPU：")
            out.append(ladder.thresholdsC.zip(ladder.freqsMHz)
                .joinToString("  ") { (c, mhz) -> "${c}°C→${mhz}M" })
        }
        policy.vtskinModes.forEach { (name, mode) -> out.append("\n皮温通道 $name：$mode") }
        if (policy.skinThresholdsC.isNotEmpty()) {
            out.append("\n皮温分级阈值：${policy.skinThresholdsC.joinToString("/")}°C")
            if (policy.skinLevels.size == policy.skinThresholdsC.size) {
                out.append("\n对应等级：${policy.skinLevels.joinToString(" / ")}")
            }
        }
        if (policy.chargerThresholdsC.isNotEmpty() && policy.chargerCurrentsMA.isNotEmpty()) {
            out.append("\n充电时限流：")
            out.append(policy.chargerThresholdsC.zip(policy.chargerCurrentsMA)
                .joinToString("  ") { (c, ma) -> "${c}°C→${ma}mA" })
        }

        return out.toString()
    }
}
