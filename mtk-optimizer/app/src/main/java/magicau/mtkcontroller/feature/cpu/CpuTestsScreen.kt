package magicau.mtkcontroller.feature.cpu

import magicau.mtkcontroller.R
import magicau.mtkcontroller.ui.components.FailureNote
import magicau.mtkcontroller.ui.components.SUB_SCREEN_EXTRA_PADDING
import magicau.mtkcontroller.ui.text
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import magicau.mtkcontroller.data.cpu.CpuProbeMode
import magicau.mtkcontroller.ui.theme.LocalShapeStyle

@Composable
fun CpuTestsScreen(
    state: CpuUiState,
    onResume: () -> Unit,
    onProbe: (Int, CpuProbeMode) -> Unit,
    onStopProbe: () -> Unit,
    onProbeAll: (Int) -> Unit,
    onExportProbe: (android.net.Uri, String) -> Unit,
    onCollectRomConfigs: () -> Unit,
    onRange: (Int, Int, Int) -> Unit,
    onRelease: () -> Unit,
    onOpenFeatureTrial: () -> Unit = {},
) {
    LifecycleResumeEffect(Unit) { onResume(); onPauseOrDispose { } }
    var legacyExpanded by remember { mutableStateOf(false) }
    var archiveToExport by remember { mutableStateOf("") }
    val archiveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
        // ui-check:ignore 导出协议前缀，不是给用户看的文案
    ) { uri -> if (uri != null) onExportProbe(uri, "ROM_ZIP:$archiveToExport") }
    var probeIndex by remember { mutableStateOf(0) }
    var pendingProbe by remember { mutableStateOf<CpuProbeMode?>(null) }
    var pendingAll by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> if (uri != null) onExportProbe(uri, exportText) }
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view, state.probing) {
        val previous = view.keepScreenOn
        if (state.probing) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    if (pendingAll) AlertDialog(
        onDismissRequest = { pendingAll = false },
        title = { Text(stringResource(R.string.cpu_tests_full_compare_title)) },
        text = { Text(stringResource(R.string.cpu_tests_full_compare_body)) },
        confirmButton = { TextButton(onClick = { pendingAll = false; onProbeAll(probeIndex) }) { Text(stringResource(R.string.cpu_tests_start_record)) } },
        dismissButton = { TextButton(onClick = { pendingAll = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    pendingProbe?.let { mode ->
        AlertDialog(onDismissRequest = { pendingProbe = null },
            title = { Text(stringResource(mode.labelRes)) },
            // ROM 是只读快照，其余都要下发请求。两种预警告不同，别混成一句。
            text = { Text(if (mode == CpuProbeMode.ROM)
                stringResource(R.string.cpu_tests_mode_rom_body)
                else stringResource(R.string.cpu_tests_mode_generic_body)) },
            confirmButton = { TextButton(onClick = { pendingProbe = null; onProbe(probeIndex, mode) }) { Text(stringResource(R.string.cpu_tests_start_test)) } },
            dismissButton = { TextButton(onClick = { pendingProbe = null }) { Text(stringResource(R.string.action_cancel)) } })
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = SUB_SCREEN_EXTRA_PADDING),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            // 适配数据采集：**原来这里是两节**（「频率策略快照」和「实现包」），
            // 采的是同一件事的两种导出 —— 一个是接口 dump 文本，一个是实现文件 ZIP。
            // 分成两节只会让人以为要跑两个流程。合成一节，两个按钮并列说明各自产出什么。
            TestSection(
                stringResource(R.string.cpu_tests_collect_title),
                stringResource(R.string.cpu_tests_collect_desc),
            ) {
                Text(stringResource(R.string.cpu_tests_release_hint), style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = onRelease, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cpu_tests_release)) }
                Button(onClick = { pendingProbe = CpuProbeMode.ROM }, enabled = !state.busy && state.edits.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cpu_tests_collect_snapshot)) }
                FilledTonalButton(onClick = onCollectRomConfigs, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cpu_tests_collect_files)) }
                if (state.configArchive.isNotBlank()) {
                    Text(state.configArchive, style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(enabled = !state.busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        archiveToExport = state.configArchive
                        archiveLauncher.launch(state.configArchive)
                    }) { Text(stringResource(R.string.cpu_tests_export_zip)) }
                }
            }
        }

        if (state.message != null) item { FailureNote(failure = state.message) }
        if (state.probing) item {
            TestSection(stringResource(R.string.cpu_tests_recording_title), state.probeStage) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                FilledTonalButton(onClick = onStopProbe, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cpu_tests_stop)) }
            }
        }
        item {
            TestSection(stringResource(R.string.cpu_tests_feature_trial_title), stringResource(R.string.cpu_tests_feature_trial_desc)) {
                FilledTonalButton(onClick = onOpenFeatureTrial, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cpu_tests_open_feature_trial))
                }
            }
        }
        item {
            TestSection(stringResource(R.string.cpu_tests_report_title), stringResource(R.string.cpu_tests_report_desc)) {
                if (state.probeSaveNote.isNotBlank()) Text(state.probeSaveNote, style = MaterialTheme.typography.bodySmall)
                if (state.probeReport.isBlank()) Text(stringResource(R.string.cpu_tests_no_report), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    FilledTonalButton(enabled = !state.probing, modifier = Modifier.fillMaxWidth(), onClick = {
                        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.cpu_tests_clip_label), state.probeReport))
                    }) { Text(stringResource(R.string.cpu_tests_copy_report)) }
                    Button(enabled = !state.probing, modifier = Modifier.fillMaxWidth(), onClick = {
                        exportText = state.probeReport
                        exportLauncher.launch("cpu-probe-${System.currentTimeMillis()}.txt")
                    }) { Text(stringResource(R.string.cpu_tests_export_txt)) }
                    var preview by remember { mutableStateOf(false) }
                    TextButton(onClick = { preview = !preview }) { Text(stringResource(if (preview) R.string.cpu_tests_collapse_preview else R.string.cpu_tests_view_tail)) }
                    if (preview) Text(state.probeReport.takeLast(2500), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            TestSection(stringResource(R.string.cpu_tests_legacy_title), stringResource(R.string.cpu_tests_legacy_desc)) {
                FilledTonalButton(onClick = { legacyExpanded = !legacyExpanded }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (legacyExpanded) R.string.cpu_tests_legacy_collapse else R.string.cpu_tests_legacy_expand)) }
                if (legacyExpanded) {
                    state.edits.forEachIndexed { index, edit ->
                        FilterChip(selected = probeIndex == index, onClick = { probeIndex = index }, enabled = !state.busy, label = { Text(edit.cluster.policy) })
                    }
                    state.edits.getOrNull(probeIndex)?.let { edit ->
                        Text(stringResource(R.string.cpu_tests_freq_range, edit.cluster.policy, edit.minFreqKhz / 1000, edit.maxFreqKhz / 1000))
                        if (edit.cluster.availableFreqs.size > 1) RangeSlider(
                            value = edit.minIndex.toFloat()..edit.maxIndex.toFloat(),
                            onValueChange = { onRange(probeIndex, it.start.toInt(), it.endInclusive.toInt()) },
                            valueRange = 0f..edit.cluster.availableFreqs.lastIndex.toFloat(),
                            steps = (edit.cluster.availableFreqs.size - 2).coerceAtLeast(0), enabled = !state.busy,
                        )
                    }
                    Text(stringResource(R.string.cpu_tests_range_note), style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = { pendingAll = true }, enabled = !state.busy && state.powerHal.available, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cpu_tests_full_load_compare)) }
                    // ROM 在「适配数据采集」那一节，这里不重复列出。
                    CpuProbeMode.entries.filter { it != CpuProbeMode.ROM }.forEach { mode ->
                        FilledTonalButton(enabled = !state.busy && state.edits.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = { pendingProbe = mode }) { Text(stringResource(mode.labelRes)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TestSection(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = LocalShapeStyle.current.card) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            magicau.mtkcontroller.ui.components.InfoHeading(title, description)
            content()
        }
    }
}
