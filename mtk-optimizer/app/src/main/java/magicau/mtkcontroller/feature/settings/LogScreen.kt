package magicau.mtkcontroller.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.data.log.LogLevel

/**
 * The in-memory log, newest at the bottom.
 *
 * Read-only, but every line is selectable — the report these lines end up in is
 * the main way a device we cannot test gets characterised, so copying a single
 * telling line out has to be easy. The two buttons cover the whole-report case.
 */
@Composable
fun LogScreen(
    state: SettingsUiState,
    onClear: () -> Unit,
    onCopyReport: () -> Unit,
    onExportReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = state.logEntries
    val listState = rememberLazyListState()

    // Follow the tail as new lines arrive.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "级别:${state.logLevel.label} · 最多保留 500 条 · 长按可选中文字",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onCopyReport, modifier = Modifier.weight(1f)) { Text("复制报告") }
            OutlinedButton(onClick = onExportReport, modifier = Modifier.weight(1f)) { Text("导出报告") }
            OutlinedButton(onClick = onClear) { Text("清空") }
        }

        state.backupMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            )
        }

        if (entries.isEmpty()) {
            Text(
                "还没有日志。切换功能或重新探测后这里会有内容。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                SelectionContainer {
                    Text(
                        text = entry.format(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = entry.level.color(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LogLevel.color(): Color = when (this) {
    LogLevel.VERBOSE -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
}
