package magicau.mtkcontroller.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * Vertical list whose items can be reordered by dragging a handle.
 *
 * A non-lazy Column is used on purpose: the home dashboard holds a handful of
 * cards with wildly different heights, and dragging between them is far simpler
 * (and more predictable) than the windowing arithmetic a LazyColumn would need.
 *
 * The target slot is derived from the drag distance divided by the average item
 * height, then the move is applied once on release.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 12.dp,
    itemContent: @Composable (index: Int, item: T, isDragging: Boolean, dragHandle: Modifier) -> Unit,
) {
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Int, Int>() }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.forEachIndexed { index, item ->
            val isDragging = index == draggingIndex

            Box(
                modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .offset {
                        IntOffset(0, if (isDragging) dragOffsetPx.roundToInt() else 0)
                    }
                    .shadow(if (isDragging) 10.dp else 0.dp)
                    .onSizeChanged { heights[index] = it.height },
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
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                dragOffsetPx = 0f
                            },
                            onDragEnd = {
                                val average = heights.values.average().takeIf { it > 0.0 } ?: 1.0
                                val steps = (dragOffsetPx / average).roundToInt()
                                val target = (index + steps).coerceIn(0, items.lastIndex)
                                draggingIndex = -1
                                dragOffsetPx = 0f
                                if (target != index) onMove(index, target)
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
