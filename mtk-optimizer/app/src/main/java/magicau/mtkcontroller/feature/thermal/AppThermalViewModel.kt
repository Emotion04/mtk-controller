package magicau.mtkcontroller.feature.thermal

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
import magicau.mtkcontroller.data.privilege.ForegroundApp
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.privilege.PrivilegeMode
import magicau.mtkcontroller.data.thermal.AppThermalRule
import magicau.mtkcontroller.data.thermal.AppThermalStore
import magicau.mtkcontroller.data.thermal.ThermalPolicyReader

/** 一条待添加/编辑的规则在界面上的中间态。 */
data class DraftRule(
    val pkg: String = "",
    val policyIndex: Int = 1,
    val label: String = "",
)

data class AppThermalUiState(
    val loading: Boolean = true,
    val running: Boolean = false,
    val rules: List<AppThermalRule> = emptyList(),
    /** 设备上真实存在的温控策略索引。空 = 还没读到或读不到。 */
    val availableIndices: List<Int> = emptyList(),
    val foreground: String = "",
    val draft: DraftRule = DraftRule(),
    val showAddDialog: Boolean = false,
    val message: Failure? = null,
)

/**
 * 按应用温控。
 *
 * **规则表里不存"每个索引是什么意思"** —— 那是会随 ROM 变的分析结论。
 * 界面只让用户选编号，编号的实际效果由温控策略索引卡片上的实时摘要说明
 * （现读现解，见 `ThermalPolicyReader`）。
 */
class AppThermalViewModel(private val container: magicau.mtkcontroller.di.AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(AppThermalUiState())
    val state: StateFlow<AppThermalUiState> = _state.asStateFlow()

    private val store = AppThermalStore(container.context)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val indices = runCatching { ThermalPolicyReader.availableIndices() }
                .getOrDefault(emptyList())
            val foreground = withContext(Dispatchers.IO) { ForegroundApp.current() }
            _state.value = _state.value.copy(
                loading = false,
                availableIndices = indices,
                foreground = foreground,
                message = if (indices.isEmpty()) Failure(Message(R.string.app_thermal_no_indices)) else null,
            )
        }
        // 开关读服务的真实存活状态，不读"上次点了什么"。
        // 服务自己会因为"没有规则"而 stopSelf，以前开关还停在开。
        viewModelScope.launch {
            AppThermalService.running.collect { running ->
                _state.value = _state.value.copy(running = running)
            }
        }
        viewModelScope.launch {
            store.rules.collect { rules ->
                _state.value = _state.value.copy(rules = rules)
            }
        }
    }

    fun setRunning(running: Boolean) {
        if (running) {
            if (!privilegeOk()) {
                _state.value = _state.value.copy(message = Failure(Message(R.string.app_thermal_need_shizuku)))
                return
            }
            AppThermalService.start(container.context)
        } else {
            AppThermalService.stop(container.context)
        }
        // 不在这里写 running：服务真的起来/停掉之后由 [AppThermalService.running] 反映。
    }

    private fun privilegeOk(): Boolean =
        PrivilegeManager.state.value.mode.canElevate

    fun showAdd() {
        _state.value = _state.value.copy(showAddDialog = true, draft = DraftRule())
    }

    fun hideAdd() {
        _state.value = _state.value.copy(showAddDialog = false)
    }

    fun setDraftPkg(pkg: String) {
        _state.value = _state.value.copy(draft = _state.value.draft.copy(pkg = pkg))
    }

    fun setDraftIndex(index: Int) {
        _state.value = _state.value.copy(draft = _state.value.draft.copy(policyIndex = index))
    }

    fun addRule() {
        val draft = _state.value.draft
        if (draft.pkg.isBlank()) {
            _state.value = _state.value.copy(message = Failure(Message(R.string.app_thermal_need_app)))
            return
        }
        viewModelScope.launch {
            // 包名必须是真实安装的应用，避免手滑写错还查不到。
            val ok = withContext(Dispatchers.IO) {
                PrivilegeManager.exec("pm path ${draft.pkg}")?.contains("package:") == true
            }
            if (!ok) {
                _state.value = _state.value.copy(message = Failure(Message(R.string.app_thermal_no_path)))
                return@launch
            }
            store.add(AppThermalRule(draft.pkg, draft.policyIndex, draft.label))
            _state.value = _state.value.copy(showAddDialog = false, message = null)
        }
    }

    fun removeRule(pkg: String) {
        viewModelScope.launch { store.remove(pkg) }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
