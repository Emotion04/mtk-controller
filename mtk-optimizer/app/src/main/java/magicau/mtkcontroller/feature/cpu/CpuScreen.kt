package magicau.mtkcontroller.feature.cpu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.domain.model.CpuControlStyle
import kotlin.math.roundToInt

@Composable
fun CpuScreen(
    state: CpuUiState,
    onMinChange: (Int, Int) -> Unit,
    onMaxChange: (Int, Int) -> Unit,
    onGovernorChange: (Int, String) -> Unit,
    onLock: (Int, Int) -> Unit,
    onRange: (Int, Int, Int) -> Unit,
    onApply: () -> Unit,
    onRelease: () -> Unit,
    onSaveProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(state) }

        itemsIndexed(state.edits) { index, edit ->
            ClusterCard(
                edit = edit,
                style = state.controlStyle,
                enabled = !state.busy,
                onMinChange = { onMinChange(index, it) },
                onMaxChange = { onMaxChange(index, it) },
                onGovernorChange = { onGovernorChange(index, it) },
                onLock = { onLock(index, it) },
                onRange = { lo, hi -> onRange(index, lo, hi) },
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onApply,
                        enabled = !state.busy && state.powerHalAvailable,
                        modifier = Modifier.weight(1f),
                    ) { Text("应用") }

                    OutlinedButton(
                        onClick = onRelease,
                        enabled = !state.busy && state.powerHalAvailable,
                        modifier = Modifier.weight(1f),
                    ) { Text("释放") }
                }

                OutlinedButton(
                    onClick = { showSaveDialog = true },
                    enabled = !state.busy && state.edits.any { it.cluster.availableFreqs.isNotEmpty() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存为方案") }

                if (!state.powerHalAvailable) {
                    Text(
                        text = "PowerHAL 不可用:请先授权 Shizuku。非 MTK 设备无法调频。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                state.message?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClusterCard(
    edit: ClusterEdit,
    style: CpuControlStyle,
    enabled: Boolean,
    onMinChange: (Int) -> Unit,
    onMaxChange: (Int) -> Unit,
    onGovernorChange: (String) -> Unit,
    onLock: (Int) -> Unit,
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

            when (style) {
                CpuControlStyle.RANGE_SLIDER -> RangeSlider(
                    value = edit.minIndex.toFloat()..edit.maxIndex.toFloat(),
                    onValueChange = { range ->
                        onMinChange(range.start.toInt())
                        onMaxChange(range.endInclusive.toInt())
                    },
                    valueRange = 0f..lastIndex.toFloat(),
                    steps = (lastIndex - 1).coerceAtLeast(0),
                    enabled = enabled,
                )

                CpuControlStyle.SINGLE_SLIDER -> Slider(
                    value = edit.maxIndex.toFloat(),
                    onValueChange = { onLock(it.roundToInt()) },
                    valueRange = 0f..lastIndex.toFloat(),
                    steps = (lastIndex - 1).coerceAtLeast(0),
                    enabled = enabled,
                )

                CpuControlStyle.SEGMENT_BAR -> SegmentBar(
                    count = freqs.size,
                    minIndex = edit.minIndex,
                    maxIndex = edit.maxIndex,
                    enabled = enabled,
                    onRange = onRange,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("下限 ${edit.minFreqKhz / 1000} MHz", style = MaterialTheme.typography.bodyMedium)
                Text("上限 ${edit.maxFreqKhz / 1000} MHz", style = MaterialTheme.typography.bodyMedium)
            }

            GovernorPicker(
                governors = edit.cluster.governors,
                selected = edit.governor,
                enabled = enabled && edit.cluster.governorWritable,
                hint = if (edit.cluster.governors.isEmpty()) {
                    "该设备未报告可用调速器"
                } else if (!edit.cluster.governorWritable) {
                    "调速器需要 root 才能修改"
                } else {
                    null
                },
                onSelect = onGovernorChange,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GovernorPicker(
    governors: List<String>,
    selected: String?,
    enabled: Boolean,
    hint: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it },
        ) {
            OutlinedTextField(
                value = selected ?: "—",
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text("调速器") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                governors.forEach { governor ->
                    DropdownMenuItem(
                        text = { Text(governor) },
                        onClick = {
                            onSelect(governor)
                            expanded = false
                        },
                    )
                }
            }
        }
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
 * A progress-bar style range picker: one segment per available frequency.
 *
 * First tap sets the anchor, the second extends the range to it — the same
 * two-tap idiom used by range pickers elsewhere, which avoids tiny drag
 * targets when a cluster exposes twenty-odd steps.
 */
@Composable
private fun SegmentBar(
    count: Int,
    minIndex: Int,
    maxIndex: Int,
    enabled: Boolean,
    onRange: (Int, Int) -> Unit,
) {
    var anchor by remember { mutableStateOf<Int?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth().height(26.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(count) { i ->
            val inRange = i in minIndex..maxIndex
            val isAnchor = anchor == i
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            isAnchor -> MaterialTheme.colorScheme.tertiary
                            inRange -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .then(
                        if (enabled) Modifier.clickable {
                            val start = anchor
                            if (start == null) {
                                onRange(i, i)
                                anchor = i
                            } else {
                                onRange(minOf(start, i), maxOf(start, i))
                                anchor = null
                            }
                        } else Modifier
                    ),
            )
        }
    }
}
