package magicau.mtkcontroller.feature.diag

import magicau.mtkcontroller.ui.components.FailureNote
import magicau.mtkcontroller.ui.components.SUB_SCREEN_EXTRA_PADDING
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.log.LogLevel
import magicau.mtkcontroller.ui.components.FeatureInfo
import magicau.mtkcontroller.ui.components.InfoHeading
import magicau.mtkcontroller.ui.components.SectionLabel
import magicau.mtkcontroller.ui.components.SettingsCard
import magicau.mtkcontroller.ui.components.SettingsRow
import magicau.mtkcontroller.ui.components.StatusChip
import magicau.mtkcontroller.ui.components.StatusTone

/**
 * 诊断导出。一个入口，两个产物。
 *
 * 布局顺序按用户的实际流程排：先看基线有没有采到，再决定导什么、要不要录。
 * 录制的开关放在导出选项之前 —— 用户是"复现问题后回来导出"，录制是前置动作。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportScreen(
    state: ExportViewModel.UiState,
    onSetInclude: (String, Boolean) -> Unit,
    onToggleRecording: () -> Unit,
    onSetRecordingLevel: (LogLevel) -> Unit,
    onDiscardRecovered: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onClearLogs: () -> Unit,
    onDismissMessage: () -> Unit,
    onStartAdaptation: () -> Unit = {},
    onStopAdaptation: () -> Unit = {},
    onSetBlindWrites: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 这一页有几组勾选项 + 录制区 + 导出按钮，比一屏长。
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(top = 12.dp, bottom = SUB_SCREEN_EXTRA_PADDING),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.export_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
        )
        Text(
            stringResource(R.string.export_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
        )

        // --- 适配测试 ---------------------------------------------------
        SectionLabel(stringResource(R.string.adapt_title))
        SettingsCard {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.adapt_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsRow(
                    icon = Icons.Filled.Build,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.adapt_blind),
                    subtitle = stringResource(R.string.adapt_blind_sub),
                    trailing = {
                        Switch(
                            checked = state.blindWrites,
                            onCheckedChange = onSetBlindWrites,
                            enabled = !state.adaptationRunning,
                        )
                    },
                )
                Button(
                    onClick = if (state.adaptationRunning) onStopAdaptation else onStartAdaptation,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (state.adaptationRunning) R.string.adapt_stop else R.string.adapt_start,
                        ),
                    )
                }
            }
        }

        // --- 基线状态 ---------------------------------------------------
        SettingsCard {
            SettingsRow(
                Icons.Filled.Info,
                MaterialTheme.colorScheme.primary,
                stringResource(R.string.export_baseline_title),
                // 副标题已经说清"已记录/尚未记录"，再加一个 OK 方框是重复表达。
                // 方框留给下面录制行那种"一句话说不完"的状态。
                subtitle = state.baselineAt
                    ?.let { stringResource(R.string.export_baseline_ok, it) }
                    ?: stringResource(R.string.export_baseline_missing),
            )
        }

        // --- 录制 -------------------------------------------------------
        SectionLabel(stringResource(R.string.export_record_title))
        SettingsCard {
            val recording = state.recording
            if (recording != null && recording.recovered) {
                SettingsRow(
                    Icons.Filled.Refresh,
                    MaterialTheme.colorScheme.tertiary,
                    stringResource(R.string.export_record_recovered),
                    trailing = {
                        TextButton(onClick = onDiscardRecovered) {
                            Text(stringResource(R.string.export_record_discard))
                        }
                    },
                )
            } else {
                SettingsRow(
                    Icons.Filled.Build,
                    if (recording != null) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.primary,
                    if (recording != null) {
                        stringResource(R.string.export_record_running, stringResource(recording.level.labelRes))
                    } else {
                        stringResource(R.string.export_record_title)
                    },
                    subtitle = if (recording != null) null
                    else stringResource(R.string.export_record_idle),
                    onClick = onToggleRecording,
                    trailing = {
                        Text(
                            stringResource(
                                if (recording != null) R.string.export_record_stop
                                else R.string.export_record_start,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
            if (state.recording == null) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.export_record_level),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    listOf(LogLevel.WARN, LogLevel.INFO, LogLevel.DEBUG).forEach { level ->
                        TextButton(onClick = { onSetRecordingLevel(level) }) {
                            Text(
                                stringResource(level.labelRes),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.recordingLevel == level) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }

        // --- 内容选择 ---------------------------------------------------
        SectionLabel(stringResource(R.string.export_options_title))
        SettingsCard {
            CheckOptionRow(
                stringResource(R.string.export_option_adaptation),
                state.includeAdaptation,
            ) { onSetInclude("adaptation", it) }
            CheckOptionRow(
                stringResource(R.string.export_option_24h),
                state.include24h,
            ) { onSetInclude("24h", it) }
            CheckOptionRow(
                stringResource(R.string.export_option_1h),
                state.include1h,
            ) { onSetInclude("1h", it) }
            CheckOptionRow(
                stringResource(R.string.export_option_all),
                state.includeAll,
            ) { onSetInclude("all", it) }
        }

        // --- 存储 -------------------------------------------------------
        SettingsCard {
            // 「日志占用 X，上限 Y」是**状态行，不是标题**。
            //
            // 原先把它塞进 InfoHeading 的 title 位，一次错了两处：那个组件的标题
            // 没有左内边距，文字于是贴着卡片边缘；而一行状态数据也不该占着标题的
            // 排版（titleMedium、还带一个信息按钮）。改成普通 Text，与卡片内其他
            // 内容同样留 16dp。
            Text(
                text = stringResource(
                    R.string.export_storage,
                    formatBytes(state.logBytes),
                    formatBytes(160L * 1024 * 1024),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
            )
            Text(
                text = stringResource(R.string.export_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            )
            Row(Modifier.padding(start = 16.dp, bottom = 8.dp)) {
                TextButton(onClick = onClearLogs) { Text(stringResource(R.string.export_clear)) }
            }
        }

        // --- 动作 -------------------------------------------------------
        Button(
            onClick = onExport,
            enabled = !state.busy && (state.includeAdaptation || state.include24h ||
                state.include1h || state.includeAll),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { Text(stringResource(R.string.export_generate)) }

        // 生成成功后给三个出口：分享 / 保存 / 复制路径。
        // 三条路是因为没有一条在所有机型上都可用 —— 分享面板可能被裁掉，
        // SAF 可能没有可写目录，而复制路径至少能让用户自己去找。
        //
        // 排版上两条讲究：
        //
        // 1. **分享是主操作**，用实心 Button 拿主题色；另两条是备选，用
        //    FilledTonalButton 退一级。一屏一个实心按钮，重点才不会散。
        // 2. **不让三个按钮等分宽度。** 等分时最长的那个必然在词中间折行
        //    （"保存到文 / 件"），第二行只剩一两个字。改成 FlowRow：按钮按内容
        //    宽度排，排不下就整颗换到下一行，文字永远不拆。
        //    也不缩小字号 —— 那会让这一屏的按钮和别处不一样，读起来更累。
        if (state.pending != null) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onShare) {
                    Text(stringResource(R.string.export_share))
                }
                FilledTonalButton(onClick = onSave) {
                    Text(stringResource(R.string.export_save))
                }
                FilledTonalButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(state.pending.absolutePath))
                    },
                ) { Text(stringResource(R.string.export_copy_path)) }
            }
        }

        state.message?.let { msg ->
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 摘要 + 可展开的原始错误共用 FailureNote；"知道了"仍由页面自己收尾。
                FailureNote(failure = msg, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissMessage) {
                    Text(stringResource(R.string.action_got_it))
                }
            }
        }
    }
}

@Composable
/** 勾选一行。与 DiagScreen 的 CheckRow 同名但含义不同，所以这里叫 CheckOptionRow。 */
private fun CheckOptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
