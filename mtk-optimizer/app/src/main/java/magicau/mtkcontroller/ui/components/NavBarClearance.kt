package magicau.mtkcontroller.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.domain.model.NavBarVisibility

/**
 * 内容区底部要给悬浮底栏让出的高度。
 *
 * ## 为什么要收成一个值
 *
 * 底栏是**浮在所有页面之上**的一层（不在 Scaffold 里），页面不会自动避开它。
 * 之前 `110.dp` 和 `108.dp` 两个数散在十几个文件里，改底栏尺寸就得挨个找，
 * 而且一定会漏。收进主题之后和圆角、颜色是同一个道理：一处改，全应用跟着变。
 *
 * ## 两种取值
 *
 * - [NavBarVisibility.FIXED]：底栏常驻，内容必须真的让开，否则最后几行被压住
 *   且**滚不上来**（滚动容器的视口一直延伸到屏幕底，内容认为已经到底了）。
 * - [NavBarVisibility.AUTO_HIDE]：底栏随滚动隐去。内容**铺满**即可 ——
 *   再硬留一条空白，页面底部就会切出一道横线，而底栏根本不需要那个位置。
 *   留一点点只是为了最后一行不贴着屏幕边。
 *
 * 默认取 [FIXED] 的值，这样在主题之外渲染（预览、测试）也是完整的样子。
 */
val LocalNavBarClearance = staticCompositionLocalOf { FIXED_NAV_BAR_CLEARANCE }

/** 底栏常驻时内容要让出的高度。含底栏自身、它与屏幕底的距离，以及系统导航条。 */
val FIXED_NAV_BAR_CLEARANCE = 110.dp

/** 底栏会自动隐去时只留的呼吸位。 */
val AUTO_HIDE_NAV_BAR_CLEARANCE = 16.dp

/** 页面自己加的一点点内边距，叠在 [LocalNavBarClearance] 之上。 */
val SUB_SCREEN_EXTRA_PADDING = 8.dp

fun clearanceFor(visibility: NavBarVisibility) = when (visibility) {
    NavBarVisibility.FIXED -> FIXED_NAV_BAR_CLEARANCE
    NavBarVisibility.AUTO_HIDE -> AUTO_HIDE_NAV_BAR_CLEARANCE
}
