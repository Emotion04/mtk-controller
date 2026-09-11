package magicau.mtkcontroller.feature.gpu

import magicau.mtkcontroller.ui.components.FailureNote
import magicau.mtkcontroller.ui.components.LocalNavBarClearance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.feature.cpu.EmptyState
import magicau.mtkcontroller.ui.displayNameRes

@Composable
fun GpuScreen(
    state: GpuUiState,
    onMinChange: (Int) -> Unit,
    onMaxChange: (Int) -> Unit,
    onApply: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        return
    }

    if (!state.info.controllable) {
        EmptyState(
            title = stringResource(R.string.gpu_empty_title),
            body = stringResource(R.string.gpu_empty_body),
            modifier = modifier,
        )
        return
    }

    val freqs = state.freqs

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = LocalNavBarClearance.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        magicau.mtkcontroller.ui.components.InfoHeading(
            stringResource(R.string.gpu_page_title),
            stringResource(R.string.gpu_page_info),
        )
        Text(
            state.info.channel.displayNameRes.let { stringResource(it) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.gpu_current_freq), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        state.info.currentFreqKhz?.let { stringResource(R.string.mhz_value, it / 1000) }
                            ?: stringResource(R.string.value_dash),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (freqs.isEmpty()) {
                    Text(
                        stringResource(R.string.gpu_no_freqs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    RangeSlider(
                        value = state.minIndex.toFloat()..state.maxIndex.toFloat(),
                        onValueChange = {
                            onMinChange(it.start.toInt())
                            onMaxChange(it.endInclusive.toInt())
                        },
                        valueRange = 0f..freqs.lastIndex.toFloat(),
                        steps = (freqs.lastIndex - 1).coerceAtLeast(0),
                        enabled = !state.busy,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.gpu_limit_floor, (state.minKhz ?: 0) / 1000),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.gpu_limit_ceiling, (state.maxKhz ?: 0) / 1000),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApply, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_apply))
            }
            FilledTonalButton(onClick = onRelease, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_release))
            }
        }



        state.message?.let { FailureNote(failure = it) }
    }
}
