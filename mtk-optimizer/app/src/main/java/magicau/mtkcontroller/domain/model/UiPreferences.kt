package magicau.mtkcontroller.domain.model

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

/**
 * Which control the CPU screen uses per cluster.
 *
 * All three edit the same underlying min/max pair; they differ only in how the
 * range is expressed.
 */
enum class CpuControlStyle(val label: String, val description: String) {
    RANGE_SLIDER("双滑块", "分别拖动下限与上限"),
    SINGLE_SLIDER("单滑块", "锁定到单一频率(上下限相同)"),
    SEGMENT_BAR("分段进度条", "点按分段直接选择上下限"),
    ;

    companion object {
        fun fromName(name: String?): CpuControlStyle =
            entries.firstOrNull { it.name == name } ?: RANGE_SLIDER
    }
}
