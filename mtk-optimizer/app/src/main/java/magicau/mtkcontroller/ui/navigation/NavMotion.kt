package magicau.mtkcontroller.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import magicau.mtkcontroller.ui.theme.Motion
import magicau.mtkcontroller.ui.theme.MotionSpec

/**
 * 页面转场。**只有一种形态：横向平移，不淡出。**
 *
 * ## 为什么不做淡出
 *
 * 之前是"平移 + 同时淡出"。两者叠加会让页面**边滑边溶解**，看起来像在原地缩小
 * 然后消失，而不是被推开。iOS 的导航转场只有平移、没有淡出，观感上"这一页是被
 * 推到一边的"，方向感明确。
 *
 * 去掉淡出之后，位移本身就是唯一的视觉信号 —— 这也让预测性返回的手势预览
 * 与我们的转场是同一个动作，而不是"系统在拖、我们在淡"。
 *
 * ## 只有一种关系：进出子页
 *
 * | 关系 | 位移 | 时长 |
 * |---|---|---|
 * | 进出子页 | 进来的一页整屏，底下那页挪 1/3（视差） | [Motion.DRILL] |
 *
 * 子页用视差而不是两页都整屏：整屏位移会让两页在过渡中同时占满视野，
 * 分不清谁在上面。底下那页只挪三分之一，层级关系一眼可见。
 *
 * ## 为什么不再有"同级标签切换"
 *
 * 顶层标签曾经是 NavHost 的四个目的地，切标签就是换目的地，所以要一套横向推移。
 * 现在四个标签是 NavHost **外面**那一层 `HorizontalPager` 的页（见 `AppNavHost`），
 * 切标签由 Pager 自己的滚动完成，根本不经过 NavHost —— 这里再也收不到标签切换。
 *
 * 曾经为此留下的 `isTabSwitch` / `lateralEnter` / `lateralExit` / `movesForward`
 * 已经删掉：它们判的是"两端路由都是标签路由"，而图里没有任何一条路由等于标签路由，
 * 恒为假。留着等于留一段永远不会执行、却还在注释里声称自己在执行的代码。
 *
 * ## 为什么不给用户选风格
 *
 * 曾经有个「应用内 / 跟随系统」的二选一，其中"跟随系统"是纯淡入淡出。
 * 那个选项解决的是"自定义位移可能与系统手势预览打架" —— 但现在的平移方向与
 * 返回手势一致，本来就不打架，选项失去意义。**留着一个不该选的选项，比没有更糟**：
 * 用户会以为选错了才导致观感不对。
 *
 * 用哪一套转场是**编译期常量**（[USE_CUSTOM_TRANSITIONS]），不是设置项。
 * 系统的预测性返回预览由 `AndroidManifest.xml` 的
 * `android:enableOnBackInvokedCallback` 控制 —— 那是编译期属性，装到手机上之后
 * 无法更改；而系统预览与我们的转场会**同时播放**，两套叠在一起就是"缩小 + 平移"
 * 的怪样子。所以只能二选一，且必须在打包时定：改这一个常量 + 改 manifest 那一行，
 * 两处必须一致。
 */
const val USE_CUSTOM_TRANSITIONS = true

object NavMotion {

    fun enter(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
        motion: MotionSpec,
    ): EnterTransition = if (!USE_CUSTOM_TRANSITIONS) EnterTransition.None else drillEnter(motion)

    fun exit(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
        motion: MotionSpec,
    ): ExitTransition = if (!USE_CUSTOM_TRANSITIONS) ExitTransition.None else drillExit(motion)

    fun popEnter(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
        motion: MotionSpec,
    ): EnterTransition = if (!USE_CUSTOM_TRANSITIONS) EnterTransition.None else drillPopEnter(motion)

    fun popExit(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
        motion: MotionSpec,
    ): ExitTransition = if (!USE_CUSTOM_TRANSITIONS) ExitTransition.None else drillPopExit(motion)

    // --- 进出子页 -----------------------------------------------------------

    /** 进来的一页从右侧整屏推入。 */
    private fun drillEnter(motion: MotionSpec): EnterTransition =
        slideInHorizontally(motion.offsetDrill()) { it }

    /** 被压住的那页同时往左让出三分之一 —— 视差，让层级关系可见。 */
    private fun drillExit(motion: MotionSpec): ExitTransition =
        slideOutHorizontally(motion.offsetDrill()) { -it / 3 }

    /** 返回：底下那页从左侧三分之一处归位。 */
    private fun drillPopEnter(motion: MotionSpec): EnterTransition =
        slideInHorizontally(motion.offsetDrill()) { -it / 3 }

    /**
     * 返回：**当前页向右整屏移出。**
     *
     * 这就是"上层界面向右迅速平移"那一条 —— 没有淡出，所以它是干净地被推走，
     * 而不是边滑边溶解。
     */
    private fun drillPopExit(motion: MotionSpec): ExitTransition =
        slideOutHorizontally(motion.offsetDrill()) { it }
}
