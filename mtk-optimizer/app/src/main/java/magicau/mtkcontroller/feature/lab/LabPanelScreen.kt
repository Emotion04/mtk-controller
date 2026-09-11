package magicau.mtkcontroller.feature.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.data.lab.LabProbe
import magicau.mtkcontroller.ui.components.InsetDivider
import magicau.mtkcontroller.ui.theme.adaptiveAccent
import magicau.mtkcontroller.ui.theme.isDarkScheme

private val OkIcon = Color(0xFF2E7D32)
private val MissingIcon = Color(0xFF8A8A8A)

/**
 * The lab's first panel: everything the kernel will tell us, and nothing else.
 *
 * A node that does not exist is shown as such rather than hidden, because on
 * MediaTek the *presence* of a node is the capability answer — ids and node
 * names move between BSP revisions, so "missing" is a real and useful result.
 */
@Composable
fun LabPanelScreen(
    state: LabPanelUiState,
    onRefresh: () -> Unit,
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "内核实际在执行的限制。这个页面不做任何写入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "读到 ${state.readingsFound}/${state.readingsTotal} 个节点",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onRefresh) { Text("刷新") }
                }
            }
        }

        state.sections.forEach { section ->
            item(key = section.title) { SectionCard(section) }
        }

        item {
            Text(
                "节点不存在是正常结果 —— MediaTek 在不同平台会更换 ID 与节点名," +
                    "所以本应用靠节点是否存在来判断功能是否可用,而不是靠一张机型表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(section: LabProbe.Section) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Column(modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 6.dp)) {
                Text(section.title, style = MaterialTheme.typography.titleMedium)
                section.note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            section.readings.forEachIndexed { index, reading ->
                if (index > 0) InsetDivider(startIndent = 16.dp)
                ReadingRow(reading)
            }
        }
    }
}

@Composable
private fun ReadingRow(reading: LabProbe.Reading) {
    val accent = (if (reading.exists) OkIcon else MissingIcon).adaptiveAccent(isDarkScheme())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.padding(top = 2.dp),
        )

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(reading.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = reading.value ?: "不存在",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (reading.exists) FontWeight.Medium else FontWeight.Normal,
                    color = if (reading.exists) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                reading.node,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            reading.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
