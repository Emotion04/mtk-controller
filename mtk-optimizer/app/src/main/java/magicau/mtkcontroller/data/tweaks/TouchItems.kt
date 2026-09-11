package magicau.mtkcontroller.data.tweaks

import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.settings.SettingChannel

/**
 * 触控子项。
 *
 * ## 为什么拆开
 *
 * 原来"触控优化"是一个总开关，底下一次写二十几条命令，三个后果：
 *
 * 1. **分不出哪一条有用。** 没有自动探针能回答"系统理不理睬"，只有人能判断
 *    "跟手了没有"。整族一起写，写完了也白写。
 * 2. **一条被拒就连坐。** `device_config put` 在这台机器上被 SELinux 拒（退出码 255），
 *    `CommandSet` 还没分层的那个版本把它当成整体失败，触控开关因此永远打不开 ——
 *    用户看到的现象是"能开但关不掉、也调不了"。
 * 3. **已知无效的也跟着跑。** 三个 `debug.*report_rate` 做过对照实验：写 180 与写 0
 *    测出的上报率中位数都是 129.5 Hz，完全一样。
 *
 * 拆开后每项自己声明写什么、关掉时写回什么，[TweakRepository] 逐项执行，
 * 一项失败不影响其他项。**新增一项只加一条 [TouchItem]，新增一个旋钮只加一条
 * [TouchKnob]，不加分支。**
 *
 * ## 不收进来的两类
 *
 * - `sys_uidcpupower`：全局调度策略，不是触控。打开它之后，别的开关有没有效果就说不清了，
 *   而本软件的第一条规矩是不碰原有 CPU 控制。
 * - `debug.sf.hw 0`：关掉硬件合成，改的是整条显示合成方式。功耗和画面异常
 *   风险都由用户承担，且与触控无直接关系，不放进来。
 */

/** 旋钮归属哪个命名空间。prop 走 `setprop`，其余走 settings。 */
enum class TouchNs { PROP, SYSTEM, SECURE, GLOBAL }

/**
 * 一个旋钮：一个键，一个开启值。
 *
 * **没有"关闭值"字段，关闭一律写回打开前存下的原值。**
 *
 * 这条是踩出来的：`touch_scan_press_time_delay` 在这台机器出厂就是 300，而它的"关闭"
 * 语义确实是 0。若在数据里写死 `offValue = 0`，用户点一下"关闭"就把一台本来调好的
 * 机器改差了，而且读回还是成功的，看不出任何异常。同理 `pointer_speed` 出厂是 0，
 * 写死 `offValue = 0` 在这台机器上碰巧对，换一台就错。
 *
 * 所以：关闭 = 恢复快照。快照取不到时才退回 [fallbackOff]，那是这一族旋钮的通用
 * "关闭"语义（归零 / false），适用于从没被本软件打开过的项。
 */
data class TouchKnob(
    val ns: TouchNs,
    val key: String,
    val onValue: String,
    /** 没有快照时的关态。仅用于从没被本软件打开过的项。 */
    val fallbackOff: String = "0",
    /**
     * 可以填的数值范围 —— 只有带范围的旋钮才由用户在界面上填数。
     *
     * 为什么有的项是开关、有的是数值：**开关只能表达"有没有这一档"，而这一档背后
     * 本来就是个数**。按下防抖的四个键是四个毫秒数、指针速度是 0–7 的档位，写成
     * 开关等于只给人一个出厂预设。范围只写本机确认过的区间；没真机验过取值含义的
     * 参数（响应延迟）不给范围，免得猜出一个假的上限。
     */
    val numberRange: IntRange? = null,
) {
    /**
     * 这条旋钮要执行的命令。
     *
     * @param onValue 开启时要写的值；用户填过数就是那个数，否则是 [onValue]。
     *   写 settings 一律走 [SettingChannel]，数值原样作为字符串写 —— 键的消费者可能
     *   把它当整数（`touch_scan_press_time_delay` 就是毫秒），别在外面做格式化。
     */
    fun command(on: Boolean, offValue: String, onValue: String = this.onValue): String {
        val value = if (on) onValue else offValue
        return if (ns == TouchNs.PROP) "setprop $key $value"
        else SettingChannel.putCommand(ns.name.lowercase(), key, value)
    }

    /** 写后回读命令。 */
    fun readCommand(): String =
        if (ns == TouchNs.PROP) "getprop $key" else "settings get ${ns.name.lowercase()} $key"

    /** 回读时应该看到的值。 */
    fun expectedRead(on: Boolean, offValue: String, onValue: String = this.onValue): String =
        if (on) onValue else offValue
}

