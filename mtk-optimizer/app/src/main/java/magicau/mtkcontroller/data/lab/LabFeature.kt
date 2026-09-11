package magicau.mtkcontroller.data.lab

import androidx.annotation.StringRes
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.thermal.THERMAL_POLICY_RESOURCE


/**
 * 「温控策略索引」这一项的 key。
 *
 * 放在这里而不是界面里：它是 [LabFeature] 的一个身份，界面和 ViewModel 都要按它
 * 找出这一项。曾经两边各写一遍字符串常量，改一处漏一处不会有任何编译错误。
 */
const val THERMAL_POLICY_KEY = "thermal_policy"

/** Where a canary feature is filed in the lab. */
/**
 * 实验室里每一项的**归属**。与 [LabCategory] 是两个不同的轴：
 *
 *  - [LabCategory] 说"这功能管哪块硬件"（CPU / 调度 / 触控…），是技术分类；
 *  - [LabPlacement] 说"这东西对用户意味着什么"，是**给用户看的分类**。
 *
 * 为什么要第二个轴：实验室原来一屏平铺 18 张大卡，每张占接近一屏，
 * 用户分不出"哪些是正式功能、哪些只是探测"。
 *
 * **只有两组，没有"开发工具"组。** 原来有第三组放只读采集类，实际一项都没挂上去
 * （逐 ID 测试和只读面板各自有正式入口，不属于实验室列表）。空分组不会渲染，
 * 但枚举值会诱使后来者往一个不存在的组里塞东西 —— 删掉。
 */
enum class LabPlacement(
    @StringRes val titleRes: Int,
    val order: Int,
    @StringRes val hintRes: Int,
) {
    /**
     * 别处已经有正式入口，这里只是"手动发单个资源"的仪器。
     *
     * 放这一组不是冗余：逐 ID 测试和硬上限回答的是"单个资源号在这台机器上干什么"，
     * 而正式功能一次发一组。两个问题都要有人能答。
     */
    INTEGRATED(R.string.lab_placement_integrated, 0, R.string.lab_placement_integrated_hint),

    /**
     * 有写后读回判定、结论跟机型走的探测项。
     *
     * 这一组的判定结果**只对这台机器有效**：换个 ROM 可能相反。
     * 所以每条都带生效/无效果/不支持，不让用户猜。
     */
    DEVICE_CAPABILITY(R.string.lab_placement_capability, 1, R.string.lab_placement_capability_hint),
}

enum class LabCategory(@StringRes val titleRes: Int, val order: Int) {
    CPU(R.string.lab_cat_cpu, 0),
    SCHED(R.string.lab_cat_sched, 1),
    TOUCH(R.string.lab_cat_touch, 2),
    PLATFORM(R.string.lab_cat_platform, 3),
    MEMORY(R.string.lab_cat_memory, 4),
    THERMAL(R.string.lab_cat_thermal, 5),
    ACCEL(R.string.lab_cat_accel, 6),
    OEM(R.string.lab_cat_oem, 7),
    ADVANCED(R.string.lab_cat_advanced, 8),
}

/**
 * How much damage a write that lands on the wrong resource could do.
 *
 * This decides whether the lab may probe the feature automatically:
 * [LOW] and [MEDIUM] may be round-trip probed, [HIGH] is never probed and must
 * be driven by an explicit user action with the risk shown on the card.
 */
enum class LabRisk(@StringRes val labelRes: Int) {
    LOW(R.string.lab_risk_level_low),
    MEDIUM(R.string.lab_risk_level_medium),
    HIGH(R.string.lab_risk_level_high),
}

enum class LabControl { READONLY, TOGGLE, SLIDER, CHOICE }

/**
 * 逐核功能的核编号约定。控制器、ViewModel 和界面都要用同一份，放一处。
 *
 * [COUNT] 是硬编码的 8：本机 `present` 是 `0-7`，而这一族资源 ID 也是按 cpu0..cpu7
 * 编排的。不按键位动态探测 —— 多出来的核发下去只会是无效写入。
 */
