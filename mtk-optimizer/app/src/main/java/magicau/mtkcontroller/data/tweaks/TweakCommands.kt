package magicau.mtkcontroller.data.tweaks

import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.settings.SettingChannel
import magicau.mtkcontroller.data.powerhal.PowerHal

/**
 * 系统调校用的 shell 命令集。
 *
 * 触控相关已整体移到 [TouchItems]：每项自己声明旋钮，界面上逐项开关。
 * 这里只留与触控无关的零散命令。
 */

object TweakCommands {

    /** 上报率档位，供界面选。实际写入由 [TouchItems] 决定。 */
    val TOUCH_RATES = listOf(120, 180, 240, 300, 360)

    const val DEFAULT_TOUCH_RATE = TouchItems.DEFAULT_RATE

    /**
     * 写 settings 的命令字符串。实现统一走 [SettingChannel]，别在这里另写一份。
     *
     * **必须用 `content insert`，不能用 `settings put`**：真机实测 `settings put` 与
     * `cmd settings put` 在这台 ROM 上退出码 0 但不落库（含一个已存在且有值的键）。
     */
    fun settingsPut(namespace: String, key: String, value: String): String =
        SettingChannel.putCommand(namespace, key, value)

    /**
     * 频率请求时**附带下发的调度器配合项**。
     *
     * 出处：本机 `/vendor/etc/powerscntbl.xml` 的场景 hint 3160
     * （注释 `benchmark mode package switch boost`）—— 厂商把 policy7 软下限
     * 顶到 3250000 的同时发了这几条。我们此前只发频率、从不携它们，
     * 软下限因此一直被压在 2300。
     *
     * **未在本机验证过。** 默认不启用，需在设置里显式打开（实验项，可随时关）。
     * 打开后若无效或有害，关掉即恢复原状，不会留下残留 —— 这些都是无状态开关。
     */
    val SCHED_COMPANIONS: List<Pair<Int, String>> = listOf(
        PowerHal.SCHED_CLOSE_PELT_ENABLE to "0",
        PowerHal.SCHED_ADAPTIVE_MARGIN to "0",
        PowerHal.SCHED_PACKING_CPU to "0",
        PowerHal.KICK_IDLE_SPEED_UP to "1",
    )

    /** 另一套配合：把 top-app 的 uclamp 下限顶上去。与 [SCHED_COMPANIONS] 二选一试。 */
    val UCLAMP_COMPANIONS: List<Pair<Int, String>> = listOf(
        PowerHal.SCHED_UCLAMP_MIN_TA to "70",
    )

    /**
     * 两套待验证的配合方案。**都不默认启用**，由用户在设置里选，
     * 选哪个就把哪个的 (opcode, value) 附在频率请求后面。
     *
     * 加一套只加一条数据 + 一个文案资源，不加分支。
     */
    /**
     * 频率请求要不要带上限。
     *
     * **为什么可选**：BSP 源码 —— "达到或超过 HAL 表 `freqMax` 的软上限会被翻译成
     * 一个被忽略的帽值"。本机 HAL 表的 freqMax 约在 2000–2300，所以我们请求
     * 2900–3400 时，那条软上限不是"请求上限"，而是"给自己戴一个 2300 的帽子"。
     * 用户最早报告"一应用自己的频率就缩到 2.3"正是这个。
     *
     * 厂商自己的高性能场景（`powerscntbl.xml` 3160）**只发三个 MIN，一个上限都不发**。
     * 所以"只发下限"是抄它的做法，也是目前最可能解开上限的一条。
     */
    /**
     * 下发哪些边界。三种模式，每个只发自己那一半。
     *
     * 做成一枚举而不是两个布尔：两个布尔能表达出"上下限都不发"这种没有意义的状态，
     * 而枚举天然互斥。以后别的功能要"只限一头"，复用这个枚举即可。
     */
    enum class RangeMode(val labelRes: Int) {
        /** 上下限都发 —— 把频率限制在区间内。 */
        RANGE(R.string.freq_ceiling_both),

        /**
         * 只发下限，不设上限 —— 频率由负载和平台决定。
         *
         * 想**突破**系统默认上限时用这个：上一条软上限会被另一场景的软下限抬高，
         * 而发上限请求等于给自己戴一个帽子（厂商的高性能场景就是只发下限）。
         */
        FLOOR_ONLY(R.string.freq_ceiling_floor_only),

        /**
         * 只发上限，不抬下限 —— 用于压住发热，低频段交给系统自己调度。
         *
         * **未在真机上验证过。** 本机 2026-09-24 的区间实测覆盖的是"下限+上限"与
         * "只有下限"两种，缺这一种。首次使用请对照 `scaling_cur_freq` 的峰值确认。
         */
        CEILING_ONLY(R.string.freq_ceiling_only),
    }

