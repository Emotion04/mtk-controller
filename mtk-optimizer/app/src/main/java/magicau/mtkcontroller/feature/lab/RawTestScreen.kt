package magicau.mtkcontroller.feature.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.data.lab.LabController

/**
 * Send CPU frequency ids one at a time and watch the kernel answer.
 *
 * This screen exists because the app spent several rounds *explaining* this
 * device's behaviour and every explanation was wrong. Sending a single id at a
 * time and reading the result back replaces the story with a measurement — the
 * first press tells you whether that id does anything at all here, and the
 * combination tells you whether floor and ceiling are independent.
 *
 * Restricted to the four CPU frequency ids. A free-form id field would let a
 * typo reach the resources that control radio power and page-cache dropping;
 * those are listed in `LabCatalog.blockedIds` for the same reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RawTestScreen(
    state: RawTestUiState,
    onPolicy: (String) -> Unit,
    onRow: (Int, Boolean?, Int?) -> Unit,
    onHold: (Boolean) -> Unit,
    onSend: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Column(
            modifier = modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "逐个 ID 单独下发,看内核怎么回应。这是唯一能确定「哪个 ID 在这台机器上真的生效」的办法 —— " +
                    "之前的解释都是推测,而且都错了。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("簇", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.clusters.forEach { cluster ->
                            FilterChip(
                                selected = state.policy == cluster.policy,
                                onClick = { onPolicy(cluster.policy) },
                                label = { Text("${cluster.policy} · ${cluster.cpus.size}核") },
                            )
                        }
                    }
                    state.cluster?.let { cluster ->
                        Text(
                            "index=${cluster.index} · 可用 ${cluster.availableFreqs.size} 档 · " +
                                "范围 ${cluster.minFreqKhz / 1000}-${cluster.maxFreqKhz / 1000}MHz",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        state.nodePreview,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("要发送的 ID", style = MaterialTheme.typography.titleMedium)
                    state.rows.forEachIndexed { index, row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Switch(
                                checked = row.enabled,
                                onCheckedChange = { onRow(index, it, null) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    state.cluster?.let { row.idFor(it.index) } ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedTextField(
                                value = row.value.toString(),
                                onValueChange = { text ->
                                    text.filter { it.isDigit() }.toIntOrNull()?.let { onRow(index, null, it) }
                                },
                                singleLine = true,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Text(
                        "单位 kHz。先只勾一个,单独测出每个 ID 的作用,再组合。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("长期保持", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (state.hold) "永久有效,必须手动撤销"
                            else "20 秒后自动失效",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.hold, onCheckedChange = onHold)
                }

                Button(
                    onClick = onSend,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.busy) "发送中…" else "发送并读回") }

                OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                    Text("释放(撤销本次下发)")
                }

                state.verdict?.let { verdict ->
                    VerdictLine(verdict)
                }
            }
        }
    }
}

@Composable
private fun VerdictLine(verdict: LabController.Verdict) {
    val state = verdict.stateEnum()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = {}, label = { Text(state.label) })
            }
            Text(verdict.detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
