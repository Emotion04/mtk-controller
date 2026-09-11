package magicau.mtkcontroller.feature.tweaks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.tweaks.*
import magicau.mtkcontroller.ui.components.*
import magicau.mtkcontroller.ui.components.StatusTone
import magicau.mtkcontroller.ui.text

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TweaksScreen(
    state: TweaksUiState,
    onVivo: (VivoFeature, Boolean, String?, Boolean) -> Unit,
    onCollectReport: () -> Unit,
    onExportReport: (android.net.Uri) -> Unit,
    onDisplay: (Boolean) -> Unit,
    onMaintenance: (String, String) -> Unit,
    onRefreshVivo: () -> Unit,
    onTouch: (Boolean) -> Unit,
    onTouchRate: (Int) -> Unit,
    /** 单个触控子项开关：(子项 id, 是否开启)。 */
    onTouchItem: (String, Boolean) -> Unit = { _, _ -> },
    onTouchNumber: (String, String, Int) -> Unit = { _, _, _ -> },
    /** 进入显示分组时探一次本机有哪些触控项。与 [onEnterGame] 同理，只跑一次。 */
    onEnterDisplay: (() -> Unit)? = null,
    /** 选中了一个本机不支持的性能档位；参数是档位 id。 */
    onVivoUnsupported: (String) -> Unit = {},
    onFrameInterpolation: (Boolean) -> Unit,
    onFrameInterpolationSr: (Boolean) -> Unit,
    onRotationSuggestion: (Boolean) -> Unit,
    onAutoRotate: (Boolean) -> Unit = {},
    onThermalBrightness: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenNotifications: () -> Unit,
    onSetPhaseOffset: (String, Long) -> Unit = { _, _ -> },
    onRestorePhaseOffsets: () -> Unit = {},
    onFakeBattery: (String) -> Unit = {},
    onApplyGameBoost: () -> Unit = {},
    onRestoreGameBoost: () -> Unit = {},
    onSelectGameBoostApp: (String) -> Unit = {},
    onForegroundApp: () -> Unit = {},
    /** 进入"游戏与画面实验"页时调用：先记录相位偏移原值与 boost 状态。 */
    onEnterGame: (() -> Unit)? = null,
    group: String = "display",
    modifier: Modifier = Modifier,
) {
    // key 是 Unit，这个效果只会跑一次，所以它攥着的回调永远是第一次组合那一份。
    // 经 rememberUpdatedState 取，至少保证呼叫的是当前这一份。
    val enterGameNow by rememberUpdatedState(onEnterGame)
    LaunchedEffect(Unit) { enterGameNow?.invoke() }
    val enterDisplayNow by rememberUpdatedState(onEnterDisplay)
    LaunchedEffect(group) { if (group == "display") enterDisplayNow?.invoke() }
    var selectedApp by rememberSaveable { mutableStateOf("") }
    var appPicker by rememberSaveable { mutableStateOf(false) }
    if (appPicker) AlertDialog(onDismissRequest = { appPicker = false }, title = { Text(stringResource(R.string.tweaks_pick_app)) },
        text = { LazyColumn { items(state.apps.size) { index ->
            val (pkg, label) = state.apps[index]
            TextButton(onClick = { selectedApp = pkg; appPicker = false }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        } } }, confirmButton = { TextButton(onClick = { appPicker = false }) { Text(stringResource(R.string.action_cancel)) } })
    var dnsHost by rememberSaveable { mutableStateOf("") }
    var abe by rememberSaveable { mutableStateOf("off") }
    var perfMode by rememberSaveable { mutableStateOf("enhanced") }
    var frameRate by rememberSaveable { mutableStateOf("120") }
    var chargeLimit by rememberSaveable { mutableStateOf("") }
    var touchAdjust by rememberSaveable { mutableStateOf("") }
    // 确认文案在 lambda 外面取：onClick 不是 @Composable 上下文。
    val confirmDirectPower = stringResource(R.string.confirm_direct_power)
    val confirmChargeLimit = stringResource(R.string.confirm_charge_limit)
    // 同样是给非 composable 的 lambda 用的：`LazyColumn` 的 content 与
    // `onCheckedChange` 都取不到资源。
    val maxRefreshTitle = stringResource(R.string.tweaks_max_refresh)
    val maxRefreshConfirm = stringResource(R.string.tweaks_max_refresh_confirm)
    val brightnessTitle = stringResource(R.string.tweaks_brightness_title)
    val brightnessConfirm = stringResource(R.string.tweaks_brightness_confirm)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingTitle by rememberSaveable { mutableStateOf("") }
    var pendingInfo by rememberSaveable { mutableStateOf("") }
    fun explainThenApply(title: String, info: String, action: () -> Unit) {
        pendingTitle = title; pendingInfo = info; pending = action
    }
    if (pending != null) AlertDialog(
        onDismissRequest = { pending = null }, title = { Text(pendingTitle) },
        text = { Text(pendingInfo) },
        confirmButton = {
            TextButton(onClick = { val action = pending; pending = null; action?.invoke() }) {
                Text(stringResource(R.string.action_enable))
            }
        },
        dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (state.loading) { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            InfoHeading(stringResource(R.string.tweaks_intro_title), stringResource(R.string.tweaks_intro))
            if (!state.privilege.mode.canElevate) {
                Button(onClick = onRequestPermission) { Text(stringResource(R.string.action_authorize_shizuku)) }
            } else {
                TextButton(onClick = onRefreshVivo, enabled = state.canRun) {
                    Text(stringResource(R.string.tweaks_refresh_support))
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.message?.let { message -> item {
            SettingsCard { Column(Modifier.padding(horizontal = 16.dp)) {
                // 摘要留在外面，明细（键名、回读值）收进 ExpandableDetails。
                // 直接铺开会让真正的结论被满屏技术细节盖掉。
                InfoHeading(
                    stringResource(if (state.lastSuccess) R.string.tweaks_op_done else R.string.tweaks_op_failed),
                    message.summary.text(),
                )
                ExpandableDetails(state.lastFailures)
            } }
        } }
        if (group == "display") {
        item { SectionLabel(stringResource(R.string.tweaks_section_display)) }
        item {
            ToolCard {
                // 总开关由逐项**设备读数**汇总，不再看本地集合。
                ToggleRow(stringResource(R.string.tweaks_touch_title), checked = state.touchOn == true,
                    onCheckedChange = onTouch,
                    // 读不到也允许操作：没有回读不等于不能下发（这是本项目的既有规矩）。
                    enabled = state.canRun,
                    info = stringResource(R.string.touch_info),
                    unknown = state.touchOn == null)
                Text(stringResource(state.touchBackend.labelRes), style = MaterialTheme.typography.bodySmall)
                // 档位芯片之前被 `touchBackend == LEGACY` 挡住，用 vivo 后端的用户根本看不到、
                // 也调不了。两个后端都有档位可言，所以只看是否已开启。
                if (TouchItems.REPORT_RATE in state.touchItems) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TweakCommands.TOUCH_RATES.forEach { rate -> FilterChip(selected = state.touchRate == rate,
                        onClick = { onTouchRate(rate) }, enabled = state.canRun, label = { Text("${rate}Hz") }) }
                }
                InsetDivider()
                SectionLabel(stringResource(R.string.touch_items_heading))
                // 逐项开关：只有人能判断哪一项真的让操作变跟手，整族一起写等于没测。
                // 数据全在 TouchItems 里，这里只渲染，不写分支。
                // 固件触控方案是一次原子服务调用，没有逐项可言 —— 置灰并说明，别让它
                // 看起来能点。切回默认方案即可逐项控制。
                val perItem = state.canRun && state.touchBackend != TouchBackend.VIVO
                state.touchItemsById.forEach { (item, on, _) ->
                    TouchItemRow(item, on, perItem, onTouchItem, onTouchNumber,
                        knobValues = item.knobs.mapNotNull { knob ->
                            state.touchReadout["${item.id}|${knob.key}"]?.let { knob to it }
                        }.toMap(),
                        knobNumbers = state.touchNumbers,
                    )
                }
                if (!perItem) Text(stringResource(R.string.touch_items_backend_note),
                    style = MaterialTheme.typography.bodySmall)
                TouchChangeSummary(state)
            }
        }
        item {
            ToolCard {
                ToggleRow(maxRefreshTitle,
                    checked = state.session.fps > 0,
                    enabled = state.canRun && (state.displaySupported || state.session.fps > 0),
                    chipText = stringResource(R.string.chip_experimental), chipTone = StatusTone.Warn,
                    info = stringResource(R.string.tweaks_max_refresh_info),
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            explainThenApply(maxRefreshTitle, maxRefreshConfirm) { onDisplay(true) }
                        } else {
                            onDisplay(false)
                        }
                    },
                )
                Text(
                    if (state.displaySupported) {
                        stringResource(R.string.tweaks_display_hz, state.displayHz, state.maxDisplayHz)
                    } else {
                        stringResource(R.string.tweaks_display_unavailable)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!state.displaySupported) {
                    InfoHeading(
                        stringResource(R.string.tweaks_compat_info),
                        state.displayStatus.ifBlank { stringResource(R.string.tweaks_not_detected) },
                    )
                }
                // 会话消息为 null = 还没跑过；fps > 0 但还没写消息时仍显示"未运行"，
                // 和以前一致。
                val sessionMessage = state.session.message
                if (state.session.fps > 0 || sessionMessage != null) {
                    Text(
                        sessionMessage?.text() ?: stringResource(R.string.display_session_idle),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            ToolCard {
                ToggleRow(brightnessTitle,
                    // 读服务/设备，不读偏好：进程被杀之后偏好还是 true，而守护早就停了。
                    checked = state.brightnessOn == true,
                    enabled = state.canRun,
                    unknown = state.brightnessOn == null,
                    subtitle = stringResource(
                        R.string.tweaks_brightness_current,
                        stringResource(state.brightnessBackend.labelRes),
                    ),
                    info = stringResource(R.string.tweaks_brightness_info),
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            explainThenApply(brightnessTitle, brightnessConfirm) { onThermalBrightness(true) }
                        } else {
                            onThermalBrightness(false)
                        }
                    },
                )
            }
        }
        item { ToolCard {
            ToggleRow(stringResource(R.string.tweak_frame_interpolation), checked = state.frameInterpolation, onCheckedChange = onFrameInterpolation,
                enabled = state.canRun,
                info = stringResource(R.string.tweaks_frame_interp_info))
            ToggleRow(stringResource(R.string.tweak_frame_interpolation_sr), checked = state.frameInterpolationSr, onCheckedChange = onFrameInterpolationSr,
                enabled = state.canRun,
                info = stringResource(R.string.tweaks_frame_interp_sr_info))
            ToggleRow(stringResource(R.string.tweak_rotation_suggestion), checked = state.rotationSuggestion, onCheckedChange = onRotationSuggestion,
                enabled = state.canRun,
                info = stringResource(R.string.tweaks_rotation_info))
            // 自动旋转以前只同步不显示：读回来一个值却没有地方用它。
            ToggleRow(stringResource(R.string.tweak_auto_rotate), checked = state.autoRotate, onCheckedChange = onAutoRotate,
                enabled = state.canRun)
        } }
        }
        if (group == "config") {
        item { SectionLabel(stringResource(R.string.tweaks_section_animation)) }
        item { SettingsCard {
            listOf(VivoFeature.WINDOW_ANIMATION, VivoFeature.TRANSITION_ANIMATION, VivoFeature.ANIMATION).forEachIndexed { index, feature ->
                if (index > 0) InsetDivider()
                AnimationSetting(feature, state, onVivo)
            }
        } }
        item { SectionLabel(stringResource(R.string.tweaks_section_restorable)) }
        VivoFeature.entries.filter { it.lifetime != TweakLifetime.TIMED && !it.isAnimation && it !in setOf(VivoFeature.BRIGHTNESS, VivoFeature.AI) }.forEach { feature -> item {
            ToolCard {
                val label = feature.labelText()
                val badge = feature.evidenceOrNull()
                // 下面那个 onClick 不是 composable 上下文，确认文案在这里取好。
                val dnsTitle = stringResource(R.string.vivo_feature_dns)
                val dnsConfirm = stringResource(R.string.tweaks_dns_confirm)
                val perfTitle = stringResource(R.string.vivo_feature_performance_mode)
                val perfConfirm = stringResource(R.string.tweaks_perf_mode_confirm)
                val directPowerTitle = stringResource(R.string.vivo_feature_direct_power)
                val chargeLimitTitle = stringResource(R.string.vivo_feature_charge_limit)
                val blocked = state.vivoUnsupported[feature]
                InfoHeading(label,
                    feature.detailText() + "\n" + stringResource(R.string.vivo_hint_restore),
                    chipText = badge?.let { stringResource(it.labelRes) },
                    chipTone = badge?.tone() ?: StatusTone.Neutral)
                // 本机不支持的项保留开关但置灰，并说清是哪一条没过。
                // 完全不显示会让人以为这些功能不存在，实际只是这台机器没有。
                Text(blocked ?: (state.vivoStatus[feature] ?: stringResource(R.string.tweaks_not_detected)), style = MaterialTheme.typography.bodySmall,
                    color = if (blocked != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (blocked == null && feature in state.vivoSupported) {
                    when (feature) {
                        VivoFeature.DNS -> OutlinedTextField(value = dnsHost, onValueChange = { dnsHost = it }, enabled = state.canRun,
                            label = { Text(stringResource(R.string.tweaks_dns_host)) }, placeholder = { Text("dns.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        VivoFeature.CHARGE_LIMIT -> OutlinedTextField(value = chargeLimit, onValueChange = { chargeLimit = it.filter(Char::isDigit).take(3) },
                            enabled = state.canRun, label = { Text(stringResource(R.string.vivo_feature_charge_limit)) }, placeholder = { Text("80") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        // 默认带出设备当前值 —— 这组数字随机型而不同，让用户凭空猜不合理。
                        VivoFeature.TOUCH_BOOST -> OutlinedTextField(
                            value = touchAdjust.ifBlank { state.vivoValues["system vts_game_para_adjust"].orEmpty() },
                            onValueChange = { touchAdjust = it.filter { c -> c.isDigit() || c == ',' }.take(24) },
                            enabled = state.canRun, label = { Text(stringResource(R.string.vivo_feature_touch_boost_label)) },
                            placeholder = { Text("1,6,6,6") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        VivoFeature.ABE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "off" to stringResource(R.string.vivo_abe_restricted),
                                "balanced" to stringResource(R.string.vivo_abe_balanced),
                            ).forEach { (value, label) -> FilterChip(selected = abe == value, onClick = { abe = value }, enabled = state.canRun, label = { Text(label) }) }
                        }
                        // 档位编号来自固件实测，不是公开语义；只列数据里有的，不虚构别的。
                        // 本机缺键的档位置灰（这台 PD2430A 上没有 monster_key_start），
                        // 不支持的档位看得见但点不了 —— 全抹掉会让人以为没有这些档。
                        VivoFeature.PERFORMANCE_MODE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TweakValues.PERF_LEVELS.forEach { level ->
                                val ok = level.supported(state.perfAvailableKeys)
                                FilterChip(selected = perfMode == level.id,
                                    onClick = { perfMode = level.id },
                                    enabled = state.canRun && ok,
                                    label = { Text(stringResource(level.labelRes)) })
                            }
                        }
                        VivoFeature.FRAME_UNLOCK -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("60" to "60", "90" to "90", "120" to "120").forEach { (value, label) ->
                                FilterChip(selected = frameRate == value, onClick = { frameRate = value }, enabled = state.canRun, label = { Text(label) })
                            }
                        }
                        else -> Unit
                    }
                    if (feature == VivoFeature.PERFORMANCE_MODE) {
                        Text(
                            stringResource(R.string.tweaks_perf_mode_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (feature == VivoFeature.FRAME_UNLOCK) {
                        Text(
                            stringResource(R.string.tweaks_frame_unlock_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (feature in state.vivoSupported) Button(enabled = state.canRun && when (feature) {
                        VivoFeature.DNS -> VivoTweaks.validDnsHost(dnsHost)
                        VivoFeature.CHARGE_LIMIT -> TweakValues.chargeLimit(chargeLimit) != null
                        VivoFeature.TOUCH_BOOST -> TweakValues.touchAdjust(
                            touchAdjust.ifBlank { state.vivoValues["system vts_game_para_adjust"] }) != null
                        else -> true
                    }, onClick = {
                        val option = when (feature) {
                            VivoFeature.DNS -> dnsHost; VivoFeature.ABE -> abe; VivoFeature.PERFORMANCE_MODE -> perfMode
                            VivoFeature.FRAME_UNLOCK -> frameRate; VivoFeature.CHARGE_LIMIT -> chargeLimit
                            VivoFeature.TOUCH_BOOST -> touchAdjust.ifBlank { state.vivoValues["system vts_game_para_adjust"] }
                            else -> null
                        }
                        // 档位置灰之后它仍可能被选中（FilterChip 的选中态不会自动让位）。
                        // 在这里拦住并说清原因，别静默发一个注定失败的请求。
                        if (feature == VivoFeature.PERFORMANCE_MODE &&
                            state.perfAvailableKeys.isNotEmpty() &&
                            TweakValues.perfLevel(perfMode)?.supported(state.perfAvailableKeys) != true
                        ) {
                            onVivoUnsupported(perfMode)
                        } else if (feature == VivoFeature.DNS) {
                            explainThenApply(dnsTitle, dnsConfirm) { onVivo(feature, false, option, false) }
                        } else if (feature == VivoFeature.PERFORMANCE_MODE) {
                            explainThenApply(perfTitle, perfConfirm) { onVivo(feature, false, option, false) }
                        }
                        // 这两个直接影响充电行为：直驱会让电池停止充电，上限填错会让电充不满。
                        else if (feature == VivoFeature.DIRECT_POWER) {
                            explainThenApply(directPowerTitle, confirmDirectPower) {
                                onVivo(feature, false, option, false)
                            }
                        } else if (feature == VivoFeature.CHARGE_LIMIT) {
                            explainThenApply(chargeLimitTitle, confirmChargeLimit) {
                                onVivo(feature, false, option, false)
                            }
                        } else {
                            onVivo(feature, false, option, false)
                        }
                    }) { Text(stringResource(R.string.action_apply)) }
                    if (feature in state.vivoSnapshots) TextButton(
                        onClick = { onVivo(feature, true, null, feature.name in state.pendingForceRestore) },
                        enabled = state.canRun,
                    ) {
                        Text(
                            if (feature.name in state.pendingForceRestore) {
                                stringResource(R.string.tweaks_restore_anyway)
                            } else {
                                stringResource(R.string.action_restore_original)
                            },
                        )
                    }
                }
            }
        } }
        }
        if (group == "maintenance") item { ToolCard {
            InfoHeading(stringResource(R.string.maintenance_channel), stringResource(R.string.maintenance_info))
            TextButton(onClick = { appPicker = true }, enabled = !state.task.running) {
                Text(state.apps.firstOrNull { it.first == selectedApp }?.second ?: stringResource(R.string.tweaks_pick_app))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onMaintenance("compile", selectedApp) }, enabled = state.canRun && !state.task.running && selectedApp.isNotEmpty()) {
                    Text(stringResource(R.string.tweaks_compile))
                }
                TextButton(onClick = { onMaintenance("warm", selectedApp) }, enabled = state.canRun && !state.task.running && selectedApp.isNotEmpty()) {
                    Text(stringResource(R.string.tweaks_warm_charge))
                }
            }
            // 系统自己也会编译，重复编译不损坏但会白干。按下去之前先看一眼现状。
            var compileState by remember { mutableStateOf<magicau.mtkcontroller.data.privilege.CompileStatus.State?>(null) }
            LaunchedEffect(selectedApp) {
                compileState = if (selectedApp.isEmpty()) null else withContext(Dispatchers.IO) {
                    runCatching { magicau.mtkcontroller.data.privilege.CompileStatus.check(selectedApp) }.getOrNull()
                }
            }
            compileState?.let { cs ->
                InfoHeading(when {
                    !cs.hasCompiledArtifact -> stringResource(R.string.compile_status_none)
                    cs.stale -> stringResource(R.string.compile_status_stale)
                    cs.compiledAfterUpdate -> stringResource(R.string.compile_status_ok)
                    else -> stringResource(R.string.compile_status_unknown)
                }, cs.note.text())
            }
            if (state.task.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                // 编译原本是个 120 秒黑盒。这里做一次只读快照，让用户知道现在大概在干什么；
                // 成功与否仍以 cmd 的返回为准，进度只是旁证。
                var progress by remember { mutableStateOf<magicau.mtkcontroller.data.privilege.CompileProgress.Snapshot?>(null) }
                LaunchedEffect(selectedApp) {
                    while (true) {
                        progress = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            magicau.mtkcontroller.data.privilege.CompileProgress.snapshot(selectedApp)
                        }
                        kotlinx.coroutines.delay(3000)
                    }
                }
                progress?.let { p ->
                    InfoHeading(
                        stringResource(R.string.tweaks_compile_progress),
                        p.note.text() + p.odexSizeKb?.let { stringResource(R.string.tweaks_compile_odex, it / 1024) }.orEmpty(),
                    )
                }
            }
            InfoHeading(
                when {
                    state.task.running -> stringResource(R.string.tweaks_task_running)
                    state.task.ok == false -> stringResource(R.string.tweaks_task_failed)
                    else -> stringResource(R.string.tweaks_task_status)
                },
                state.task.message.text(),
            )
        } }

        // --- 游戏与画面实验 ---------------------------------------------------
        // 这一页的东西都不改 CPU 频率，也不持有任何 PowerHAL 句柄，所以和 CpuControl
        // 铁律不冲突。每一项都自带读写回与快照。
        if (group == "game") item { ToolCard {
            InfoHeading(stringResource(R.string.tweaks_foreground_title), stringResource(R.string.tweaks_foreground_sub))
            Text(
                if (state.foregroundApp.isNotBlank()) state.foregroundApp
                else state.foregroundError?.let { stringResource(R.string.tweaks_read_failed, it) }
                    ?: stringResource(R.string.tweaks_not_read),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { onForegroundApp() }, enabled = state.canRun) {
                Text(stringResource(R.string.tweaks_read_foreground))
            }
        } }

        if (group == "game") item { ToolCard {
            InfoHeading(stringResource(R.string.tweaks_game_boost_title),
                stringResource(R.string.game_boost_info) + "\n" + stringResource(R.string.game_boost_driver_caveat))
            TextButton(onClick = { appPicker = true }, enabled = state.canRun) {
                Text(state.apps.firstOrNull { it.first == state.gameBoostSelected }?.second ?: stringResource(R.string.tweaks_pick_app))
            }
            if (state.gameBoostStatus.isNotBlank()) Text(state.gameBoostStatus, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onApplyGameBoost() }, enabled = state.canRun && state.gameBoostSelected.isNotEmpty()) {
                    Text(stringResource(R.string.tweaks_boost_apply))
                }
                if (state.gameBoostActive) TextButton(onClick = { onRestoreGameBoost() }, enabled = state.canRun) {
                    Text(stringResource(R.string.tweaks_boost_restore))
                }
            }
            if (state.gameBoostActive) {
                Text(
                    stringResource(R.string.tweaks_boost_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } }

        if (group == "game") item { ToolCard {
            InfoHeading(
                stringResource(R.string.tweaks_phase_title),
                stringResource(R.string.tweaks_phase_info),
            )
            for (key in magicau.mtkcontroller.data.tweaks.PhaseOffset.keys) {
                Text(
                    stringResource(
                        R.string.phase_offset_current,
                        stringResource(key.labelRes),
                        state.phaseCurrent[key.prop]?.ifBlank { stringResource(R.string.value_empty) }
                            ?: stringResource(R.string.value_unreadable),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            for (key in magicau.mtkcontroller.data.tweaks.PhaseOffset.keys) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf(100_000L to "100µs", 250_000L to "250µs", 500_000L to "500µs", 2_000_000L to "2ms", 6_000_000L to "6ms").forEach { (ns, label) ->
                        AssistChip(onClick = { onSetPhaseOffset(key.prop, ns) }, enabled = state.canRun,
                            label = { Text(stringResource(R.string.phase_offset_chip, stringResource(key.labelRes), label)) })
                    }
                    TextButton(onClick = { onSetPhaseOffset(key.prop, 0) }, enabled = state.canRun) {
                        Text(stringResource(R.string.phase_offset_zero, stringResource(key.labelRes)))
                    }
                }
            }
            Text(
                stringResource(R.string.tweaks_phase_zero_note),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { onRestorePhaseOffsets() }, enabled = state.canRun &&
                (state.phaseHasOriginal || state.phaseMessage.isNotBlank())) {
                Text(stringResource(R.string.tweaks_phase_restore))
            }
            if (state.phaseMessage.isNotBlank()) Text(state.phaseMessage, style = MaterialTheme.typography.bodySmall,
                color = if (state.phaseSuccess) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        } }

        if (group == "game") item { ToolCard {
            InfoHeading(
                stringResource(R.string.tweaks_fake_title),
                stringResource(R.string.tweaks_fake_info),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onFakeBattery("charge") }, enabled = state.canRun) {
                    Text(stringResource(R.string.tweaks_fake_charge))
                }
                Button(onClick = { onFakeBattery("discharge") }, enabled = state.canRun) {
                    Text(stringResource(R.string.tweaks_fake_discharge))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 15, 50).forEach { level ->
                    AssistChip(
                        onClick = { onFakeBattery("level:$level") },
                        enabled = state.canRun,
                        label = { Text(stringResource(R.string.tweaks_fake_level, level)) },
                    )
                }
                TextButton(onClick = { onFakeBattery("reset") }, enabled = state.canRun) {
                    Text(stringResource(R.string.tweaks_fake_reset))
                }
            }
            Text(
                stringResource(R.string.tweaks_fake_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } }
        item { TweakReportActions(state, onCollectReport, onExportReport) }
        if (group in listOf("display", "config", "maintenance")) item {
            SectionLabel(stringResource(R.string.tweaks_section_experimental))
            SettingsCard {
                val unavailable = stringResource(R.string.tweaks_na_unavailable)
                when (group) {
                    "display" -> {
                        UnavailableSetting(stringResource(R.string.tweaks_na_brightness_callback), unavailable)
                        InsetDivider()
                        UnavailableSetting(stringResource(R.string.tweaks_na_touch_rate), unavailable)
                        InsetDivider()
                        UnavailableSetting(stringResource(R.string.tweaks_na_memc), unavailable)
                    }
                    "config" -> {
                        UnavailableSetting(stringResource(R.string.tweaks_na_perf_mode), unavailable)
                        InsetDivider()
                        UnavailableSetting(stringResource(R.string.tweaks_na_benchmark), unavailable)
                        InsetDivider()
                        UnavailableSetting(
                            stringResource(R.string.tweaks_na_bypass_charge),
                            stringResource(R.string.tweaks_na_bypass_note),
                        )
                        InsetDivider()
                        UnavailableSetting(
                            stringResource(R.string.tweaks_na_dns_filter),
                            stringResource(R.string.tweaks_na_dns_note),
                        )
                    }
                    "maintenance" -> {
                        UnavailableSetting(stringResource(R.string.tweaks_na_full_compile), unavailable)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(content: @Composable ColumnScope.() -> Unit) {
    SettingsCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

/**
 * 一个触控子项的开关。文案和证据等级都从数据来，这里不加分支。
 *
 * 证据徽标只在"不是有系统读数"时挂 —— 全都标反而显得每项都不可信。
 */
@Composable
private fun TouchItemRow(
    item: TouchItem,
    /** 设备上的真实状态；null = 读不到。 */
    on: Boolean?,
    enabled: Boolean,
    onChange: (String, Boolean) -> Unit,
    onNumber: (String, String, Int) -> Unit,
    /**
     * 要显示的旋钮当前值，键是旋钮本身。
     *
     * 键必须是旋钮而不是 item.id —— 按下防抖有四个键，一个值显示不出来。
     * ViewModel 那边按 `"itemId|旋钮键名"` 存，取的时候要对上，
     * 否则两边类型都是 Map<String, String>，编译不报错，但值永远取不到。
     */
    knobValues: Map<TouchKnob, String>,
    /** 用户填过的数，键为 `"<itemId>|<旋钮键名>"`。 */
    knobNumbers: Map<String, Int>,
) {
    val label = stringResource(item.labelRes)
    val detail = buildString {
        append(stringResource(item.detailRes))
        append('\n')
        append(
            when (on) {
                true -> stringResource(R.string.touch_item_state_on)
                false -> stringResource(R.string.touch_item_state_off)
                null -> stringResource(R.string.touch_state_unknown)
            },
        )
        if (item.evidence != Evidence.CONFIRMED) {
            append("，")
            append(stringResource(item.evidence.labelRes))
        }
    }
    // 只读项：不画开关，只报当前值。画开关会让人以为能改，而它写了也是同一个值。
    if (item.readOnly) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                ValueLines(item, knobValues)
            }
            FeatureInfo(label, detail)
        }
        return
    }
    Column {
        // 读不到只影响"显示什么"，不影响"能不能写" —— 没有回读不等于不能下发。
        // 开关照旧可点，旁边写明"未读"，不把未知画成"关"。
        ToggleRow(label, checked = on == true, onCheckedChange = { onChange(item.id, it) },
            enabled = enabled, info = detail, unknown = on == null)
        // 带数值的项：每个键一个可填的框。开关只决定"这一族用不用"，
        // 具体写多少由这里决定 —— 出厂预设只是起点，不是唯一选项。
        NumericFields(item, enabled, on == true, knobNumbers, knobValues) { knob, value ->
            onNumber(item.id, knob.key, value)
        }
        // 取值来自外部记录、语义未公开的项，把当前值摊开 —— 那是判断
        // "这台机器是什么配置"的唯一依据，也让人看得出开关是不是空动作。
        if (item.showsValues && item.numericKnobs.isEmpty()) ValueLines(item, knobValues)
    }
}

/**
 * 数值项的可调控件：滑杆粗调 + 输入框精调。
 *
 * 两件工具各管一半：滑杆适合"往左拉一点试试"这种**手感驱动的试**，输入框适合
 * "我就是要 250"这种**知道自己要什么**的填。滑杆单独用在对不准的毫秒数上会让人
 * 一直差几毫秒，输入框单独用则每次都要呼键盘、先删再打。
 *
 * 输入框不合法（空、超范围）时滑杆保持上一个有效值，**不写设备、不悄悄夹取** ——
 * 夹取会让人以为自己设的那个数已经生效了。
 */
@Composable
private fun NumericFields(
    item: TouchItem,
    enabled: Boolean,
    on: Boolean,
    numbers: Map<String, Int>,
    values: Map<TouchKnob, String>,
    onEdit: (TouchKnob, Int) -> Unit,
) {
    item.numericKnobs.forEach { knob ->
        val range = knob.numberRange ?: return@forEach
        val saved = numbers["${item.id}|${knob.key}"]
        var text by rememberSaveable(item.id, knob.key, saved) {
            mutableStateOf((saved ?: knob.onValue).toString())
        }
        val current = values[knob]
        // 滑杆的位置：框里是有效数就跟着框走，否则停在上一个有效值。
        val valid = text.toIntOrNull()?.takeIf { it in range } ?: saved ?: knob.onValue.toIntOrNull() ?: range.first
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(knob.key, style = MaterialTheme.typography.bodySmall)
                    // 设备上现在是多少。和框里的数不同就说明还没写进去。
                    Text(
                        stringResource(R.string.touch_number_current, current ?: "—"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter { it.isDigit() }.take(4)
                        text.toIntOrNull()?.let { if (it in range) onEdit(knob, it) }
                    },
                    enabled = enabled && on,
                    singleLine = true,
                    isError = text.toIntOrNull()?.let { it !in range } ?: true,
                    supportingText = {
                        Text(stringResource(R.string.touch_number_range, range.first, range.last))
                    },
                    modifier = Modifier.width(120.dp),
                )
            }
            Slider(
                value = valid.toFloat(),
                onValueChange = { raw ->
                    val next = raw.roundToInt()
                    text = next.toString()
                    if (next != valid) onEdit(knob, next)
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                // 每单位一格：0–1000 的毫秒在滑杆上要能精确停到整数，
                // 不给 steps 时 Compose 按像素吸附，拖到哪算哪。
                steps = (range.last - range.first - 1).coerceAtLeast(0),
                enabled = enabled && on,
            )
        }
    }
}

/** 逐旋钮显示当前值。读不到的旋钮直接不列，不用 0 或空白冒充。 */
@Composable
private fun ValueLines(item: TouchItem, knobValues: Map<TouchKnob, String>) {
    val shown = item.knobs.mapNotNull { knob -> knobValues[knob]?.let { knob to it } }
    if (shown.isEmpty()) {
        Text(stringResource(R.string.touch_value_unreadable), style = MaterialTheme.typography.bodySmall)
        return
    }
    // 四个键一行写完：每个键一行时，按下防抖下面会挂四行键名，看着像调试输出。
    // 键名仍是键名（用户要拿去比对手册或别的机器），但排在值后面、小一号，不抢正文。
    Text(
        shown.joinToString("   ") { (knob, value) -> "${knob.key} $value" },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 这一次开关实际改了什么。
 *
 * 写后回读只证明"写进去了"，证明不了"系统理不理睬" —— 人能判断的输入正是前后值。
 * 按子项分组列出，一个旋钮都没动的项明确说"本机已是目标值"，
 * 别让它看起来像试过了 —— 那会让人把"没变化"记成"这项无效"。
 */
@Composable
private fun TouchChangeSummary(state: TweaksUiState) {
    val changes = state.touchChanges
    if (changes.isEmpty()) return
    val labelOf = TouchItems.all(state.touchRate).associate { it.id to it.labelRes }
    InsetDivider()
    SectionLabel(stringResource(R.string.touch_change_heading))
    Text(stringResource(R.string.touch_change_hint), style = MaterialTheme.typography.bodySmall)
    val moved = changes.count { it.changed }
    Text(
        if (moved > 0) stringResource(R.string.touch_change_moved, moved)
        else stringResource(R.string.touch_change_none),
        style = MaterialTheme.typography.bodySmall,
        color = if (moved > 0) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // 按子项分组，顺序跟着 TouchItems —— 不按 changes 的顺序，否则每次点完顺序都跳。
    TouchItems.all(state.touchRate).forEach { item ->
        val mine = changes.filter { it.itemId == item.id }
        if (mine.isEmpty()) return@forEach
        Text(stringResource(labelOf.getValue(item.id)), style = MaterialTheme.typography.labelSmall)
        if (mine.none { it.changed }) {
            Text(
                stringResource(R.string.touch_change_noop),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // 键名、旧值、新值都是内部标识符（`debug.hwui.disable_vsync：false → true`），
    // 铺在主界面上就是拿日志当界面用。主界面只说"改了哪几项"，明细点开才看。
    ExpandableDetails(
        changes.filter { it.changed }.map { change ->
            "${change.key}：${change.before.ifEmpty { "空" }} → ${change.after.ifEmpty { "空" }}"
        },
    )
}
