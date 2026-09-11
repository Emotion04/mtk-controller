package magicau.mtkcontroller.feature.tweaks

import magicau.mtkcontroller.ui.components.FailureNote
import magicau.mtkcontroller.ui.text
import magicau.mtkcontroller.ui.components.SUB_SCREEN_EXTRA_PADDING
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.tweaks.*
import magicau.mtkcontroller.ui.components.*
import androidx.compose.ui.Alignment
import magicau.mtkcontroller.ui.components.StatusChip
import magicau.mtkcontroller.ui.components.StatusTone

private fun timeLabel(time: Long, notRead: String) =
    if (time == 0L) notRead
    else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))

@Composable
fun SmartPlatformScreen(state: TweaksUiState, vm: TweaksViewModel, onBackup: () -> Unit) {
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var mute by rememberSaveable { mutableStateOf(false) }
    var directory by rememberSaveable { mutableStateOf(false) }
    val notRead = stringResource(R.string.tweaks_not_read)
    LifecycleResumeEffect(state.privilege.mode.canElevate) {
        if (state.privilege.mode.canElevate) vm.enterSmartPlatform()
        onPauseOrDispose { }
    }
    if (state.smartInitialNotice) AlertDialog(
        onDismissRequest = { if (!state.busy) vm.dismissSmartNotice() },
        title = {
            Text(
                stringResource(
                    if (state.busy) R.string.smart_saving else R.string.smart_initial_result,
                ),
            )
        },
        text = {
            val msg = state.message
            Text(
                if (state.busy) {
                    stringResource(R.string.smart_first_run)
                } else if (msg != null) {
                    msg.summary.text()
                } else {
                    stringResource(R.string.smart_check_backup)
                },
            )
        },
        confirmButton = {
            TextButton(enabled = !state.busy, onClick = { vm.dismissSmartNotice(); onBackup() }) {
                Text(stringResource(R.string.smart_view_export))
            }
        },
        dismissButton = {
            TextButton(enabled = !state.busy, onClick = vm::dismissSmartNotice) {
                Text(stringResource(R.string.action_later))
            }
        },
    )
    if (pending != null) AlertDialog(
        onDismissRequest = { pending = null },
        title = { Text(stringResource(R.string.smart_backup_first)) },
        text = {
            Column {
                Text(stringResource(R.string.smart_backup_first_body))
                Row {
                    Checkbox(checked = mute, onCheckedChange = { mute = it })
                    Text(stringResource(R.string.smart_dont_remind), Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.setSmartReminder(!mute)
                val value = pending; pending = null
                vm.setVivo(VivoFeature.AI, value == "restore", value?.takeUnless { it == "restore" })
            }) { Text(stringResource(R.string.smart_backup_continue)) }
        },
        dismissButton = {
            TextButton(onClick = { pending = null; onBackup() }) {
                Text(stringResource(R.string.smart_export_first))
            }
        },
    )
    fun change(value: String) {
        if (state.smartReminder) pending = value else vm.setVivo(VivoFeature.AI, value == "restore", value.takeUnless { it == "restore" })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp).padding(bottom = SUB_SCREEN_EXTRA_PADDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.privilege.mode.canElevate) SettingsCard {
            SettingsRow(
                Icons.Default.Notifications, MaterialTheme.colorScheme.error,
                stringResource(R.string.tweak_need_shizuku),
                onClick = vm::requestPermission,
            )
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { FailureNote(failure = it) }
        SectionLabel(stringResource(R.string.smart_current_config))
        SettingsCard {
            SettingsRow(
                Icons.Default.Refresh, MaterialTheme.colorScheme.primary,
                stringResource(R.string.smart_read_now),
                timeLabel(state.smartReadAt, notRead),
                onClick = { if (state.canRun) vm.inspectSmartPlatform() },
            )
            InsetDivider()
            val value = state.smartValues[SmartBackupCodec.AI_KEY]
            SettingsRow(
                Icons.Default.Star, MaterialTheme.colorScheme.primary,
                stringResource(R.string.smart_ai_config),
                when (value) {
                    "1" -> stringResource(R.string.smart_config_on)
                    "0" -> stringResource(R.string.smart_config_off)
                    "null" -> stringResource(R.string.smart_config_absent)
                    else -> stringResource(R.string.smart_config_unknown)
                },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(stringResource(R.string.chip_experimental), StatusTone.Warn)
                        FeatureInfo(
                            stringResource(R.string.smart_ai_title),
                            VivoFeature.AI.detailText() + "\n" + stringResource(R.string.vivo_hint_ai),
                        )
                    }
                },
            )
            Row(
                Modifier.padding(start = 54.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = value == "1",
                    enabled = state.canRun && value in listOf("0", "1") && state.smartInitial != null,
                    onCheckedChange = { change(if (it) "1" else "0") },
                )
                if (VivoFeature.AI in state.vivoSnapshots) {
                    TextButton(enabled = state.canRun, onClick = { change("restore") }) {
                        Text(stringResource(R.string.action_restore_original))
                    }
                }
            }
        }
        SectionLabel(stringResource(R.string.smart_data_protection))
        SettingsCard {
            SettingsRow(
                Icons.Default.Refresh, MaterialTheme.colorScheme.secondary,
                stringResource(R.string.smart_backup_restore),
                state.smartInitial?.let { stringResource(R.string.smart_initial_at, timeLabel(it.capturedAt, notRead)) }
                    ?: stringResource(R.string.smart_no_initial),
                onClick = onBackup,
            )
        }
        TweakReportActions(state, vm::collectTweakReport, vm::exportTweakReport)
        SectionLabel(stringResource(R.string.smart_capabilities))
        SettingsCard {
            SettingsRow(
                Icons.Default.List, MaterialTheme.colorScheme.tertiary,
                stringResource(R.string.smart_catalog),
                stringResource(R.string.smart_catalog_sub),
                onClick = { directory = !directory },
            )
            // "AI 加速"上面已经单独一行有开关，这里不重复列。
            if (directory) SmartPlatform.engines.filter { it.id != "ai" }.forEach { engine ->
                InsetDivider()
                val unread = stringResource(R.string.smart_key_unread)
                val details = (engine.keys + engine.services).joinToString("\n") {
                    "$it：${state.smartValues[it] ?: unread}"
                }
                val title = stringResource(engine.titleRes)
                SettingsRow(
                    Icons.Default.Info, MaterialTheme.colorScheme.secondary, title,
                    if (engine.id == "cooling") {
                        stringResource(R.string.smart_cooling_hint)
                    } else {
                        stringResource(R.string.smart_detect_only)
                    },
                    trailing = {
                        FeatureInfo(title, stringResource(engine.noteRes) + "\n" + details)
                    },
                )
            }
        }
        SectionLabel(stringResource(R.string.tweaks_section_experimental))
        ExperimentalSetting(VivoFeature.GAME_THERMAL, state, vm::setVivo)
        ExperimentalSetting(VivoFeature.HTP, state, vm::setVivo)
        SettingsCard {
            UnavailableSetting(
                stringResource(R.string.smart_na_pem),
                stringResource(R.string.smart_na_pem_note),
            )
            InsetDivider()
            UnavailableSetting(
                stringResource(R.string.smart_na_game_link),
                stringResource(R.string.smart_na_game_link_note),
            )
            InsetDivider()
            UnavailableSetting(
                stringResource(R.string.smart_na_network),
                stringResource(R.string.smart_na_network_note),
            )
        }
    }
}

