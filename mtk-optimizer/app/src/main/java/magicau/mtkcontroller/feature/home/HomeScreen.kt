package magicau.mtkcontroller.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.data.tweaks.TweakCommands
import magicau.mtkcontroller.domain.model.HomeCard
import magicau.mtkcontroller.domain.model.HomeCardType
import magicau.mtkcontroller.ui.components.FrequencyChart
import magicau.mtkcontroller.ui.components.ReorderableColumn

@Composable
fun HomeScreen(
    state: HomeUiState,
    onMoveCard: (Int, Int) -> Unit,
    onRemoveCard: (HomeCard) -> Unit,
    onAddCard: (HomeCardType) -> Unit,
    onResetCards: () -> Unit,
    onApplyProfile: (String) -> Unit,
    onRelease: () -> Unit,
    onTouchEnabled: (Boolean) -> Unit,
    onTouchRate: (Int) -> Unit,
    onRotationSuggestion: (Boolean) -> Unit,
    onThermalBrightness: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var adding by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("MTK Controller", style = MaterialTheme.typography.headlineSmall)
            Text(
                state.activeProfileName?.let { "当前方案:$it" } ?: "未套用方案",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ReorderableColumn(
            items = state.cards,
            onMove = onMoveCard,
            modifier = Modifier.fillMaxWidth(),
        ) { _, card, isDragging, dragHandle ->
            DashboardCard(
                card = card,
                state = state,
                isDragging = isDragging,
                dragHandle = dragHandle,
                onRemove = { onRemoveCard(card) },
                onApplyProfile = onApplyProfile,
                onRelease = onRelease,
                onTouchEnabled = onTouchEnabled,
                onTouchRate = onTouchRate,
                onRotationSuggestion = onRotationSuggestion,
                onThermalBrightness = onThermalBrightness,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  添加卡片")
            }
            TextButton(onClick = onResetCards) { Text("恢复默认") }
        }

        Text(
            "长按卡片右上角的拖动手柄可调整顺序",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }

    if (adding) {
        AddCardDialog(
            existing = state.cards.map { it.type }.toSet(),
            onDismiss = { adding = false },
            onPick = {
                onAddCard(it)
                adding = false
            },
        )
    }
}

@Composable
private fun DashboardCard(
    card: HomeCard,
    state: HomeUiState,
    isDragging: Boolean,
    dragHandle: Modifier,
    onRemove: () -> Unit,
    onApplyProfile: (String) -> Unit,
    onRelease: () -> Unit,
    onTouchEnabled: (Boolean) -> Unit,
    onTouchRate: (Int) -> Unit,
    onRotationSuggestion: (Boolean) -> Unit,
    onThermalBrightness: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (isDragging) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 1.5.dp),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(card.type.title, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {}, modifier = dragHandle) {
                        Icon(
                            Icons.Filled.List,
                            contentDescription = "拖动排序",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Close, contentDescription = "删除", Modifier.size(18.dp))
                    }
                }
            }

            Box(modifier = Modifier.padding(end = 8.dp)) {
                when (card.type) {
                    HomeCardType.QUICK_SETTINGS -> QuickSettingsCard(
                        state = state,
                        onTouchEnabled = onTouchEnabled,
                        onTouchRate = onTouchRate,
                        onRotationSuggestion = onRotationSuggestion,
                        onThermalBrightness = onThermalBrightness,
                    )

                    HomeCardType.CPU_CHART -> CpuChartCard(state)
                    HomeCardType.ACTIVE_PROFILE -> ActiveProfileCard(state, onApplyProfile, onRelease)
                    HomeCardType.CLUSTER_STATUS -> ClusterStatusCard(state)
                    HomeCardType.PRIVILEGE -> PrivilegeCard(state)
                    HomeCardType.GPU_STATUS -> GpuCard(state)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickSettingsCard(
    state: HomeUiState,
    onTouchEnabled: (Boolean) -> Unit,
    onTouchRate: (Int) -> Unit,
    onRotationSuggestion: (Boolean) -> Unit,
    onThermalBrightness: (Boolean) -> Unit,
) {
    val tweaks = state.tweaks
    val enabled = state.privilege.mode.canElevate && !state.busy

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ToggleRow(
            title = "触控优化",
            subtitle = "提高触控采样率并降低响应延迟",
            checked = tweaks.touchEnabled,
            enabled = enabled,
            onCheckedChange = onTouchEnabled,
        )

        if (tweaks.touchEnabled) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TweakCommands.TOUCH_RATES.forEach { rate ->
                    FilterChip(
                        selected = tweaks.touchRate == rate,
                        onClick = { onTouchRate(rate) },
                        enabled = enabled,
                        label = { Text("${rate}Hz") },
                    )
                }
            }
        }

        ToggleRow(
            title = "旋转建议",
            subtitle = "检测到屏幕转向时,在导航栏显示旋转按钮",
            checked = tweaks.rotationSuggestion,
            enabled = enabled,
            onCheckedChange = onRotationSuggestion,
        )

        ToggleRow(
            title = "高温降亮度恢复",
            subtitle = "设备过热被系统压低亮度时自动恢复",
            checked = tweaks.thermalBrightness,
            enabled = enabled,
            onCheckedChange = onThermalBrightness,
        )

        if (!enabled) {
            Text(
                "需要先授权 Shizuku 才能使用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun CpuChartCard(state: HomeUiState) {
    if (state.series.isEmpty()) {
        Text(
            "未检测到 CPU 簇,无法绘制频率曲线。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FrequencyChart(series = state.series, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ActiveProfileCard(
    state: HomeUiState,
    onApplyProfile: (String) -> Unit,
    onRelease: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = state.activeProfileName ?: "未套用方案",
            style = MaterialTheme.typography.bodyLarge,
        )

        if (state.profiles.isEmpty()) {
            Text(
                "还没有保存的方案。到 CPU 页调好上下限后点「保存为方案」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.profiles.take(4).forEach { profile ->
                val active = profile.name == state.activeProfileName
                OutlinedButton(
                    onClick = { onApplyProfile(profile.id) },
                    enabled = !state.busy && !active,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (active) "${profile.name}(生效中)" else "套用「${profile.name}」")
                }
            }
        }

        OutlinedButton(
            onClick = onRelease,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("释放调频") }
    }
}

@Composable
private fun ClusterStatusCard(state: HomeUiState) {
    if (state.clusters.isEmpty()) {
        Text(
            "未检测到 CPU 簇。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.clusters.forEach { cluster ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(cluster.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = cluster.currentFreqKhz?.let { "${it / 1000} MHz" } ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PrivilegeCard(state: HomeUiState) {
    val privilege = state.privilege
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text(privilege.mode.name) })
            if (privilege.uid >= 0) {
                AssistChip(onClick = {}, label = { Text("uid ${privilege.uid}") })
            }
        }
        Text(
            text = when {
                !privilege.binderAlive -> "Shizuku 未运行 —— 到「设置 → 诊断」查看引导。"
                !privilege.permissionGranted -> "尚未授权 Shizuku。"
                privilege.mode.canWriteSysfs -> "已获得 root 权限,调速器可修改。"
                else -> "已获得 adb 权限。频率上下限可用;调速器需要 root。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GpuCard(state: HomeUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(state.gpu.channel.displayName, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = state.gpu.currentFreqKhz?.let { "当前 ${it / 1000} MHz" } ?: "未读取到当前频率",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddCardDialog(
    existing: Set<HomeCardType>,
    onDismiss: () -> Unit,
    onPick: (HomeCardType) -> Unit,
) {
    val available = HomeCardType.entries.filterNot { it in existing }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加卡片") },
        text = {
            if (available.isEmpty()) {
                Text("所有卡片都已在首页上。")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    available.forEach { type ->
                        TextButton(
                            onClick = { onPick(type) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(type.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    type.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Suppress("unused")
private val UnusedColor = Color.Unspecified
