package magicau.mtkcontroller.ui.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 四个顶层标签的横向容器。
 *
 * ## 为什么用 Pager 而不是继续用 NavHost 的四个目的地
 *
 * 用 NavHost 时切标签是"换一页"，中间没有过渡；用 Pager 之后几个标签像是**并排
 * 连在一起**，左右滑动就能过去，底栏也跟着手指走。这是用户明确要的形态。
 *
 * ## 这一层归谁所有
 *
 * 由 `AppNavHost` 渲染在 **NavHost 之外**。子页面（设置里的二级页、实验室等）
 * 是 NavHost 的独立目的地，压在它上面 —— 它们是"进入下一层"，不是"平移到隔壁"，
 * 两者的返回语义不同。
 *
 * 放在 NavHost 之外是性能上的必需：放进 `composable(TABS_ROUTE)` 的话，
 * 一进子页这一层就离开组合、返回时整套重新组合加重新布局，实测返回一帧要 233 ms。
 *
 * ## 四个标签全部常驻组合
 *
 * 见 [HorizontalPager] 处 `beyondViewportPageCount` 的注释。结论是给页数减一：
 * 切换 = 纯动画，没有组合也没有布局。
 *
 * ## 与底栏的关系
 *
 * 两个方向，一条出口：**这一层只往上报"用户停在哪一页"**（[onTabChange]，
 * 由 `settledPage` 的收集器触发），从不自己决定"现在该显示哪个标签"。
 * 反方向（外部选中变了要滑过去）由下面那个 `LaunchedEffect(selectedIndex)` 做。
 *
 * 所以"当前是哪个标签"只有一份，在调用方的 `selectedTab` 里。底栏点击走的是
 * 调用方自己的 `switchTab`，不经 [onTabChange] —— 那条路调用方本来就要改
 * `selectedTab`，再绕一圈回来只会多一次重算。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabsPager(
    tabs: List<Destination>,
    selected: Destination,
    onTabChange: (Destination) -> Unit,
    /**
     * 外部持有的 Pager 状态。底栏拖动时要直接改它的滚动位置，所以状态必须
     * 提上去 —— 两边各持一份就没法做到"拖多少、页面跟多少"。
     */
    pagerState: androidx.compose.foundation.pager.PagerState,
    modifier: Modifier = Modifier,
    /** [isSelected] 为真表示这一页正显示在屏幕上，周期读取只该在它为真时做。 */
    page: @Composable (Destination, Boolean) -> Unit,
) {
    val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)

    // 外部选中变化 → 滑过去。
    //
    // **"到位"要同时看页码和偏移，不能只看 currentPage。** 拖过中线松手时
    // Pager 的 currentPage 已经跳到目标页，而滚动位置还停在那两页中间 ——
    // 只判 currentPage 的话这里会认为"已经在那儿了"从而整段跳过，
    // 页面就卡在两页中间，要用户再碰一下才好。
    LaunchedEffect(selectedIndex, tabs.size) {
        if (selectedIndex !in tabs.indices) return@LaunchedEffect
        if (pagerState.currentPage == selectedIndex && pagerState.currentPageOffsetFraction == 0f) {
            return@LaunchedEffect
        }
        pagerState.animateScrollToPage(selectedIndex)
    }

    // 滑动停止 → 通知外部。用 settledPage 而不是 currentPage：后者在滑动过程中
    // 就会变，底栏会在半途闪一下再定下来。
    //
    // **回调必须经 rememberUpdatedState。** 下面这个 LaunchedEffect 的 key 只有
    // pagerState，重组不会重启它，协程里捕获的 `onTabChange` 会一直是第一次组合时
    // 那一份。调用方每次重组都会给一个新 lambda（它读的是组合作用域里的状态），
    // 于是这个协程会一直呼叫旧的那份 —— 里面算出来的 val 全是旧值。
    // 以前能跑是因为调用方恰好只用了状态委托；换成读普通 val 就会静默失效。
    val currentOnTabChange by rememberUpdatedState(onTabChange)
    LaunchedEffect(pagerState) {
        // **第一下不算换页。** snapshotFlow 在开始收集的那一刻会把"当前停在哪一页"
        // 立刻报出来，而那正是这次组合开始时的状态，不是用户刚做的动作。
        //
        // 这一条修的是一个真实 bug：标签容器从子页返回时是**重新组合**的，
        // 此时 Pager 还停在原标签上，这句会把外部刚设好的 selectedTab 覆盖回去 ——
        // 现象是"在子页点底栏只是退回上一层，标签根本没换"。
        var seenInitial = false
        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { index ->
            if (seenInitial) tabs.getOrNull(index)?.let(currentOnTabChange) else seenInitial = true
        }
    }

    // 冷启动那一帧只造当前标签，其余的在首帧画完之后补齐。
    //
    // 为什么要拆开：四个一起造会把另外三个标签的界面也塞进启动帧，
    // 而启动帧是用户等得最久的那一帧。补齐全在下一帧发生，晚几毫秒没人感觉得到，
    // 换来的结果是之后每一次切标签都不再造页。
    var tabsResident by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 等一个帧回调：LaunchedEffect 在首帧的组合阶段之后跑，所以这个回调
        // 落在下一帧 —— 那时首帧已经画完送上屏了，再加活就不挡它。
        withFrameNanos { }
        tabsResident = true
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        // **四个标签全部常驻组合，一个都不在动画中途现造。**
        //
        // 曾经设成 1（只预组合左右各一页）。省下的那点内存换来的是每次切标签
        // 必掉一帧：Pager 按"离当前滚动位置多远"决定组合哪些页，滑动一开始
        // 边界就往外扩一页，那一页此刻才第一次组合。实测每一跳都有**一帧**
        // 卡在 130 ms（其中 92 ms 在主线程的 Compose 帧里），
        // 而它落在动画中段，正是眼睛最盯着的地方 —— 用户说的"卡一下"就是它。
        // 跨两页的切换要连造两页，掉帧更多。
        //
        // 常驻之后切换 = 纯动画，没有组合、没有布局，帧时间只剩动画本身的几毫秒。
        // 代价是四个标签的界面同时活着，所以周期读取必须按可见性门控：
        // 首页的采样器和性能页的频率轮询都只看 [page] 的 isSelected，
        // 没被选中的那一页只是"组合着"，不读任何节点、不发任何请求。
        // 只有四个标签，所以给页数减一（Compose 会自己夹到页数）。
        beyondViewportPageCount = if (tabsResident) tabs.size - 1 else 1,
    ) { index ->
        // 同上：标签页也要有自己的底，否则滑动时相邻两页的文字会叠。
        androidx.compose.material3.Surface(
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            val tab = tabs.getOrNull(index)
            tab?.let { page(it, index == pagerState.settledPage) }
        }
    }
}
