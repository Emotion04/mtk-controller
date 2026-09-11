package magicau.mtkcontroller.data.privilege

/** 仅允许当前 ROM 配置或代码已出现的固定参数，不开放任意资源写入。 */
data class VivoProbePolicy(
    val resource: Int,
    val value: Int,
    val durationMs: Int,
    val extraResources: List<Pair<Int, Int>> = emptyList(),
) {
    /** 传给 perf service 的资源对。顺序与 ROM 的固定数组保持一致。 */
    val commands: List<Pair<Int, Int>> get() = listOf(resource to value) + extraResources

    companion object {
        fun select(profile: Int): VivoProbePolicy = when (profile) {
            0 -> VivoProbePolicy(0xF0000C00.toInt(), 88000003, 8000)
            9, 71 -> VivoProbePolicy(0xF0100000.toInt(), profile, 2000)
            // CPUFreqPolicy.BOOST_CPU_MAX_FREQ：ROM 游戏场景使用的三簇
            // MAX=4095 请求。它只提供明确的“无额外上限”候选，不写下限。
            100 -> VivoProbePolicy(
                resource = 0x40804000,
                value = 4095,
                durationMs = 5000,
                extraResources = listOf(
                    0x40804100 to 4095,
                    0x40804200 to 4095,
                ),
            )
            else -> error("未允许的策略：$profile")
        }
    }
}