@Composable
fun SmartBackupScreen(state: TweaksUiState, vm: TweaksViewModel) {
    var exportSlot by rememberSaveable { mutableStateOf("initial") }
    var restoreSlot by rememberSaveable { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { vm.exportSmart(it, exportSlot) } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importSmart) }
    if (restoreSlot != null) AlertDialog(
        onDismissRequest = { restoreSlot = null },
        title = { Text(stringResource(R.string.smart_restore_title)) },
        text = { Text(stringResource(R.string.smart_restore_body)) },
        confirmButton = {
            TextButton(onClick = { val slot = restoreSlot!!; restoreSlot = null; vm.restoreSmart(slot) }) {
                Text(stringResource(R.string.action_restore_original))
            }
        },
        dismissButton = {
            TextButton(onClick = { restoreSlot = null }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp).padding(bottom = SUB_SCREEN_EXTRA_PADDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { FailureNote(failure = it) }
        if (!state.privilege.mode.canElevate) SettingsCard {
            SettingsRow(
                Icons.Default.Notifications, MaterialTheme.colorScheme.error,
                stringResource(R.string.smart_restore_need_shizuku),
                stringResource(R.string.smart_import_no_auth),
                onClick = vm::requestPermission,
            )
        }
        SectionLabel(stringResource(R.string.smart_archives))
        val notRead = stringResource(R.string.tweaks_not_read)
        val noBackup = stringResource(R.string.smart_no_backup)
        val slotLabels = mapOf(
            "initial" to stringResource(R.string.smart_slot_initial),
            "previous" to stringResource(R.string.smart_slot_previous),
            "imported" to stringResource(R.string.smart_slot_imported),
        )
        val slotNotes = mapOf(
            "initial" to stringResource(R.string.smart_slot_initial_note),
            "previous" to stringResource(R.string.smart_slot_previous_note),
            "imported" to stringResource(R.string.smart_slot_imported_note),
        )
        listOf(
            Triple("initial", "initial", state.smartInitial),
            Triple("previous", "previous", state.smartPrevious),
            Triple("imported", "imported", state.smartImported),
        ).forEach { (slot, _, backup) ->
            SettingsCard {
                val label = slotLabels.getValue(slot)
                SettingsRow(
                    Icons.Default.List, MaterialTheme.colorScheme.primary, label,
                    backup?.let { stringResource(R.string.smart_backup_at, timeLabel(it.capturedAt, notRead), it.values.size) }
                        ?: noBackup,
                    trailing = {
                        FeatureInfo(
                            label,
                            backup?.let {
                                "${it.device}\n${it.rom}\n${it.source}\n" +
                                    stringResource(R.string.smart_missing_count, it.missing.size) +
                                    stringResource(R.string.smart_only_ai_restorable)
                            } ?: slotNotes.getValue(slot),
                        )
                    },
                )
                if (backup != null) Row(
                    Modifier.padding(start = 54.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        enabled = !state.busy,
                        onClick = {
                            exportSlot = slot
                            export.launch("smart-platform-$slot-${backup.capturedAt}.json")
                        },
                    ) { Text(stringResource(R.string.action_export_file)) }
                    TextButton(
                        enabled = state.canRun && SmartBackupCodec.AI_KEY in backup.values,
                        onClick = { restoreSlot = slot },
                    ) { Text(stringResource(R.string.action_restore_original)) }
                }
            }
        }
        SectionLabel(stringResource(R.string.smart_manage))
        SettingsCard {
            SettingsRow(
                Icons.Default.Refresh, MaterialTheme.colorScheme.primary,
                stringResource(R.string.smart_read_save_first),
                stringResource(R.string.smart_read_save_first_sub),
                onClick = { if (state.canRun) vm.enterSmartPlatform() },
            )
            InsetDivider()
            SettingsRow(
                Icons.Default.List, MaterialTheme.colorScheme.secondary,
                stringResource(R.string.smart_import_file),
                stringResource(R.string.smart_import_file_sub),
                onClick = { if (!state.busy) import.launch(arrayOf("application/json", "text/plain")) },
            )
            InsetDivider()
            SettingsRow(
                Icons.Default.Notifications, MaterialTheme.colorScheme.tertiary,
                stringResource(R.string.smart_remind_backup),
                stringResource(R.string.smart_remind_backup_sub),
                trailing = { Switch(checked = state.smartReminder, onCheckedChange = vm::setSmartReminder) },
            )
        }
    }
}
