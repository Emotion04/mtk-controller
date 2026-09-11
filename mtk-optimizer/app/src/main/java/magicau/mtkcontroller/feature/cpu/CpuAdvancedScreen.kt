package magicau.mtkcontroller.feature.cpu

import magicau.mtkcontroller.ui.components.SUB_SCREEN_EXTRA_PADDING
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.thermal.ThermalPolicy
import magicau.mtkcontroller.ui.displayLabel
import magicau.mtkcontroller.ui.components.FailureNote
import magicau.mtkcontroller.ui.components.InsetDivider
import magicau.mtkcontroller.ui.components.SettingsCard
import magicau.mtkcontroller.ui.components.SettingsRow

private val ThermalIcon = Color(0xFF9C4146)
private val GovernorIcon = Color(0xFF55606E)

/**
 * 「高级设置」二级页：温控档位 + CPU 调速器。
 *
 * ## 为什么是**页面**而不是弹层
 *
 * 它装的是低频控件（选一次就放着的档位、几乎不动的调速器），内容又比一屏多
 * （档位芯片 + 一张温度频率表 + 每簇调速器）。弹层盖在 CPU 页上、又比一屏高，
 * 滚起来像"浮在半空的窗口"，而且和设置里那些同样性质的页面长得不一样。
 * 做成普通二级页之后：顶部标题栏和别处一致，返回手势一致，内容也能自然滚动。
 *
 * ## UI 与设置页一致
 *
 * 用同一套 [SettingsCard] / [SettingsRow]：行内展开、不跳转、不用对话框。
 * **不把每个控件都摊在外面** —— 温控档位里的"参数原文"这类低频开关收在
 * 行里展开，默认只给一眼能看完的东西。
 */
