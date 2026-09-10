package magicau.mtkcontroller.feature.gpu

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.feature.cpu.EmptyState

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
            title = "未检测到 GPU 调频接口",
            body = "已尝试 /proc/gpufreqv2、/proc/gpufreq 与 /sys/class/devfreq/mtk-mali。\n" +
                "模拟器上没有这些节点;真机(MTK)上应能看到其中一条。",
            modifier = modifier,
        )
        return
    }

    val freqs = state.freqs

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GPU 调频", style = MaterialTheme.typography.titleLarge)
        Text(
            state.info.channel.displayName,
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
                    Text("当前频率", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        state.info.currentFreqKhz?.let { "${it / 1000} MHz" } ?: "—",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (freqs.isEmpty()) {
                    Text(
                        "该接口没有报告可用频率,只能使用「释放」。",
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
                        Text("下限 ${(state.minKhz ?: 0) / 1000} MHz", style = MaterialTheme.typography.bodyMedium)
                        Text("上限 ${(state.maxKhz ?: 0) / 1000} MHz", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApply, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                Text("应用")
            }
            OutlinedButton(onClick = onRelease, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                Text("释放")
            }
        }

        Text(
            "MTK 的 GPU 调频由固件管理,厂商电源服务可能覆盖设置,App 会周期性重申。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
