package magicau.mtkcontroller.feature.lab

import magicau.mtkcontroller.ui.components.SUB_SCREEN_EXTRA_PADDING
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = SUB_SCREEN_EXTRA_PADDING),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.lab_raw_intro),
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
                    Text(stringResource(R.string.lab_raw_cluster), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.clusters.forEach { cluster ->
                            FilterChip(
                                selected = state.policy == cluster.policy,
                                onClick = { onPolicy(cluster.policy) },
                                label = { Text(stringResource(R.string.lab_raw_cluster_chip, cluster.policy, cluster.cpus.size)) },
                            )
                        }
                    }
                    state.cluster?.let { cluster ->
                        // ui-check:ignore 实验室页就该显示集群与索引
                        Text(
                            stringResource(
                                R.string.lab_raw_cluster_info,
                                cluster.index,
                                cluster.availableFreqs.size,
                                cluster.minFreqKhz / 1000,
                                cluster.maxFreqKhz / 1000,
                            ),
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
                    Text(stringResource(R.string.lab_raw_send_ids), style = MaterialTheme.typography.titleMedium)
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
                                Text(stringResource(row.labelRes), style = MaterialTheme.typography.bodyMedium)
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
                        stringResource(R.string.lab_raw_units_hint),
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
                        Text(stringResource(R.string.lab_send_mode), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(if (state.hold) R.string.lab_raw_hold_on else R.string.lab_raw_hold_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilterChip(selected = !state.hold, onClick = { onHold(false) },
                        label = { Text(stringResource(R.string.lab_send_temporary)) })
                    FilterChip(selected = state.hold, onClick = { onHold(true) },
                        label = { Text(stringResource(R.string.lab_send_hold)) })
                }

                Button(
                    onClick = onSend,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (state.busy) R.string.lab_raw_sending else R.string.lab_raw_send)) }

                FilledTonalButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.lab_raw_release))
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
                AssistChip(onClick = {}, label = { Text(stringResource(state.labelRes)) })
            }
            Text(verdict.detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
