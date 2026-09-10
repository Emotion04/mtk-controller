package magicau.mtkcontroller.feature.diag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import magicau.mtkcontroller.data.diag.CapabilityReport
import magicau.mtkcontroller.data.diag.CheckCategory
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.di.AppContainer

data class DiagUiState(
    val loading: Boolean = true,
    val report: CapabilityReport? = null,
)

class DiagViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DiagUiState())
    val state: StateFlow<DiagUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val report = container.capabilityProbe.probe()
            _state.value = DiagUiState(loading = false, report = report)
        }
    }

    fun requestPermission() {
        PrivilegeManager.requestPermission()
        refresh()
    }

    /** Reads the live sysfs min/max so a PowerHAL apply can be verified visually. */
    fun verifyApplied() {
        viewModelScope.launch {
            val clusters = container.cpuScanner.scan()
            val limits = container.capabilityProbe.readAppliedLimits(clusters)
            val text = if (limits.isEmpty()) {
                "没有可读取的簇"
            } else {
                limits.entries.joinToString("\n") { (policy, pair) ->
                    "$policy: min=${pair.first?.div(1000) ?: "?"} MHz, max=${pair.second?.div(1000) ?: "?"} MHz"
                }
            }
            val current = _state.value.report ?: return@launch
            _state.value = _state.value.copy(
                report = current.copy(
                    checks = current.checks + CapabilityReport.Check(
                        category = CheckCategory.CPU,
                        label = "实时 sysfs 上下限",
                        ok = limits.isNotEmpty(),
                        detail = text,
                    ),
                ),
            )
        }
    }
}