/** 打开前存下的原值。键由 [TouchKnob.originalKey] 生成。 */
typealias TouchOriginals = Map<String, String>

/** 这条旋钮的原值在快照里的键。 */
fun TouchKnob.originalKey(itemId: String): String = "$itemId|${ns.name}|$key"

/**
 * 在共享快照里这条旋钮的名字：`命名空间.键名`。
 *
 * 用点号而不是竖线，是因为快照存储本身的键已经用竖线分隔了空间和条目；
 * 名字里再带竖线，`release` 按前缀拼键时就分不清边界。
 */
val TouchKnob.storeKey: String get() = "${ns.name.lowercase()}.$key"

/**
 * 关闭时该写成什么：优先快照，取不到才退回通用关闭语义。
 *
 * 纯函数，不碰 IO —— 单元测试直接覆盖"出厂值非零时关闭不会把它改差"这条。
 */
fun TouchKnob.offTarget(itemId: String, originals: TouchOriginals): String =
    originals[originalKey(itemId)] ?: fallbackOff

/**
 * 一个可单独开关的触控子项。
 *
 * @param id 持久化用的稳定标识，改名等于用户丢一次配置，别改。
 */
data class TouchItem(
    val id: String,
    val labelRes: Int,
    val detailRes: Int,
    val evidence: Evidence,
    val knobs: List<TouchKnob>,
    /**
     * 只读项：界面上不画开关，只显示当前值。
     *
     * 用于"开启值、关闭值、出厂值三者相同"的键 —— 那种开关在定义上就是空动作，
     * 画出来只会让人以为试过了。但键本身仍要留在 [knobs] 里：登记表、备份、
     * 漂移监测都从 knobs 推导，清空它会让这一项从所有机制里消失。
     */
    val readOnly: Boolean = false,

    /**
     * 是否在界面上把这个子项各个旋钮的当前值显示出来。
     *
     * [readOnly] 的项隐含为 true。开关项也可以要：**取值来自外部记录、语义又没公开的
     * 项，显示当前值是人唯一能判断"这台机器是什么配置"的办法** ——
     * 按下防抖就是这种，这台机器出厂即目标值，开关是空动作，不显示数值根本看不出
     * 它已经是 300/200/300/2。
     */
    val showsValues: Boolean = readOnly,
) {
    val all: List<TouchKnob> get() = knobs

    /** 这一项里可以由用户填数的旋钮；空 = 这一项是纯开关。 */
    val numericKnobs: List<TouchKnob> get() = knobs.filter { it.numberRange != null }
}

object TouchItems {

    const val REPORT_RATE = "report_rate"
    const val PRESS_DELAY = "press_delay"
    const val POINTER_SPEED = "pointer_speed"
    const val RESPONSE_DELAY = "response_delay"
    const val RENDER_VSYNC = "render_vsync"
    const val SF_LATCH = "sf_latch"

    /**
     * 按下防抖那几个毫秒键的填写范围。
     *
     * 下限 0 是"不防抖"；上限取 1000 ms —— 再长就已经不是防抖，而是"点了要等一秒"，
     * 固件大概率另有夹取。**这个上限是本软件给的范围，不是固件公布的**，
     * 所以超过 300（本机出厂值）时界面上要和用户说清楚。
     */
    val PRESS_DELAY_MS = 0..1000

    /** 上报率的开启值。只有这一项需要用户选档位。 */
    const val DEFAULT_RATE = 360

