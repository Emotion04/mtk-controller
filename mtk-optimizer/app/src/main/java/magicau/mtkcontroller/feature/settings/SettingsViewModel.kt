package magicau.mtkcontroller.feature.settings

import magicau.mtkcontroller.data.tweaks.TweakCommands
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.tweaks.TouchBackend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.diag.CapabilityReport
import magicau.mtkcontroller.data.diag.DiagReport
import magicau.mtkcontroller.data.lab.LabProbe
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.log.LogEntry
import magicau.mtkcontroller.data.log.LogLevel
import magicau.mtkcontroller.data.settings.SettingsRepository
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.ApplyMode
import magicau.mtkcontroller.domain.model.Backup
import magicau.mtkcontroller.domain.model.CpuLayoutMode
import magicau.mtkcontroller.domain.model.RangeControlMode
import magicau.mtkcontroller.domain.model.ScreenOffPolicy
import magicau.mtkcontroller.domain.model.NavBarStyle
import magicau.mtkcontroller.domain.model.NavBarVisibility
import magicau.mtkcontroller.domain.model.TabRestoreMode
import magicau.mtkcontroller.domain.model.ThemeMode
import magicau.mtkcontroller.ui.navigation.Destination
import magicau.mtkcontroller.ui.theme.AppPalette
import magicau.mtkcontroller.data.privilege.PrivilegeManager

