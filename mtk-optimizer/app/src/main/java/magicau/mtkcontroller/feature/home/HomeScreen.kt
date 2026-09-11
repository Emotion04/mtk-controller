package magicau.mtkcontroller.feature.home

import magicau.mtkcontroller.ui.components.FailureNote
import magicau.mtkcontroller.ui.components.LocalNavBarClearance
import androidx.compose.ui.res.stringResource
import magicau.mtkcontroller.R
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.FilledTonalButton
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
import magicau.mtkcontroller.ui.components.ToggleRow
import magicau.mtkcontroller.ui.descriptionRes
import magicau.mtkcontroller.ui.displayLabel
import magicau.mtkcontroller.ui.displayNameRes
import magicau.mtkcontroller.ui.titleRes
import magicau.mtkcontroller.ui.theme.LocalShapeStyle
import magicau.mtkcontroller.ui.components.FrequencyGauge

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
    onOpenTests: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var adding by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = LocalNavBarClearance.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("MTK Controller", style = MaterialTheme.typography.headlineSmall)
            Text(
                state.activeProfileName
                    ?.let { stringResource(R.string.home_current_profile, it) }
                    ?: stringResource(R.string.profile_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ReorderableColumn(
            items = state.cards,
            onMove = onMoveCard,
            modifier = Modifier.fillMaxWidth(),
            // 传滚动状态：拖到屏幕边缘时页面要自己滚动，否则长列表里
            // 把最后一张拖到第一张就得先放下、手动滚、再接着拖。
            scrollState = scrollState,
            // 卡片的类型就是它的稳定标识：同一张卡片顶多加一张，类型不重复。
            key = { it.type },
            // 阴影跟着卡片的圆角走，否则拖动时圆角卡片后面会露出方角阴影。
            itemShape = LocalShapeStyle.current.card,
        ) { _, card, isDragging, dragHandle ->
            DashboardCard(
                card = card,
                state = state,
                isDragging = isDragging,
                dragHandle = dragHandle,
                onRemove = { onRemoveCard(card) },
                onOpenTests = onOpenTests,
                onApplyProfile = onApplyProfile,
                onRelease = onRelease,
                onTouchEnabled = onTouchEnabled,
                onTouchRate = onTouchRate,
                onRotationSuggestion = onRotationSuggestion,
                onThermalBrightness = onThermalBrightness,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { adding = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                // 间隔用 Spacer，不用前导空格 —— 空格在字符串资源里会被裁掉，
                // 而"图标和文字离多远"本来就该由布局决定。
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_add_card))
            }
            TextButton(onClick = onResetCards) { Text(stringResource(R.string.home_reset_cards)) }
        }

        Text(
            stringResource(R.string.home_reorder_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.message?.let { FailureNote(failure = it) }
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
    onOpenTests: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = LocalShapeStyle.current.card,
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
                Text(stringResource(card.type.titleRes), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {}, modifier = dragHandle) {
                        Icon(
                            Icons.Filled.List,
                            contentDescription = stringResource(R.string.action_drag_reorder),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_delete),
                            modifier = Modifier.size(18.dp),
                        )
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
                    HomeCardType.FREQ_GAUGE -> FreqGaugeCard(state)
                    HomeCardType.TEST_CENTER -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.home_tests_summary), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(onClick = onOpenTests, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_open_tests)) }
                    }
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
            title = stringResource(R.string.home_touch_title),
            subtitle = stringResource(R.string.home_touch_sub),
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
            title = stringResource(R.string.home_rotation_title),
            subtitle = stringResource(R.string.home_rotation_sub),
            checked = tweaks.rotationSuggestion,
            enabled = enabled,
            onCheckedChange = onRotationSuggestion,
        )

        ToggleRow(
            title = stringResource(R.string.home_brightness_title),
            subtitle = stringResource(R.string.home_brightness_sub),
            checked = tweaks.thermalBrightness,
            enabled = enabled,
            onCheckedChange = onThermalBrightness,
        )

        if (!enabled) {
            Text(
                stringResource(R.string.home_need_shizuku),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CpuChartCard(state: HomeUiState) {
    if (state.series.isEmpty()) {
        Text(
            stringResource(R.string.home_chart_no_clusters),
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
            text = state.activeProfileName ?: stringResource(R.string.profile_none),
            style = MaterialTheme.typography.bodyLarge,
        )

        if (state.profiles.isEmpty()) {
            Text(
                stringResource(R.string.home_no_profiles),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.profiles.take(4).forEach { profile ->
                val active = profile.name == state.activeProfileName
                FilledTonalButton(
                    onClick = { onApplyProfile(profile.id) },
                    enabled = !state.busy && !active,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 两条分开成两条资源，不要"一条里塞三元表达式" ——
                    // 译文里"生效中"和"套用"的句式不同，塞一起就翻不出来了。
                    Text(
                        stringResource(
                            if (active) R.string.home_profile_active
                            else R.string.home_profile_apply,
                            profile.name,
                        ),
                    )
                }
            }
        }

        FilledTonalButton(
            onClick = onRelease,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.home_release_freq)) }
    }
}

@Composable
private fun ClusterStatusCard(state: HomeUiState) {
    if (state.clusters.isEmpty()) {
        Text(
            stringResource(R.string.app_no_clusters),
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
                Text(cluster.displayLabel(), style = MaterialTheme.typography.bodyMedium)
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
                !privilege.binderAlive -> stringResource(R.string.home_priv_not_running)
                !privilege.permissionGranted -> stringResource(R.string.home_priv_not_granted)
                privilege.mode.canWriteSysfs -> stringResource(R.string.home_priv_root)
                else -> stringResource(R.string.home_priv_adb)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GpuCard(state: HomeUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(state.gpu.channel.displayNameRes), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = state.gpu.currentFreqKhz
                ?.let { stringResource(R.string.home_gpu_current, it / 1000) }
                ?: stringResource(R.string.home_gpu_unread),
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
        title = { Text(stringResource(R.string.action_add_card)) },
        text = {
            if (available.isEmpty()) {
                Text(stringResource(R.string.home_all_cards_added))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    available.forEach { type ->
                        TextButton(
                            onClick = { onPick(type) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(type.titleRes), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(type.descriptionRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Suppress("unused")
private val UnusedColor = Color.Unspecified

/**
 * 首页的频率仪表卡片。
 *
 * 与性能页那张用同一个 [FrequencyGauge]，只是这里每簇一张小图 ——
 * 首页的用途是"扫一眼有没有异常"，不需要精确读数。
 */
@Composable
private fun FreqGaugeCard(state: HomeUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.home_freq_title), style = MaterialTheme.typography.titleSmall)
            if (state.clusters.isEmpty()) {
                Text(
                    stringResource(R.string.app_no_clusters),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.clusters.forEach { cluster ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            cluster.displayLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                        FrequencyGauge(
                            currentKhz = cluster.currentFreqKhz,
                            // 首页不做 caps 轮询（那是性能页的事），所以外环不画红 ——
                            // 即"看不出额外限制"。要看真实限制请去性能页。
                            maxLimitKhz = null,
                            minLimitKhz = null,
                            hardwareMaxKhz = cluster.maxFreqKhz.takeIf { it > 0 }
                                ?: cluster.availableFreqs.lastOrNull() ?: 0L,
                            compact = true,
                        )
                    }
                }
            }
        }
    }
}
