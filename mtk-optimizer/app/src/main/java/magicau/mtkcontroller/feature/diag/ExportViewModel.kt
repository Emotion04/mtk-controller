package magicau.mtkcontroller.feature.diag

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.data.Message

import magicau.mtkcontroller.data.diag.AdaptationSession
import magicau.mtkcontroller.data.diag.ReportExporter
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.log.DebugRecorder
import magicau.mtkcontroller.data.log.LogFileStore
import magicau.mtkcontroller.data.log.LogLevel
import magicau.mtkcontroller.di.AppContainer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断导出。两个产物、一个入口：
 *
 *  - **适配报告** —— 设备状态快照，几 KB，任何时候都能导，用来适配新机型
 *  - **调试日志** —— 用户复现问题时录下的窗口，几 MB，用来查具体故障
 *
 * 分开是因为触发时机不同：适配报告最好在没出问题时就采好，调试日志只能在
 * 出问题之后采。合并成一个"Mega 报告"会让默认导出变得又慢又大，用户就不导了。
 */
class ExportViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val baselineAt: String? = null,
        val logBytes: Long = 0,
        val logFiles: Int = 0,
        val recording: DebugRecorder.Session? = null,
        val recordingLevel: LogLevel = LogLevel.WARN,
        val includeAdaptation: Boolean = true,
        val include24h: Boolean = false,
        val include1h: Boolean = false,
        val includeAll: Boolean = false,
        val busy: Boolean = false,
        /** 本次操作的结果。摘要与原始错误分两层，界面用 `FailureNote` 展示。 */
    val message: Failure? = null,
        /** 生成好但还没交出去的文件，供"分享/保存/复制路径"三个出口复用。 */
        val pending: File? = null,
        /** 适配测试会话是否在跑。 */
        val adaptationRunning: Boolean = false,
        /** 本次适配测试是否允许在判据节点读不到时做有界下发。 */
        val blindWrites: Boolean = false,
    ) {
        val estimatedBytes: Long
            get() = when {
                includeAll -> logBytes
                include24h || include1h -> logBytes / 4
                else -> 0
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)

    init {
        viewModelScope.launch {
            container.debugRecorder.session.collect { session ->
                _state.value = _state.value.copy(recording = session)
            }
        }
        viewModelScope.launch { refreshStorage() }
        // 会话状态比本 ViewModel 活得久：离开页面再回来要接着显示“结束”。
        viewModelScope.launch {
            AdaptationSession.state.collect { session ->
                _state.value = _state.value.copy(
                    adaptationRunning = session.running,
                    blindWrites = session.blind,
                )
            }
        }
    }

    private suspend fun refreshStorage() = withContext(Dispatchers.IO) {
        val files = LogFileStore.listFiles(container.context)
        _state.value = _state.value.copy(
            baselineAt = container.baselineSnapshot.read()?.optString("captured_at"),
            logBytes = files.sumOf { it.length() },
            logFiles = files.size,
        )
    }

    fun setInclude(field: String, value: Boolean) {
        _state.value = when (field) {
            "adaptation" -> _state.value.copy(includeAdaptation = value)
            "24h" -> _state.value.copy(include24h = value)
            "1h" -> _state.value.copy(include1h = value)
            "all" -> _state.value.copy(includeAll = value)
            else -> _state.value
        }
    }

    fun setRecordingLevel(level: LogLevel) {
        _state.value = _state.value.copy(recordingLevel = level)
    }

    fun toggleRecording() {
        val current = _state.value.recording
        if (current == null) {
            container.debugRecorder.start(_state.value.recordingLevel)
        } else {
            container.debugRecorder.stop()
        }
    }

    fun discardRecovered() {
        container.debugRecorder.discard()
        viewModelScope.launch { refreshStorage() }
    }

    /**
     * 生成一个包。勾选项决定它是"适配报告"还是"调试日志"，两者可以一起导 —
     * 但内容分开放在 report.json 与 logs/ 里，读的人一眼知道哪份是哪种。
     */
    fun setBlindWrites(enabled: Boolean) = AdaptationSession.setBlind(enabled)

    /**
     * 适配测试：开始后用户去复现问题，结束后自动生成报告。
     *
     * 报告窗口从“开始”前 10 秒到“结束”，所以开始前后的日志都在里面。
     * 盲测开关只在这里生效；退出会话立刻关掉，正常使用仍然遵守“读不到不下发”。
     */
    fun startAdaptation() {
        _state.value = _state.value.copy(message = null)
        AdaptationSession.start()
    }

    fun stopAdaptation() {
        val elapsed = AdaptationSession.stop() ?: return
        exportWithWindow(elapsed + 10_000)
    }

    fun export() {
        val s = _state.value
        val window = when {
            s.includeAll -> null
            s.include1h -> 60L * 60 * 1000
            s.include24h -> 24L * 60 * 60 * 1000
            else -> null
        }
        exportWithWindow(window)
    }

    private fun exportWithWindow(window: Long?) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                val s = _state.value
                val withLogs = s.includeAll || s.include24h || s.include1h || window != null
                val kind = if (withLogs && !s.includeAdaptation) {
                    ReportExporter.Kind.DEBUG
                } else {
                    ReportExporter.Kind.ADAPTATION
                }
                // 导出前先采一次当前状态，否则 report.json 里的 current 是上一次的。
                container.reportExporter.refreshProbe()
                val result = container.reportExporter.build(
                    ReportExporter.Request(
                        kind = kind,
                        logWindowMs = window,
                        includeAllLogs = s.includeAll,
                    ),
                )
                _state.value = _state.value.copy(
                    pending = result.file,
                    message = Failure(
                        Message(
                            R.string.export_generated,
                            listOf(result.file.name, formatSize(result.bytes), result.entries),
                        ),
                    ),
                )
            } catch (t: Throwable) {
                AppLog.e(TAG, "生成诊断包失败", t)
                _state.value = _state.value.copy(
                    message = Failure(Message(R.string.export_generate_failed, listOf(t.javaClass.simpleName))),
                )
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** 保存到用户选的位置。 */
    fun saveTo(uri: Uri) {
        val file = _state.value.pending ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { container.reportExporter.saveTo(uri, file) }
            _state.value = _state.value.copy(
                message = Failure(
                    Message(if (ok) R.string.export_saved else R.string.export_save_failed),
                ),
            )
        }
    }

    fun fileForShare(): File? = _state.value.pending

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun clearLogs() {
        viewModelScope.launch {
            LogFileStore.clear(container.context)
            refreshStorage()
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private companion object {
        const val TAG = "ExportViewModel"
    }
}
