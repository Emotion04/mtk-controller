package magicau.mtkcontroller.feature.diag

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.data.diag.CapabilityReport
import magicau.mtkcontroller.data.diag.CheckCategory

/**
 * Diagnostics grouped by category. Each category is a collapsible card so the
 * screen reads as a short list of sections instead of a wall of rows.
 */
@Composable
fun DiagScreen(
    state: DiagUiState,
    onRefresh: () -> Unit,
    onRequestPermission: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        return
    }

    val report = state.report ?: return
    val grouped = report.checksByCategory()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "真机首次运行后,把这里的结果截图发我,即可确认探测是否准确。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) { Text("重新探测") }
                OutlinedButton(onClick = onRequestPermission, modifier = Modifier.weight(1f)) {
                    Text("授权 Shizuku")
                }
            }
        }

        item {
            OutlinedButton(onClick = onVerify, modifier = Modifier.fillMaxWidth()) {
                Text("读取实时 sysfs 上下限")
            }
        }

        grouped.forEach { (category, checks) ->
            item(key = category.name) {
                CategoryCard(category = category, checks = checks)
            }
        }

        item { PrivilegeDetailCard(report) }
    }
}

@Composable
private fun CategoryCard(category: CheckCategory, checks: List<CapabilityReport.Check>) {
    var expanded by remember { mutableStateOf(true) }
    val allOk = checks.all { it.ok }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(category.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${checks.count { it.ok }}/${checks.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (allOk) OkColor else MaterialTheme.colorScheme.error,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    checks.forEachIndexed { index, check ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                        CheckRow(check)
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(check: CapabilityReport.Check) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(check.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (check.ok) "✓" else "✗",
                color = if (check.ok) OkColor else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(
            text = check.detail,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (check.multiline) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PrivilegeDetailCard(report: CapabilityReport) {
    val privilege = report.privilege
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("提权详情", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Text(
                buildString {
                    appendLine("模式: ${privilege.mode}")
                    appendLine("uid: ${privilege.uid}")
                    appendLine("Shizuku 版本: ${privilege.shizukuVersion}")
                    appendLine("UserService 已绑定: ${report.remoteBound}")
                    appendLine("UserService 实际 uid: ${report.remoteUid ?: "—"}")
                    privilege.message?.let { appendLine("备注: $it") }
                }.trim(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val OkColor = Color(0xFF2E7D32)