    /**
     * 全部子项。顺序就是界面顺序。
     *
     * **每加一项先去真机取一次出厂态**（重启，然后 `getprop` / `settings get` 读一遍）。
     * 出厂态决定这一项在本机会不会真的是空动作，也决定关闭时要恢复成什么。
     * 本项目第一版就是没做这一步，五六个开关里有三个是空动作还不自知。
     *
     * @param rateHz 触控上报率这一项的开启值；其余项不用它。
     */
    fun all(rateHz: Int = DEFAULT_RATE): List<TouchItem> = listOf(
        TouchItem(
            id = REPORT_RATE,
            labelRes = R.string.touch_item_report_rate,
            detailRes = R.string.touch_item_report_rate_detail,
            // 对照实验：写 180 与写 0，实测上报率中位数都是 129.5 Hz。
            evidence = Evidence.LIKELY_USELESS,
            knobs = listOf(
                TouchKnob(TouchNs.PROP, "debug.touch.report_rate", "$rateHz", "0"),
                TouchKnob(TouchNs.PROP, "debug.touchscreen.report_rate", "$rateHz", "0"),
                TouchKnob(TouchNs.PROP, "debug.game_touch_sampling_rate", "$rateHz", "0"),
            ),
        ),
        TouchItem(
            id = PRESS_DELAY,
            labelRes = R.string.touch_item_press_delay,
            detailRes = R.string.touch_item_press_delay_detail,
            evidence = Evidence.WRITTEN,
            showsValues = true,
            knobs = listOf(
                // 四个键分工没有公开文档，混着毫秒和档位（_grade 是 2 档而不是 2 ms），
                // 所以每个各给各的范围，界面上用键名区分，不替固件编一句"这是 XX 毫秒"。
                TouchKnob(TouchNs.SYSTEM, "touch_scan_press_time_delay", "300", "0", PRESS_DELAY_MS),
                TouchKnob(TouchNs.SYSTEM, "touch_scan_press_time_delay_double", "200", "0", PRESS_DELAY_MS),
                TouchKnob(TouchNs.SYSTEM, "touch_scan_press_time_delay_single", "300", "0", PRESS_DELAY_MS),
                TouchKnob(TouchNs.SYSTEM, "touch_scan_press_time_delay_grade", "2", "0", 0..9),
            ),
        ),
        TouchItem(
            id = POINTER_SPEED,
            labelRes = R.string.touch_item_pointer_speed,
            detailRes = R.string.touch_item_pointer_speed_detail,
            // 唯一有系统读数的：0 → 7 已确认落到系统里。
            evidence = Evidence.CONFIRMED,
            knobs = listOf(
                TouchKnob(TouchNs.SYSTEM, "pointer_speed", "7", "0", 0..7),
            ),
        ),
        TouchItem(
            id = RENDER_VSYNC,
            labelRes = R.string.touch_item_render_vsync,
            detailRes = R.string.touch_item_render_vsync_detail,
            evidence = Evidence.WRITTEN,
            knobs = listOf(
                TouchKnob(TouchNs.PROP, "debug.hwui.disable_vsync", "true", "false"),
                TouchKnob(TouchNs.PROP, "debug.cpurend.vsync", "false", "true"),
            ),
        ),
        // 只读状态项：界面上只显示当前值，不给开关。
        //
        // 为什么不留成开关：开启值 0、关闭值 0、本机出厂值也是 0 —— 三项同一个数，
        // 这个开关在定义上就是空动作。留着只会让人以为"试过了，没用"。
        // 但外部取值就是 0，说明别的地方可能真的拿它做事，所以留着读数，
        // 留给少数可能生效的机型；那些机型上这一项才有意义。
        TouchItem(
            id = RESPONSE_DELAY,
            labelRes = R.string.touch_item_response_delay,
            detailRes = R.string.touch_item_response_delay_detail,
            evidence = Evidence.LIKELY_USELESS,
            knobs = listOf(
                TouchKnob(TouchNs.SYSTEM, "touch_response_delay", "0", fallbackOff = "0"),
            ),
            readOnly = true,
        ),
        TouchItem(
            id = SF_LATCH,
            labelRes = R.string.touch_item_sf_latch,
            detailRes = R.string.touch_item_sf_latch_detail,
            evidence = Evidence.WRITTEN,
            // 真机实测（重启后取到的出厂态）：
            //   auto_latch_unsignaled 出厂空   → 写 true 是真变化
            //   latch_unsignaled     出厂空   → 写 1 是真变化
            //   disable_backpressure 出厂 1   → 写 1 是空动作（保留：别的 ROM 上可能是 0）
            // 原来还有一条 debug.sf.enable_hwc_vds 写 1，已删：本机 ROM 自己把它设成 0，
            // 写 1 是在和 ROM 对着干，方向不明，不该由触控功能去翻它。
            knobs = listOf(
                TouchKnob(TouchNs.PROP, "debug.sf.auto_latch_unsignaled", "true", "false"),
                TouchKnob(TouchNs.PROP, "debug.sf.disable_backpressure", "1", "0"),
                TouchKnob(TouchNs.PROP, "debug.sf.latch_unsignaled", "1", "0"),
            ),
        ),
    )

    fun byId(id: String, rateHz: Int = DEFAULT_RATE): TouchItem? = all(rateHz).firstOrNull { it.id == id }
}
