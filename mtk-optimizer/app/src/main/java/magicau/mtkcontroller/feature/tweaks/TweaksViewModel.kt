package magicau.mtkcontroller.feature.tweaks

import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.data.tweaks.TweakRepository

import magicau.mtkcontroller.data.tweaks.TweakLifetime
import magicau.mtkcontroller.data.tweaks.TweakValues
import magicau.mtkcontroller.data.tweaks.VivoFeature

import magicau.mtkcontroller.data.tweaks.BrightnessBackend
import magicau.mtkcontroller.data.tweaks.TouchBackend
import magicau.mtkcontroller.data.settings.SettingChannel
import magicau.mtkcontroller.data.tweaks.TouchItem
import magicau.mtkcontroller.data.tweaks.TouchNs
import magicau.mtkcontroller.data.tweaks.TouchItems
import magicau.mtkcontroller.data.tweaks.Evidence

import kotlin.math.roundToInt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.BuildConfig
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.privilege.PrivilegeState
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.feature.thermal.ThermalBrightnessService

data class TweaksUiState(
    val experimentFeature: VivoFeature? = null,
    val experimentRunning: Boolean = false,
    val experimentSeconds: Int = 0,
    val experimentMessage: String? = null,
    val experimentSuccess: Boolean = true,
    val smartInitial: magicau.mtkcontroller.data.tweaks.SmartBackup? = null,
    val smartImported: magicau.mtkcontroller.data.tweaks.SmartBackup? = null,
    val smartPrevious: magicau.mtkcontroller.data.tweaks.SmartBackup? = null,
    val smartInitialNotice: Boolean = false,
    val smartReminder: Boolean = true,
    val smartReadAt: Long = 0,
    val smartValues: Map<String, String> = emptyMap(),
    val smartReport: String = "",
    val tweakReport: String = "",
    val vivoValues: Map<String, String?> = emptyMap(),
    val apps: List<Pair<String, String>> = emptyList(),
    val task: MaintenanceState = MaintenanceState(),
    val brightnessBackend: magicau.mtkcontroller.data.tweaks.BrightnessBackend = magicau.mtkcontroller.data.tweaks.BrightnessBackend.LEGACY,
    val displaySupported: Boolean = false,
    /** 探测前的占位。界面显示时按语言取资源，这里只是"还没测"的标记。 */
    val displayStatus: String = "",
    val maxDisplayHz: Int = 0,
    val displayHz: Int = 0,
    val session: DisplaySessionState = DisplaySessionState(),
    val touchBackend: TouchBackend = TouchBackend.LEGACY,
    val vivoStatus: Map<VivoFeature, String> = emptyMap(),
    val vivoSupported: Set<VivoFeature> = emptySet(),
    /**
     * 本机不支持的功能 → 原因。开关保留但置灰，说明是哪一条没过。
     *
     * 不支持的项不从界面抹掉：用户需要知道这些功能存在、只是这台机器没有。
     */
    val vivoUnsupported: Map<VivoFeature, String> = emptyMap(),
    val vivoSnapshots: Set<VivoFeature> = emptySet(),
    /** 恢复被"期间系统改过"拦住的功能名；再点一次恢复就带 force 放行。 */
    val pendingForceRestore: Set<String> = emptySet(),
    // --- 游戏与画面实验 ---
    val phaseCurrent: Map<String, String?> = emptyMap(),
    /** 有没有可恢复的原值记录（持久化在 RecoverySnapshot 里）。 */
    val phaseHasOriginal: Boolean = false,
    val phaseMessage: String = "",
    val phaseSuccess: Boolean = true,
    val gameBoostSelected: String = "",
    val gameBoostActive: Boolean = false,
    val gameBoostStatus: String = "",
    val foregroundApp: String = "",
    val foregroundError: String? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val privilege: PrivilegeState = PrivilegeState(),
    /** 已开启的触控子项 id。总开关只图方便，逐项开关才是真正用来判生效的。 */
    val touchItems: Set<String> = emptySet(),

    /**
     * 最近一次触控开关后，每个旋钮的实际前后值。
     *
     * 写后回读只能证明"写进去了"，证明不了"系统理不理睬"。人能判断的输入正是前后值 ——
     * 看到哪些真的动了，才知道该去感受哪儿。原先这两个值读出来就扔了。
     */
    val touchChanges: List<TweakRepository.KnobChange> = emptyList(),
    val touchRate: Int = 360,

    /**
     * 本机实际存在的性能模式键。决定哪几档可点 —— 这台 PD2430A 没有
     * `monster_key_start`，Monster 两档据此置灰；别的机型键存在就照常可点。
     *
     * 空集表示还没探测过。此时不据此置灰任何档位，别让界面刚进来就全灰。
     */
    val perfAvailableKeys: Set<String> = emptySet(),

    /**
     * 只读触控项的当前值，键是 TouchItem.id。
     *
     * 存在这里而不是界面上现读：composable 里做 IO 会在每次重组时重复读一遍，
     * 而这些值只在进页面和刷新时变。
     */
    val touchReadout: Map<String, String> = emptyMap(),
    /**
     * 每个触控项在**设备上**的状态，`true` 开、`false` 关、`null` 读不到。
     *
     * 开关显示的是它，不是本地 `touchItems` —— 本地记的是"上次点了什么"，
     * 用户或系统把键改掉之后就和设备分叉了。读不到显示"未读"，不显示成关。
     */
    val touchDevice: Map<String, Boolean?> = emptyMap(),
    val touchDeviceProbed: Boolean = false,
    /** 亮度守护的服务是否真的在跑（LEGACY 后端）。 */
    val brightnessRunning: Boolean = false,
    /** 亮度守护在设备上的状态（VIVO 后端读 `vivo_lcm_brightness_policy_disable`）。 */
    val brightnessDevice: Boolean? = null,
    /**
     * 用户给数值项填过的值，键为 `"<itemId>|<旋钮键名>"`。
     *
     * 和 [touchReadout] 分开：读数是我们从系统读回来的，这里是用户填的。
     * 合在一起会让"字段里显示的是我的设置还是设备的现状"分不清。
     */
    val touchNumbers: Map<String, Int> = emptyMap(),
    /**
     * 本机**读得到**的触控项。null = 还没探完，界面先按全部列出。
     *
     * 整项的键一个都读不回 `null` 以外的结果时，这一项在本机没有着落，
     * 写进去只会凭空建一个没人读的键 —— 那种项不列。
     */
    val touchPresent: Set<String>? = null,
    val frameInterpolation: Boolean = false,
    val frameInterpolationSr: Boolean = false,
    val rotationSuggestion: Boolean = false,
    /** 自动旋转；和旋转建议一样，每次进页面从设备读。 */
    val autoRotate: Boolean = false,
    val thermalBrightness: Boolean = false,
    val lastFailures: List<String> = emptyList(),
    /** 本次操作的结果。摘要与原始错误分两层，界面用 `FailureNote` 展示。 */
    val message: Failure? = null,
    val lastSuccess: Boolean = true,
) {
    val canRun: Boolean get() = privilege.mode.canElevate && !busy

    /**
     * 触控子项与各自开关状态的配对，顺序跟着 [TouchItems]。
     *
     * 派生而不是新增字段：界面要按数据顺序渲染，而"哪些开着"已经存在 [touchItems] 里，
     * 再存一份必然对不上。新增子项只改 `TouchItems`，这里和界面都不用动。
     */
    val touchItemsById: List<Triple<TouchItem, Boolean?, Boolean>>
        get() = TouchItems.all(touchRate)
            .filter { touchPresent?.contains(it.id) ?: true }
            // 正式包（`-PminEvidence=CONFIRMED`）里，证据不足的项整个不列 ——
            // 不是置灰：列表上一排"没用"的开关只会让人以为试过了。
            .filter { visibleInBuild(it.evidence) }
            .map { Triple(it, touchDevice[it.id], it.id in touchItems) }

    /**
     * 触控总开关 = 逐项真实状态的汇总。
     *
     * 全灭 → 关；有一项确定开着 → 开；全都没读到 → 未读（null）。
     * **不看本地集合**：全部键都被还原之后本地集合可能还留着旧 id。
     */
    val touchOn: Boolean?
        get() {
            val states = touchItemsById.map { it.second }
            return when {
                states.any { it == true } -> true
                states.all { it == false } -> false
                else -> null
            }
        }

    /** 亮度守护在界面上该显示的状态。两个后端各读各的真实来源。 */
    val brightnessOn: Boolean?
        get() = when (brightnessBackend) {
            BrightnessBackend.LEGACY -> brightnessRunning
            BrightnessBackend.VIVO -> brightnessDevice
        }

    /** 当前构建的最低证据等级，来自 `BuildConfig.MIN_EVIDENCE`。 */
    private fun visibleInBuild(evidence: Evidence): Boolean {
        val floor = runCatching { Evidence.valueOf(BuildConfig.MIN_EVIDENCE) }.getOrNull()
            ?: return true // 写错的构建参数不该让列表变空
        return evidence >= floor
    }
}

class TweaksViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(TweaksUiState())
    val state: StateFlow<TweaksUiState> = _state.asStateFlow()

    private fun displayInfo(): Pair<Int, Int> {
        val display = container.context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return 0 to 0
        val mode = display.mode
        val hz = display.supportedModes.filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .maxOfOrNull { it.refreshRate }?.roundToInt() ?: 0
        return hz to display.refreshRate.roundToInt()
    }

    fun setDisplay(enabled: Boolean) = run {
        if (enabled) {
            val probe = PrivilegeManager.vivoDisplayRequest(0)
            if (!probe.startsWith("OK:")) {
                // 服务原样回的错误就是设备原文，只包一层格式。
                return@run TweakRepository.Outcome(
                    false,
                    Message(R.string.raw_text, listOf(probe.removePrefix("ERROR:"))),
                )
            }
            val hz = displayInfo().first
            if (hz !in 30..240) {
                return@run TweakRepository.Outcome(false, Message(R.string.tweak_display_no_hz))
            }
            DisplaySessionService.start(container.context, hz)
        } else DisplaySessionService.stopDisplay(container.context)
        TweakRepository.Outcome(
            true,
            Message(if (enabled) R.string.tweak_display_on else R.string.tweak_display_off),
        )
    }

    private suspend fun refreshVivo() {
        // **整体切 IO。** 这个方法是整个项目里最贵的读路径：syncSwitchStateFromDevice
        // 四次 exec、currentValues() 每个 settings 键一次 exec、probeGate 每项一次
        // getprop、availableKeys() 又一次、再加 vivoDisplayRequest 和逐键回读。
        // 调用点在 `viewModelScope`（Main）上，以前全压在首屏组合的前几帧里，
        // 实测把单帧拖到 100 ms 以上 —— "点进调校页卡一下"就是它。
        //
        // `PrivilegeManager.exec` 是阻塞的 binder 调用，不是挂起函数：
        // 没有 withContext 就不会换线程，"suspend" 只换了协程不换线程。
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            refreshVivoLocked()
        }
    }

    /** 读每个触控项的设备状态。失败就是未知，不回退本地记录。 */
    private suspend fun refreshTouchDevice() {
        val device = runCatching {
            container.tweakRepository.touchDeviceState(_state.value.touchRate, _state.value.touchNumbers)
        }.getOrNull() ?: return
        _state.value = _state.value.copy(touchDevice = device, touchDeviceProbed = true)
    }

    private suspend fun refreshBrightnessDevice() {
        val value = runCatching { container.tweakRepository.vivoBrightnessOn() }.getOrNull()
        _state.value = _state.value.copy(brightnessDevice = value)
    }

    private suspend fun refreshVivoLocked() {
        // 先把几个开关的本地记录换成设备真实状态，再读下面那批。
        // 顺序不能反：本地记录若和设备分叉，界面会显示假状态（看着是关的，
        // 点开却什么都没发生）。读不到时这个方法保持原值不动。
        container.tweakRepository.syncSwitchStateFromDevice()
        // 触控和亮度守护也按设备读。`syncSwitchStateFromDevice` 只覆盖四个开关，
        // 这两族以前完全靠本地记录 —— 那正是"假开关"的来源。
        refreshTouchDevice()
        refreshBrightnessDevice()
        val vivo = container.tweakRepository.vivo
        val features = VivoFeature.entries.filter { it != VivoFeature.BRIGHTNESS }
        val values = vivo.currentValues()
        val status = features.associateWith { vivo.describe(it, values) }
        // 两层判据：settings 键 + 门禁（包/prop）。门禁没过的项保留开关但标为不支持 ——
        // 全部从界面上抹掉会让用户以为这些功能不存在，实际上只是这台机器没有。
        val unsupportedReasons = features.associateWith { vivo.probeGate(it) }
        val supported = features.filter { feature ->
            unsupportedReasons[feature] == null && feature.values.keys.all { key ->
                values[key]?.let { feature.allowMissing || it != "null" } == true
            }
        }.toSet()
        val snapshots = features.filter { vivo.hasSnapshot(it) }.toSet()
        val displayProbe = PrivilegeManager.vivoDisplayRequest(0)
        val (maxHz, currentHz) = displayInfo()
        // 门禁没过的项 → 原因。mapNotNull 把 null（支持）滤掉，只留真正不支持的。
        val unsupported = unsupportedReasons.mapNotNull { (f, why) -> why?.let { f to it } }.toMap()
        // 挂起读取结束后再取最新 UI 状态，避免覆盖并行收集到的开关或忙碌状态。
        _state.value = _state.value.copy(vivoValues = values, vivoStatus = status, vivoSupported = supported,
            vivoSnapshots = snapshots, vivoUnsupported = unsupported,
            displaySupported = displayProbe.startsWith("OK:"), displayStatus = displayProbe.substringAfter(":"),
            maxDisplayHz = maxHz, displayHz = currentHz,
            perfAvailableKeys = container.tweakRepository.vivo.availableKeys(),
            touchReadout = readTouchReadout(),
            touchNumbers = container.tweakRepository.touchValues())
    }

    /**
     * 读需要显示当前值的触控项。键是 `"<itemId>|<旋钮键名>"`。
     *
     * 只读项和显式要求显示的项都读。粒度到旋钮而不是整项 —— 按下防抖有四个键，
     * 整项一个值显示不出来。
     */
    private suspend fun readTouchReadout(): Map<String, String> = buildMap {
        // 数值项也要读：字段旁边显示"设备现在是多少"，用户才分得清
        // "我填的这个数有没有落进去"和"它原本是多少"。
        for (item in TouchItems.all()) {
            if (!item.showsValues && item.numericKnobs.isEmpty()) continue
            for (knob in item.knobs) {
                val value = if (knob.ns == TouchNs.PROP) {
                    // 必须剥掉用户服务的输出包装，否则值里拖着 "[exit] 0"。
                    val exitMarker = "\n" + "[exit] "
                    PrivilegeManager.exec("getprop ${knob.key}")?.substringBefore(exitMarker)?.trim()
                } else {
                    SettingChannel.get(knob.ns.name.lowercase(), knob.key)?.trim()
                }
                if (!value.isNullOrEmpty()) put("${item.id}|${knob.key}", value)
            }
        }
    }

    fun setVivo(feature: VivoFeature, restore: Boolean, option: String?, force: Boolean = false) = run {
        val experiment = feature.lifetime == TweakLifetime.TIMED
        if (experiment) _state.value = _state.value.copy(experimentFeature = feature, experimentRunning = true,
            experimentSeconds = 0, experimentSuccess = true,
            experimentMessage = if (restore) {
                container.context.getString(R.string.tweak_exp_restoring)
            } else {
                container.context.getString(R.string.tweak_exp_running)
            })
        val timer = if (experiment) viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                _state.value = _state.value.copy(experimentSeconds = _state.value.experimentSeconds + 1)
            }
        } else null
        val result = try {
            container.tweakRepository.vivo.apply(feature, restore, option, force)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
                // 异常类名必须留下：摘要只说"操作异常"，证据进 detail 并可复制。
                TweakRepository.Outcome(
                    false,
                    Message(R.string.tweak_op_exception),
                    detail = Failure.detailOf(e),
                )
        }
        finally { timer?.cancel(); if (experiment) _state.value = _state.value.copy(experimentRunning = false) }
        // 先显示控制结果，后续诊断失败不能抹掉写入和恢复的结论。
        if (experiment) {
            _state.value = _state.value.copy(
                experimentMessage = result.message.resolve(container.context),
                experimentSuccess = result.success,
            )
        }
        // 恢复被"系统改过"拦住时记住这个功能，用户再点一次就带 force 放行。
        _state.value = _state.value.copy(
            message = Failure(result.message, result.detail),
            lastSuccess = result.success,
            pendingForceRestore = if (result.needsConfirm) _state.value.pendingForceRestore + feature.name
            else _state.value.pendingForceRestore - feature.name,
            vivoSnapshots = VivoFeature.entries.filter { container.tweakRepository.vivo.hasSnapshot(it) }.toSet())
        try {
        refreshVivo()
        if (feature == VivoFeature.AI) refreshSmartState()
        val report = container.tweakRepository.vivo.diagnosticReport()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            java.io.File(container.context.filesDir, "tweak-report.txt").writeText(report)
        }
        _state.value = _state.value.copy(tweakReport = report)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            val summary = Message(R.string.tweak_report_failed, listOf(result.message, e.message.orEmpty()))
            if (experiment) {
                _state.value = _state.value.copy(
                    experimentMessage = summary.resolve(container.context),
                )
            }
            return@run TweakRepository.Outcome(false, summary)
        }
        result
    }

    fun collectTweakReport() = run {
        val touchLines = buildString {
            append("\n显示=${_state.value.displayHz}Hz 触控方案=${container.context.getString(_state.value.touchBackend.labelRes)} 会话=${_state.value.session}\n")
            // 逐项列出子项与开关状态：导出报告要能回答"这台机器上到底哪几项开着"。
            append(container.context.getString(R.string.report_touch_items))
            _state.value.touchItemsById.forEach { (item, on, _) ->
                append(
                    "  ${item.id} = " +
                        container.context.getString(
                            when (on) {
                                true -> R.string.tweak_touch_item_on
                                false -> R.string.tweak_touch_item_off
                                null -> R.string.touch_state_unknown
                            },
                        ) +
                        "  证据=${item.evidence.name}\n",
                )
                if (on == true) item.knobs.forEach { knob ->
                    append("      ${knob.ns.name.lowercase()}:${knob.key} = ${knob.onValue}\n")
                }
            }
        }
        val report = container.tweakRepository.vivo.diagnosticReport() + "\n" +
            magicau.mtkcontroller.data.tweaks.SmartPlatform.report(
                container.context,
                magicau.mtkcontroller.data.tweaks.SmartPlatform.inspect(),
            ) +
            magicau.mtkcontroller.data.tweaks.SmartPlatform.serviceReport() + touchLines
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            java.io.File(container.context.filesDir, "tweak-report.txt").writeText(report)
        }
        _state.value = _state.value.copy(tweakReport = report)
        TweakRepository.Outcome(true, Message(R.string.tweak_report_saved))
    }

    fun exportTweakReport(uri: android.net.Uri) = run {
        val report = _state.value.tweakReport
        check(report.isNotBlank()) {
            container.context.getString(R.string.tweak_report_not_generated)
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            container.context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(report.toByteArray()) }
                ?: error(container.context.getString(R.string.tweak_report_write_failed))
        }
        TweakRepository.Outcome(true, Message(R.string.tweak_report_exported))
    }

    private suspend fun refreshSmartState() {
        val vivo = container.tweakRepository.vivo
        val values = magicau.mtkcontroller.data.tweaks.SmartPlatform.inspect()
        _state.value = _state.value.copy(smartValues = values, smartReadAt = System.currentTimeMillis(),
            smartReport = magicau.mtkcontroller.data.tweaks.SmartPlatform.report(container.context, values),
            smartInitial = vivo.smartBackup(), smartImported = vivo.smartBackup("imported"),
            smartPrevious = vivo.smartBackup("previous"), smartReminder = vivo.remindSmartBackup())
    }

    fun enterSmartPlatform() = run {
        val first = container.tweakRepository.vivo.smartBackup() == null
        _state.value = _state.value.copy(smartInitialNotice = first)
        container.tweakRepository.vivo.captureSmartInitial()
        refreshSmartState()
        refreshVivo()
        val backup = _state.value.smartInitial!!
        TweakRepository.Outcome(
            true,
            if (first) {
                Message(R.string.tweak_smart_initial, listOf(backup.values.size, backup.missing.size))
            } else {
                Message(R.string.tweak_smart_read)
            },
        )
    }

    fun dismissSmartNotice() { _state.value = _state.value.copy(smartInitialNotice = false) }
    fun setSmartReminder(enabled: Boolean) {
        container.tweakRepository.vivo.setSmartReminder(enabled)
        _state.value = _state.value.copy(smartReminder = enabled)
    }
    fun restoreSmart(slot: String) = run {
        val result = container.tweakRepository.vivo.restoreSmartBackup(slot)
        refreshSmartState()
        refreshVivo()
        result
    }
    fun exportSmart(uri: android.net.Uri, slot: String) = run {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val backup = container.tweakRepository.vivo.smartBackup(slot) ?: error(container.context.getString(R.string.tweak_smart_no_backup_export))
            container.context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(magicau.mtkcontroller.data.tweaks.SmartBackupCodec.encode(backup).toByteArray())
            } ?: error(container.context.getString(R.string.tweak_smart_write_failed))
        }
        TweakRepository.Outcome(true, Message(R.string.tweak_smart_exported))
    }
    fun importSmart(uri: android.net.Uri) = run {
        val backup = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bytes = container.context.contentResolver.openInputStream(uri)?.use { it.readNBytes(magicau.mtkcontroller.data.tweaks.SmartBackupCodec.MAX_BYTES + 1) }
                ?: error(container.context.getString(R.string.tweak_smart_read_failed))
            magicau.mtkcontroller.data.tweaks.SmartBackupCodec.decode(bytes.decodeToString())
        }
        container.tweakRepository.vivo.importSmartBackup(backup)
        _state.value = _state.value.copy(smartImported = backup)
        TweakRepository.Outcome(true, Message(R.string.tweak_smart_imported))
    }

    fun refreshVivoCapabilities() = run {
        refreshVivo()
        TweakRepository.Outcome(true, Message(R.string.tweak_refreshed))
    }

    fun inspectSmartPlatform() = run {
        refreshSmartState()
        refreshVivo()
        val values = _state.value.smartValues
        TweakRepository.Outcome(
            values.isNotEmpty(),
            Message(if (values.isEmpty()) R.string.tweak_inspect_empty else R.string.tweak_inspect_done),
        )
    }

    fun runMaintenance(kind: String, pkg: String) {
        if (_state.value.task.running || !_state.value.canRun) return
        viewModelScope.launch {
            try { MaintenanceService.start(container.context, kind, pkg) }
            catch (e: Exception) {
                _state.value = _state.value.copy(
                    message = Failure.from(
                        "Maintenance",
                        Message(R.string.tweak_task_start_failed, listOf(e.message.orEmpty())),
                        e,
                    ),
                    lastSuccess = false,
                )
            }
        }
    }

    // --- 游戏与画面实验 ------------------------------------------------------

    /**
     * SF 相位偏移。原值先在 UI 侧读一次存进 state，恢复时逐项写回，
     * 而不是无脑写 0 —— 0 只是"不启用"，不一定是本 ROM 的原始默认值。
     */
    fun setPhaseOffset(prop: String, ns: Long) {
        if (!_state.value.canRun) return
        viewModelScope.launch {
            // 写之前先把原值落盘，只取第一次（后面的读到的是我们自己写的值）。
            val has = withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.tweakRepository.capturePhaseOffsets()
                container.tweakRepository.phaseOriginals().isNotEmpty()
            }
            _state.value = _state.value.copy(phaseHasOriginal = has)
            // 成功与否由数据层回的结果说了算。**以前这里判 `result.startsWith("OK")`，
            // 而 `PhaseOffset.set` 从来不返回以 "OK" 开头的串** —— 于是每次成功的写入
            // 都被渲染成红色错误。拿前缀猜状态就是这么坏掉的。
            val result = runCatching { magicau.mtkcontroller.data.tweaks.PhaseOffset.set(prop, ns) }
                .getOrNull()
            if (result == null) {
                _state.value = _state.value.copy(
                    phaseMessage = container.context.getString(R.string.tweak_phase_failed),
                    phaseSuccess = false,
                )
                return@launch
            }
            _state.value = _state.value.copy(
                phaseMessage = result.message.resolve(container.context),
                phaseSuccess = result.success,
            )
            refreshPhaseOffsets()
        }
    }

    /** 进页面时记一次原值；首次写入语义由 `RecoverySnapshot` 保证，重复调用不会覆盖。 */
    fun snapshotPhaseOffsets() {
        viewModelScope.launch {
            val has = withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.tweakRepository.capturePhaseOffsets()
                container.tweakRepository.phaseOriginals().isNotEmpty()
            }
            _state.value = _state.value.copy(phaseHasOriginal = has)
        }
    }

    fun restorePhaseOffsets() {
        if (!_state.value.canRun) return
        viewModelScope.launch {
            val snap = withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.tweakRepository.phaseOriginals()
            }
            if (snap.isEmpty()) {
                _state.value = _state.value.copy(phaseMessage = container.context.getString(R.string.tweak_phase_no_original), phaseSuccess = false)
                return@launch
            }
            val items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                magicau.mtkcontroller.data.tweaks.PhaseOffset.restore(snap)
            }
            if (items.all { it.success }) {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    container.tweakRepository.releasePhaseOffsets()
                }
                _state.value = _state.value.copy(phaseHasOriginal = false)
            }
            // 逐项读数行是**证据**（属性名 + 结果），拼起来给用户看。
            // 成功与否由每一项自己报，不去猜生成出来的文字里有没有"失败"。
            _state.value = _state.value.copy(
                phaseMessage = items.joinToString("\n") {
                    "${it.prop} ${it.message.resolve(container.context)}"
                },
                phaseSuccess = items.all { it.success },
            )
            refreshPhaseOffsets()
        }
    }

    private fun refreshPhaseOffsets() {
        viewModelScope.launch {
            val current = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                magicau.mtkcontroller.data.tweaks.PhaseOffset.snapshot()
            }
            _state.value = _state.value.copy(phaseCurrent = current)
        }
    }

    fun fakeBattery(command: String) {
        if (!_state.value.canRun) return
        viewModelScope.launch {
            val reply = PrivilegeManager.fakeBattery(command)
            val ok = reply.startsWith("OK")
            // 服务端回的是 "OK:<shell 命令>" 或 "ERROR:<原因>"。那条 shell 命令是留给
            // 固件侧对质用的，不进摘要 —— 用户不需要知道我们跑了哪条 dumpsys。
            val serverNote = reply.substringAfter(':', "").trim().takeIf { it.isNotBlank() }
            val message = if (!ok) {
                // 失败时不要去核对"系统显示在不在充电"：那时候读到的任何状态都说明不了
                // 本次设置的结果，只会给一句文不对题的结论。
                Failure(Message(R.string.tweak_fake_failed), detail = serverNote)
            } else {
                val faked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (command == "reset") null else magicau.mtkcontroller.data.privilege.FakeBattery.fakedOrUnknown()
                }
                Failure(
                    when {
                        command == "reset" -> Message(R.string.tweak_fake_reset_done)
                        faked == true -> Message(R.string.tweak_fake_conflict)
                        faked == false -> Message(R.string.tweak_fake_real)
                        else -> Message(R.string.tweak_fake_unconfirmed)
                    },
                    detail = serverNote,
                )
            }
            _state.value = _state.value.copy(message = message, lastSuccess = ok)
        }
    }

    fun selectGameBoostApp(pkg: String) {
        viewModelScope.launch {
            container.tweakRepository.gameBoost.selected = pkg
            _state.value = _state.value.copy(gameBoostStatus = "", gameBoostSelected = pkg)
            refreshGameBoostStatus()
        }
    }

    fun applyGameBoost() {
        if (!_state.value.canRun) return
        viewModelScope.launch {
            val outcome = container.tweakRepository.gameBoost.apply()
            _state.value = _state.value.copy(
                message = Failure(outcome.message, outcome.detail),
                lastSuccess = outcome.success,
            )
            refreshGameBoostStatus()
        }
    }

    fun restoreGameBoost() {
        if (!_state.value.canRun) return
        viewModelScope.launch {
            val outcome = container.tweakRepository.gameBoost.restore()
            _state.value = _state.value.copy(
                message = Failure(outcome.message, outcome.detail),
                lastSuccess = outcome.success,
            )
            refreshGameBoostStatus()
        }
    }

    fun refreshGameBoostStatus() {
        if (!_state.value.canRun) return
        viewModelScope.launch {
            val active = container.tweakRepository.gameBoost.hasSnapshot()
            val status = runCatching { container.tweakRepository.gameBoost.status() }
                .getOrElse {
                container.context.getString(R.string.tweak_read_failed_msg, it.message.orEmpty())
            }
            _state.value = _state.value.copy(gameBoostActive = active, gameBoostStatus = status)
        }
    }

    fun refreshForegroundApp() {
        if (!_state.value.canRun) return
        viewModelScope.launch {
            // 一次 dumpsys 就是几十毫秒 CPU 加几十 KB 输出（见 ForegroundApp），
            // 不能跑在 Main 上。
            val reply = withContext(kotlinx.coroutines.Dispatchers.IO) { PrivilegeManager.foregroundApp() }
            _state.value = _state.value.copy(
                foregroundApp = if (reply.startsWith("ERROR")) "" else reply,
                foregroundError = reply.takeIf { it.startsWith("ERROR") }?.substringAfter(':'),
            )
        }
    }

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val saved = runCatching { java.io.File(container.context.filesDir, "tweak-report.txt").readText() }.getOrDefault("")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (_state.value.tweakReport.isEmpty()) _state.value = _state.value.copy(tweakReport = saved)
            }
        }
        val vivo = container.tweakRepository.vivo
        // 四份 SharedPreferences 读 + 四份编解码。第一次读要落盘加载整个文件，
        // 解码是 base64 + 反序列化。全在 IO 上做，别占首屏组合的帧。
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val smart = SmartState(
                initial = vivo.smartBackup(),
                imported = vivo.smartBackup("imported"),
                previous = vivo.smartBackup("previous"),
                reminder = vivo.remindSmartBackup(),
            )
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                _state.value = _state.value.copy(
                    smartInitial = smart.initial,
                    smartImported = smart.imported,
                    smartPrevious = smart.previous,
                    smartReminder = smart.reminder,
                )
            }
        }
        viewModelScope.launch {
            MaintenanceService.state.collect { task -> _state.value = _state.value.copy(task = task) }
        }
        viewModelScope.launch {
            val apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val pm = container.context.packageManager
                pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }.sortedBy { it.second }
            }
            _state.value = _state.value.copy(apps = apps)
        }
        viewModelScope.launch {
            container.tweakRepository.autoRotate.collect { on ->
                _state.value = _state.value.copy(autoRotate = on)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.brightnessBackend.collect { backend ->
                _state.value = _state.value.copy(brightnessBackend = backend)
            }
        }
        viewModelScope.launch {
            DisplaySessionService.state.collect { session ->
                _state.value = _state.value.copy(session = session, displayHz = displayInfo().second)
            }
        }
        viewModelScope.launch {
            ThermalBrightnessService.running.collect { running ->
                _state.value = _state.value.copy(brightnessRunning = running)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.touchBackend.collect { backend ->
                _state.value = _state.value.copy(touchBackend = backend)
            }
        }
        viewModelScope.launch {
            PrivilegeManager.state.collect { privilege ->
                _state.value = _state.value.copy(loading = false, privilege = privilege)
                if (privilege.mode.canElevate) refreshVivo()
            }
        }
        viewModelScope.launch {
            container.tweakRepository.touchItems.collect { ids ->
                _state.value = _state.value.copy(touchItems = ids)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.touchRate.collect { rate ->
                _state.value = _state.value.copy(touchRate = rate)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.frameInterpolation.collect { enabled ->
                _state.value = _state.value.copy(frameInterpolation = enabled)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.frameInterpolationSr.collect { enabled ->
                _state.value = _state.value.copy(frameInterpolationSr = enabled)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.rotationSuggestion.collect { enabled ->
                _state.value = _state.value.copy(rotationSuggestion = enabled)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.thermalBrightness.collect { enabled ->
                _state.value = _state.value.copy(thermalBrightness = enabled)
            }
        }
    }

    private fun run(block: suspend () -> TweakRepository.Outcome) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null, lastFailures = emptyList())
            val outcome = try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                TweakRepository.Outcome(
                    false,
                    Message(R.string.tweak_op_failed),
                    detail = Failure.detailOf(e),
                )
            }
            finally { _state.value = _state.value.copy(busy = false) }
            _state.value = _state.value.copy(
                busy = false,
                message = Failure(outcome.message, outcome.detail),
                lastSuccess = outcome.success,
                lastFailures = outcome.failures,
            )
        }
    }

    fun setTouch(enabled: Boolean) = run {
        val result = container.tweakRepository.setTouchOptimization(enabled, _state.value.touchRate)
        _state.value = _state.value.copy(touchChanges = container.tweakRepository.lastTouchChanges)
        if (result.success && enabled && _state.value.touchBackend == TouchBackend.VIVO) {
            try { DisplaySessionService.start(container.context) }
            catch (e: Exception) { container.tweakRepository.setTouchOptimization(false); throw e }
        }
        result
    }

    fun setTouchRate(rate: Int) =
        run { container.tweakRepository.setTouchOptimization(true, rate) }

    /** 选中了本机不支持的性能档位：把原因显示出来，不发起写入。 */
    fun reportPerfUnsupported(levelId: String) {
        val level = TweakValues.perfLevel(levelId)
        _state.value = _state.value.copy(
            message = Failure(
                if (level == null) {
                    Message(R.string.vivo_pick_perf)
                } else {
                    Message(
                        R.string.tweak_perf_unsupported,
                        listOf(Message(level.labelRes)),
                    )
                },
            ),
            lastSuccess = false,
        )
    }

    /** 单个触控子项开关。只改这一项，其他项不动。 */
    /**
     * 探一次本机有哪些触控项。进页面调一次即可 —— 它跑 N 条 shell 读，
     * 每次重组都跑会拖慢列表。
     */
    fun probeTouchItems() {
        viewModelScope.launch {
            val present = runCatching { container.tweakRepository.presentTouchItemIds() }.getOrNull()
                ?: return@launch
            _state.value = _state.value.copy(touchPresent = present)
        }
    }

    fun setTouchItem(id: String, enabled: Boolean) = run {
        val result = container.tweakRepository.setTouchItem(id, enabled, _state.value.touchRate)
        _state.value = _state.value.copy(
            touchChanges = container.tweakRepository.lastTouchChanges,
            touchReadout = readTouchReadout(),
        )
        withContext(kotlinx.coroutines.Dispatchers.IO) { refreshTouchDevice() }
        result
    }

    /**
     * 数值项里填了一个数。
     *
     * 项关着时只记下来（仓库那边同一套逻辑），所以这里不读回 —— 没写设备就没有新值可读。
     */
    fun setTouchNumber(id: String, knobKey: String, value: Int) = run {
        val result = container.tweakRepository.setTouchNumber(id, knobKey, value, _state.value.touchRate)
        _state.value = _state.value.copy(
            touchNumbers = container.tweakRepository.touchValues(),
            touchChanges = container.tweakRepository.lastTouchChanges,
            touchReadout = if (id in _state.value.touchItems) readTouchReadout() else _state.value.touchReadout,
        )
        result
    }

    fun setFrameInterpolation(enabled: Boolean) =
        run { container.tweakRepository.setFrameInterpolation(enabled, superResolution = false) }

    fun setFrameInterpolationSr(enabled: Boolean) =
        run { container.tweakRepository.setFrameInterpolation(enabled, superResolution = true) }

    fun setAutoRotate(enabled: Boolean) = run {
        container.tweakRepository.setAutoRotate(enabled)
    }

    fun setRotationSuggestion(enabled: Boolean) =
        run { container.tweakRepository.setRotationSuggestion(enabled) }

    fun setThermalBrightness(enabled: Boolean) {
        run {
            val outcome = container.tweakRepository.setThermalBrightnessWatch(enabled)
            if (outcome.success) {
                // 判据和首页共用一份（原来这里自己写了一遍，只认 LEGACY，
                // 首页那份却是无条件起 —— 同一个开关两种行为）。
                if (container.tweakRepository.brightnessGuardShouldRun()) {
                    try { ThermalBrightnessService.start(container.context) }
                    catch (e: Exception) { container.tweakRepository.setThermalBrightnessWatch(false); throw e }
                } else ThermalBrightnessService.stop(container.context)
            }
            outcome
        }
    }

    fun requestPermission() {
        PrivilegeManager.requestPermission()
        PrivilegeManager.refresh()
    }
}

/**
 * [TweaksViewModel.init] 里一次读出来的四份智算平台备份。
 *
 * 只为跨线程传递存在：这四份必须在 IO 上读，读完好一次性交给 Main 更新界面。
 * 直接在这里写 `_state` 四次会让界面看到三个中间态。
 */
private data class SmartState(
    val initial: magicau.mtkcontroller.data.tweaks.SmartBackup?,
    val imported: magicau.mtkcontroller.data.tweaks.SmartBackup?,
    val previous: magicau.mtkcontroller.data.tweaks.SmartBackup?,
    val reminder: Boolean,
)