data class SettingsUiState(
    val brightnessBackend: magicau.mtkcontroller.data.tweaks.BrightnessBackend = magicau.mtkcontroller.data.tweaks.BrightnessBackend.LEGACY,
    val touchBackend: TouchBackend = TouchBackend.LEGACY,
    val touchBackendMessage: magicau.mtkcontroller.data.Failure? = null,
    val loading: Boolean = true,
    val defaultTab: Destination = Destination.HOME,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val palette: AppPalette = AppPalette.SYSTEM,
    val navBarStyle: NavBarStyle = NavBarStyle.COLORFUL,
    val navBarCustomPalette: AppPalette = AppPalette.TEAL,
    val applyMode: ApplyMode = ApplyMode.SINGLE,
    val reapplyIntervalMs: Long = SettingsRepository.DEFAULT_REAPPLY_INTERVAL_MS,
    /** 锁屏时释放还是保持 CPU 频率请求。 */
    val screenOffPolicy: ScreenOffPolicy = ScreenOffPolicy.KEEP,
    /** 是否从最近任务列表隐藏本应用。 */
    val hideFromRecents: Boolean = false,
    /** 开机后 Shizuku 就绪时自动按上一次范围下发一次。 */
    val applyOnBoot: Boolean = false,
    val logLevel: LogLevel = LogLevel.INFO,
    val logEntries: List<LogEntry> = emptyList(),
    /** Parsed but not yet applied; non-null while the confirm dialog shows. */
    val backupFileName: String = "",
    /** 当前选的频率配合项 id；null = 不带配合项（默认）。 */
    val freqCompanionId: String? = null,
    /** 配合项的中文名，设置页副标题用。 */
    val freqCompanionLabel: String = "",
    /** true = 只发下限，不设上限。 */
    val freqRangeMode: TweakCommands.RangeMode = TweakCommands.RangeMode.RANGE,
    val freqHardLimits: Boolean = false,
    /** 授权状态。界面不直接读权限层 —— 那是系统副作用，统一经 ViewModel。 */
    val privilege: magicau.mtkcontroller.data.privilege.PrivilegeState =
        magicau.mtkcontroller.data.privilege.PrivilegeState(),
    val motionSpeed: magicau.mtkcontroller.ui.theme.MotionSpeed = magicau.mtkcontroller.ui.theme.MotionSpeed.SYSTEM,
    val tabSwipeMode: magicau.mtkcontroller.ui.navigation.TabSwipeMode =
        magicau.mtkcontroller.ui.navigation.TabSwipeMode.NEAREST,
    val cpuLayoutMode: CpuLayoutMode = CpuLayoutMode.PER_CLUSTER,
    val rangeControlMode: RangeControlMode = RangeControlMode.SEGMENT,
    val navBarVisibility: NavBarVisibility = NavBarVisibility.AUTO_HIDE,
    /** 切回标签时保留原二级页面，还是回到标签主页。 */
    val tabRestoreMode: TabRestoreMode = TabRestoreMode.KEEP,
    /** 自定义强调色的色相，0–360。 */
    val customHue: Int = SettingsRepository.DEFAULT_CUSTOM_HUE,
    /** 自定义强调色的浓度，0–100。 */
    val customConcentration: Int = SettingsRepository.DEFAULT_CUSTOM_CONCENTRATION,
    val shapeStyle: magicau.mtkcontroller.ui.theme.ShapeStyle =
        magicau.mtkcontroller.ui.theme.ShapeStyle.STANDARD,
    val pendingImport: Backup? = null,
    /** 导出时带哪几类。默认全选。 */
    val exportCategories: Set<magicau.mtkcontroller.domain.model.BackupCategory> =
        magicau.mtkcontroller.domain.model.BackupCategory.ALL,
    /** 导入时写入哪几类。默认全选。 */
    val importCategories: Set<magicau.mtkcontroller.domain.model.BackupCategory> =
        magicau.mtkcontroller.domain.model.BackupCategory.ALL,
    /** Latest capability probe, so the exported report is complete. */
    val report: CapabilityReport? = null,
    val backupMessage: Failure? = null,
    /** 「恢复全部设置为默认」的结果。 */
    val resetMessage: Failure? = null,
    /** 常驻通知与快捷控制。 */
    val keepAlive: Boolean = false,
    val notifShowProfile: Boolean = true,
    val notifShowCpu: Boolean = true,
    val notifShowTemp: Boolean = true,
    val notifQuickSwitch: Boolean = true,
    /** 应用频率时一并放开温控。 */
    val freqThermalCompanion: Boolean = true,
    val busy: Boolean = false,
    /**
     * 在不在电池优化白名单里。
     *
     * **null = 读不到**，不是"不在"。没授权 Shizuku 或命令失败时是这个值，
     * 界面据此不显示开关、只显示"读不到" —— 拿 false 冒充"未加入"会让用户
     * 去拨一个我们并不知道状态的开关。
     */
    val batteryWhitelisted: Boolean? = null,
    /** 只有白名单读写时置位；不要复用全局 busy，否则别的开关会跟着闪。 */
    val batteryBusy: Boolean = false,
    /** 电池白名单读写失败的原因；成功或重新读取后清空。 */
    val batteryMessage: Failure? = null,
)

