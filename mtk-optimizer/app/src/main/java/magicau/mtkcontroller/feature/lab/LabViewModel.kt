package magicau.mtkcontroller.feature.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.lab.LabCatalog
import magicau.mtkcontroller.data.lab.LabCategory
import magicau.mtkcontroller.data.lab.LabController
import magicau.mtkcontroller.data.lab.LabFeature
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.CpuCluster

/** One editable feature, plus everything known about how this device responds. */
data class LabItemState(
    val feature: LabFeature,
    /** null = not checked yet; false = the backing node is absent on this device. */
    val supported: Boolean? = null,
    val nodeValue: String? = null,
    val verdict: LabController.Verdict? = null,
    /** policy name (or [GLOBAL]) -> value. */
    val values: Map<String, Int> = emptyMap(),
    val hold: Boolean = false,
    val busy: Boolean = false,
)

data class LabUiState(
    val loading: Boolean = true,
    val powerHalReady: Boolean = false,
    val clusters: List<CpuCluster> = emptyList(),
    val groups: List<Pair<LabCategory, List<LabItemState>>> = emptyList(),
    val message: String? = null,
)

/**
 * Backs the canary lab.
 *
 * The screen has two halves with different risk profiles, and they are kept
 * apart deliberately: the read-only panel never writes, and the canary list only
 * writes when the user moves a control on a card that says what it will do.
 */
class LabViewModel(private val container: AppContainer) : ViewModel() {

    companion object {
        /** Key used for features that are not per-cluster. */
        const val GLOBAL = ""
    }

    private val _state = MutableStateFlow(LabUiState())
    val state: StateFlow<LabUiState> = _state.asStateFlow()

    private var clusters: List<CpuCluster> = emptyList()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)

            clusters = withContext(Dispatchers.IO) {
                runCatching { container.cpuScanner.scan(deep = false) }.getOrDefault(emptyList())
            }
            val powerHalReady = PowerHal.isAvailableNow()
            val verdicts = container.labController.verdicts()

            // Check every declared node once, so the UI can grey out what this
            // device does not have instead of offering a control that cannot work.
            val items = LabCatalog.features.map { feature ->
                val node = feature.node?.takeIf { !feature.perCluster || it.contains("%s") }
                val supported = if (node == null) null else container.labController.nodeValue(node) != null
                LabItemState(
                    feature = feature,
                    supported = supported,
                    nodeValue = node,
                    verdict = verdicts[feature.key],
                    values = defaultValues(feature),
                )
            }

            _state.value = LabUiState(
                loading = false,
                powerHalReady = powerHalReady,
                clusters = clusters,
                groups = items.groupBy { it.feature.category }
                    .toSortedMap(compareBy { it.order })
                    .map { (category, list) -> category to list },
            )
        }
    }

    private fun defaultValues(feature: LabFeature): Map<String, Int> =
        if (feature.perCluster) {
            clusters.associate { it.policy to feature.range.first }
        } else {
            mapOf(GLOBAL to feature.range.first)
        }

    fun setValue(feature: LabFeature, policy: String, value: Int) {
        updateItem(feature.key) { item ->
            item.copy(values = item.values + (policy to value.coerceIn(feature.range)))
        }
    }

    fun setHold(feature: LabFeature, hold: Boolean) {
        updateItem(feature.key) { it.copy(hold = hold) }
    }

    /** Send the current values and record what the device did. */
    fun test(feature: LabFeature) {
        val item = currentItem(feature.key) ?: return
        if (item.supported == false) return

        viewModelScope.launch {
            updateItem(feature.key) { it.copy(busy = true) }
            val verdict = container.labController.apply(
                feature = feature,
                clusters = clusters,
                values = item.values,
                hold = item.hold,
            )
            updateItem(feature.key) {
                it.copy(busy = false, verdict = verdict)
            }
            _state.value = _state.value.copy(message = null)
        }
    }

    /** Release everything the lab holds and re-read the panel. */
    fun resetAll() {
        viewModelScope.launch {
            val message = container.labController.reset()
            _state.value = _state.value.copy(message = message)
        }
    }

    fun clearObservations() {
        viewModelScope.launch {
            container.labController.clearVerdicts()
            refresh()
        }
    }

    private fun currentItem(key: String): LabItemState? =
        _state.value.groups.flatMap { it.second }.firstOrNull { it.feature.key == key }

    private fun updateItem(key: String, transform: (LabItemState) -> LabItemState) {
        _state.value = _state.value.copy(
            groups = _state.value.groups.map { (category, items) ->
                category to items.map { if (it.feature.key == key) transform(it) else it }
            },
        )
    }
}
