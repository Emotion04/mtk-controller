package magicau.mtkcontroller.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import magicau.mtkcontroller.ui.theme.LocalMotion

/**
 * 纵向列表，长按手柄后可以拖动重排。
 *
 * ## 为什么用普通 Column 而不是 LazyColumn
 *
 * 首页卡片数量少、高度差异大。LazyColumn 的窗口化裁剪在"被拖的卡片要浮在
 * 其他卡片之上、其他卡片要按它的落点让位"这件事上要额外处理跨项坐标，
 * 而普通 Column 里每一项的位置是确定的，算落点直接累加即可。
 *
 * ## 高度和位置都按 [key] 记，不按下标记
 *
 * 重排之后同一个下标会指向另一张卡片。按下标记高度的话，重排完那张表就是错的
 * （A 的高度记在 B 头上），下一次拖动会算出错误的落点。所以两张表都以 [key] 为索引。
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    /** 外层的滚动状态。给了才能做"拖到边缘自动滚动"。 */
    scrollState: androidx.compose.foundation.ScrollState? = null,
    /** 每一项的稳定标识。跨重排追踪被拖的那一项要用它。 */
    key: (T) -> Any,
    /**
     * 每一项的外形，**只用来投影**。
     *
     * 不传的话阴影是矩形的：卡片本身是圆角，阴影却是方角，拖起来能看见四个尖角
     * 从圆角后面戳出来。阴影的形状由 `shadow(elevation, shape)` 的 shape 决定，
     * 所以这个必须跟着卡片自己的圆角走。
     */
    itemShape: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape,
    itemContent: @Composable (index: Int, item: T, isDragging: Boolean, dragHandle: Modifier) -> Unit,
) {
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Any, Int>() }
    val density = LocalDensity.current
    val spacingPx = with(density) { spacing.roundToPx() }

    /** 一项占的高度，含它下面的间距。没量到之前按 0 算。 */
    fun rowHeightPx(index: Int): Float =
        (heights[key(items[index])] ?: 0).toFloat() + spacingPx

    /**
     * 全部高度都量到了才算得准槽位边界。
     *
     * 首帧 [heights] 是空的，这时候按下拖动会算出错误的落点（所有槽位都是零高，
     * 中心必然落在最后一格），所以量齐之前不动落点。
     */
    val heightsReady = heights.size == items.size && heights.values.none { it <= 0 }

    /**
     * 第 [index] 项在内容坐标系里的顶边。
     *
     * Column 用 `Arrangement.spacedBy`，所以第 i 项的顶边等于它前面所有项的
     * 高度加间距之和 —— 直接累加，不需要另记一份位置。
     */
    fun slotTopPx(index: Int): Float {
        var acc = 0f
        for (j in 0 until index) acc += rowHeightPx(j)
        return acc
    }

    /**
     * 拖动过程中算出的**落点**。
     *
     * 松手前就要算，因为其他卡片靠它决定往哪边让 —— 只在松手时算的话，
     * 用户全程看不到"会落到哪"。
     */
    val targetIndex = if (draggingIndex < 0 || !heightsReady) {
        -1
    } else {
        val draggedHeight = (heights[key(items[draggingIndex])] ?: 0).toFloat()
        // 被拖卡片的**中心**在内容坐标系里的位置。
        val center = slotTopPx(draggingIndex) + dragOffsetPx + draggedHeight / 2f
        var acc = 0f
        var found = items.lastIndex
        for (j in items.indices) {
            val slotHeight = rowHeightPx(j)
            if (center < acc + slotHeight) {
                found = j
                break
            }
            acc += slotHeight
        }
        found.coerceIn(0, items.lastIndex)
    }

    // **pointerInput 的 lambda 会捕获创建时那一刻的值。**
    // 它的 key 是 (index, items.size)，不包含 targetIndex —— 所以 onDragEnd 里
    // 直接读 targetIndex 拿到的是拖动开始前的旧值（那时 draggingIndex = -1，
    // 于是 targetIndex = -1），`target >= 0` 永远为假，卡片就总是弹回原位。
    // rememberUpdatedState 让 lambda 每次读到的都是最新值。
    val latestTarget by rememberUpdatedState(targetIndex)

    // --- 拖到边缘时自动滚动 ---------------------------------------------------
    //
    // 长列表里要把最后一张拖到第一张，手指只能在屏幕上走有限的距离；
    // 到边缘时页面自己要动，否则用户得先放下、手动滚、再接着拖。
    //
    // 判定用的是**被拖项当前的窗口纵坐标**，而不是手指坐标：手指在屏幕外时
    // 就没有坐标了，而项的位置是我们能算的。
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val edgeTopPx = windowHeightPx * 0.22f
    val edgeBottomPx = windowHeightPx * 0.72f
    var dragStartWindowY by remember { mutableFloatStateOf(0f) }
    /** 每项的窗口纵坐标，按 key 记。按下那一刻取一次，作为自动滚动的基准。 */
    val itemTops = remember { mutableStateMapOf<Any, Float>() }
    var dragStartScroll by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // --- 松手落位 ------------------------------------------------------------
    //
    // 松手时卡片不一定正好在空位上（手指停在哪是任意的），所以要有**一段动画
    // 把它从松手的位置送到空位**。起点取"手指松开时卡片的顶边"，终点取空位的顶边，
    // 中间走一条缓出曲线 —— 看起来就是卡片自己滑回落点。
    val settleOffset = remember { Animatable(0f) }
    var settlingKey by remember { mutableStateOf<Any?>(null) }
    var settleFromPx by remember { mutableFloatStateOf(0f) }
    var settleToPx by remember { mutableFloatStateOf(0f) }
    // 落位用缓出曲线（立方贝塞尔）：起手快、收尾慢，看着像卡片自己滑进空位。
    // 时长跟用户选的动画速度走，不写死。
    val settleSpec = LocalMotion.current.baseSpec<Float>()

    LaunchedEffect(settlingKey) {
        if (settlingKey == null) return@LaunchedEffect
        settleOffset.snapTo(settleFromPx)
        settleOffset.animateTo(settleToPx, settleSpec)
        // 动画结束才清 key：中途清掉的话这一项会瞬间跳回落点，
        // 动画就白做了。
        settlingKey = null
    }

    LaunchedEffect(draggingIndex) {
        val state = scrollState ?: return@LaunchedEffect
        if (draggingIndex < 0) return@LaunchedEffect
        // 每帧挪一点。step 与"离边缘多近"成比例：越靠边越快，
        // 否则贴到最边上反而不动。
        while (true) {
            val y = dragStartWindowY + dragOffsetPx - (state.value - dragStartScroll)
            val step = when {
                y < edgeTopPx -> (y - edgeTopPx) / 6f
                y > edgeBottomPx -> (y - edgeBottomPx) / 6f
                else -> 0f
            }
            if (step != 0f) {
                val before = state.value
                // **正号。** scrollBy 的正向是"内容往上走"（露出下面的内容），
                // 而 step 在"项低于下边缘"时为正 —— 那正是要往下看。
                // 写成 -step 会让方向整个反掉。
                state.scrollBy(step)
                // 页面滚了，手指相对内容的位置也要跟着挪，
                // 否则被拖的卡片会"粘"在屏幕上不跟内容走。
                if (state.value != before) dragOffsetPx += (state.value - before)
            }
            kotlinx.coroutines.delay(16)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.forEachIndexed { index, item ->
            val isDragging = index == draggingIndex
            val itemKey = key(item)

            // 让位：被拖的卡片会占掉落点那一格，中间的卡片整体让开。
            //
            // 位移量统一取**被拖卡片自己的高度加间距**，而不是每个让位卡片
            // 各自的高度。两点原因：
            //  1. 腾出来的空位正好等于被拖卡片 —— 空位是要给它的，它多高空位就多大。
            //     用各自高度的话，被拖的是矮卡片而挡路的是高卡片，就会空出一大块。
            //  2. 统一位移量 = 整段刚性平移，让位的卡片之间的间距原样保留。
            //     各移各的会让相邻两张挤在一起或拉开，看着像散架。
            val dragFootPx = if (draggingIndex >= 0) rowHeightPx(draggingIndex) else 0f
            val shiftTargetPx = when {
                targetIndex < 0 || isDragging -> 0f
                targetIndex > draggingIndex && index in (draggingIndex + 1)..targetIndex -> -dragFootPx
                targetIndex < draggingIndex && index in targetIndex..(draggingIndex - 1) -> dragFootPx
                else -> 0f
            }
            // **拖动中才做位移动画，松手后直接归零。**
            //
            // 松手那一帧列表已经重排，每项的**布局位置**本身就变了；此时若再让
            // 位移量慢慢回零，卡片会先瞬移到新布局位置、再滑一段。让位归零要瞬时，
            // 只有被拖那一张走落位动画（见 settleOffset）。
            val animatedShift by animateFloatAsState(
                targetValue = shiftTargetPx,
                animationSpec = if (draggingIndex >= 0) {
                    settleSpec
                } else {
                    snap()
                },
                label = "reorderShift",
            )

            val yOffsetPx = when {
                isDragging -> dragOffsetPx
                itemKey == settlingKey -> settleOffset.value
                else -> animatedShift
            }

            Box(
                modifier = Modifier
                    .zIndex(if (isDragging) 2f else if (itemKey == settlingKey) 1f else 0f)
                    .offset { IntOffset(0, yOffsetPx.roundToInt()) }
                    // 落位过程中留一点高度：卡片是"浮着落下去"的，
                    // 阴影一落位就消失会让它看着像陷进列表里。
                    // shape 必须传：否则阴影是方角，圆角卡片后面会戳出四个尖角。
                    .shadow(
                        elevation = when {
                            isDragging -> 10.dp
                            itemKey == settlingKey -> 4.dp
                            else -> 0.dp
                        },
                        shape = itemShape,
                        clip = false,
                    )
                    .onSizeChanged { heights[itemKey] = it.height }
                    .onGloballyPositioned { coords ->
                        itemTops[itemKey] = coords.positionInWindow().y
                    },
            ) {
                itemContent(
                    index,
                    item,
                    isDragging,
                    Modifier.pointerInput(index, items.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffsetPx = 0f
                                dragStartWindowY = itemTops[itemKey] ?: 0f
                                dragStartScroll = scrollState?.value ?: 0
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                dragOffsetPx = 0f
                            },
                            onDragEnd = {
                                // 用与让位同一个 targetIndex：两处各算一次必然会出现
                                // "卡片让到了 A 位置、结果提交去了 B 位置"。
                                val target = latestTarget
                                // 落位动画的起点：松手那一刻卡片的顶边。
                                val releasedTopPx = slotTopPx(index) + dragOffsetPx
                                // 终点：空位的顶边。往下拖时，让位的那几张整体上移了
                                // 一个"被拖卡片的脚印"，所以落点比目标格自身还要再往上
                                // 让掉脚印减去目标格高度的那部分。
                                val landingTopPx = if (target > index) {
                                    slotTopPx(target) + rowHeightPx(target) - dragFootPx
                                } else {
                                    slotTopPx(target)
                                }
                                draggingIndex = -1
                                dragOffsetPx = 0f
                                if (target >= 0 && target != index) {
                                    settleFromPx = releasedTopPx - landingTopPx
                                    settleToPx = 0f
                                    settlingKey = itemKey
                                    onMove(index, target)
                                }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffsetPx += amount.y
                            },
                        )
                    },
                )
            }
        }
    }
}

/** Padding helper so callers can align the drag handle with card content. */
val DragHandlePadding = Modifier.padding(end = 4.dp)
