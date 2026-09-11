package magicau.mtkcontroller.domain.model

/**
 * Light/dark selection, independent of the accent palette.
 *
 * SYSTEM follows the OS; the explicit values exist because the app is often
 * used at night on a device whose system theme is not what you want here.
 */
enum class ThemeMode(val label: String, val description: String) {
    SYSTEM("跟随系统", "与系统深色模式保持一致"),
    LIGHT("浅色", "始终使用浅色界面"),
    DARK("深色", "始终使用深色界面"),
    ;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * How CPU/GPU limits are applied.
 *
 * SINGLE writes once and leaves the kernel to enforce it. POLLING re-sends the
 * same limits on a timer, because MTK's powerhal and FPSGO are free to overwrite
 * them and nothing else puts them back.
 */
enum class ApplyMode(val label: String, val description: String) {
    SINGLE("单次设置", "只在点击应用时下发一次"),
    POLLING("轮询修改", "按间隔反复下发,防止被系统覆盖"),
    ;

    companion object {
        fun fromName(name: String?): ApplyMode =
            entries.firstOrNull { it.name == name } ?: SINGLE
    }
}

/**
 * How the CPU screen's segment bar is driven.
 *
 * Both modes lock on a single tap; they differ in the gesture that produces a
 * range, so a user who keeps triggering ranges by accident can turn dragging off
 * entirely rather than learning to tap more carefully.
 */
enum class CpuControlStyle(val label: String, val description: String) {
    TAP("点击型", "点一下锁死该频率 · 再点一个值选范围"),
    SLIDE("滑动型", "拖过的一段就是范围 · 点一下锁死"),
    ;

    companion object {
        fun fromName(name: String?): CpuControlStyle =
            entries.firstOrNull { it.name == name } ?: TAP
    }
}

/** How the bottom navigation bar is coloured. */
enum class NavBarStyle(val label: String, val description: String) {
    COLORFUL("彩色", "每个标签使用自己的颜色"),
    FOLLOW_THEME("跟随主题色", "全部使用当前主题的强调色"),
    CUSTOM("自定义色系", "为底栏单独指定一个色系"),
    ;

    companion object {
        fun fromName(name: String?): NavBarStyle =
            entries.firstOrNull { it.name == name } ?: COLORFUL
    }
}
