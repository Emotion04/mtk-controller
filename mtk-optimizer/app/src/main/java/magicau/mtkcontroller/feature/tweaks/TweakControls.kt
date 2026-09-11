package magicau.mtkcontroller.feature.tweaks

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.tweaks.*
import magicau.mtkcontroller.ui.components.*
import magicau.mtkcontroller.ui.components.StatusChip
import magicau.mtkcontroller.ui.components.StatusTone

/**
 * 配置项名称。文案在 strings.xml 里，界面重构不会连坐。
 */
@Composable
fun VivoFeature.labelText(): String = stringResource(labelRes)

/**
 * 配置项说明：detail 加上这个界面需要补的那一句。
 *
 * 两段都来自资源文件；没有补充说明时只返回 detail。
 */
@Composable
fun VivoFeature.detailText(): String {
    val detail = detailRes?.let { stringResource(it) }.orEmpty()
    if (hintRes == 0) return detail
    val hint = stringResource(hintRes)
    return when {
        detail.isBlank() -> hint
        hint.isBlank() -> detail
        else -> "$detail\n$hint"
    }
}

/**
 * 需要提醒时返回它，正常状态返回 null。
 *
 * 有系统读数的配置项不标 —— 标了反而显得每项都不可信。返回枚举而不是渲染好的
 * 字符串，是为了让调用方用小方框（[StatusChip]）表达严重程度，而不是把状态
 * 拼进标题。
 */
fun VivoFeature.evidenceOrNull(): Evidence? =
    if (evidence == Evidence.CONFIRMED) null else evidence

/** 证据等级 → 严重程度。决定小方框的颜色，不决定文案。 */
fun Evidence.tone(): StatusTone = when (this) {
    Evidence.CONFIRMED -> StatusTone.Ok
    Evidence.WRITTEN -> StatusTone.Warn
    Evidence.LIKELY_USELESS -> StatusTone.Error
}

/** 行尾的状态标记 + 说明按钮。所有设置行共用这一种排法。 */
@Composable
fun RowTrailing(
    badge: Evidence?,
    label: String,
    description: String,
) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        badge?.let { StatusChip(stringResource(it.labelRes), it.tone()) }
        FeatureInfo(label, description)
    }
}