object LabCores {
    const val COUNT = 8
    fun key(core: Int) = "cpu$core"
    val keys: List<String> get() = (0 until COUNT).map(::key)
}

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
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val category: LabCategory,
    /** 归属。决定它出现在实验室的哪一组下面。 */
    val placement: LabPlacement = LabPlacement.DEVICE_CAPABILITY,
    /**
     * 相同 mergeKey 的项在界面上合成**一张卡**。
     *
     * 给"同一个机制、只作用对象不同"的功能用 —— 前台应用 uclamp 和可见应用 uclamp
     * 原来是两张卡，各占一屏，而它们只是 top-app / foreground 两个任务组之别。
     * 合并之后一张卡两个滑杆，文案说清区别。
     *
     * null = 不合并，自己一张卡。
     */
    val mergeKey: String? = null,
    val control: LabControl,
    val baseId: Int? = null,
    val node: String? = null,
    val range: IntRange = 0..100,
    val unit: String = "",
    val risk: LabRisk = LabRisk.MEDIUM,
    val perCluster: Boolean = false,

    /**
     * 逐**核**（cpu0…cpu7）而不是逐簇。见 [baseId] 的偏移说明。
     *
     * 与 [perCluster] 互斥。没有合并成一个"分组维度"字段，是因为两者的
     * **编号约定不一样**：簇是 `base + 0x100 * 簇序号`（0 起），
     * 而 `_CPU_n` 这一族是 `base + 0x100 * (核序号 + 1)`（0x0080C000 那一格是
     * "整簇"而不是 cpu0）。用同一个公式会把 cpu0 写成整簇开关。
     */
    val perCore: Boolean = false,
    val valueChoices: List<Pair<String, Int>> = emptyList(),

    /**
     * 运行时才解析得出的选项，优先于 [valueChoices]。
     *
     * 给"选项来自设备本身"的功能用 —— 温控策略索引要看
     * `/vendor/etc/thermal/` 里真有哪几个 `thermal_policy_NN.conf`。
     * 写死一个范围等于把猜测当事实：设备没有的号也会出现在界面上。
     */
    val dynamicChoices: (suspend () -> List<LabChoice>)? = null,

    /**
     * 运行时读数，显示在卡片上。返回 null = 读不到，界面显示"读不到"。
     *
     * 给"有效果但看不到"的功能用 —— 温控策略索引读的是当前皮肤阈值，
     * 那是唯一能无 root 看出"现在生效哪一组策略"的通道。
     */
    val readout: (suspend () -> String?)? = null,
    @StringRes val dangerRes: Int? = null,
    val selfClearing: Boolean = true,

    /**
     * 要自动扫过去的值，非空就在界面上多一个「自动扫一遍」。
     *
     * 给"取值语义没有公开文档、只能靠试"的功能用（温控策略索引就是：
     * 配置文件被加密，读不出每个索引是什么）。手动一个个拖既慢又容易漏，
     * 还难保证每档之间的请求已经释放。
     */
    val sweepValues: List<Int>? = null,

)

/**
 * Everything the lab can try, with the evidence level recorded in the description.
 *
 * Ids come from MediaTek's own resource tables (see `docs/cpu/protocol.md`), but ids
 * **drift between BSP revisions** — so nothing here is trusted blindly. A feature
 * whose node is missing is reported as unsupported on this device, and one whose
 * write does not move its node is reported as having no effect.
 */
object LabCatalog {

    private const val CPUCTL = "/dev/cpuctl"
    private const val PERFMGR = "/proc/perfmgr"

