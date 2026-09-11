package magicau.mtkcontroller.feature.tweaks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.tweaks.FeatureTrial
import magicau.mtkcontroller.data.tweaks.OwnedKeys
import magicau.mtkcontroller.data.tweaks.StateBackup
import magicau.mtkcontroller.data.tweaks.VivoFeature
import magicau.mtkcontroller.data.tweaks.VivoTweaks
import magicau.mtkcontroller.di.AppContainer

/**
 * 功能逐个试用的状态与动作。
 *
 * 为什么单独一个 ViewModel 而不是复用 TweaksViewModel：那边管"正常用"，
 * 这边管"试一项、看一会儿、下结论"，生命周期完全不同 —— 这边有一次只开一项的约束，
 * 而且要把写前写后的键值都留下来。
 */
class FeatureTrialViewModel(container: AppContainer) : ViewModel() {

    data class State(
        val busy: Boolean = false,
        val message: String = "",
        val trials: Map<String, FeatureTrial.Trial> = emptyMap(),
        val report: String = "",
        /** 待导出的备份文本。非空 → 界面弹保存框。 */
        val pendingBackupExport: String = "",
    /** 这份待导出备份实际包含的键数（读不到的不算）。 */
    val pendingBackupCount: Int = 0,
        /** 导入的备份待恢复清单。空 = 没有待恢复的请求。 */
        val restoreItems: List<StateBackup.RestoreItem> = emptyList(),
        /** 导入来源的摘要，显示在确认表顶部。 */
        val restoreSource: String = "",
        /** 勾了要恢复的键 id（"表 键"）。空 = 一项都不恢复。 */
        val restoreSelected: Set<String> = emptySet(),
        /** 漂移监测是否在跑。 */
        val driftRunning: Boolean = false,
        /** 漂移结果：键名 → (旧值, 新值)。 */
        val driftResult: List<Triple<String, String?, String?>> = emptyList(),
        /**
         * 是否跑完过至少一次。
         *
         * 没有它就分不清"没测过"和"测过且没变化"—— 而这两句给用户的信息完全相反。
         */
        val driftRan: Boolean = false,
    )

    /** 报告已收好，等着弹系统保存框。界面收集这个信号后自己重置。 */
    private val _pendingExport = MutableStateFlow(false)
    val pendingExport: StateFlow<Boolean> = _pendingExport.asStateFlow()

    /** 备份文本已收好，等着弹系统保存框。界面收集这个信号后自己重置。 */
    private val _pendingBackup = MutableStateFlow("")
    val pendingBackup: StateFlow<String> = _pendingBackup.asStateFlow()

    fun consumeExport() {
        _pendingExport.value = false
    }

    /** 必须第一个声明：Kotlin 按声明顺序初始化，init 块会用到它。 */
    private val containerContext: android.content.Context = container.context

    private val vivo: VivoTweaks = container.tweakRepository.vivo