@Composable
fun CpuAdvancedScreen(
    state: CpuUiState,
    onGovernorChange: (Int, String) -> Unit,
    onSelectThermalIndex: (Int) -> Unit,
    onToggleThermalDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPolicySheet by remember { mutableStateOf(false) }
    // 核簇索引 -> 硬件最高频率（MHz）；纵轴拉到超大核物理上限，并给百分比。
    val physicalMaxByCluster = state.edits.mapNotNull { edit ->
        val max = edit.cluster.availableFreqs.maxOrNull() ?: return@mapNotNull null
        edit.cluster.index to (max / 1000).toInt()
    }.toMap()

    if (showPolicySheet) {
        ThermalPolicySheet(
            indices = state.thermalIndices,
            policies = state.thermalPolicies,
            full = state.thermalFull,
            currentIndex = state.thermalIndexEdit ?: state.thermalPolicyIndex,
            showDetail = state.showThermalDetail,
            physicalMaxByCluster = physicalMaxByCluster,
            clusterLabel = { index ->
                val cluster = state.edits.getOrNull(index)?.cluster
                // 取不到簇就只报编号 —— 这里没有簇可显示，也不该编一个。
                if (cluster != null) cluster.displayLabel()
                else stringResource(R.string.cpu_cluster_core, index)
            },
            onToggleDetail = onToggleThermalDetail,
            onDismiss = { showPolicySheet = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 这一页比一屏长（档位芯片 + 一张温度频率表 + 每簇调速器），
            // 必须能滚。底栏留位在 SubScreenScaffold 里统一处理。
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(top = 8.dp, bottom = SUB_SCREEN_EXTRA_PADDING),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ThermalCard(
            state = state,
            onSelectIndex = onSelectThermalIndex,
            onToggleDetail = onToggleThermalDetail,
            onOpenSheet = { showPolicySheet = true },
        )
        GovernorCard(state.edits, !state.busy, onGovernorChange)
    }
}

/**
 * 温控档位。
 *
 * **选档只记录、不下发。** 与频率区间一样，等用户在 CPU 页点「应用」才落地 ——
 * 所以这里不再有开关，选中态就是"编辑区里选了什么"，另有一行文字说明
 * 当前**生效**的是哪一档。
 */
@Composable
private fun ThermalCard(
    state: CpuUiState,
    onSelectIndex: (Int) -> Unit,
    onToggleDetail: () -> Unit,
    onOpenSheet: () -> Unit,
) {
    var detailExpanded by remember { mutableStateOf(false) }
    val physicalMaxByCluster = state.edits.mapNotNull { edit ->
        val max = edit.cluster.availableFreqs.maxOrNull() ?: return@mapNotNull null
        edit.cluster.index to (max / 1000).toInt()
    }.toMap()

    // 先把两个索引取出来：`when` 里要判断"选的和生效的是不是同一个"，
    // 直接重复读 state 会写四遍同样的比较。原文案里"已生效"那一支还写了两遍。
    val editing = state.thermalIndexEdit
    val shown = editing ?: state.thermalPolicyIndex

    SettingsCard {
        SettingsRow(
            icon = Icons.Filled.Refresh,
            iconTint = ThermalIcon,
            title = stringResource(R.string.thermal_card_title),
            subtitle = when {
                shown == null -> stringResource(R.string.thermal_index_none)
                // 选了但还没落地 —— 提示回上一页点应用，而不是这里直接下发。
                editing != null && editing != state.thermalPolicyIndex ->
                    stringResource(R.string.thermal_index_pending, editing)
                else -> stringResource(R.string.thermal_index_active, shown)
            },
            trailingText = state.thermalIndexEdit?.let { "%02d".format(it) },
        )

        // 没有档位时说一句结果，不叠主语、位置和排障建议。
        //
        // 读失败时用 [FailureNote]：摘要一句话，原始错误默认收起、点"显示更多"才看、
        // 可复制。**不要求用户去运行日志里翻** —— 排障信息跟着失败位置走。
        if (state.thermalIndices.isEmpty()) {
            val failure = state.thermalReadError
            if (failure != null) {
                FailureNote(
                    failure = failure,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.thermal_no_files),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                )
            }
        } else {
            FlowRow(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.thermalIndices.forEach { candidate ->
                    FilterChip(
                        selected = state.thermalIndexEdit == candidate,
                        // 没授权也能选 —— 选档本来就不下发，它是"我打算用哪一档"。
                        enabled = !state.busy,
                        onClick = { onSelectIndex(candidate) },
                        label = { Text("%02d".format(candidate)) },
                    )
                }
            }

            val shown = state.thermalIndexEdit ?: state.thermalPolicyIndex
                ?: state.thermalIndices.firstOrNull()
            // 当前**实际生效**的皮肤阈值。上面那些摘要是"这一档是什么阶梯"，
            // 这一行是"现在加载的是哪一档" —— 无 root 时唯一能看出后者的通道
            // （THERM-013）。读不到就留空，不用历史值顶替。
            state.thermalSkinThreshold?.let { skin ->
                Text(
                    stringResource(R.string.cpu_thermal_skin, skin),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                )
            }
            val policy: ThermalPolicy? = shown?.let { state.thermalPolicies[it] }
            if (policy != null && policy.decodeError == null) {
                ThermalPolicyOverview(
                    policy = policy,
                    clusterLabel = { index ->
                        val cluster = state.edits.getOrNull(index)?.cluster
                        if (cluster != null) cluster.displayLabel()
                        else stringResource(R.string.cpu_cluster_core, index)
                    },
                    showDetail = state.showThermalDetail,
                    fullText = shown?.let { state.thermalFull[it] },
                    physicalMaxByCluster = physicalMaxByCluster,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                )
            } else {
                Text(
                    shown?.let { state.thermalBriefs[it] }.orEmpty().ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                )
            }
        }

        InsetDivider(startIndent = 16.dp)
        // 两个低频入口收在一行里展开，不各占一行 —— 它们在绝大多数时候没人点。
        SettingsRow(
            icon = Icons.Filled.List,
            iconTint = ThermalIcon,
            title = stringResource(R.string.thermal_detail_entry),
            onClick = { detailExpanded = !detailExpanded },
            trailing = {
                Icon(
                    imageVector = if (detailExpanded) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (detailExpanded) R.string.action_collapse else R.string.action_expand,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        AnimatedVisibility(visible = detailExpanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                TextButton(onClick = onToggleDetail) {
                    Text(
                        stringResource(
                            if (state.showThermalDetail) R.string.thermal_detail_hide
                            else R.string.thermal_detail_show,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                TextButton(onClick = onOpenSheet) {
                    Text(
                        stringResource(R.string.thermal_all_notes),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/** CPU 调速器。每个核簇一行可展开的横排芯片。 */
@Composable
private fun GovernorCard(
    edits: List<ClusterEdit>,
    enabled: Boolean,
    onGovernorChange: (Int, String) -> Unit,
) {
    SettingsCard {
        SettingsRow(
            icon = Icons.Filled.Star,
            iconTint = GovernorIcon,
            title = stringResource(R.string.governor_title),
            subtitle = edits.map { it.governor ?: "—" }.distinct().joinToString(" / "),
            trailingText = if (edits.none { it.cluster.governorWritable }) {
                stringResource(R.string.governor_read_only)
            } else {
                null
            },
        )

        edits.forEachIndexed { index, edit ->
            val governors = edit.cluster.governors
                .ifEmpty { listOfNotNull(edit.cluster.currentGovernor) }
            InsetDivider(startIndent = 16.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    edit.cluster.displayLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (governors.isEmpty()) {
                    Text(
                        stringResource(R.string.governor_unsupported),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                governors.forEach { governor ->
                    FilterChip(
                        selected = edit.governor == governor,
                        enabled = enabled,
                        onClick = { onGovernorChange(index, governor) },
                        label = { Text(governor) },
                    )
                }
            }
        }
    }
}