class SettingsViewModel(val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun setFreqThermalCompanion(enabled: Boolean) =
        viewModelScope.launch { container.settingsRepository.setFreqThermalCompanion(enabled) }

    fun setKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    container.context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                _state.value = _state.value.copy(
                    resetMessage = Failure(Message(R.string.settings_keepalive_need_permission)),
                )
                return@launch
            }
            container.settingsRepository.setKeepAlive(enabled)
            if (enabled) {
                // 常驻通知的另一半是电池优化白名单：只开前台服务，系统仍可能把它冻掉。
                if (!magicau.mtkcontroller.data.power.BatteryWhitelist.isWhitelisted(container.context)) {
                    magicau.mtkcontroller.data.power.BatteryWhitelist.set(true)
                }
                magicau.mtkcontroller.feature.notifications.KeepAliveService.start(container.context)
            } else {
                magicau.mtkcontroller.feature.notifications.KeepAliveService.stop(container.context)
            }
        }
    }

    fun setNotifShowProfile(on: Boolean) = viewModelScope.launch { container.settingsRepository.setNotifShowProfile(on) }
    fun setNotifShowCpu(on: Boolean) = viewModelScope.launch { container.settingsRepository.setNotifShowCpu(on) }
    fun setNotifShowTemp(on: Boolean) = viewModelScope.launch { container.settingsRepository.setNotifShowTemp(on) }
    fun setNotifQuickSwitch(on: Boolean) = viewModelScope.launch { container.settingsRepository.setNotifQuickSwitch(on) }

    /** 全部恢复系统默认。删键而不是写值，见 [TweakRepository.resetAllToSystemDefaults]。 */
    fun resetAllToDefaults() {
        viewModelScope.launch {
            if (!PrivilegeManager.state.value.mode.canElevate) {
                _state.value = _state.value.copy(
                    resetMessage = Failure(Message(R.string.tweak_need_shizuku)),
                )
                return@launch
            }
            val outcome = container.tweakRepository.resetAllToSystemDefaults()
            _state.value = _state.value.copy(resetMessage = Failure(outcome.message, outcome.detail))
        }
    }

    fun setBrightnessBackend(backend: magicau.mtkcontroller.data.tweaks.BrightnessBackend) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val result = container.tweakRepository.setBrightnessBackend(backend)
                _state.value = _state.value.copy(touchBackendMessage = Failure(result.message, result.detail))
            } finally { _state.value = _state.value.copy(busy = false) }
        }
    }

    fun setTabSwipeMode(mode: magicau.mtkcontroller.ui.navigation.TabSwipeMode) {
        viewModelScope.launch { container.settingsRepository.setTabSwipeMode(mode.name) }
    }

    fun setCpuLayoutMode(mode: CpuLayoutMode) {
        viewModelScope.launch { container.settingsRepository.setCpuLayoutMode(mode.name) }
    }

    fun setRangeControlMode(mode: RangeControlMode) {
        viewModelScope.launch { container.settingsRepository.setRangeControlMode(mode.name) }
    }

    fun setCustomHue(hueDeg: Int) {
        // 只在自定义档位下写。别处改色相没有意义，还会把用户没选过的值写进存储。
        if (_state.value.palette != AppPalette.CUSTOM) return
        viewModelScope.launch { container.settingsRepository.setCustomAccent(hueDeg, _state.value.customConcentration) }
    }

    fun setCustomConcentration(concentration: Int) {
        if (_state.value.palette != AppPalette.CUSTOM) return
        viewModelScope.launch { container.settingsRepository.setCustomAccent(_state.value.customHue, concentration) }
    }

    fun setTabRestoreMode(mode: TabRestoreMode) {
        viewModelScope.launch { container.settingsRepository.setTabRestoreMode(mode.name) }
    }

    fun setNavBarVisibility(mode: NavBarVisibility) {
        viewModelScope.launch { container.settingsRepository.setNavBarVisibility(mode.name) }
    }

    fun setMotionSpeed(speed: magicau.mtkcontroller.ui.theme.MotionSpeed) {
        viewModelScope.launch { container.settingsRepository.setMotionSpeed(speed.name) }
    }


    fun setShapeStyle(style: magicau.mtkcontroller.ui.theme.ShapeStyle) {
        viewModelScope.launch { container.settingsRepository.setShapeStyle(style.name) }
    }

    fun setFreqRangeMode(mode: TweakCommands.RangeMode) {
        viewModelScope.launch { container.tweakRepository.setFreqRangeMode(mode) }
    }

    fun setFreqHardLimits(enabled: Boolean) {
        viewModelScope.launch { container.tweakRepository.setFreqHardLimits(enabled) }
    }

    fun setFreqCompanion(id: String?) {
        viewModelScope.launch {
            container.tweakRepository.setFreqSchedCompanion(id)
        }
    }

    fun setTouchBackend(backend: TouchBackend) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val result = container.tweakRepository.setTouchBackend(backend)
                _state.value = _state.value.copy(touchBackendMessage = Failure(result.message, result.detail))
            } finally { _state.value = _state.value.copy(busy = false) }
        }
    }

    init {
        // 这几个开关的状态都必须读回来，否则控件永远显示默认值 —— 数据写进去了
        // （DataStore 里确实有值），界面不知道。**每个 Flow 一个 launch。**
        // DataStore 的 Flow 不会结束，写在同一个 launch 里串成
        // `a.collect{}; b.collect{}` 的话，第二行是死代码 —— 实测就是这个错：
        // 硬上下限写进去了、读不回来，开关看起来按不动。
        viewModelScope.launch {
            container.tweakRepository.freqRangeMode.collect { mode ->
                // 只存枚举本身；文案由界面按 labelRes 取 —— 这层不产出句子。
                _state.value = _state.value.copy(freqRangeMode = mode)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.freqHardLimits.collect { on ->
                _state.value = _state.value.copy(freqHardLimits = on)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.freqSchedCompanion.collect { companion ->
                _state.value = _state.value.copy(
                    freqCompanionId = companion?.name,
                    freqCompanionLabel = companion?.name
                        ?.let { id ->
                            container.context.getString(TweakCommands.Companion.valueOf(id).labelRes)
                        }
                        ?: container.context.getString(R.string.freq_companion_off),
                )
            }
        }
        viewModelScope.launch {
            container.tweakRepository.brightnessBackend.collect { backend ->
                _state.value = _state.value.copy(brightnessBackend = backend)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.touchBackend.collect { backend ->
                _state.value = _state.value.copy(touchBackend = backend)
            }
        }
        _state.value = _state.value.copy(
            backupFileName = container.backupRepository.suggestedFileName(System.currentTimeMillis()),
        )
        viewModelScope.launch {
            container.settingsRepository.defaultTabRoute.collect { route ->
                _state.value = _state.value.copy(
                    loading = false,
                    // 配置的标签可能已被功能开关隐藏；fromRoute 会落到第一个可见标签。
                    defaultTab = Destination.fromRoute(route),
                )
            }
        }
        viewModelScope.launch {
            container.settingsRepository.themeMode.collect { name ->
                _state.value = _state.value.copy(loading = false, themeMode = ThemeMode.fromName(name))
            }
        }
        // 白名单用 PowerManager 就能读，**不需要 Shizuku**。先读一次，
        // 否则没授权时这个开关会一直显示“读不到”，用户点了也没有东西可点。
        refreshBatteryWhitelist()
        viewModelScope.launch {
            magicau.mtkcontroller.data.privilege.PrivilegeManager.state.collect { p ->
                _state.value = _state.value.copy(privilege = p)
                // 白名单读取不依赖 Shizuku；不要在这里并发重读，否则可能用旧值
                // 覆盖刚从系统授权页返回后的新值。
            }
        }
        viewModelScope.launch {
            container.settingsRepository.freqThermalCompanion.collect { on ->
                _state.value = _state.value.copy(freqThermalCompanion = on)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.keepAlive.collect { on -> _state.value = _state.value.copy(keepAlive = on) }
        }
        viewModelScope.launch {
            container.settingsRepository.notifShowProfile.collect { v -> _state.value = _state.value.copy(notifShowProfile = v) }
        }
        viewModelScope.launch {
            container.settingsRepository.notifShowCpu.collect { v -> _state.value = _state.value.copy(notifShowCpu = v) }
        }
        viewModelScope.launch {
            container.settingsRepository.notifShowTemp.collect { v -> _state.value = _state.value.copy(notifShowTemp = v) }
        }
        viewModelScope.launch {
            container.settingsRepository.notifQuickSwitch.collect { v -> _state.value = _state.value.copy(notifQuickSwitch = v) }
        }
        viewModelScope.launch {
            container.settingsRepository.tabSwipeMode.collect { name ->
                _state.value = _state.value.copy(
                    tabSwipeMode = magicau.mtkcontroller.ui.navigation.TabSwipeMode.fromName(name),
                )
            }
        }
        viewModelScope.launch {
            container.settingsRepository.motionSpeed.collect { name ->
                _state.value = _state.value.copy(motionSpeed = magicau.mtkcontroller.ui.theme.MotionSpeed.fromName(name))
            }
        }
        viewModelScope.launch {
            container.settingsRepository.cpuLayoutMode.collect { name ->
                _state.value = _state.value.copy(cpuLayoutMode = CpuLayoutMode.fromName(name))
            }
        }
        viewModelScope.launch {
            container.settingsRepository.rangeControlMode.collect { name ->
                _state.value = _state.value.copy(rangeControlMode = RangeControlMode.fromName(name))
            }
        }
        // 一个 Flow 一个 launch —— DataStore 的 Flow 不会结束，串写会让后面几个
        // collect 变成死代码（界面读不到已保存的值，控件停在一个默认值上）。
        viewModelScope.launch {
            container.settingsRepository.navBarVisibility.collect { name ->
                _state.value = _state.value.copy(navBarVisibility = NavBarVisibility.fromName(name))
            }
        }
        viewModelScope.launch {
            container.settingsRepository.tabRestoreMode.collect { name ->
                _state.value = _state.value.copy(tabRestoreMode = TabRestoreMode.fromName(name))
            }
        }
        viewModelScope.launch {
            container.settingsRepository.customHue.collect { hue ->
                _state.value = _state.value.copy(customHue = hue)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.customConcentration.collect { c ->
                _state.value = _state.value.copy(customConcentration = c)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.shapeStyle.collect { name ->
                _state.value = _state.value.copy(
                    shapeStyle = magicau.mtkcontroller.ui.theme.ShapeStyle.fromName(name),
                )
            }
        }
        viewModelScope.launch {
            container.settingsRepository.themePalette.collect { name ->
                _state.value = _state.value.copy(loading = false, palette = AppPalette.fromName(name))
            }
        }
        viewModelScope.launch {
            container.settingsRepository.navBarStyle.collect { name ->
                _state.value = _state.value.copy(navBarStyle = NavBarStyle.fromName(name))
            }
        }
        viewModelScope.launch {
            container.settingsRepository.navBarCustomPalette.collect { name ->
                _state.value = _state.value.copy(navBarCustomPalette = AppPalette.fromName(name))
            }
        }
        viewModelScope.launch {
            container.settingsRepository.logLevel.collect { name ->
                _state.value = _state.value.copy(logLevel = LogLevel.fromName(name))
            }
        }
        viewModelScope.launch {
            container.settingsRepository.applyMode.collect { name ->
                _state.value = _state.value.copy(applyMode = ApplyMode.fromName(name))
            }
        }
        viewModelScope.launch {
            container.settingsRepository.reapplyIntervalMs.collect { ms ->
                _state.value = _state.value.copy(reapplyIntervalMs = ms)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.screenOffPolicy.collect { name ->
                _state.value = _state.value.copy(screenOffPolicy = ScreenOffPolicy.fromName(name))
            }
        }
        viewModelScope.launch {
            container.settingsRepository.hideFromRecents.collect { hidden ->
                _state.value = _state.value.copy(hideFromRecents = hidden)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.applyOnBoot.collect { on ->
                _state.value = _state.value.copy(applyOnBoot = on)
            }
        }
        viewModelScope.launch {
            AppLog.entries.collect { entries ->
                _state.value = _state.value.copy(logEntries = entries)
            }
        }
    }

    fun setDefaultTab(destination: Destination) {
        viewModelScope.launch { container.settingsRepository.setDefaultTabRoute(destination.route) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { container.settingsRepository.setThemeMode(mode.name) }
    }

    fun setPalette(palette: AppPalette) {
        viewModelScope.launch { container.settingsRepository.setThemePalette(palette.name) }
    }

    fun setNavBarStyle(style: NavBarStyle) {
        viewModelScope.launch { container.settingsRepository.setNavBarStyle(style.name) }
    }

    fun setNavBarCustomPalette(palette: AppPalette) {
        viewModelScope.launch { container.settingsRepository.setNavBarCustomPalette(palette.name) }
    }

    fun setApplyMode(mode: ApplyMode) {
        viewModelScope.launch {
            container.settingsRepository.setApplyMode(mode.name)
            // Switching to single-shot should stop an already-running loop.
            container.syncCpuReapply()
        }
    }

    fun setReapplyIntervalMs(value: Long) {
        viewModelScope.launch {
            container.settingsRepository.setReapplyIntervalMs(value)
            // Restart the service so the new interval takes effect immediately.
            container.syncCpuReapply()
        }
    }

    fun setScreenOffPolicy(policy: ScreenOffPolicy) {
        viewModelScope.launch {
            container.settingsRepository.setScreenOffPolicy(policy.name)
            // 关掉联动时要停服务；打开时若已有活动请求则立刻开始听屏幕广播。
            container.syncCpuReapply()
        }
    }

    fun setHideFromRecents(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setHideFromRecents(enabled)
            magicau.mtkcontroller.data.RecentsHider.apply(container.context, enabled)
        }
    }

    fun setApplyOnBoot(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setApplyOnBoot(enabled) }
    }

    fun setLogLevel(level: LogLevel) {
        viewModelScope.launch { container.settingsRepository.setLogLevel(level.name) }
    }

    fun clearLogEntries() = AppLog.clear()

    // --- backup ------------------------------------------------------------

    fun exportBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, backupMessage = null)
            val result = container.backupRepository.export(
                container.context.contentResolver, uri, _state.value.exportCategories,
            )
            _state.value = _state.value.copy(busy = false, backupMessage = result)
        }
    }

    /** Read the file and show what it holds; nothing is written yet. */
    fun prepareImport(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, backupMessage = null)
            val result = container.backupRepository.parse(container.context.contentResolver, uri)
            _state.value = result.fold(
                onSuccess = { _state.value.copy(busy = false, pendingImport = it) },
                onFailure = {
                    _state.value.copy(
                        busy = false,
                        backupMessage = Failure(
                            Message(R.string.import_read_failed),
                            detail = it.message ?: it.javaClass.simpleName,
                        ),
                    )
                },
            )
        }
    }

    /** 勾选/取消一类。导入导出各一份，互不影响。 */
    fun toggleExportCategory(category: magicau.mtkcontroller.domain.model.BackupCategory, on: Boolean) {
        val next = _state.value.exportCategories.toMutableSet().apply { if (on) add(category) else remove(category) }
        _state.value = _state.value.copy(exportCategories = next)
    }

    fun toggleImportCategory(category: magicau.mtkcontroller.domain.model.BackupCategory, on: Boolean) {
        val next = _state.value.importCategories.toMutableSet().apply { if (on) add(category) else remove(category) }
        _state.value = _state.value.copy(importCategories = next)
    }

    fun confirmImport() {
        val backup = _state.value.pendingImport ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, pendingImport = null)
            val result = container.backupRepository.apply(backup, _state.value.importCategories)
            _state.value = _state.value.copy(busy = false, backupMessage = result)        }
    }

    fun cancelImport() {
        _state.value = _state.value.copy(pendingImport = null)
    }

    fun clearBackupMessage() {
        _state.value = _state.value.copy(backupMessage = null)
    }

    // --- diagnostic report -------------------------------------------------

    /** Re-probe so a report taken straight after opening the log is complete. */
    fun refreshReport() {
        viewModelScope.launch {
            val report = runCatching { container.capabilityProbe.probe() }.getOrNull()
            _state.value = _state.value.copy(report = report)
        }
    }

    /** Suggested file name for the report picker. */
    fun reportFileName(): String =
        "mtk-god-report-${System.currentTimeMillis() / 86_400_000}.txt"

    private suspend fun buildReport(): String {
        val current = _state.value
        val clusters = runCatching { container.cpuScanner.scan(deep = false) }.getOrDefault(emptyList())
        val sections = runCatching { LabProbe.readAll(container.context, clusters) }.getOrDefault(emptyList())
        return DiagReport.build(
            container.context, current.report, current.logEntries, sections, container.cpuControl.status.value,
        )
    }

    fun copyReport() {
        viewModelScope.launch {
        val text = buildReport()
        val clipboard = container.context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(
            android.content.ClipData.newPlainText(
                container.context.getString(R.string.report_title_plain),
                text,
            ),
        )
        _state.value = _state.value.copy(
            backupMessage = Failure(Message(R.string.report_copied, listOf(text.length))),
        )
        }
    }

    fun exportReport(uri: android.net.Uri) {
        viewModelScope.launch {
            val text = buildReport()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    container.context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray())
                    } ?: error(container.context.getString(R.string.report_write_failed))
                }
                Failure(Message(R.string.report_exported, listOf(text.length)))
            }.getOrElse {
                Failure(
                    Message(R.string.report_export_failed),
                    detail = it.message ?: it.javaClass.simpleName,
                )
            }
            _state.value = _state.value.copy(backupMessage = result)
        }
    }

    /**
     * 触发 Shizuku 授权。
     *
     * 界面不直接调 PrivilegeManager：授权是系统副作用，只能由 ViewModel 发起，
     * 这样它和"应用/释放频率"走同一条意图路径，也才可能被测试替换。
     */
    fun requestAuth() {
        PrivilegeManager.requestPermission()
    }

    /** 现读白名单状态。**不缓存**：它是系统事实，存下来就会和真实状态不一致。 */
    fun refreshBatteryWhitelist() {
        if (_state.value.batteryBusy) return
        viewModelScope.launch {
            val inWhitelist = withContext(Dispatchers.IO) {
                magicau.mtkcontroller.data.power.BatteryWhitelist.isWhitelisted(container.context)
            }
            // 写入过程中不要用旧值覆盖刚刚的回读结果。
            if (!_state.value.batteryBusy) {
                _state.value = _state.value.copy(batteryWhitelisted = inWhitelist)
            }
        }
    }

    /**
     * 加入 / 移出白名单，然后**回读**。
     *
     * `set()` 返回 true 只说明"命令被系统接受"，不等于状态已变 ——
     * 所以界面显示的是回读结果，不是命令的结果。
     */
    fun setBatteryWhitelist(enabled: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(batteryBusy = true, batteryMessage = null)
            try {
                val accepted = withContext(Dispatchers.IO) {
                    magicau.mtkcontroller.data.power.BatteryWhitelist.set(enabled)
                }
                val actual = withContext(Dispatchers.IO) {
                    magicau.mtkcontroller.data.power.BatteryWhitelist.isWhitelisted(container.context)
                }
                if (!accepted) AppLog.w("Settings", "白名单${if (enabled) "加入" else "移出"}命令未被接受")
                _state.value = _state.value.copy(
                    batteryBusy = false,
                    batteryWhitelisted = actual,
                    batteryMessage = when {
                        !accepted -> Failure(Message(R.string.battery_whitelist_change_failed))
                        actual != enabled -> Failure(Message(R.string.battery_whitelist_not_changed))
                        else -> null
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Settings", "白名单操作异常: ${e.javaClass.simpleName}: ${e.message}")
                _state.value = _state.value.copy(
                    batteryBusy = false,
                    batteryMessage = Failure(Message(R.string.battery_whitelist_change_failed)),
                )
            } finally {
                // 任何路径都不能把界面留在 busy，否则开关再也点不动。
                if (_state.value.batteryBusy) _state.value = _state.value.copy(batteryBusy = false)
            }
        }
    }

    fun clearBatteryMessage() {
        _state.value = _state.value.copy(batteryMessage = null)
    }

    /** 系统电池设置页打不开时给用户一个明确结果，而不是只写日志。 */
    fun reportBatterySettingsFailure() {
        _state.value = _state.value.copy(
            batteryMessage = Failure(Message(R.string.battery_whitelist_open_failed)),
        )
    }
}
