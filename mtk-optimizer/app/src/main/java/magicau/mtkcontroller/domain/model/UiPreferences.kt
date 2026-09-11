package magicau.mtkcontroller.domain.model

/**
 * Light/dark selection, independent of the accent palette.
 *
 * SYSTEM follows the OS; the explicit values exist because the app is often
 * used at night on a device whose system theme is not what you want here.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
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
enum class ApplyMode {
    SINGLE,
    POLLING,
    ;

    companion object {
        fun fromName(name: String?): ApplyMode =
            entries.firstOrNull { it.name == name } ?: SINGLE
    }
}

/**
 * CPU 页控制区的排布方式。两套都给用户留着，因为两者各有各的清楚处。
 *
 *  - [PER_CLUSTER]：温控、调速器、每个核簇各占一张卡片。每张卡只讲一件事，
 *    改哪一项就找哪一张。
 *  - [UNIFIED]：全部收进一张卡片，标题就是当前方案。方案是这一页的主语，
 *    换方案比调单项频繁，把切换提到最前面。
 *
 * 不替用户定：卡片高度、屏幕大小、常调的是方案还是单项，三件事一起决定哪个更顺手。
 */
enum class CpuLayoutMode {
    PER_CLUSTER,
    UNIFIED,
    ;

    companion object {
        fun fromName(name: String?): CpuLayoutMode =
            entries.firstOrNull { it.name == name } ?: UNIFIED
    }
}

/**
 * 频率区间的调节方式。两种，用户选。
 *
 * 两者产出的结果是同一个东西（一簇的 min..max），只是**手势模型**不同：
 *
 *  - [SEGMENT]：一排等宽的格子，一下点锁定、第二下点出区间、拖动划一段。
 *    好处是**离散档位可见**，每一档有自己的点击目标，滑条做不到这件事。
 *  - [SLIDER]：两根滑块，一根 min 一根 max，各自拖。这是**通用做法** ——
 *    第一次用的人不用学就知道怎么用，代价是看不出总共有几档。
 *
 * 默认 [SEGMENT]：它信息量更大，而且已经在用。给选择是因为"哪个更顺手"
 * 取决于用户怎么用这一页 —— 精调某一档的人喜欢格子，拖个大概的人喜欢滑块。
 */
enum class RangeControlMode {
    SEGMENT,
    SLIDER,
    ;

    companion object {
        fun fromName(name: String?): RangeControlMode =
            entries.firstOrNull { it.name == name } ?: SEGMENT
    }
}

/**
 * 从底栏切走再切回来时，二级/三级页面怎么办。
 *
 * 两个选项对应两种意图，没有谁更对：
 *
 *  - [KEEP]：切走是"我去隔壁看一眼"，回来还在原处。适合一层层翻设置的人。
 *  - [RESET]：切走是"这事办完了"，回来从标签主页开始。适合把底栏当导航用的人。
 *
 * 默认 [KEEP]：丢位置比多看一眼更烦，而且想要另一种的一次就能改掉。
 */
enum class TabRestoreMode {
    KEEP,
    RESET,
    ;

    companion object {
        fun fromName(name: String?): TabRestoreMode =
            entries.firstOrNull { it.name == name } ?: KEEP
    }
}

/**
 * 悬浮底栏在滚动时的行为。
 *
 * ## 为什么要有这个选择
 *
 * 底栏是浮在内容之上的一层。内容要么**让开**它，要么被它压住 —— 没有第三种。
 * 让开的话页面底部会空出一条，长列表看起来像被"切"了一道；不让开的话最后几行
 * 会被压住而且滚不上来。
 *
 * [AUTO_HIDE] 是第三条路：内容铺满，底栏在用户往深处滚的时候自己滑走，
 * 往回滚再回来。要看底部内容时它自然不在，要切标签时一往回滚它就在。
 *
 * 代价是"底栏现在在不在"变成不确定的。默认给 [AUTO_HIDE]：这块屏幕以纵向内容
 * 为主，让内容铺满比留住一条底栏更值；想切标签时往回滚一下它就在。
 */
enum class NavBarVisibility {
    FIXED,
    AUTO_HIDE,
    ;

    companion object {
        fun fromName(name: String?): NavBarVisibility =
            entries.firstOrNull { it.name == name } ?: AUTO_HIDE
    }
}

/**
 * 锁屏时 CPU 频率请求怎么处理。
 *
 * 厂商服务会在熄屏后重排频率限制，有些机型上亮屏后用户设的范围已经丢了。
 * 两种处理都包含“亮屏后重新下发一次”，区别只在熄屏期间是否主动释放：
 *
 *  - [OFF]：不联动，保持原有行为。
 *  - [KEEP]：息屏不释放，亮屏后重新下发。默认值，不改动已有请求。
 *  - [RELEASE]：锁屏先释放，亮屏后重新下发。适合担心熄屏时高频下限耗电的场景。
 *
 * 这里只涉及频率请求；温控档位、实验室请求、GPU 不跟着释放。
 */
enum class ScreenOffPolicy {
    OFF,
    KEEP,
    RELEASE,
    ;

    companion object {
        fun fromName(name: String?): ScreenOffPolicy =
            entries.firstOrNull { it.name == name } ?: KEEP
    }
}

/** How the bottom navigation bar is coloured. */
enum class NavBarStyle {
    COLORFUL,
    FOLLOW_THEME,
    CUSTOM,
    ;

    companion object {
        fun fromName(name: String?): NavBarStyle =
            entries.firstOrNull { it.name == name } ?: COLORFUL
    }
}
