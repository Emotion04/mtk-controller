package magicau.mtkcontroller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import magicau.mtkcontroller.domain.model.NavBarStyle
import magicau.mtkcontroller.ui.navigation.Destination
import magicau.mtkcontroller.ui.navigation.TabSwipeMode

/**
 * 算作"移动了一次"的最小像素。低于它就当手指没动。
 *
 * 取 38px 而不是更小的值：手指按住不动也有几像素的自然抖动，抖动会不断
 * 刷新"最后一次移动"的时刻，让停留永远计时不满。阈值要高于抖动、低于
 * 一次有意的滑动，38px 大约是四五个字宽，随手的滑动轻松超过。
 */
private const val MOVE_EPSILON_PX = 38f

/**
 * 悬浮胶囊底栏。
 *
 * 选中的那一项由一个实心胶囊标出，胶囊可以横向拖 —— 松手后由调用方决定吸附到
 * 哪一页（见 [onSettle]），所以底栏同时是几个顶层标签之间的滑块。
 *
 * 整条底栏共用同一个胶囊外形：背景、阴影和会动的指示器都是同一个圆角形状，
 * 底栏底下不会露出直角边。
 */
@Composable
fun FloatingCapsuleNavBar(
    destinations: List<Destination>,
    selectedRoute: String,
    onSelect: (Destination) -> Unit,
    /**
     * 当前页位置，0.0 到 `size - 1`。**由外部的 Pager 决定，是唯一真源。**
     *
     * 之前这里自己存一份 `positionPx`，再和 Pager 同步 —— 两份状态靠回调互相追，
     * 拖动时必然打架（跳到一半、卡住、吸到最远）。现在只读不存：胶囊位置由它算出来，
     * 拖动则改 Pager，Pager 变了这里跟着变，单向流动就没有分歧。
     *
     * **是 lambda 不是值。** 这个数每个动画帧都会变。如果调用方在*自己的*组合里
     * 求值再传进来（`pageOffset = pager.currentPage + pager.currentPageOffsetFraction`），
     * 那么读 snapshot 状态的位置就在调用方的作用域 —— 结果是一帧失效**整个调用方**，
     * 连带它内部的 NavHost 和所有页面一起重组。实测表现就是切标签卡一下、滑动掉帧。
     *
     * 传 lambda 则读取发生在本组件内部，失效范围缩小到这一个 composable；
     * 胶囊再单独拆一层，就只剩那一个 Box 每帧重组。
     */
    pageOffset: () -> Float = { 0f },
    /** 开始拖动。调用方据此记下起点页 —— HOLD 模式下弹回要用它。 */
    onDragStart: (() -> Unit)? = null,
    /** 拖动：报告**页面单位**的变化量（0.1 表示往前 0.1 页）。 */
    onDragBy: ((Float) -> Unit)? = null,
    /**
     * 松手：报告手指最后停在**哪一页附近**，以及**是否停够了 [TabSwipeMode.HOLD_MS]**。
     *
     * 第一个参数只是"离哪一页近"，不是最终决定 —— 「停留后切换」模式下没停够要
     * 弹回拖动起点，那是调用方的策略。底栏只负责测时间和报告位置。
     */
    onSettle: ((nearestPage: Int, heldStill: Boolean) -> Unit)? = null,
    style: NavBarStyle = NavBarStyle.COLORFUL,
    customColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    if (destinations.isEmpty()) return

    var barWidthPx by remember { mutableIntStateOf(0) }
    // 最后一次移动的时刻，用来判断"停住多久"。0 表示还没拖过。
    var lastMoveMs by remember { mutableLongStateOf(0L) }

    val pill = RoundedCornerShape(percent = 50)
    val themeAccent = MaterialTheme.colorScheme.primary
    fun accentFor(destination: Destination): Color = when (style) {
        NavBarStyle.COLORFUL -> destination.accent
        NavBarStyle.FOLLOW_THEME -> themeAccent
        NavBarStyle.CUSTOM -> customColor
    }
    val selectedIndex = destinations.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val itemWidthPx = if (barWidthPx > 0) barWidthPx.toFloat() / destinations.size else 0f

    // 胶囊位置直接由 pageOffset 算出来，没有本地状态、没有自己的动画 ——
    // 动画由 Pager 那边统一驱动，两者天然同帧。
    //
    // 读它只发生在 [SelectionCapsule] 里：拖动期间每帧变化的是这一个 Box，
    // 不是整条底栏（图标和文字不必跟着重组）。

    Box(
        modifier = modifier
            .shadow(elevation = 12.dp, shape = pill, clip = false)
            .clip(pill)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .height(50.dp)
                .onSizeChanged { barWidthPx = it.width }
                .pointerInput(itemWidthPx, destinations.size) {
                    if (itemWidthPx <= 0f) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            lastMoveMs = System.currentTimeMillis()
                            onDragStart?.invoke()
                        },
                        onDragCancel = {
                            onSettle?.invoke(pageOffset().roundToInt(), false)
                        },
                        onDragEnd = {
                            // 吸附由 Pager 完成：它知道自己的滚动状态，比按胶囊位置
                            // 反推更准（拖动中胶囊是跟随的，不是真源）。
                            val heldStill = System.currentTimeMillis() - lastMoveMs >=
                                TabSwipeMode.HOLD_MS
                            onSettle?.invoke(
                                pageOffset().roundToInt().coerceIn(0, destinations.lastIndex),
                                heldStill,
                            )
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            if (itemWidthPx > 0f) {
                                // 只有**看得出移动**才算移动。手指静止时仍有触摸抖动，
                                // 每次都刷新计时的话，"停住半秒"永远达不到 ——
                                // 表现就是停留模式失效、总是弹回。
                                //
                                // 阈值按"一次有意的移动"取，不是按抖动取：太小则
                                // 轻微滑一下就被算成移动，用户想停留也停不住。
                                if (kotlin.math.abs(dragAmount) > MOVE_EPSILON_PX) {
                                    lastMoveMs = System.currentTimeMillis()
                                }
                                onDragBy?.invoke(dragAmount / itemWidthPx)
                            }
                        },
                    )
                },
        ) {            // Moving selection capsule. Same pill shape as the bar itself.
            if (itemWidthPx > 0f) {
                SelectionCapsule(
                    offsetProvider = pageOffset,
                    widthPx = itemWidthPx,
                    color = accentFor(destinations[selectedIndex]),
                    pill = pill,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                destinations.forEach { destination ->
                    val active = destination.route == selectedRoute
                    // Selected icon sits on the capsule and flips to onPrimary;
                    // unselected icons take the style's accent.
                    val tint = if (active) MaterialTheme.colorScheme.onPrimary else accentFor(destination)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .clip(pill)
                            .selectable(
                                selected = active,
                                role = Role.Tab,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(destination) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = stringResource(destination.labelRes),
                                tint = tint,
                            )
                            Text(
                                text = stringResource(destination.labelRes),
                                style = MaterialTheme.typography.labelSmall,
                                color = tint,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 会动的那个选中胶囊。
 *
 * **单独拆成一个 composable 是性能上的必要，不是为了整齐**：位置每个动画帧都变，
 * 而底栏的图标和文字不该跟着重组。读取 [offsetProvider] 的动作发生在这里，
 * 失效范围就只剩这一个 Box。
 *
 * 用 `Modifier.offset { }`（lambda 版）而不是 `offset(x)`：前者把像素值的读取
 * 推迟到布局阶段，位置变化只触发重新布局，不触发重组。
 */
@Composable
private fun SelectionCapsule(
    offsetProvider: () -> Float,
    widthPx: Float,
    color: Color,
    pill: RoundedCornerShape,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset { IntOffset((offsetProvider() * widthPx).roundToInt(), 0) }
            .width(with(density) { widthPx.toDp() })
            .height(50.dp)
            .padding(horizontal = 4.dp)
            .clip(pill)
            .background(color),
    )
}
