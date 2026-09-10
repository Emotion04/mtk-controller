package magicau.mtkcontroller.feature.cpu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

@Composable
fun CpuScreen(
    state: CpuUiState,
    onGovernorChange: (Int, String) -> Unit,
    onRange: (Int, Int, Int) -> Unit,
    onApply: () -> Unit,
    onRelease: () -> Unit,
    onSaveProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
    onResume: () -> Unit = {},
) {
    // Re-check PowerHAL whenever the screen comes back to the foreground: the
    // user may have granted Shizuku while the CPU tab was in the background.
    LifecycleResumeEffect(Unit) {
        onResume()
        onPauseOrDispose { }
    }

    if (state.loading) {
        Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        return
    }

    if (state.edits.isEmpty()) {
        EmptyState(
            title = "未检测到 CPU 簇",
            body = "设备上没有找到 cpufreq 策略目录。模拟器通常没有,真机上应能看到 policy0/policy4/…",
            modifier = modifier,
        )
        return
    }

    var showSaveDialog by remember { mutableStateOf(false) }
    // Governor is deliberately collapsed: it is a secondary knob that needs
    // root on most ROMs, so it should not dominate a screen whose job is
    // frequency limits.
    var governorExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(state) }

        item {
            GovernorSection(
                edits = state.edits,
                expanded = governorExpanded,
                enabled = !state.busy,
                onToggle = { governorExpanded = !governorExpanded },
                onGovernorChange = onGovernorChange,
            )
        }

        itemsIndexed(
            items = state.edits,
            key = { _, edit -> edit.cluster.policy },
        ) { index, edit ->
            ClusterCard(
                edit = edit,
                enabled = !state.busy,
                onRange = { lo, hi -> onRange(index, lo, hi) },
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onApply,
                        enabled = !state.busy && state.powerHal.available,
                        modifier = Modifier.weight(1f),
                    ) { Text("应用") }

                    OutlinedButton(
                        onClick = onRelease,
                        enabled = !state.busy && state.powerHal.available,
                        modifier = Modifier.weight(1f),
                    ) { Text("释放") }
                }

                OutlinedButton(
                    onClick = { showSaveDialog = true },
                    enabled = !state.busy && state.edits.any { it.cluster.availableFreqs.isNotEmpty() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存为方案") }

                if (!state.powerHal.available && state.powerHal.checked) {
                    Text(
                        text = state.powerHal.reason ?: "调频当前不可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                state.message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showSaveDialog) {
        SaveProfileDialog(
            onDismiss = { showSaveDialog = false },
            onConfirm = {
                onSaveProfile(it)
                showSaveDialog = false
            },
        )
    }
}

@Composable
private fun Header(state: CpuUiState) {
    val clusters = state.edits.map { it.cluster }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("CPU 调频", style = MaterialTheme.typography.titleLarge)
        Text(
            // The cluster count is shown because it is discovered at runtime:
            // two-, three- and four-cluster SoCs all land here.
            text = "检测到 ${clusters.size} 个簇 · " +
                clusters.joinToString(" / ") { "${it.cpus.size}核" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = state.activeProfileName?.let { "当前方案:$it" } ?: "未套用方案",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Collapsible governor picker covering every cluster.
 *
 * Collapsed by default: it is one row summarising the current governor, and it
 * expands to a radio list. A dropdown per cluster inside an already-scrollable
 * card was awkward to hit and made the screen much longer than it needed to be.
 */
@Composable
private fun GovernorSection(
    edits: List<ClusterEdit>,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onGovernorChange: (Int, String) -> Unit,
) {
    val writable = edits.any { it.cluster.governorWritable }
    val summary = edits.map { it.governor ?: "—" }.distinct().joinToString(" / ")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, onClick = onToggle)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("CPU 调速器", style = MaterialTheme.typography.titleMedium)
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
                    if (!writable) {
                        Text(
                            "当前无法写入调速器节点,选择后可能不会生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                        )
                    }
                    edits.forEachIndexed { index, edit ->
                        Text(
                            edit.cluster.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp),
                        )
                        val governors = edit.cluster.governors.ifEmpty {
                            listOfNotNull(edit.cluster.currentGovernor)
                        }
                        if (governors.isEmpty()) {
                            Text(
                                "该设备未报告可用调速器",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                            )
                        }
                        governors.forEach { governor ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = edit.governor == governor,
                                        role = Role.RadioButton,
                                        enabled = enabled,
                                        onClick = { onGovernorChange(index, governor) },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(
                                    selected = edit.governor == governor,
                                    onClick = null,
                                    enabled = enabled,
                                )
                                Text(governor, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClusterCard(
    edit: ClusterEdit,
    enabled: Boolean,
    onRange: (Int, Int) -> Unit,
) {
    val freqs = edit.cluster.availableFreqs
    val lastIndex = freqs.lastIndex.coerceAtLeast(0)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(edit.cluster.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = edit.cluster.currentFreqKhz?.let { "${it / 1000} MHz" } ?: "—",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (freqs.isEmpty()) {
                Text(
                    text = "该簇没有可用的频率列表,无法调节。" +
                        (edit.cluster.driver?.let { "(驱动:$it)" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            SegmentBar(
                count = freqs.size,
                minIndex = edit.minIndex,
                maxIndex = edit.maxIndex,
                enabled = enabled,
                onRange = onRange,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LimitLabel("min", edit.minFreqKhz)
                LimitLabel("max", edit.maxFreqKhz)
            }
        }
    }
}

/** "min 1234 MHz" — Latin labels, monospaced value so the row does not jitter. */
@Composable
private fun LimitLabel(name: String, freqKhz: Long) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${freqKhz / 1000} MHz",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SaveProfileDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存为方案") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("方案名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { "未命名方案" }) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Segment bar: one segment per available frequency, tap or drag to pick.
 *
 * The single control covers all three intents:
 *  - one tap      → lock the cluster to that frequency
 *  - a second tap → widen to the range between the two taps
 *  - a drag       → select the span the finger crossed
 *
 * The span is always normalised to low..high, so it does not matter which end
 * the gesture started from — the earlier version could hand the high value to
 * the "min" slot when the user tapped right-to-left.
 */
@Composable
private fun SegmentBar(
    count: Int,
    minIndex: Int,
    maxIndex: Int,
    enabled: Boolean,
    onRange: (Int, Int) -> Unit,
) {
    var tapAnchor by remember { mutableStateOf<Int?>(null) }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragTo by remember { mutableStateOf<Int?>(null) }
    var widthPx by remember { mutableStateOf(0) }

    fun indexAt(x: Float): Int {
        if (widthPx <= 0 || count <= 0) return 0
        // Each segment occupies an equal slice including its share of the gaps.
        val slot = (x / widthPx * count).toInt()
        return slot.coerceIn(0, count - 1)
    }

    /** Highlight during a drag follows the finger; otherwise the committed range. */
    val shownFrom = dragFrom ?: minIndex
    val shownTo = dragTo ?: maxIndex

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .onSizeChanged { widthPx = it.width }
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(count, widthPx) {
                        detectTapGestures { offset ->
                            val i = indexAt(offset.x)
                            val anchor = tapAnchor
                            if (anchor == null) {
                                // First tap locks immediately…
                                onRange(i, i)
                                tapAnchor = i
                            } else {
                                // …the second one opens it into a range.
                                onRange(minOf(anchor, i), maxOf(anchor, i))
                                tapAnchor = null
                            }
                        }
                    }
                    .pointerInput(count, widthPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                val i = indexAt(offset.x)
                                dragFrom = i
                                dragTo = i
                                tapAnchor = null
                            },
                            onDragEnd = {
                                val from = dragFrom
                                val to = dragTo
                                if (from != null && to != null) {
                                    onRange(minOf(from, to), maxOf(from, to))
                                }
                                dragFrom = null
                                dragTo = null
                            },
                            onDragCancel = {
                                dragFrom = null
                                dragTo = null
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                dragTo = indexAt(change.position.x)
                            },
                        )
                    }
            ),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(count) { i ->
            val inRange = i in minOf(shownFrom, shownTo)..maxOf(shownFrom, shownTo)
            val isEdge = i == shownFrom || i == shownTo
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            isEdge && shownFrom != shownTo -> MaterialTheme.colorScheme.tertiary
                            inRange -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
            )
        }
    }
}
