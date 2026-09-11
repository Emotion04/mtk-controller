package magicau.mtkcontroller.feature.lab

import magicau.mtkcontroller.ui.components.SUB_SCREEN_EXTRA_PADDING
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = SUB_SCREEN_EXTRA_PADDING),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.lab_panel_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.lab_panel_readings, state.readingsFound, state.readingsTotal),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = onRefresh) { Text(stringResource(R.string.lab_refresh)) }
                }
            }
        }

        state.sections.forEach { section ->
            item(key = section.titleRes) { SectionCard(section) }
        }

        item {
            Text(
                stringResource(R.string.lab_panel_footer),
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
                Text(stringResource(section.titleRes), style = MaterialTheme.typography.titleMedium)
                if (section.noteRes != 0) {
                    Text(
                        stringResource(section.noteRes),
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
                Text(stringResource(reading.labelRes, *reading.labelArgs.toTypedArray()), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = reading.value ?: stringResource(R.string.lab_absent),
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
            if (reading.noteRes != 0) {
                Text(
                    stringResource(reading.noteRes, *reading.noteArgs.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
