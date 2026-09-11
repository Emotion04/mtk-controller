package magicau.mtkcontroller.feature.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.lab.LabProbe
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.CpuCluster

data class LabPanelUiState(
    val loading: Boolean = true,
    val sections: List<LabProbe.Section> = emptyList(),
    /** How many nodes answered, so the header can say something useful. */
    val readingsFound: Int = 0,
    val readingsTotal: Int = 0,
)

/**
 * Backs the read-only panel.
 *
 * Does no writing at all — this screen exists so the user can see what the
 * platform is enforcing, and so an experiment can be judged against reality
 * instead of against what the app believed it sent.
 */
class LabPanelViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(LabPanelUiState())
    val state: StateFlow<LabPanelUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val clusters: List<CpuCluster> = withContext(Dispatchers.IO) {
                runCatching { container.cpuScanner.scan(deep = false) }.getOrDefault(emptyList())
            }
            val sections = LabProbe.readAll(clusters)
            val readings = sections.flatMap { it.readings }
            _state.value = LabPanelUiState(
                loading = false,
                sections = sections,
                readingsFound = readings.count { it.exists },
                readingsTotal = readings.size,
            )
        }
    }
}