@Composable
fun AnimationSetting(feature: VivoFeature, state: TweaksUiState, onVivo: (VivoFeature, Boolean, String?, Boolean) -> Unit) {
    var opened by rememberSaveable { mutableStateOf(false) }
    var ticks by rememberSaveable { mutableIntStateOf(10) }
    val current = state.vivoValues[feature.values.keys.single()]
    val label = feature.labelText()
    val badge = feature.evidenceOrNull()
    SettingsRow(Icons.Default.Refresh, MaterialTheme.colorScheme.primary, label,
        current?.let { if (it == "null") stringResource(R.string.tweaks_anim_system_default) else stringResource(R.string.tweaks_anim_current, it) } ?: stringResource(R.string.tweaks_value_not_read),
        onClick = { if (state.canRun) { ticks = ((current?.toFloatOrNull() ?: 1f) * 10).roundToInt().coerceIn(0, 100); opened = true } },
        trailing = { RowTrailing(badge, label, feature.detailText()) })
    if (opened) AlertDialog(onDismissRequest = { opened = false }, title = { Text(label) },
        text = { Column {
            Text("${ticks / 10}.${ticks % 10}×")
            Slider(value = ticks.toFloat(), onValueChange = { ticks = it.roundToInt() }, valueRange = 0f..100f, steps = 99)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = ticks > 0, onClick = { ticks-- }) { Text("−0.1") }
                TextButton(onClick = { ticks = 10 }) { Text(stringResource(R.string.tweaks_anim_standard)) }
                TextButton(enabled = ticks < 100, onClick = { ticks++ }) { Text("+0.1") }
            }
            if (feature in state.vivoSnapshots) TextButton(enabled = state.canRun, onClick = { opened = false; onVivo(feature, true, null, false) }) { Text(stringResource(R.string.action_restore_original)) }
        } },
        confirmButton = { TextButton(enabled = state.canRun && feature in state.vivoSupported, onClick = { opened = false; onVivo(feature, false, "${ticks / 10}.${ticks % 10}", false) }) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = { opened = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
fun ExperimentalSetting(feature: VivoFeature, state: TweaksUiState, onVivo: (VivoFeature, Boolean, String?, Boolean) -> Unit) {
    var opened by rememberSaveable { mutableStateOf(false) }
    var preset by rememberSaveable { mutableStateOf("low") }
    SettingsCard {
        val label = feature.labelText()
        val badge = feature.evidenceOrNull()
        SettingsRow(Icons.Default.Build, MaterialTheme.colorScheme.tertiary, label,
            state.vivoStatus[feature] ?: stringResource(R.string.tweaks_not_detected), trailing = { RowTrailing(badge, label, feature.detailText()) },
            onClick = { if (state.canRun && feature in state.vivoSupported) opened = true })
        if (state.experimentFeature == feature) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.experimentRunning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.tweaks_experiment_waiting, state.experimentSeconds), style = MaterialTheme.typography.bodySmall)
            }
            state.experimentMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (state.experimentSuccess) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
            if (!state.experimentRunning && state.busy) Text(stringResource(R.string.tweaks_experiment_returned), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.padding(start = 54.dp, end = 16.dp)) {
            TextButton(enabled = state.canRun && feature in state.vivoSupported && feature !in state.vivoSnapshots, onClick = { opened = true }) { Text(stringResource(R.string.tweaks_experiment_start)) }
            if (feature in state.vivoSnapshots) TextButton(enabled = state.canRun, onClick = { onVivo(feature, true, null, false) }) { Text(stringResource(R.string.action_restore_original)) }
        }
    }
    if (opened) AlertDialog(onDismissRequest = { opened = false }, title = { Text(feature.labelText()) },
        text = { Column {
            Text(feature.detailText())
            if (feature == VivoFeature.HTP) listOf(
                "low" to stringResource(R.string.tweaks_htp_preset_low),
                "medium" to stringResource(R.string.tweaks_htp_preset_medium),
                "high" to stringResource(R.string.tweaks_htp_preset_high),
            ).forEach { (value, label) ->
                FilterChip(selected = value == preset, onClick = { preset = value }, label = { Text(label) })
            }
        } },
        confirmButton = { TextButton(enabled = state.canRun, onClick = { opened = false; onVivo(feature, false, if (feature == VivoFeature.HTP) preset else null, false) }) { Text(stringResource(R.string.tweaks_htp_backup_test)) } },
        dismissButton = { TextButton(onClick = { opened = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
fun UnavailableSetting(title: String, reason: String) {
    SettingsRow(Icons.Default.Info, MaterialTheme.colorScheme.onSurfaceVariant, title, stringResource(R.string.tweaks_unavailable_status),
        trailing = { FeatureInfo(title, reason) })
}

@Composable
fun TweakReportActions(state: TweaksUiState, collect: () -> Unit, export: (Uri) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(export) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.tweaks_clip_label)
    SectionLabel(stringResource(R.string.tweaks_report_section))
    SettingsCard {
        SettingsRow(Icons.Default.List, MaterialTheme.colorScheme.primary, stringResource(R.string.tweaks_report_generate), stringResource(R.string.tweaks_report_generate_sub),
            onClick = { if (state.canRun) collect() }, trailing = { FeatureInfo(stringResource(R.string.tweaks_report_section), stringResource(R.string.tweaks_report_info)) })
        Row(Modifier.padding(start = 54.dp, end = 16.dp)) {
            TextButton(enabled = !state.busy && state.tweakReport.isNotBlank(), onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(clipLabel, state.tweakReport))) } }) { Text(stringResource(R.string.action_copy)) }
            TextButton(enabled = !state.busy && state.tweakReport.isNotBlank(), onClick = { launcher.launch("tweak-report-${System.currentTimeMillis()}.txt") }) { Text(stringResource(R.string.action_export_file)) }
        }
    }
}
