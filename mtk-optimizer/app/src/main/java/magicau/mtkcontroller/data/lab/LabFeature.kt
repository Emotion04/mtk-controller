package magicau.mtkcontroller.data.lab

/** Where a canary feature is filed in the lab. */
enum class LabCategory(val title: String, val order: Int) {
    CPU("CPU", 0),
    SCHED("调度", 1),
    TOUCH("触控", 2),
    PLATFORM("平台限制", 3),
    MEMORY("内存 · 互连", 4),
    THERMAL("温控", 5),
    ACCEL("GPU · 加速器", 6),
    OEM("厂商保留区", 7),
    ADVANCED("高级", 8),
}

/**
 * How much damage a write that lands on the wrong resource could do.
 *
 * This decides whether the lab may probe the feature automatically:
 * [LOW] and [MEDIUM] may be round-trip probed, [HIGH] is never probed and must
 * be driven by an explicit user action with the risk shown on the card.
 */
enum class LabRisk(val label: String) {
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
}

enum class LabControl { READONLY, TOGGLE, SLIDER, CHOICE }

/**
 * One canary feature, described as data.
 *
 * The lab renders and drives every feature from this struct, so adding one is a
 * catalogue entry rather than a new screen. That matters because the interesting
 * part of a MediaTek device is how *differently* it responds to the same id —
 * the point is to make trying many of them cheap.
 *
 * @param baseId command id base. When [perCluster], the cpufreq policy index is
 *   added as `+ index * 0x100`.
 * @param node kernel node used both as the capability check and as the read-back.
 *   `%s` is replaced with the policy name when [perCluster]. **Null means we have
 *   no way to see the effect**, which is reported as such rather than guessed —
 *   a missing read-back is why several of these are still canaries.
 * @param valueChoices for [LabControl.CHOICE], label → value.
 * @param selfClearing false when the value must be explicitly restored, in which
 *   case the UI offers a one-tap reset and never persists it.
 */
data class LabFeature(
    val key: String,
    val label: String,
    val description: String,
    val category: LabCategory,
    val control: LabControl,
    val baseId: Int? = null,
    val node: String? = null,
    val range: IntRange = 0..100,
    val unit: String = "",
    val risk: LabRisk = LabRisk.MEDIUM,
    val perCluster: Boolean = false,
    val valueChoices: List<Pair<String, Int>> = emptyList(),
    val danger: String? = null,
    val selfClearing: Boolean = true,
)

/**
 * Everything the lab can try, with the evidence level recorded in the description.
 *
 * Ids come from MediaTek's own resource tables (see `docs/protocol.md`), but ids
 * **drift between BSP revisions** — so nothing here is trusted blindly. A feature
 * whose node is missing is reported as unsupported on this device, and one whose
 * write does not move its node is reported as having no effect.
 */
object LabCatalog {

    private const val CPUCTL = "/dev/cpuctl"
    private const val PERFMGR = "/proc/perfmgr"
    private const val PPM = "/proc/ppm/policy"