    /** 漂移监测开始时的那一次采样。只在监测期间有值。 */
    private var driftBaseline: Map<String, String?> = emptyMap()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        reload()
    }

    private fun reload() {
        _state.value = _state.value.copy(
            trials = FeatureTrial.trials(containerContext).associateBy { it.feature },
        )
    }

    /**
     * 只开这一项。
     *
     * 写前写后的键值都由 [FeatureTrial.applyOnce] 统一采集 —— 那里还会顺手把
     * 键域实况收进出报告，所以这里不重复读一遍。
     */
    /**
     * 只开这一项。
     *
     * [option] 由界面上的取值控件给出，和正常页面共用同一个 [FeatureOptionEditor]。
     * 早期这里写死 `option = null`，结果性能模式、帧率解锁等需要选值的项全部返回
     * "请选择性能模式档位" —— 而试用页上根本没有选的地方。
     */
    fun apply(feature: VivoFeature, option: String?) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = containerContext.getString(R.string.tweaks_trial_msg_writing))
            // probeGate 里有 getprop，是一次特权往返。viewModelScope 在 Main 上，
            // 不切线程的话点一下按钮界面就停住几十毫秒。
            val gateReason = withContext(kotlinx.coroutines.Dispatchers.IO) { vivo.probeGate(feature) }
            val message = FeatureTrial.applyOnce(containerContext, vivo, feature, gateReason, option)
            _state.value = _state.value.copy(busy = false, message = message)
            reload()
        }
    }

    /** 恢复这一项的原值。 */
    fun restore(feature: VivoFeature) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = containerContext.getString(R.string.tweaks_trial_msg_restoring))
            val outcome = vivo.apply(feature, restore = true, option = null, force = false)
            _state.value = _state.value.copy(busy = false, message = outcome.message.resolve(containerContext))
            reload()
        }
    }

    fun verdict(feature: VivoFeature, verdict: FeatureTrial.Verdict) {
        FeatureTrial.setVerdict(containerContext, feature, verdict)
        reload()
    }

    fun clear() {
        FeatureTrial.clear(containerContext)
        reload()
    }

    /**
     * 采集当前状态，通知界面弹保存框。
     *
     * 采集的是**本软件会写的全部键**，不只是开着的那些 —— 关着的键也记下来，
     * 恢复时才分得清"当时不存在"和"当时是 0"。
     */
    fun backupThenExport() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = containerContext.getString(R.string.tweaks_trial_msg_reading_config))
            val capture = StateBackup.capture()
            if (capture.entries.isEmpty()) {
                // 一个键都读不到时导出没有意义，而且恢复会误删 —— 直接拒绝。
                _state.value = _state.value.copy(
                    busy = false,
                    message = containerContext.getString(R.string.tweaks_trial_msg_read_failed),
                )
                return@launch
            }
            val text = StateBackup.encode(
                entries = capture.entries,
                labelOf = { res -> containerContext.getString(res) },
                uptimeMinutes = SystemClock.elapsedRealtime() / 60_000,
            )
            // 读不到的键没写进备份，要告诉用户这份备份不完整，别当成全量。
            _state.value = _state.value.copy(
                busy = false,
                pendingBackupExport = text,
                pendingBackupCount = capture.entries.size,
                message = if (capture.unreadable > 0) {
                    containerContext.getString(R.string.tweaks_trial_msg_backup_partial, capture.unreadable)
                } else "",
            )
            _pendingBackup.value = text
        }
    }

    fun consumeBackupExport() {
        _pendingBackup.value = ""
        _state.value = _state.value.copy(pendingBackupExport = "")
    }

    fun exportBackup(uri: android.net.Uri) {
        val text = _state.value.pendingBackupExport.ifEmpty { return }
        viewModelScope.launch {
            containerContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                ?: error(containerContext.getString(R.string.tweaks_trial_err_write_failed))
            _state.value = _state.value.copy(pendingBackupExport = "",
                message = containerContext.getString(R.string.tweaks_trial_msg_exported, _state.value.pendingBackupCount))
        }
    }

    /**
     * 读入一份备份，只比对、不写。
     *
     * 比对的结果交给界面渲染确认表，用户勾完才调 [applyRestore]。
     * **这里一次都不写** —— 恢复等于用旧值盖当前值，必须让人先看过那张表。
     */
    fun importBackup(uri: android.net.Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = containerContext.getString(R.string.tweaks_trial_msg_reading_backup))
            val text = runCatching {
                containerContext.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull() ?: run {
                _state.value = _state.value.copy(busy = false,
                    message = containerContext.getString(R.string.backup_read_failed))
                return@launch
            }
            val entries = StateBackup.decode(text)
            if (entries.isEmpty()) {
                _state.value = _state.value.copy(busy = false,
                    message = containerContext.getString(R.string.backup_no_recognised_keys))
                return@launch
            }
            val items = StateBackup.diff(entries)
            _state.value = _state.value.copy(busy = false, restoreItems = items,
                restoreSource = StateBackup.summary(text, containerContext), message = "")
        }
    }

    /** 勾选状态变化。只改内存里的勾选，不碰系统。 */
    fun toggleRestore(index: Int, checked: Boolean) {
        val items = _state.value.restoreItems.toMutableList()
        if (index !in items.indices) return
        val item = items[index]
        // 用一个独立的选中集合更直观，但 RestoreItem 已带 unchanged；
        // 勾选状态单独存，避免把它混进"要不要恢复"的业务判据里。
        val selected = _state.value.restoreSelected.toMutableSet()
        val id = "${item.owned.table} ${item.owned.key}"  // ui-check:ignore 同上
        if (checked) selected += id else selected -= id
        _state.value = _state.value.copy(restoreSelected = selected)
    }

    /**
     * 写入勾过的项，然后回读核对。
     *
     * 只写选中的。没有"全选一键覆盖"这条路 —— 要全恢复，用户得逐条看过再逐条勾。
     */
    fun applyRestore() {
        val state = _state.value
        if (state.busy) return
        val chosen = state.restoreItems.filter {
            "${it.owned.table} ${it.owned.key}" in state.restoreSelected
        }
        if (chosen.isEmpty()) {
            _state.value = state.copy(message = containerContext.getString(R.string.tweaks_trial_msg_none_selected))
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = containerContext.getString(R.string.tweaks_trial_msg_applying))
            val (ok, failures) = StateBackup.restore(chosen)
            _state.value = _state.value.copy(busy = false, restoreItems = emptyList(),
                restoreSelected = emptySet(), restoreSource = "",
                message = if (failures.isEmpty()) containerContext.getString(R.string.tweaks_trial_msg_restored, ok)
                else containerContext.getString(
                    R.string.tweaks_trial_msg_restore_partial, ok, failures.size, failures.joinToString("、"),
                ))
        }
    }

    /**
     * 开始漂移监测：只读一遍，不写任何东西。
     *
     * 存在的意义：没法事先判断一份备份干不干净（系统会自行改其中一部分键），
     * 所以不猜，测。参见 [StateBackup.drift]。
     */
    fun startDrift() {
        if (_state.value.driftRunning) return
        viewModelScope.launch {
            _state.value = _state.value.copy(driftRunning = true, message = containerContext.getString(R.string.tweaks_trial_msg_sampling))
            driftBaseline = StateBackup.sample()
            _state.value = _state.value.copy(message = "")
        }
    }

    /** 停止监测并给出结果。第二次采样同样只读。 */
    fun stopDrift() {
        if (!_state.value.driftRunning) return
        viewModelScope.launch {
            _state.value = _state.value.copy(message = containerContext.getString(R.string.tweaks_trial_msg_resampling))
            val now = StateBackup.sample()
            val changed = StateBackup.drift(driftBaseline, now)
            driftBaseline = emptyMap()
            _state.value = _state.value.copy(driftRunning = false, driftResult = changed,
                driftRan = true, message = "")
        }
    }

    fun cancelRestore() {
        _state.value = _state.value.copy(restoreItems = emptyList(), restoreSelected = emptySet(),
            restoreSource = "", message = "")
    }

    /**
     * 只收集到屏幕上看，不弹保存框。
     *
     * 和 [buildReportThenExport] 分开：导出是一键流程，查看是可选步骤。
     */
    fun viewReport() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = containerContext.getString(R.string.tweaks_trial_msg_collecting))
            val report = FeatureTrial.report(containerContext)
            _state.value = _state.value.copy(busy = false, report = report, message = "")
        }
    }

    /**
     * 一键导出：收集报告 → 通知界面弹保存框。
     *
     * 不需要先逐项试用。报告里的「键域实况」和「能力门禁」两节**不依赖任何试用记录**，
     * 新机型装上任一扫一遍就能拿到完整画像，人那边不用做任何事。
     */
    fun buildReportThenExport() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = containerContext.getString(R.string.tweaks_trial_msg_collecting_device))
            val report = FeatureTrial.report(containerContext)
            _state.value = _state.value.copy(busy = false, report = report, message = "")
            _pendingExport.value = true
        }
    }

    /**
     * 把报告写进用户选定的位置。
     *
     * 报告是**自给自足**的：设备信息、每个键在本机的真实状态、门禁判据、每项的判定
     * 都在里面。拿到它就能直接适配新机型，不需要再向使用者追问。
     */
    fun export(uri: android.net.Uri) {
        viewModelScope.launch {
            val text = _state.value.report.ifBlank { FeatureTrial.report(containerContext) }
            val ok = runCatching {
                containerContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray())
                } != null
            }.getOrDefault(false)
            _state.value = _state.value.copy(
                message = containerContext.getString(
                    if (ok) R.string.tweaks_trial_msg_report_saved else R.string.tweaks_trial_msg_save_failed,
                ),
            )
        }
    }
}