    enum class Companion(val labelRes: Int, val pairs: List<Pair<Int, String>>) {
        /** 关 PELT 钳制那一套，对应厂商跑分场景。 */
        SCHED(R.string.freq_companion_sched, SCHED_COMPANIONS),

        /** 顶 top-app 的 uclamp 下限。 */
        UCLAMP(R.string.freq_companion_uclamp, UCLAMP_COMPANIONS),
    }

    /**
     * 配合项的展示名。null = 不认识这个 id。
     *
     * **直接用枚举自己的 `labelRes`，不另造一套短名。** 曾经这里拼的是
     * `"方案 ${ordinal + 1}"`，于是同一项在设置页叫一个名字、在结果说明里叫另一个，
     * 而且那个序号是枚举顺序，枚举一动就错。
     */
    fun companionMessage(id: String): Message? =
        runCatching { Companion.valueOf(id) }.getOrNull()?.let { Message(it.labelRes) }

    // --- frame interpolation ------------------------------------------------

    /**
     * 开启时写入的插帧命令。
     *
     * **不要把 `0:-1:0:0:0` 当开启值** —— 那是关闭哨兵。固件侧的判定就是
     * `第一段==0 && 第二段==-1 && 第四段==0 && 第五段==0`，本软件之前在"开启"分支里
     * 写的正是它，于是开关显示成功而实际什么都没开（`settings put` 对任何值都返回 0，
     * 而调用方只查退出码）。
     *
     * 这五个冒号分隔段来自 vivo 固件的实测格式：开关:模式:?:帧间隔:目标帧率。
     * 120Hz 的官方命令形如 `1:1:0:30:120`。
     */
    private const val INTERP_ENABLE = "1:1:0:30:120"

    /** 关闭哨兵。写它而不是 delete，这样回读才有可判定的值。 */
    private const val INTERP_DISABLE = "0:-1:0:0:0"

    /** 关闭哨兵的前缀。第三段会变，所以只比前两段。 */
    private const val OFF_PREFIX = "0:-1"

    const val KEY_INTERP = "gamecube_frame_interpolation"
    const val KEY_INTERP_SR = "gamecube_frame_interpolation_for_sr"

    fun frameInterpolation(enable: Boolean, superResolution: Boolean = false): String {
        val key = if (superResolution) KEY_INTERP_SR else KEY_INTERP
        return if (enable) {
            SettingChannel.putCommand("system", key, INTERP_ENABLE)
        } else {
            SettingChannel.putCommand("system", key, INTERP_DISABLE)
        }
    }

    /** 供写后回读定位键名。 */
    fun keyFor(superResolution: Boolean): String =
        if (superResolution) KEY_INTERP_SR else KEY_INTERP

    /** 供写后回读比对目标值。 */
    fun interpValue(enable: Boolean): String =
        if (enable) INTERP_ENABLE else INTERP_DISABLE

    /**
     * 读回来的值算不算"已关闭"。
     *
     * 只比前两段：第三段是运行时会变的数（实测出现过 10846），拿整串去比会把
     * 正在生效的插帧判成关闭。判定规则留在这里，别在调用点重写一遍。
     */
    fun isInterpOff(raw: String): Boolean = raw.startsWith(OFF_PREFIX)

    // --- screen rotation ----------------------------------------------------

    /**
     * Android's own "rotate suggestion": while auto-rotate is off, the system
     * keeps the orientation sensor alive and offers a rotate button in the
     * navigation bar when it notices the device has been turned.
     *
     * `Settings.Secure.show_rotation_suggestions` — 1 shows the button, 0 hides it.
     */
    fun rotationSuggestion(enable: Boolean): String =
        SettingChannel.putCommand("secure", "show_rotation_suggestions", (if (enable) 1 else 0).toString())

    fun readRotationSuggestion(): String = "settings get secure show_rotation_suggestions"

    fun autoRotate(enable: Boolean): String =
        SettingChannel.putCommand("system", "accelerometer_rotation", (if (enable) 1 else 0).toString())

    /** 0 = 0°, 1 = 90°, 2 = 180°, 3 = 270°. */
    fun forceRotation(rotation: Int): List<String> = listOf(
        SettingChannel.putCommand("system", "accelerometer_rotation", "0"),
        SettingChannel.putCommand("system", "user_rotation", rotation.toString()),
    )

    fun readAutoRotate(): String = "settings get system accelerometer_rotation"

    // --- thermal brightness -------------------------------------------------

    fun readBrightness(): String = "settings get system screen_brightness"

    fun setBrightness(value: Int): String = SettingChannel.putCommand("system", "screen_brightness", value.toString())

    fun readBrightnessMode(): String = "settings get system screen_brightness_mode"
}
