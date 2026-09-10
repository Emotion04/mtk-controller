package magicau.mtkcontroller.feature.cpu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import magicau.mtkcontroller.data.cpu.CpuTuner
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.ClusterSetting
import magicau.mtkcontroller.domain.model.CpuCluster
import magicau.mtkcontroller.domain.model.CpuControlStyle
import magicau.mtkcontroller.domain.model.Profile
import java.util.UUID

/** One cluster's editable state. Indices point into [CpuCluster.availableFreqs]. */
data class ClusterEdit(
    val cluster: CpuCluster,
    val minIndex: Int,
    val maxIndex: Int,
    val governor: String?,
) {
    val minFreqKhz: Long get() = cluster.availableFreqs.getOrElse(minIndex) { cluster.minFreqKhz }
    val maxFreqKhz: Long get() = cluster.availableFreqs.getOrElse(maxIndex) { cluster.maxFreqKhz }
}

data class CpuUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val edits: List<ClusterEdit> = emptyList(),
    val powerHalAvailable: Boolean = false,
    val activeProfileName: String? = null,
    val controlStyle: CpuControlStyle = CpuControlStyle.RANGE_SLIDER,
    val message: String? = null,
)

class CpuViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CpuUiState())
    val state: StateFlow<CpuUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsRepository.cpuControlStyle.collect { name ->
                _state.value = _state.value.copy(controlStyle = CpuControlStyle.fromName(name))
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val clusters = container.cpuScanner.scan()
            val edits = clusters.map { cluster ->
                val lo = cluster.availableFreqs.indexOfFirst { it >= cluster.minFreqKhz }
                    .takeIf { it >= 0 } ?: 0
                val hi = cluster.availableFreqs.indexOfLast { it <= cluster.maxFreqKhz }
                    .takeIf { it >= 0 } ?: (cluster.availableFreqs.lastIndex.coerceAtLeast(0))
                ClusterEdit(cluster, lo, hi.coerceAtLeast(lo), cluster.currentGovernor)
            }
            val activeId = container.profileRepository.activeProfileId.first()
            val activeName = activeId?.let { id ->
                container.profileRepository.profiles.first().firstOrNull { it.id == id }?.name
            }
            _state.value = CpuUiState(
                loading = false,
                edits = edits,
                controlStyle = _state.value.controlStyle,
                powerHalAvailable = PrivilegeManager.state.value.mode.canElevate &&
                    magicau.mtkcontroller.data.powerhal.PowerHal.isAvailable(),
                activeProfileName = activeName,
            )
        }
    }

    fun setMin(index: Int, value: Int) = update(index) { edit ->
        // Dragging min past max pushes max along instead of producing an invalid range.
        val min = value.coerceIn(0, edit.maxIndex)
        edit.copy(minIndex = min)
    }

    fun setMax(index: Int, value: Int) = update(index) { edit ->
        val max = value.coerceIn(edit.minIndex, edit.cluster.availableFreqs.lastIndex.coerceAtLeast(0))
        edit.copy(maxIndex = max)
    }

    fun setGovernor(index: Int, governor: String) = update(index) { it.copy(governor = governor) }

    /** Single-slider style: lock the cluster to one frequency. */
    fun lockTo(index: Int, freqIndex: Int) = update(index) { edit ->
        val clamped = freqIndex.coerceIn(0, edit.cluster.availableFreqs.lastIndex.coerceAtLeast(0))
        edit.copy(minIndex = clamped, maxIndex = clamped)
    }

    /** Segmented bar style: set an explicit [min, max] pair. */
    fun setRange(index: Int, minIndex: Int, maxIndex: Int) = update(index) { edit ->
        val last = edit.cluster.availableFreqs.lastIndex.coerceAtLeast(0)
        val lo = minIndex.coerceIn(0, last)
        val hi = maxIndex.coerceIn(0, last)
        edit.copy(minIndex = minOf(lo, hi), maxIndex = maxOf(lo, hi))
    }

    private fun update(index: Int, transform: (ClusterEdit) -> ClusterEdit) {
        _state.value = _state.value.copy(
            edits = _state.value.edits.mapIndexed { i, edit -> if (i == index) transform(edit) else edit },
        )
    }

    fun apply() {
        val edits = _state.value.edits
        if (edits.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val settings = edits.map {
                ClusterSetting(
                    policy = it.cluster.policy,
                    minFreqKhz = it.minFreqKhz,
                    maxFreqKhz = it.maxFreqKhz,
                    governor = it.governor,
                )
            }
            val outcome = container.cpuTuner.apply(edits.map { it.cluster }, settings)
            outcome.handler?.let { container.profileRepository.setCpuHandler(it) }
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    fun release() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val handler = container.profileRepository.cpuHandler()
            val outcome = container.cpuTuner.release(handler)
            if (outcome.success) container.profileRepository.setCpuHandler(0)
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    fun saveAsProfile(name: String) {
        val edits = _state.value.edits
        if (edits.isEmpty()) return
        viewModelScope.launch {
            val profile = Profile(
                id = UUID.randomUUID().toString(),
                name = name,
                clusters = edits.map {
                    ClusterSetting(it.cluster.policy, it.minFreqKhz, it.maxFreqKhz, it.governor)
                },
                createdAtEpochMs = System.currentTimeMillis(),
            )
            container.profileRepository.upsert(profile)
            _state.value = _state.value.copy(message = "已保存方案「$name」")
        }
    }

    fun applyProfile(profile: Profile) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val clusters = _state.value.edits.map { it.cluster }
                .ifEmpty { container.cpuScanner.scan() }
            val outcome = container.cpuTuner.apply(clusters, profile.clusters)
            outcome.handler?.let { container.profileRepository.setCpuHandler(it) }
            if (outcome.success) container.profileRepository.setActiveProfile(profile.id)
            _state.value = _state.value.copy(
                busy = false,
                message = outcome.message,
                activeProfileName = if (outcome.success) profile.name else _state.value.activeProfileName,
            )
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** Exposed for the diagnostics screen. */
    suspend fun clusters(): List<CpuCluster> = container.cpuScanner.scan()

    @Suppress("unused")
    private val tuner: CpuTuner = container.cpuTuner
}
