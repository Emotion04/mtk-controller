package magicau.mtkcontroller.feature.lab

import magicau.mtkcontroller.ui.components.SUB_SCREEN_EXTRA_PADDING
import magicau.mtkcontroller.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import magicau.mtkcontroller.data.lab.LabPlacement
import magicau.mtkcontroller.ui.components.FailureNote
import magicau.mtkcontroller.data.lab.LabCategory
import magicau.mtkcontroller.data.lab.LabChoice
import magicau.mtkcontroller.data.lab.LabControl
import magicau.mtkcontroller.data.lab.LabCores
import magicau.mtkcontroller.data.lab.LabController
import magicau.mtkcontroller.data.lab.LabFeature
import magicau.mtkcontroller.data.lab.LabRisk
import magicau.mtkcontroller.data.lab.THERMAL_POLICY_KEY
import magicau.mtkcontroller.data.thermal.ThermalPolicyController
import magicau.mtkcontroller.ui.theme.isDarkScheme
import magicau.mtkcontroller.ui.theme.adaptiveAccent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import magicau.mtkcontroller.ui.theme.LocalShapeStyle

private val RiskLow = Color(0xFF2E7D32)
private val RiskMedium = Color(0xFFB4531A)
private val RiskHigh = Color(0xFF9C4146)
private val StateOk = Color(0xFF2E7D32)
private val StateBad = Color(0xFF9C4146)
private val StateNeutral = Color(0xFF6B6B68)

/**
 * The canary lab: features whose protocol is understood but which have not been
 * shown to work on *this* device.
 *
 * Each card answers the same three questions in the same order — how risky is
 * it, has it been observed working here, and what exactly did the device do when
 * tried. Opening the screen applies nothing; a card only sends when the user
 * presses 测试.
 */
@Composable
fun LabScreen(
    state: LabUiState,
    /** 点某一条入口。参数是 mergeKey（有合并时）或 feature key。 */
    onOpenFeature: (String) -> Unit,
    onOpenPanel: () -> Unit,
    onOpenRawTest: () -> Unit,
    onValueChange: (LabFeature, String, Int) -> Unit,
    onHoldChange: (LabFeature, Boolean) -> Unit,
    onTest: (LabFeature) -> Unit,
    onResetAll: () -> Unit,
    onClearObservations: () -> Unit,
    onEditModeChange: (Boolean) -> Unit,
    onMark: (LabFeature, LabController.State?) -> Unit,
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.lab_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.powerHalReady) {
                    Text(
                        stringResource(R.string.lab_freq_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // **按内容宽，不搞等分。** 三个词长短不一（4/5/2 字），
                // 等分会让最短的那个两边空一大片，而且内容一悬殊就白等 ——
                // 「保存并退出登录」和「好」放在一起，等分后前者照样断行。
                //
                // 原来坏在**混用**：前两个有 weight、第三个没有，于是前两个各分一半，
                // 而一半放不下四个字，就断成两行，三个按钮高度还不一样。
                // 同一行里要么全按内容、要么全等分，不能一半一半。
                //
                // `Arrangement.spacedBy` 左对齐排布，各自贴合文字。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(onClick = onOpenPanel) {
                        Text(stringResource(R.string.lab_readonly_panel), maxLines = 1)
                    }
                    FilledTonalButton(onClick = onOpenRawTest) {
                        Text(stringResource(R.string.lab_raw_test), maxLines = 1)
                    }
                    FilledTonalButton(onClick = onResetAll) {
                        Text(stringResource(R.string.lab_reset), maxLines = 1)
                    }
                }


                state.message?.let { FailureNote(failure = it) }
            }
        }

        state.groups.forEach { (placement, items) ->
            item(key = "header-${placement.name}") { PlacementHeader(placement) }

            // 一级列表只放**紧凑行**：标签 + 判定 + 风险 + 实时读数。
            // 原来这里直接铺 FeatureCard，一张占接近一屏，「本机探测」有 17 项，
            // 滚 17 屏才能看完一遍，而用户第一件想看的"生效了吗"反而埋在卡里。
            // 控件和说明移到二级页，一行只回答"这是什么、能不能用、灵不灵"。
            items.groupBy { it.feature.mergeKey ?: it.feature.key }.forEach { (entryKey, group) ->
                item(key = entryKey) {
                    FeatureRow(
                        items = group,
                        liveThermal = if (group.any { it.feature.key == THERMAL_POLICY_KEY } &&
                            state.thermalAppliedIndex != null && state.thermalAppliedSource != null
                        ) {
                            state.thermalAppliedIndex to state.thermalAppliedSource
                        } else {
                            null
                        },
                        onClick = { onOpenFeature(entryKey) },
                        editing = state.editing,
                        onMark = onMark,
                    )
                }
            }
        }

        item {
            FilledTonalButton(onClick = onClearObservations, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lab_clear_observations))
            }
        }
    }
}