    val features: List<LabFeature> = listOf(

        // --- scheduling --------------------------------------------------
        LabFeature(
            key = "uclamp_ta",
            labelRes = R.string.lab_uclamp_ta_label,
            descriptionRes = R.string.lab_uclamp_ta_desc,
            category = LabCategory.SCHED,
            mergeKey = "uclamp",
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.SLIDER,
            baseId = 0x01408300, // PERF_RES_SCHED_UCLAMP_MIN_TA
            node = "$CPUCTL/top-app/cpu.uclamp.min",
            range = 0..100,
            risk = LabRisk.LOW,
),
        LabFeature(
            key = "uclamp_fg",
            labelRes = R.string.lab_uclamp_fg_label,
            descriptionRes = R.string.lab_uclamp_fg_desc,
            category = LabCategory.SCHED,
            mergeKey = "uclamp",
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.SLIDER,
            baseId = 0x01408100, // PERF_RES_SCHED_UCLAMP_MIN_FG
            node = "$CPUCTL/foreground/cpu.uclamp.min",
            range = 0..100,
            risk = LabRisk.LOW,
        ),
        LabFeature(
            key = "fpsgo_enable",
            labelRes = R.string.lab_fpsgo_enable_label,
            descriptionRes = R.string.lab_fpsgo_enable_desc,
            category = LabCategory.SCHED,
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.TOGGLE,
            baseId = 0x02004000, // PERF_RES_FPS_FPSGO_ENABLE
            node = null,
            range = 0..1,
            risk = LabRisk.HIGH,
            selfClearing = false,
            dangerRes = R.string.lab_fpsgo_enable_danger,
        ),
        LabFeature(
            key = "fpsgo_thrm",
            labelRes = R.string.lab_fpsgo_thrm_label,
            descriptionRes = R.string.lab_fpsgo_thrm_desc,
            category = LabCategory.SCHED,
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.TOGGLE,
            baseId = 0x02054400, // PERF_RES_FPS_FPSGO_THRM_ENABLE
            node = null,
            range = 0..1,
            risk = LabRisk.MEDIUM,
        ),

        // --- touch -------------------------------------------------------
        LabFeature(
            key = "tchbst_enable",
            labelRes = R.string.lab_tchbst_enable_label,
            descriptionRes = R.string.lab_tchbst_enable_desc,
            category = LabCategory.TOUCH,
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.TOGGLE,
            baseId = 0x03408500, // PERF_RES_TOUCH_BOOST_ENABLE
            node = "$PERFMGR/tchbst/user/usrtch",
            range = 0..1,
            risk = LabRisk.LOW,
        ),
        LabFeature(
            key = "tchbst_opp",
            labelRes = R.string.lab_tchbst_opp_label,
            descriptionRes = R.string.lab_tchbst_opp_desc,
            category = LabCategory.TOUCH,
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.SLIDER,
            baseId = 0x03408000, // PERF_RES_TOUCH_BOOST_OPP
            node = null,
            range = 0..15,
            risk = LabRisk.LOW,
        ),
        LabFeature(
            key = "tchbst_eas",
            labelRes = R.string.lab_tchbst_eas_label,
            descriptionRes = R.string.lab_tchbst_eas_desc,
            category = LabCategory.TOUCH,
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.SLIDER,
            baseId = 0x03408300, // PERF_RES_TOUCH_BOOST_EAS_BOOST
            node = null,
            range = 0..100,
            risk = LabRisk.LOW,
        ),
        LabFeature(
            key = "tchbst_duration",
            labelRes = R.string.lab_tchbst_duration_label,
            descriptionRes = R.string.lab_tchbst_duration_desc,
            category = LabCategory.TOUCH,
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.SLIDER,
            baseId = 0x03408100, // PERF_RES_TOUCH_BOOST_DURATION
            node = null,
            range = 0..2_000_000_000,
            risk = LabRisk.MEDIUM,
            dangerRes = R.string.lab_tchbst_duration_danger,
        ),

        // --- platform limiters -------------------------------------------
        LabFeature(
            key = "syslimiter",
            labelRes = R.string.lab_syslimiter_label,
            descriptionRes = R.string.lab_syslimiter_desc,
            category = LabCategory.PLATFORM,
            placement = LabPlacement.DEVICE_CAPABILITY,
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
            labelRes = R.string.lab_cci_mode_label,
            descriptionRes = R.string.lab_cci_mode_desc,
            category = LabCategory.MEMORY,
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.CHOICE,
            baseId = 0x00410000, // PERF_RES_CPUFREQ_CCI_FREQ
            node = "/proc/cpufreq/cpufreq_cci_mode",
            valueChoices = listOf("自动" to 0, "性能" to 1),
            risk = LabRisk.MEDIUM,
            selfClearing = false,
        ),
        LabFeature(
            key = "dram_opp",
            labelRes = R.string.lab_dram_opp_label,
            descriptionRes = R.string.lab_dram_opp_desc,
            category = LabCategory.MEMORY,
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.SLIDER,
            baseId = 0x01000000, // PERF_RES_DRAM_OPP_MIN
            node = null,
            range = 0..3,
            risk = LabRisk.MEDIUM,
            dangerRes = R.string.lab_dram_opp_danger,
        ),

        // --- CPU cores ----------------------------------------------------
        LabFeature(
            key = "core_min",
            labelRes = R.string.lab_core_min_label,
            descriptionRes = R.string.lab_core_min_desc,
            category = LabCategory.CPU,
            placement = LabPlacement.DEVICE_CAPABILITY,
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
            labelRes = R.string.lab_core_max_label,
            descriptionRes = R.string.lab_core_max_desc,
            category = LabCategory.CPU,
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.SLIDER,
            baseId = 0x00804000, // PERF_RES_CPUCORE_MAX_CLUSTER_n
            node = "/sys/devices/system/cpu/cpufreq/%s/affected_cpus",
            range = 1..8,
            perCluster = true,
            risk = LabRisk.MEDIUM,
            dangerRes = R.string.lab_core_max_danger,
        ),

        LabFeature(
            key = "core_online",
            labelRes = R.string.lab_core_online_label,
            descriptionRes = R.string.lab_core_online_desc,
            category = LabCategory.CPU,
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.TOGGLE,
            baseId = 0x00810000, // PERF_RES_CPUCORE_ONLINE_CPU_n
            // 读回用每簇的 affected_cpus(0444 可读):核一旦下线,它就从对应簇的列表里消失。
            // 不读 /proc/perfmgr —— 那个在 SELinux 下整片拒绝,读不出东西。
            node = "/sys/devices/system/cpu/cpufreq/%s/affected_cpus",
            range = 0..1,
            perCore = true,
            risk = LabRisk.MEDIUM,
            dangerRes = R.string.lab_core_online_danger,
        ),
        LabFeature(
            key = "core_pause",
            labelRes = R.string.lab_core_pause_label,
            descriptionRes = R.string.lab_core_pause_desc,
            category = LabCategory.CPU,
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.TOGGLE,
            baseId = 0x0080C000, // PERF_RES_CPUCORE_FORCE_PAUSE_CPU_n
            node = "/sys/devices/system/cpu/cpufreq/%s/affected_cpus",
            range = 0..1,
            perCore = true,
            risk = LabRisk.MEDIUM,
            dangerRes = R.string.lab_core_pause_danger,
        ),

        // --- high risk ----------------------------------------------------
        //
        // **这一项与 CPU 页「高级设置 → 温控档位」是同一个 PowerHAL 资源的两条写路径。**
        // 那里跟随「应用」、写进方案；这里即时下发、可长期保持、可自动扫档，两边互不知情。
        // 用户完全可能同时被两边持有句柄，而释放时只放自己那一把。
        //
        // 保留它是因为自动扫档（连续试每一档看读数）是别处没有的能力；
        // 但只要它还在这儿，上面这个冲突就一直在。收敛方案见
        // `docs/dev/todo.md`，不要在别处再加第三条写路径。
        LabFeature(
            key = "thermal_policy",
            labelRes = R.string.lab_thermal_policy_label,
            descriptionRes = R.string.lab_thermal_policy_desc,
            category = LabCategory.THERMAL,
            placement = LabPlacement.INTEGRATED,
            control = LabControl.CHOICE,
            // 资源号只在 data/thermal/ThermalPolicyController.kt 定义一处，别处引用它 ——
            // 三份各自写 0x03000000 的时候，改一处漏两处是必然的。
            baseId = THERMAL_POLICY_RESOURCE,
            node = "$PERFMGR/thermal_policy",
            // 选项运行时从 /vendor/etc/thermal/ 列出来 —— 这个 ROM 有 13 份，
            // 编号沿用文件名。以前写死 0..19 是把猜测当事实。
            dynamicChoices = { ThermalPolicyCatalog.choices() },
            readout = { ThermalPolicyCatalog.skinThresholdText() },
            risk = LabRisk.HIGH,
            selfClearing = false,
            dangerRes = R.string.lab_thermal_policy_danger,
        ),
        LabFeature(
            key = "perf_mode",
            labelRes = R.string.lab_perf_mode_label,
            descriptionRes = R.string.lab_perf_mode_desc,
            category = LabCategory.CPU,
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.TOGGLE,
            baseId = 0x00414000, // PERF_RES_CPUFREQ_PERF_MODE
            node = null,
            range = 0..1,
            risk = LabRisk.HIGH,
            dangerRes = R.string.lab_perf_mode_danger,
        ),
        LabFeature(
            key = "hard_limit_max",
            labelRes = R.string.lab_hard_limit_max_label,
            // 2026-10-14 真机核对：`/proc/ppm/` 在这台机器上**整个目录都不存在**
            // （`ls /proc/ppm/` 是 ENOENT，不是权限拒绝）。所以这里原来写的
            // "写在 /proc/ppm/policy/hard_userlimit_cpu_freq" 是错的，
            // "普通释放不会撤销它" 也没有任何依据 —— 两条一起删。
            //
            // BSP 里另有一句 "Hard limits survive a soft release"（resources.md）。
            // 它说的是**软硬分开请求时**，放掉软的那个不清硬的那个 —— 两个限制器
            // 独立跟踪。不是"任何释放都清不掉它"。早前这里把这句话读成后者。
            //
            // 本 App 一次请求里软硬齐发（同一个句柄），所以一次释放全清。
            // 2026-10-14 真机逐个单独发过：四个资源单独发都生效，软硬等效。
            descriptionRes = R.string.lab_hard_limit_max_desc,
            category = LabCategory.CPU,
            placement = LabPlacement.INTEGRATED,
            control = LabControl.SLIDER,
            baseId = 0x0040C000, // PERF_RES_CPUFREQ_MAX_HL_CLUSTER_n
            // 这台机器没有 /proc/ppm，所以没有可读回的节点 —— 效果只看采样读数。
            node = null,
            range = 0..3_500_000,
            unit = "kHz",
            perCluster = true,
            risk = LabRisk.HIGH,
            selfClearing = false,
            // 分开请求时才要分别释放；本 App 软硬同一个句柄，一次释放全清。
            dangerRes = R.string.lab_hard_limit_max_danger,
        ),
        LabFeature(
            key = "syslimiter_disable",
            labelRes = R.string.lab_syslimiter_disable_label,
            descriptionRes = R.string.lab_syslimiter_disable_desc,
            category = LabCategory.PLATFORM,
            placement = LabPlacement.DEVICE_CAPABILITY,
            control = LabControl.TOGGLE,
            baseId = 0x01C44000, // PERF_RES_POWER_SYSLIMITER_DISABLE
            node = null,
            range = 0..1,
            risk = LabRisk.HIGH,
            selfClearing = false,
            dangerRes = R.string.lab_syslimiter_disable_danger,
        ),
    ).sortedWith(compareBy({ it.category.order }))
}