    val features: List<LabFeature> = listOf(

        // --- scheduling --------------------------------------------------
        LabFeature(
            key = "uclamp_ta",
            label = "前台应用 uclamp 下限",
            description = "把 top-app 任务组的负载估计抬到至少这个值。调度器据此选更高的频率、" +
                "更愿意用大核 —— 这是让前台变跟手的首要手段。作用于整组,不是单个应用。",
            category = LabCategory.SCHED,
            control = LabControl.SLIDER,
            baseId = 0x01408300, // PERF_RES_SCHED_UCLAMP_MIN_TA
            node = "$CPUCTL/top-app/cpu.uclamp.min",
            range = 0..100,
            risk = LabRisk.LOW,
        ),
        LabFeature(
            key = "uclamp_fg",
            label = "可见应用 uclamp 下限",
            description = "同上,作用于 foreground 组(所有可见应用)。",
            category = LabCategory.SCHED,
            control = LabControl.SLIDER,
            baseId = 0x01408100, // PERF_RES_SCHED_UCLAMP_MIN_FG
            node = "$CPUCTL/foreground/cpu.uclamp.min",
            range = 0..100,
            risk = LabRisk.LOW,
        ),
        LabFeature(
            key = "fpsgo_enable",
            label = "FPSGO 总开关",
            description = "FPSGO 是 MTK 的帧率与调度协调器,三百多个资源归它管。关掉会改变整机调度行为。",
            category = LabCategory.SCHED,
            control = LabControl.TOGGLE,
            baseId = 0x02004000, // PERF_RES_FPS_FPSGO_ENABLE
            node = null,
            range = 0..1,
            risk = LabRisk.HIGH,
            selfClearing = false,
            danger = "影响整机调度,关掉后行为不可预测。建议只在明确知道后果时使用。",
        ),
        LabFeature(
            key = "fpsgo_thrm",
            label = "FPSGO 温控联动",
            description = "已知的「手机不热也掉帧」解法:关掉 FPSGO 的温控联动。",
            category = LabCategory.SCHED,
            control = LabControl.TOGGLE,
            baseId = 0x02054400, // PERF_RES_FPS_FPSGO_THRM_ENABLE
            node = null,
            range = 0..1,
            risk = LabRisk.MEDIUM,
        ),

        // --- touch -------------------------------------------------------
        LabFeature(
            key = "tchbst_enable",
            label = "触控加速",
            description = "触摸时临时抬升频率上限。MTK 自己也在写这个节点,所以关掉可能与系统行为冲突。",
            category = LabCategory.TOUCH,
            control = LabControl.TOGGLE,
            baseId = 0x03408500, // PERF_RES_TOUCH_BOOST_ENABLE
            node = "$PERFMGR/tchbst/user/usrtch",
            range = 0..1,
            risk = LabRisk.LOW,
        ),
        LabFeature(
            key = "tchbst_opp",
            label = "触控加速档位",
            description = "触摸后抬到哪一档。档位上限因平台而异(见过的范围是 0..15)。",
            category = LabCategory.TOUCH,
            control = LabControl.SLIDER,
            baseId = 0x03408000, // PERF_RES_TOUCH_BOOST_OPP
            node = null,
            range = 0..15,
            risk = LabRisk.LOW,
        ),
        LabFeature(
            key = "tchbst_eas",
            label = "触控加速 EAS 权重",
            description = "触摸时给调度器的负载加权,0..100。",
            category = LabCategory.TOUCH,
            control = LabControl.SLIDER,
            baseId = 0x03408300, // PERF_RES_TOUCH_BOOST_EAS_BOOST
            node = null,
            range = 0..100,
            risk = LabRisk.LOW,
        ),
        LabFeature(
            key = "tchbst_duration",
            label = "触控加速时长",
            description = "**单位因平台而异** —— 摩托罗拉的配置是毫秒,chopin 是纳秒,差一百万倍。" +
                "这里的值是原始整数,请先读只读面板确认你机器的量级再改。",
            category = LabCategory.TOUCH,
            control = LabControl.SLIDER,
            baseId = 0x03408100, // PERF_RES_TOUCH_BOOST_DURATION
            node = null,
            range = 0..2_000_000_000,
            risk = LabRisk.MEDIUM,
            danger = "单位不确定。填错量级可能让触控加速永远不结束或完全失效。",
        ),

        // --- platform limiters -------------------------------------------
        LabFeature(
            key = "syslimiter",
            label = "系统总频率上限",
            description = "厂商自己的总上限,叠在所有其他限制之上。默认 -1 = 不限制。",
            category = LabCategory.PLATFORM,
            control = LabControl.SLIDER,
            baseId = 0x01C40400, // PERF_RES_POWER_SYSLIMITER
            node = "$PERFMGR/syslimiter/syslimiter_limit_freq",
            range = 0..3_000_000,
            unit = "kHz",
            risk = LabRisk.MEDIUM,
        ),

        // --- memory / interconnect ---------------------------------------
        LabFeature(
            key = "cci_mode",
            label = "CCI 互连模式",
            description = "缓存一致性互连(DSU 那层)的**模式开关,不是频率**。0 = 自动,1 = 性能。" +
                "影响跨簇通信和内存延迟;不在这个瓶颈上时纯粹费电,而且是从核心的功耗预算里扣。",
            category = LabCategory.MEMORY,
            control = LabControl.CHOICE,
            baseId = 0x00410000, // PERF_RES_CPUFREQ_CCI_FREQ
            node = "/proc/cpufreq/cpufreq_cci_mode",
            valueChoices = listOf("自动" to 0, "性能" to 1),
            risk = LabRisk.MEDIUM,
            selfClearing = false,
        ),
        LabFeature(
            key = "dram_opp",
            label = "DRAM OPP 下限",
            description = "内存带宽档位下限。**索引方向在各来源之间说法矛盾** —— 先用只读面板观察你机器上的" +
                "取值方向再动。",
            category = LabCategory.MEMORY,
            control = LabControl.SLIDER,
            baseId = 0x01000000, // PERF_RES_DRAM_OPP_MIN
            node = null,
            range = 0..3,
            risk = LabRisk.MEDIUM,
            danger = "方向和量级都未在本机验证。",
        ),

        // --- CPU cores ----------------------------------------------------
        LabFeature(
            key = "core_min",
            label = "每簇最少在线核心",
            description = "直接控制每簇保持多少个核心在线。整数,和频率是完全不同的杠杆 —— " +
                "限核比限频省电得多。",
            category = LabCategory.CPU,
            control = LabControl.SLIDER,
            baseId = 0x00800000, // PERF_RES_CPUCORE_MIN_CLUSTER_n
            // The policy's CPU list shrinks as cores are offlined, so it doubles
            // as both the capability check and the read-back.
            node = "/sys/devices/system/cpu/cpufreq/%s/affected_cpus",
            range = 0..8,
            perCluster = true,
            risk = LabRisk.MEDIUM,
        ),
        LabFeature(
            key = "core_max",
            label = "每簇最多在线核心",
            description = "每簇允许的最大在线核数。设太低会把前台任务需要的核心下线。",
            category = LabCategory.CPU,
            control = LabControl.SLIDER,
            baseId = 0x00804000, // PERF_RES_CPUCORE_MAX_CLUSTER_n
            node = "/sys/devices/system/cpu/cpufreq/%s/affected_cpus",
            range = 1..8,
            perCluster = true,
            risk = LabRisk.MEDIUM,
            danger = "设得过低会让前台应用缺核。",
        ),

        // --- high risk ----------------------------------------------------
        LabFeature(
            key = "thermal_policy",
            label = "温控策略索引",
            description = "加载 /vendor/etc/.tp/ 下的策略文件。**取值语义没有公开文档** —— " +
                "它们活在加密的厂商策略文件里,官方也没公布。请自己试并观察。",
            category = LabCategory.THERMAL,
            control = LabControl.SLIDER,
            baseId = 0x03000000, // PERF_RES_THERMAL_POLICY
            node = "$PERFMGR/thermal_policy",
            range = 0..19,
            risk = LabRisk.HIGH,
            selfClearing = false,
            danger = "改变整机温控行为,某些策略会让设备明显更热。不持久化,退出即恢复。",
        ),
        LabFeature(
            key = "perf_mode",
            label = "性能模式(全核满频)",
            description = "把所有簇的下限顶到最高频并全核在线。**它会摧毁你设置的任何频率范围** —— " +
                "和 CPU 页的范围控制互斥。",
            category = LabCategory.CPU,
            control = LabControl.TOGGLE,
            baseId = 0x00414000, // PERF_RES_CPUFREQ_PERF_MODE
            node = null,
            range = 0..1,
            risk = LabRisk.HIGH,
            danger = "会把所有簇钉在最高频,功耗和发热显著上升,并覆盖 CPU 页的范围设置。",
        ),
        LabFeature(
            key = "hard_limit_max",
            label = "硬上限(每簇)",
            description = "硬限制是独立于软上下限的另一套机制,写在 /proc/ppm/policy/hard_userlimit_cpu_freq。" +
                "**它覆盖其他所有客户端和温控**,而且普通释放不会撤销它。",
            category = LabCategory.CPU,
            control = LabControl.SLIDER,
            baseId = 0x0040C000, // PERF_RES_CPUFREQ_MAX_HL_CLUSTER_n
            node = "$PPM/hard_userlimit_cpu_freq",
            range = 0..3_500_000,
            unit = "kHz",
            perCluster = true,
            risk = LabRisk.HIGH,
            selfClearing = false,
            danger = "硬限制压过温控保护,且不会被普通释放清除。只读面板里能看到它当前是否被占用。",
        ),
        LabFeature(
            key = "syslimiter_disable",
            label = "关闭系统总上限",
            description = "拆掉 OEM 的安全上限。这是唯一一个**故意移除保护**的功能。",
            category = LabCategory.PLATFORM,
            control = LabControl.TOGGLE,
            baseId = 0x01C44000, // PERF_RES_POWER_SYSLIMITER_DISABLE
            node = null,
            range = 0..1,
            risk = LabRisk.HIGH,
            selfClearing = false,
            danger = "移除厂商的功耗与温控保护上限。不持久化,离开页面即恢复。",
        ),
    ).sortedWith(compareBy({ it.category.order }, { it.label }))

    fun byKey(key: String): LabFeature? = features.firstOrNull { it.key == key }

    /**
     * Ids that must never be sent, even from the manual entry field.
     *
     * Each of these either freezes the device or removes a protection with no
     * benign use. They are blocked rather than warned about because a typo is
     * indistinguishable from an intent here.
     */
    val blockedIds: Map<Int, String> = mapOf(
        0x0102C100 to "DRAM_VM_DROP_CACHES —— 清空页缓存,会立刻造成整机卡死",
        0x01004000 to "DRAM_VCORE_MIN —— 电压下限,影响稳定性",
        0x01004100 to "DRAM_VCORE_LP3 —— 电压下限,影响稳定性",
        0x01C3C200 to "PM_QOS_CPU_DMA_LATENCY —— 会阻止深度休眠,白耗电",
        0x03420000 to "POWERHAL_TEST_CMD —— 未公开的测试钩子",
        0x03424000 to "POWERHAL_ONESHOT_RESET —— 全局重置",
    )
}
