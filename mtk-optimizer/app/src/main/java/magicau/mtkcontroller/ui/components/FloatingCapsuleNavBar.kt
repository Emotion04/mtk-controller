package magicau.mtkcontroller.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import magicau.mtkcontroller.domain.model.NavBarStyle
import magicau.mtkcontroller.ui.navigation.Destination

/**
 * Floating pill-shaped bottom bar.
 *
 * The selected item is marked by a filled capsule that can be dragged
 * horizontally; releasing it snaps to the nearest tab and selects it, so the
 * bar doubles as a slider between destinations.
 *
 * The whole bar shares one pill outline — background, shadow and the moving
 * indicator all use the same rounded shape, so no rectangular edges show
 * through under the bar.
 */
@Composable
fun FloatingCapsuleNavBar(
    destinations: List<Destination>,
    selectedRoute: String,
    onSelect: (Destination) -> Unit,
    style: NavBarStyle = NavBarStyle.COLORFUL,
    customColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    if (destinations.isEmpty()) return

    var barWidthPx by remember { mutableIntStateOf(0) }
    var positionPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    val pill = RoundedCornerShape(percent = 50)
    val themeAccent = MaterialTheme.colorScheme.primary
    fun accentFor(destination: Destination): Color = when (style) {
        NavBarStyle.COLORFUL -> destination.accent
        NavBarStyle.FOLLOW_THEME -> themeAccent
        NavBarStyle.CUSTOM -> customColor
    }
    val selectedIndex = destinations.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val itemWidthPx = if (barWidthPx > 0) barWidthPx.toFloat() / destinations.size else 0f
    val maxOffsetPx = (destinations.size - 1) * itemWidthPx

    LaunchedEffect(selectedIndex, itemWidthPx) {
        if (!dragging && itemWidthPx > 0f) {
            animate(
                initialValue = positionPx,
                targetValue = selectedIndex * itemWidthPx,
                animationSpec = tween(durationMillis = 260),
            ) { value, _ -> positionPx = value }
        }
    }

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
                        onDragStart = { dragging = true },
                        onDragCancel = { dragging = false },
                        onDragEnd = {
                            val target = ((positionPx + itemWidthPx / 2f) / itemWidthPx)
                                .roundToInt()
                                .coerceIn(0, destinations.lastIndex)
                            dragging = false
                            if (target != selectedIndex) onSelect(destinations[target])
                            else positionPx = target * itemWidthPx
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            positionPx = (positionPx + dragAmount).coerceIn(0f, maxOffsetPx)
                        },
                    )
                },
        ) {
            // Moving selection capsule. Same pill shape as the bar itself.
            if (itemWidthPx > 0f) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(positionPx.roundToInt(), 0) }
                        .width(with(LocalDensity.current) { itemWidthPx.toDp() })
                        .height(50.dp)
                        .padding(horizontal = 4.dp)
                        .clip(pill)
                        .background(accentFor(destinations[selectedIndex])),
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
                                contentDescription = destination.label,
                                tint = tint,
                            )
                            Text(
                                text = destination.label,
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
