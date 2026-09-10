package magicau.mtkcontroller.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.diag.CapabilityReport
import magicau.mtkcontroller.data.diag.DiagReport
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.log.LogEntry
import magicau.mtkcontroller.data.log.LogLevel
import magicau.mtkcontroller.data.settings.SettingsRepository
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.ApplyMode
import magicau.mtkcontroller.domain.model.Backup
import magicau.mtkcontroller.domain.model.NavBarStyle
import magicau.mtkcontroller.domain.model.ThemeMode
import magicau.mtkcontroller.ui.navigation.Destination
import magicau.mtkcontroller.ui.theme.AppPalette

data class SettingsUiState(
    val loading: Boolean = true,
    val defaultTab: Destination = Destination.HOME,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val palette: AppPalette = AppPalette.SYSTEM,
    val navBarStyle: NavBarStyle = NavBarStyle.COLORFUL,
    val navBarCustomPalette: AppPalette = AppPalette.TEAL,
    val applyMode: ApplyMode = ApplyMode.SINGLE,
    val reapplyIntervalMs: Long = SettingsRepository.DEFAULT_REAPPLY_INTERVAL_MS,
    val logLevel: LogLevel = LogLevel.INFO,
    val logEntries: List<LogEntry> = emptyList(),
    /** Parsed but not yet applied; non-null while the confirm dialog shows. */
    val backupFileName: String = "",
    val pendingImport: Backup? = null,
    /** Latest capability probe, so the exported report is complete. */
    val report: CapabilityReport? = null,
    val backupMessage: String? = null,
    val busy: Boolean = false,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        _state.value = _state.value.copy(
            backupFileName = container.backupRepository.suggestedFileName(System.currentTimeMillis()),
        )
        viewModelScope.launch {
            container.settingsRepository.defaultTabRoute.collect { route ->
                _state.value = _state.value.copy(
                    loading = false,
                    defaultTab = Destination.fromRoute(route),
                )
            }
        }
        viewModelScope.launch {
            container.settingsRepository.themeMode.collect { name ->
                _state.value = _state.value.copy(loading = false, themeMode = ThemeMode.fromName(name))
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

    fun setLogLevel(level: LogLevel) {
        viewModelScope.launch { container.settingsRepository.setLogLevel(level.name) }
    }

    fun clearLogEntries() = AppLog.clear()

    // --- backup ------------------------------------------------------------

    fun exportBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, backupMessage = null)
            val result = container.backupRepository.export(container.context.contentResolver, uri)
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
                        backupMessage = "读取失败: ${it.message ?: it.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    fun confirmImport() {
        val backup = _state.value.pendingImport ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, pendingImport = null)
            val result = container.backupRepository.apply(backup)
            _state.value = _state.value.copy(busy = false, backupMessage = result)
        }
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

    private fun buildReport(): String {
        val current = _state.value
        return DiagReport.build(current.report, current.logEntries)
    }

    fun copyReport() {
        val text = buildReport()
        val clipboard = container.context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("MTK God 诊断报告", text))
        _state.value = _state.value.copy(
            backupMessage = "报告已复制(${text.length} 字符),可直接粘贴发送",
        )
    }

    fun exportReport(uri: android.net.Uri) {
        viewModelScope.launch {
            val text = buildReport()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    container.context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray())
                    } ?: error("无法写入所选位置")
                }
                "报告已导出(${text.length} 字符)"
            }.getOrElse { "导出失败: ${it.message ?: it.javaClass.simpleName}" }
            _state.value = _state.value.copy(backupMessage = result)
        }
    }
}