@Composable
private fun PlacementHeader(placement: LabPlacement) {
    Column(modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
        Text(
            stringResource(placement.titleRes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 一句话说清这一组是什么。原来这里只有分类名，
        // 用户看到"调度""平台限制"还是不知道这东西能不能用、该不该碰。
        Text(
            stringResource(placement.hintRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/**
 * 一级列表里的一行。
 *
 * **为什么不是卡片**：卡片把控件、说明、判定、历史全塞在一屏，17 项就要滚 17 屏。
 * 一行只放四样 —— 名称、判定、风险、实时读数；其余全部挪到二级页。
 * 用户第一件想知道的"它在这台机器上灵不灵"由此变成零成本可读。
 *
 * @param items 同一入口下的项。通常一个；`mergeKey` 相同的会是多个
 *   （前台/可见 uclamp 就是两个），行上显示主项的名称并标注"含 N 项"。
 */
@Composable
private fun FeatureRow(
    items: List<LabItemState>,
    liveThermal: Pair<Int, ThermalPolicyController.Source>?,
    onClick: () -> Unit,
    editing: Boolean,
    onMark: (LabFeature, LabController.State?) -> Unit,
) {
    val main = items.first()
    val feature = main.feature
    val merged = items.size > 1

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    // "（N 项）"是行内后缀，单独一条资源：译文里括号和量词的
                    // 位置和中文不一样，拼在模板里翻不出来。
                    if (merged) {
                        stringResource(R.string.lab_row_merged, stringResource(feature.labelRes), items.size)
                    } else {
                        stringResource(feature.labelRes)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                // 实时读数：只有个别功能有（温控档位的皮肤阈值）。它是"现在是什么"，
                // 和下面的判定"灵不灵"是两件事，都值得占一行。
                val live = main.readout ?: liveThermal?.let { (index, source) ->
                    stringResource(R.string.lab_live_thermal_row, index, sourceLabel(source))
                }
                if (live != null) {
                    Text(
                        live,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 徽章在标记图标的**左边**：标记是这一行最靠边的操作，
            // 位置固定，连点几行时手指不用横向找。
            if (feature.risk == LabRisk.HIGH) {
                Badge(stringResource(R.string.lab_risk_high), riskColor(feature.risk))
            }
            if (main.supported == false) {
                Badge(stringResource(R.string.lab_badge_absent), riskColor(LabRisk.HIGH))
            }
            // 平时只显示两种结果：**标了「能用」就是一个绿勾**，其余什么都不显示。
            // 「没用」的项根本不在列表里（见 LabViewModel 的过滤），
            // 「未测试」也不需要一行字提醒 —— 用户来这儿是要用功能，不是来看进度表。
            // 编辑模式下才露出两个图标按钮。
            if (editing) {
                ManualMark(verdict = main.verdict, onMark = { onMark(feature, it) })
            } else if (main.verdict?.stateEnum() == LabController.State.MANUAL_USABLE) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.lab_mark_usable),
                    tint = StateOk,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
fun FeatureCard(
    item: LabItemState,
    clusters: List<String>,
    /** 固件当前生效的温控档位与它的来源；非温控卡片传 null。 */
    liveThermal: Pair<Int, ThermalPolicyController.Source>? = null,
    physicalMaxByCluster: Map<Int, Int> = emptyMap(),
    onValueChange: (String, Int) -> Unit,
    onHoldChange: (Boolean) -> Unit,
    onTest: () -> Unit,
) {
    val feature = item.feature

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(feature.labelRes), style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Badge(
                        stringResource(R.string.lab_risk_badge, stringResource(feature.risk.labelRes)),
                        riskColor(feature.risk),
                    )
                    VerdictBadge(item.verdict)
                }
            }

            Text(
                stringResource(feature.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            feature.node?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            feature.dangerRes?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            item.readout?.let { readout ->
                Text(
                    readout,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // 当前**固件上**生效的档位，以及是谁要求的。来自共享状态，不是这里读的 ——
            // 温控档位有三个来源，只有一处真源才不会三处各显示各的。
            liveThermal?.let { (index, source) ->
                Text(
                    stringResource(R.string.lab_thermal_live, index, sourceLabel(source)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // 当前选中档位：**和 CPU 页共用同一套曲线/矩阵表**，不再在这里
            // 画一串等宽文字。档位编号先标出来，图表才知道自己属于哪一档。
            val selectedPolicy = item.selectedPolicy
            if (selectedPolicy != null) {
                Text(
                    stringResource(R.string.lab_thermal_selected, item.values.values.firstOrNull() ?: 0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                magicau.mtkcontroller.feature.cpu.ThermalPolicyOverview(
                    policy = selectedPolicy,
                    clusterLabel = { index ->
                        clusters.getOrNull(index) ?: stringResource(R.string.cpu_cluster_core, index)
                    },
                    physicalMaxByCluster = physicalMaxByCluster,
                )
            } else {
                item.selectedBrief?.let { brief ->
                    Text(
                        stringResource(R.string.lab_thermal_selected, item.values.values.firstOrNull() ?: 0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        brief,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (item.selectedBrief == null && item.briefs.isNotEmpty()) {
                    Text(
                        stringResource(R.string.lab_brief_loading),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (item.supported == false) {
                Text(
                    stringResource(R.string.lab_unsupported_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            Controls(
                feature = feature,
                item = item,
                clusters = clusters,
                onValueChange = onValueChange,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.lab_send_mode), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (item.hold) {
                            stringResource(R.string.lab_hold_on_desc)
                        } else {
                            stringResource(R.string.lab_hold_off_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.hold) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                // 这是**下次发送的参数**，不是设备现状 —— 所以不用开关（开关读起来像状态），
                // 用一对明确的选项。
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !item.hold, onClick = { onHoldChange(false) },
                        label = { Text(stringResource(R.string.lab_send_temporary)) })
                    FilterChip(selected = item.hold, onClick = { onHoldChange(true) },
                        label = { Text(stringResource(R.string.lab_send_hold)) })
                }
            }

            Button(
                onClick = onTest,
                enabled = !item.busy && item.supported != false,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (item.busy) R.string.lab_testing else R.string.lab_test)) }

            item.verdict?.let { verdict ->
                Text(
                    verdict.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = verdictColor(verdict.stateEnum()),
                )
            }
        }
    }
}

@Composable
private fun Controls(
    feature: LabFeature,
    item: LabItemState,
    clusters: List<String>,
    onValueChange: (String, Int) -> Unit,
) {
    when (feature.control) {
        // 一个开关渲染**一个键**：普通功能只有一个键（GLOBAL），逐核功能有 8 个。
        // 不给逐核单开一个分支 —— 那会把同一套开关逻辑抄两遍。
        LabControl.TOGGLE -> {
            item.values.keys.forEach { key ->
                val checked = (item.values[key] ?: 0) != 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            feature.perCore -> key
                            // 读不到就写「未读」，不画成「关」——"没读到"和"确实关着"是两件事。
                            !item.stateKnown -> stringResource(R.string.lab_state_unknown)
                            checked -> stringResource(R.string.lab_on)
                            else -> stringResource(R.string.lab_off)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (feature.perCore) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 未知时开关照旧可点：用户仍然可以主动下发这个值。
                    Switch(checked = checked, onCheckedChange = { onValueChange(key, if (it) 1 else 0) })
                }
            }
        }

        LabControl.CHOICE -> {
            // 选项可能来自设备本身（温控策略索引就是列 /vendor/etc/thermal/ 得来的），
            // 所以优先用运行时解析出的那份；静态 valueChoices 只是兜底。
            val choices = item.choices.ifEmpty {
                feature.valueChoices.map { LabChoice(it.second, it.first, "") }
            }
            val key = item.values.keys.firstOrNull() ?: LabViewModel.GLOBAL
            val current = item.values[key] ?: choices.firstOrNull()?.value ?: 0
            if (choices.size <= 4) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    choices.forEachIndexed { index, choice ->
                        SegmentedButton(
                            selected = current == choice.value,
                            onClick = { onValueChange(key, choice.value) },
                            shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                        ) { Text(choice.label) }
                    }
                }
            } else {
                // 档位多的时候不要一列一个 chip —— 那会把卡片撑到十几行高。
                // 横向滚动单行；每个档干什么统一从「档位说明」一个入口看。
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(choices) { choice ->
                        FilterChip(
                            selected = current == choice.value,
                            onClick = { onValueChange(key, choice.value) },
                            label = { Text(choice.label) },
                        )
                    }
                }
            }

        }

        LabControl.SLIDER -> {
            val keys = when {
                feature.perCore -> LabCores.keys
                feature.perCluster -> clusters
                else -> listOf(LabViewModel.GLOBAL)
            }
            keys.forEach { policy ->
                val value = item.values[policy] ?: feature.range.first
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (feature.perCluster || feature.perCore) policy
                            else stringResource(R.string.lab_target_value),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "$value${feature.unit}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Slider(
                        value = value.toFloat(),
                        onValueChange = { onValueChange(policy, it.roundToInt()) },
                        valueRange = feature.range.first.toFloat()..feature.range.last.toFloat(),
                    )
                }
            }
        }

        LabControl.READONLY -> Unit
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    val tint = color.adaptiveAccent(isDarkScheme())
    Box(
        modifier = Modifier
            .clip(LocalShapeStyle.current.chip)
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun VerdictBadge(verdict: LabController.Verdict?) {
    val state = verdict?.stateEnum() ?: LabController.State.UNTESTED
    Badge(stringResource(state.labelRes), verdictColor(state))
}

@Composable
private fun riskColor(risk: LabRisk): Color = when (risk) {
    LabRisk.LOW -> RiskLow
    LabRisk.MEDIUM -> RiskMedium
    LabRisk.HIGH -> RiskHigh
}

@Composable
private fun verdictColor(state: LabController.State): Color = when (state) {
    LabController.State.WORKING, LabController.State.MANUAL_USABLE -> StateOk
    LabController.State.NO_EFFECT, LabController.State.UNSUPPORTED,
    LabController.State.MANUAL_UNUSABLE,
    -> StateBad
    LabController.State.UNKNOWN, LabController.State.UNTESTED -> StateNeutral
}



/**
 * "现在这一档是谁在管"的一句话。
 *
 * 为什么值得显示：温控档位有三个来源，用户在下达命令的那一页看不到另外两个。
 * 不说明来源的话，他在 CPU 页选了 03，实验室这里显示 05，看起来就是"设置没用"。
 */
@Composable
private fun sourceLabel(source: ThermalPolicyController.Source): String = stringResource(
    when (source) {
        ThermalPolicyController.Source.USER -> R.string.thermal_source_user
        ThermalPolicyController.Source.APP_RULE -> R.string.thermal_source_app_rule
        ThermalPolicyController.Source.LAB -> R.string.thermal_source_lab
    },
)

/**
 * 编辑模式下的两个图标按钮：✓ 能用 / ✗ 没用。
 *
 * 点当前那个就清除（回到"未标记"）。
 *
 * **只有编辑模式才出现。** 平时列表里标为「没用」的项直接不显示、
 * 标为「能用」的只在行尾画一个绿勾 —— 用户来这儿是要用功能，
 * 不是来维护一张评分表，评分动作不该常驻在每一行上。
 */
@Composable
private fun ManualMark(
    verdict: LabController.Verdict?,
    onMark: (LabController.State?) -> Unit,
) {
    val state = verdict?.stateEnum()
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(
            LabController.State.MANUAL_USABLE to Icons.Filled.Check,
            LabController.State.MANUAL_UNUSABLE to Icons.Filled.Close,
        ).forEach { (target, icon) ->
            val selected = state == target
            IconButton(
                onClick = { onMark(if (selected) null else target) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = stringResource(
                        if (target == LabController.State.MANUAL_USABLE) {
                            R.string.lab_mark_usable
                        } else {
                            R.string.lab_mark_unusable
                        },
                    ),
                    tint = if (selected) verdictColor(target) else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
