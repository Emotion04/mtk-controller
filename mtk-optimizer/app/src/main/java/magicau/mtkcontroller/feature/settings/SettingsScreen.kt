package magicau.mtkcontroller.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.ui.components.InsetDivider
import magicau.mtkcontroller.ui.components.SectionLabel
import magicau.mtkcontroller.ui.components.SettingsCard
import magicau.mtkcontroller.ui.components.SettingsRow
import magicau.mtkcontroller.data.log.LogLevel
import magicau.mtkcontroller.domain.model.ApplyMode
import magicau.mtkcontroller.domain.model.NavBarStyle
import magicau.mtkcontroller.domain.model.ThemeMode
import magicau.mtkcontroller.ui.navigation.Destination
import magicau.mtkcontroller.ui.theme.AppPalette

private val PaletteIcon = Color(0xFF6A4C93)
private val ThemeIcon = Color(0xFF55606E)
private val TabIcon = Color(0xFF2E7D32)
private val ToolsIcon = Color(0xFFB4531A)
private val NotifyIcon = Color(0xFF1565C0)
private val DiagIcon = Color(0xFF0B6E6E)
private val AboutIcon = Color(0xFF8A6A4F)
private val NavIcon = Color(0xFF7A8FA6)
private val LogIcon = Color(0xFF6B6B68)
private val ReapplyIcon = Color(0xFF9C4146)
private val BackupIcon = Color(0xFF4A6572)
private val LabIcon = Color(0xFF6A4C93)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSetDefaultTab: (Destination) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetPalette: (AppPalette) -> Unit,
    onSetNavBarStyle: (NavBarStyle) -> Unit,
    onSetNavBarCustomPalette: (AppPalette) -> Unit,
    onSetApplyMode: (ApplyMode) -> Unit,
    onSetReapplyInterval: (Long) -> Unit,
    onSetLogLevel: (LogLevel) -> Unit,
    onOpenTweaks: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenLab: () -> Unit,
    onExportBackup: (android.net.Uri) -> Unit,
    onPrepareImport: (android.net.Uri) -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(onExportBackup) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onPrepareImport) }

    var paletteExpanded by remember { mutableStateOf(false) }
    var navStyleExpanded by remember { mutableStateOf(false) }
    var tabExpanded by remember { mutableStateOf(false) }
    var themeModeExpanded by remember { mutableStateOf(false) }
    var logLevelExpanded by remember { mutableStateOf(false) }
    var applyModeExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(top = 12.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
        )

        SectionLabel("外观")
        SettingsCard {
            SettingsRow(
                icon = Icons.Filled.Star,
                iconTint = ThemeIcon,
                title = "深色 / 浅色模式",
                subtitle = state.themeMode.description,
                trailingText = state.themeMode.label,
                onClick = { themeModeExpanded = !themeModeExpanded },
            )
            AnimatedVisibility(visible = themeModeExpanded) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        OptionRow(
                            label = mode.label,
                            detail = mode.description,
                            selected = state.themeMode == mode,
                            onClick = { onSetThemeMode(mode) },
                        )
                    }
                }
            }

            InsetDivider()
            SettingsRow(
                icon = Icons.Filled.Favorite,
                iconTint = PaletteIcon,
                title = "主题色",
                subtitle = state.palette.label,
                onClick = { paletteExpanded = !paletteExpanded },
            )
            AnimatedVisibility(visible = paletteExpanded) {
                Column(modifier = Modifier.padding(bottom = 10.dp)) {
                    AppPalette.entries.groupBy { it.group }.forEach { (group, palettes) ->
                        Text(
                            group,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 62.dp, top = 6.dp, bottom = 2.dp),
                        )
                        palettes.forEach { palette ->
                            PaletteRow(
                                palette = palette,
                                selected = state.palette == palette,
                                onClick = { onSetPalette(palette) },
                            )
                        }
                    }
                }
            }

            InsetDivider()
            SettingsRow(
                icon = Icons.Filled.List,
                iconTint = NavIcon,
                title = "底栏样式",
                subtitle = state.navBarStyle.description,
                trailingText = state.navBarStyle.label,
                onClick = { navStyleExpanded = !navStyleExpanded },
            )
            AnimatedVisibility(visible = navStyleExpanded) {
                Column(modifier = Modifier.padding(bottom = 10.dp)) {
                    NavBarStyle.entries.forEach { style ->
                        OptionRow(
                            label = style.label,
                            detail = style.description,
                            selected = state.navBarStyle == style,
                            onClick = { onSetNavBarStyle(style) },
                        )
                    }
                    if (state.navBarStyle == NavBarStyle.CUSTOM) {
                        Text(
                            "底栏色系",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 62.dp, top = 6.dp, bottom = 2.dp),
                        )
                        AppPalette.entries.forEach { palette ->
                            OptionRow(
                                label = palette.label,
                                detail = palette.group,
                                selected = state.navBarCustomPalette == palette,
                                swatch = palette.accent,
                                onClick = { onSetNavBarCustomPalette(palette) },
                            )
                        }
                    }
                }
            }
        }

        SectionLabel("通用")
        SettingsCard {
            SettingsRow(
                icon = Icons.Filled.Home,
                iconTint = TabIcon,
                title = "默认标签页",
                subtitle = "打开 App 时显示",
                trailingText = state.defaultTab.label,
                onClick = { tabExpanded = !tabExpanded },
            )
            AnimatedVisibility(visible = tabExpanded) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Destination.entries.forEach { destination ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = state.defaultTab == destination,
                                    role = Role.RadioButton,
                                    onClick = { onSetDefaultTab(destination) },
                                )
                                .padding(start = 56.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RadioButton(selected = state.defaultTab == destination, onClick = null)
                            Icon(destination.icon, contentDescription = null, modifier = Modifier.size(17.dp))
                            Text(destination.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

        }

        SectionLabel("CPU")
        SettingsCard {
            SettingsRow(
                icon = Icons.Filled.Refresh,
                iconTint = ReapplyIcon,
                title = "应用方式",
                subtitle = state.applyMode.description,
                trailingText = state.applyMode.label,
                onClick = { applyModeExpanded = !applyModeExpanded },
            )
            AnimatedVisibility(visible = applyModeExpanded) {
                Column(modifier = Modifier.padding(bottom = 10.dp)) {
                    ApplyMode.entries.forEach { mode ->
                        OptionRow(
                            label = mode.label,
                            detail = mode.description,
                            selected = state.applyMode == mode,
                            onClick = { onSetApplyMode(mode) },
                        )
                    }

                    if (state.applyMode == ApplyMode.POLLING) {
                        IntervalRow(
                            intervalMs = state.reapplyIntervalMs,
                            onCommit = onSetReapplyInterval,
                        )
                        Text(
                            "厂商的 powerhal / 调度器会自行改写频率节点,单次下发不保证一直有效;" +
                                "轮询会按上面的间隔反复下发。间隔越短越费电,也会和系统的温控策略" +
                                "互相顶撞 —— 2000ms 以上通常够用,不建议低于 500ms。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 2.dp),
                        )
                    }
                }
            }

        }

        SectionLabel("功能")
        SettingsCard {
            SettingsRow(
                icon = Icons.Filled.Build,
                iconTint = ToolsIcon,
                title = "实用工具",
                subtitle = "触控优化、插帧、旋转建议、高温降亮度",
                onClick = onOpenTweaks,
            )
            InsetDivider()
            SettingsRow(
                icon = Icons.Filled.Notifications,
                iconTint = NotifyIcon,
                title = "通知管理",
                subtitle = "按应用管理 Android 原生通知",
                onClick = onOpenNotifications,
            )
        }

        SectionLabel("其他")
        SettingsCard {
            SettingsRow(
                icon = Icons.Filled.Info,
                iconTint = DiagIcon,
                title = "诊断信息",
                subtitle = "Shizuku 授权、PowerHAL、节点探测结果",
                onClick = onOpenDiagnostics,
            )

            InsetDivider()
            SettingsRow(
                icon = Icons.Filled.List,
                iconTint = LogIcon,
                title = "日志记录级别",
                subtitle = state.logLevel.description,
                trailingText = state.logLevel.label,
                onClick = { logLevelExpanded = !logLevelExpanded },
            )
            AnimatedVisibility(visible = logLevelExpanded) {
                Column(modifier = Modifier.padding(bottom = 10.dp)) {
                    LogLevel.entries.forEach { level ->
                        OptionRow(
                            label = level.label,
                            detail = level.description,
                            selected = state.logLevel == level,
                            onClick = { onSetLogLevel(level) },
                        )
                    }
                }
            }

            InsetDivider()
            // Quiet by design: useful when something misbehaves, not part of
            // the normal flow, so it stays a plain row rather than a card.
            SettingsRow(
                icon = Icons.Filled.Info,
                iconTint = LogIcon,
                title = "运行日志",
                subtitle = "查看应用最近的探测与操作记录",
                onClick = onOpenLogs,
            )
        }

        SectionLabel("实验室")
        SettingsCard {
            SettingsRow(
                icon = Icons.Filled.Info,
                iconTint = LabIcon,
                title = "只读面板",
                subtitle = "内核实际在执行哪些限制 —— 不做任何写入",
                onClick = onOpenLab,
            )
        }

        SectionLabel("备份")
        SettingsCard {
            SettingsRow(
                icon = Icons.Filled.Refresh,
                iconTint = BackupIcon,
                title = "导出配置",
                subtitle = "把方案与偏好写成 JSON 文件",
                onClick = { exportLauncher.launch(state.backupFileName) },
            )
            InsetDivider()
            SettingsRow(
                icon = Icons.Filled.Info,
                iconTint = BackupIcon,
                title = "导入配置",
                subtitle = "从 JSON 文件恢复,会覆盖现有方案",
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            )
            state.backupMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 62.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }

        SectionLabel("关于")
        AboutCard()
    }

    state.pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = onCancelImport,
            title = { Text("导入配置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("将用文件里的内容覆盖当前的方案与偏好。")
                    Text(
                        "文件包含 ${pending.profiles.size} 个方案" +
                            (pending.preferences.homeCards?.let { " · ${it.size} 张主页卡片" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "导出时间戳: ${pending.exportedAtEpochMs}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onConfirmImport) { Text("覆盖导入") } },
            dismissButton = { TextButton(onClick = onCancelImport) { Text("取消") } },
        )
    }
}

@Composable
private fun PaletteRow(
    palette: AppPalette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 56.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(palette.accent),
        )
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(palette.accent.copy(alpha = 0.35f)),
        )
        Text(palette.label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Which unit the interval field is showing. Values are always stored as ms. */
private enum class IntervalUnit(val label: String, val millis: Long) {
    MILLIS("毫秒", 1L),
    SECONDS("秒", 1_000L),
}

/**
 * Interval field with a unit picker.
 *
 * Commits on focus loss or IME "done" rather than per keystroke: each commit
 * restarts the re-apply service, so committing as the user types "2000" would
 * restart it four times.
 */
@Composable
private fun IntervalRow(
    intervalMs: Long,
    onCommit: (Long) -> Unit,
) {
    fun format(ms: Long, unit: IntervalUnit): String =
        if (unit == IntervalUnit.MILLIS) ms.toString()
        else (ms.toDouble() / 1000.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }

    var unit by remember { mutableStateOf(IntervalUnit.MILLIS) }
    var text by remember(intervalMs, unit) { mutableStateOf(format(intervalMs, unit)) }

    fun commit() {
        val parsed = text.trim().toDoubleOrNull()
        if (parsed == null || parsed <= 0) {
            text = format(intervalMs, unit)
            return
        }
        onCommit((parsed * unit.millis).toLong())
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 16.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("间隔", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { if (!it.isFocused) commit() },
        )
        SingleChoiceSegmentedButtonRow {
            IntervalUnit.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = unit == entry,
                    onClick = { unit = entry },
                    shape = SegmentedButtonDefaults.itemShape(index, IntervalUnit.entries.size),
                ) { Text(entry.label) }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    detail: String? = null,
    selected: Boolean,
    swatch: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 56.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        if (swatch != null) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(swatch),
            )
        }
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
