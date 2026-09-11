package magicau.mtkcontroller.feature.tweaks

import magicau.mtkcontroller.ui.components.SUB_SCREEN_EXTRA_PADDING
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.tweaks.FeatureTrial
import magicau.mtkcontroller.data.tweaks.TweakLifetime
import magicau.mtkcontroller.data.tweaks.VivoFeature
import magicau.mtkcontroller.di.containerViewModelFactory
import magicau.mtkcontroller.ui.components.*

/**
 * 功能逐个试用。
 *
 * 设计取舍：**一次只开一项**。全部一起开就看不出是哪一项起了作用，而那正是 this
 * 页面要回答的问题。
 *
 * 每项下面有三个判定按钮。这类 settings 键写后读回永远等于写入值，没有任何自动探针
 * 能判断"系统理不理睬" —— 只能由人看。所以判定是人给的，软件负责把键值前后差异
 * 和设备信息一起存进出报告。
 */
@Composable
fun FeatureTrialScreen(container: magicau.mtkcontroller.di.AppContainer) {
    val vm: FeatureTrialViewModel = viewModel(
        factory = containerViewModelFactory { FeatureTrialViewModel(container) },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> if (uri != null) vm.export(uri) }

    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> if (uri != null) vm.exportBackup(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importBackup(uri) }

    var showReport by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp).padding(bottom = SUB_SCREEN_EXTRA_PADDING),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InfoHeading(stringResource(R.string.trial_title), stringResource(R.string.trial_intro))

        state.message.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        SectionLabel(stringResource(R.string.tweaks_trial_section_all))
        // 限时实验不放进来：它 20 秒后自动还原，人来不及观察。
        val features = VivoFeature.entries.filter {
            it.lifetime != TweakLifetime.TIMED && it != VivoFeature.AI
        }
        SettingsCard {
            features.forEachIndexed { index, feature ->
                if (index > 0) InsetDivider()
                TrialRow(
                    feature = feature,
                    trial = state.trials[feature.name],
                    busy = state.busy,
                    onApply = { option -> vm.apply(feature, option) },
                    onRestore = { vm.restore(feature) },
                    onVerdict = { vm.verdict(feature, it) },
                )
            }
        }

        SectionLabel(stringResource(R.string.tweaks_trial_section_export))
        SettingsCard {
            // 一键流程：收集 → 直接弹保存框。中间不在对话框里展示报告 ——
            // 报告很长（几十项键值），在对话框里看反而是负担。
            SettingsRow(
                Icons.Default.Share, MaterialTheme.colorScheme.primary,
                stringResource(R.string.trial_export),
                stringResource(R.string.trial_export_hint),
                onClick = { vm.buildReportThenExport() },
            )
            InsetDivider()
            SettingsRow(
                Icons.Default.List, MaterialTheme.colorScheme.onSurfaceVariant,
                stringResource(R.string.trial_view), stringResource(R.string.trial_view_hint),
                onClick = { vm.viewReport(); showReport = true },
            )
            InsetDivider()
            SettingsRow(
                Icons.Default.Delete, MaterialTheme.colorScheme.error,
                stringResource(R.string.trial_clear), stringResource(R.string.tweaks_trial_clear_sub),
                onClick = { confirmClear = true },
            )
        }

        SectionLabel(stringResource(R.string.backup_title))
        SettingsCard {
            SettingsRow(
                Icons.Default.Share, MaterialTheme.colorScheme.primary,
                stringResource(R.string.backup_export), stringResource(R.string.backup_export_hint),
                onClick = { vm.backupThenExport() },
            )
            InsetDivider()
            SettingsRow(
                Icons.Default.Refresh, MaterialTheme.colorScheme.tertiary,
                stringResource(R.string.backup_import), stringResource(R.string.backup_import_hint),
                onClick = { importLauncher.launch(arrayOf("text/plain")) },
            )
        }

        SectionLabel(stringResource(R.string.drift_title))
        SettingsCard {
            Text(
                stringResource(R.string.drift_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.startDrift() },
                    enabled = !state.driftRunning && !state.busy,
                ) { Text(stringResource(R.string.drift_start)) }
                Button(
                    onClick = { vm.stopDrift() },
                    enabled = state.driftRunning && !state.busy,
                ) { Text(stringResource(R.string.drift_stop)) }
            }
            if (state.driftRunning) {
                Text(
                    stringResource(R.string.drift_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 结果单独一块：列出的每一项都是"没人动它却自己变了"的键。
        if (!state.driftRunning && state.driftResult.isNotEmpty()) {
            SectionLabel(stringResource(R.string.drift_result_heading))
            SettingsCard {
                Text(
                    stringResource(R.string.drift_found, state.driftResult.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                state.driftResult.forEach { (key, from, to) ->
                    Column {
                        Text(key, style = MaterialTheme.typography.labelSmall)
                        Text(
                            "${from ?: stringResource(R.string.backup_key_not_exist)} → ${to ?: stringResource(R.string.backup_key_not_exist)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    stringResource(R.string.drift_restart_hint),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (!state.driftRunning && state.driftResult.isEmpty() && state.driftRan) {
            SettingsCard {
                Text(
                    stringResource(R.string.drift_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    // 备份收好后同样直接弹保存框，中间不再多一步确认。
    val backupFilePrefix = stringResource(R.string.tweaks_trial_backup_file_prefix)
    val pendingBackup by vm.pendingBackup.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(pendingBackup) {
        if (pendingBackup.isNotEmpty()) {
            vm.consumeBackupExport()
            backupExportLauncher.launch("$backupFilePrefix-${System.currentTimeMillis()}.txt")
        }
    }

    // 恢复确认表。这是唯一能改动系统的入口，且只写勾过的。
    // 必须用订阅过的 state，不能写 vm.state.value —— 后者在 Composable 里不订阅，
    // restoreItems 从空变非空时这个 if 不会重新求值，窗口就永远不弹。
    if (state.restoreItems.isNotEmpty()) RestoreConfirmDialog(state, vm)

    // buildReportThenExport 收完报告后直接拉起系统保存框，用户只需选位置。
    val pendingExport by vm.pendingExport.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(pendingExport) {
        if (pendingExport) {
            exportLauncher.launch("feature-trial-${System.currentTimeMillis()}.txt")
            vm.consumeExport()
        }
    }

    if (showReport) {
        AlertDialog(
            onDismissRequest = { showReport = false },
            title = { Text(stringResource(R.string.tweaks_trial_report_title)) },
            text = {
                SelectionContainer {
                    Text(
                        state.report.ifBlank { stringResource(R.string.tweaks_trial_report_empty) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = state.report.isNotBlank(),
                    onClick = { showReport = false; vm.buildReportThenExport() },
                ) { Text(stringResource(R.string.tweaks_trial_report_save)) }
            },
            dismissButton = { TextButton(onClick = { showReport = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.tweaks_trial_clear_title)) },
            text = { Text(stringResource(R.string.tweaks_trial_clear_body)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clear() }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * 恢复确认表。
 *
 * **这是备份功能唯一能改动系统的入口。** 三条设计全部落在这里：
 * 1. 逐条列出「功能名 / 键 / 备份值 / 当前值」，英文键名旁边带信息图标。
 * 2. 默认一项都不勾。当前值和备份不一样的排在前面并标出来 —— 那说明这期间有东西
 *    改过它，可能是系统，可能是用户手动改的，要不要盖回去由人决定。
 * 3. 没有"全选"。要全恢复就得逐条勾。
 */
@Composable
private fun RestoreConfirmDialog(
    state: FeatureTrialViewModel.State,
    vm: FeatureTrialViewModel,
) {
    val items = state.restoreItems
    val selected = state.restoreSelected
    var showDetail by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = { vm.cancelRestore() },
        title = { Text(stringResource(R.string.backup_restore_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    state.restoreSource.ifBlank { stringResource(R.string.backup_restore_warning) },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.backup_restore_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (selected.isEmpty()) {
                    Text(
                        stringResource(R.string.backup_restore_empty_checked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                items.forEachIndexed { index, item ->
                    val id = "${item.owned.table} ${item.owned.key}"  // ui-check:ignore 试用页要显示测的是哪个键
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = id in selected,
                            onCheckedChange = { vm.toggleRestore(index, it) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(item.owned.ownerRes),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${item.owned.table}  ${item.owned.key}",  // ui-check:ignore 试用页要显示测的是哪个键
                                style = MaterialTheme.typography.labelSmall,
                            )
                            val backup = item.backupValue ?: stringResource(R.string.backup_key_not_exist)
                            val current = item.currentValue ?: stringResource(R.string.backup_key_not_exist)
                            Text(
                                if (item.unchanged) {
                                    stringResource(R.string.backup_restore_unchanged) + "  $current"
                                } else {
                                    stringResource(R.string.backup_restore_changed) +
                                        "  " + stringResource(R.string.tweaks_trial_restore_compare, backup, current)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.unchanged) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            )
                            if (item.backupValue == null) {
                                Text(
                                    stringResource(R.string.backup_restore_missing),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        item.owned.noteRes?.let { noteRes ->
                            FeatureInfo(
                                "${item.owned.table} ${item.owned.key}",  // ui-check:ignore 同上
                                stringResource(noteRes),
                            )
                        }
                    }
                    if (index < items.lastIndex) InsetDivider()
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty() && !state.busy,
                onClick = { vm.applyRestore() },
            ) { Text(stringResource(R.string.backup_restore_apply)) }
        },
        dismissButton = {
            TextButton(onClick = { vm.cancelRestore() }) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun TrialRow(
    feature: VivoFeature,
    trial: FeatureTrial.Trial?,
    busy: Boolean,
    onApply: (String?) -> Unit,
    onRestore: () -> Unit,
    onVerdict: (FeatureTrial.Verdict) -> Unit,
) {
    val blocked = trial?.gateReason
    val hint = feature.trialHintRes
        .takeIf { it != 0 }
        ?.let { stringResource(R.string.trial_observe_hint, stringResource(it)) }
        ?: stringResource(R.string.trial_hint_default)

    // 需选值的项由用户选；不选的用系统默认，这样"只开这一项"能一键跑完。
    var option by rememberSaveable(feature.name) { mutableStateOf(feature.defaultOption()) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(feature.labelText(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            VerdictChip(trial?.verdict ?: FeatureTrial.Verdict.PENDING)
        }

        Text(hint, style = MaterialTheme.typography.bodySmall)

        // 门禁没过的项保留开关但置灰，并说清是哪一条没过。
        blocked?.let {
            Text(stringResource(R.string.trial_unsupported, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        // 取值控件和正常页面共用同一份 —— 后端本来就是同一个 apply()。
        if (blocked == null && feature.needsOption()) {
            FeatureOptionEditor(
                feature = feature,
                enabled = !busy,
                currentDeviceValue = option,
                onOptionChange = { option = it },
            )
        }

        trial?.let { t ->
            Text(
                stringResource(
                    if (t.writeOk) R.string.tweaks_trial_last_ok else R.string.tweaks_trial_last_fail,
                    t.message,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onApply(option) }, enabled = !busy && blocked == null) {
                Text(stringResource(R.string.trial_apply))
            }
            if (trial != null) {
                TextButton(onClick = onRestore, enabled = !busy) { Text(stringResource(R.string.trial_restore)) }
            }
        }

        if (trial != null && blocked == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    FeatureTrial.Verdict.WORKED to R.string.trial_verdict_worked,
                    FeatureTrial.Verdict.NO_EFFECT to R.string.trial_verdict_no_effect,
                    FeatureTrial.Verdict.UNSURE to R.string.trial_verdict_unsure,
                ).forEach { (verdict, labelRes) ->
                    val selected = trial.verdict == verdict
                    FilterChip(
                        selected = selected,
                        onClick = { onVerdict(verdict) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VerdictChip(verdict: FeatureTrial.Verdict) {
    val (labelRes, color) = when (verdict) {
        FeatureTrial.Verdict.PENDING -> R.string.trial_state_untried to MaterialTheme.colorScheme.onSurfaceVariant
        FeatureTrial.Verdict.WORKED -> R.string.trial_state_worked to MaterialTheme.colorScheme.primary
        FeatureTrial.Verdict.NO_EFFECT -> R.string.trial_state_no_effect to MaterialTheme.colorScheme.error
        FeatureTrial.Verdict.UNSURE -> R.string.trial_state_unsure to MaterialTheme.colorScheme.onSurfaceVariant
        FeatureTrial.Verdict.UNSUPPORTED -> R.string.trial_state_unsupported to MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(disabledLabelColor = color),
    )
}
