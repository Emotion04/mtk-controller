package magicau.mtkcontroller.feature.thermal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.ui.components.FailureNote

/**
 * 按应用温控。
 *
 * ⚠️ 别在这里加"每个索引是什么"的静态说明 —— 那会随 ROM 变。
 * 编号的实际效果看温控策略索引卡片上的实时摘要（现读现解）。
 */
@Composable
fun AppThermalScreen(
    state: AppThermalUiState,
    onSetRunning: (Boolean) -> Unit,
    onShowAdd: () -> Unit,
    onHideAdd: () -> Unit,
    onDraftPkg: (String) -> Unit,
    onDraftIndex: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Column(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LinearProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.app_thermal_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (state.running) stringResource(R.string.app_thermal_running)
                                else stringResource(R.string.app_thermal_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = state.running, onCheckedChange = onSetRunning)
                    }
                    Text(
                        stringResource(R.string.app_thermal_foreground, state.foreground),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        stringResource(R.string.app_thermal_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.app_thermal_rules_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = onShowAdd) { Text(stringResource(R.string.app_thermal_add)) }
            }
        }

        if (state.rules.isEmpty()) {
            item { Text(stringResource(R.string.app_thermal_empty), style = MaterialTheme.typography.bodySmall) }
        }

        items(state.rules, key = { it.pkg }) { rule ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.pkg, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${stringResource(R.string.app_thermal_index)} ${"%02d".format(rule.policyIndex)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    TextButton(onClick = { onRemove(rule.pkg) }) {
                        Text(stringResource(R.string.app_thermal_remove))
                    }
                }
            }
        }

        state.message?.let { msg ->
            item {
                FailureNote(failure = msg)
            }
        }
    }

    if (state.showAddDialog) {
        AlertDialog(
            onDismissRequest = onHideAdd,
            title = { Text(stringResource(R.string.app_thermal_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.draft.pkg,
                        onValueChange = onDraftPkg,
                        label = { Text(stringResource(R.string.app_thermal_pick_app)) },
                        singleLine = true,
                    )
                    Text(stringResource(R.string.app_thermal_pick_index), style = MaterialTheme.typography.labelMedium)
                    // 只列设备上真有的编号；编号含义看 CPU 页高级设置里的温控档位说明。
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.availableIndices.take(6).forEach { index ->
                            FilterChip(
                                selected = state.draft.policyIndex == index,
                                onClick = { onDraftIndex(index) },
                                label = { Text("%02d".format(index)) },
                            )
                        }
                    }
                    if (state.availableIndices.size > 6) {
                        Text(
                            // 指路要指到**真在那儿的东西**。曾经写"见温控策略索引卡片"，
                            // 而那张卡片早搬到了 CPU → 高级设置，这句话于是指向不存在的位置。
                            stringResource(R.string.app_thermal_more_indices, state.availableIndices.size - 6),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            confirmButton = { Button(onClick = onAdd) { Text(stringResource(R.string.app_thermal_add_confirm)) } },
            dismissButton = { TextButton(onClick = onHideAdd) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
