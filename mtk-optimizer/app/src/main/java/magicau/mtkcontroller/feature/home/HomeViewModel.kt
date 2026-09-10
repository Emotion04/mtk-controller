package magicau.mtkcontroller.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.privilege.PrivilegeState
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.CpuCluster
import magicau.mtkcontroller.domain.model.GpuInfo
import magicau.mtkcontroller.domain.model.HomeCard
import magicau.mtkcontroller.domain.model.HomeCardType
import magicau.mtkcontroller.domain.model.Profile
import magicau.mtkcontroller.feature.thermal.ThermalBrightnessService

/** One cluster's rolling frequency history, used by the chart. */
data class ClusterSeries(
    val policy: String,
    val label: String,
    val maxKhz: Long,
    val samples: List<Long>,
)

data class TweakUiState(
    val touchEnabled: Boolean = false,
    val touchRate: Int = 360,
    val autoRotate: Boolean = false,
    val rotationSuggestion: Boolean = false,
    val thermalBrightness: Boolean = false,
)

data class HomeUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val cards: List<HomeCard> = emptyList(),
    val clusters: List<CpuCluster> = emptyList(),
    val series: List<ClusterSeries> = emptyList(),
    val activeProfileName: String? = null,
    val profiles: List<Profile> = emptyList(),
    val privilege: PrivilegeState = PrivilegeState(),
    val gpu: GpuInfo = GpuInfo(),
    val tweaks: TweakUiState = TweakUiState(),
    val message: String? = null,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    companion object {
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val HISTORY_SIZE = 60
    }

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val history = linkedMapOf<String, ArrayDeque<Long>>()

    init {
        viewModelScope.launch {
            container.settingsRepository.homeCards.collect { cards ->
                _state.value = _state.value.copy(loading = false, cards = cards)
            }
        }
        viewModelScope.launch {
            container.profileRepository.profiles.collect { profiles ->
                val activeId = container.profileRepository.activeProfileId.first()
                _state.value = _state.value.copy(
                    profiles = profiles,
                    activeProfileName = profiles.firstOrNull { it.id == activeId }?.name,
                )
            }
        }
        viewModelScope.launch {
            PrivilegeManager.state.collect { privilege ->
                _state.value = _state.value.copy(privilege = privilege)
            }
        }
        viewModelScope.launch { sampleLoop() }
        viewModelScope.launch {
            container.tweakRepository.touchEnabled.collect { enabled ->
                _state.value = _state.value.copy(tweaks = _state.value.tweaks.copy(touchEnabled = enabled))
            }
        }
        viewModelScope.launch {
            container.tweakRepository.touchRate.collect { rate ->
                _state.value = _state.value.copy(tweaks = _state.value.tweaks.copy(touchRate = rate))
            }
        }
        viewModelScope.launch {
            container.tweakRepository.autoRotate.collect { enabled ->
                _state.value = _state.value.copy(tweaks = _state.value.tweaks.copy(autoRotate = enabled))
            }
        }
        viewModelScope.launch {
            container.tweakRepository.rotationSuggestion.collect { enabled ->
                _state.value = _state.value.copy(tweaks = _state.value.tweaks.copy(rotationSuggestion = enabled))
            }
        }
        viewModelScope.launch {
            container.tweakRepository.thermalBrightness.collect { enabled ->
                _state.value = _state.value.copy(tweaks = _state.value.tweaks.copy(thermalBrightness = enabled))
            }
        }
    }

    // --- tweaks --------------------------------------------------------------

    fun setTouchOptimization(enabled: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = container.tweakRepository.setTouchOptimization(enabled, _state.value.tweaks.touchRate)
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    fun setTouchRate(rate: Int) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = container.tweakRepository.setTouchOptimization(true, rate)
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    fun setAutoRotate(enabled: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = container.tweakRepository.setAutoRotate(enabled)
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    fun setRotationSuggestion(enabled: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = container.tweakRepository.setRotationSuggestion(enabled)
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    fun setThermalBrightness(enabled: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = container.tweakRepository.setThermalBrightnessWatch(enabled)
            if (outcome.success) {
                if (enabled) ThermalBrightnessService.start(container.context)
                else ThermalBrightnessService.stop(container.context)
            }
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    private suspend fun sampleLoop() {
        while (viewModelScope.isActive) {
            val clusters = container.cpuScanner.scan()
            clusters.forEach { cluster ->
                val freq = readCurrentFreq(cluster) ?: return@forEach
                val queue = history.getOrPut(cluster.policy) { ArrayDeque() }
                queue.addLast(freq)
                while (queue.size > HISTORY_SIZE) queue.removeFirst()
            }
            val series = clusters.map { cluster ->
                ClusterSeries(
                    policy = cluster.policy,
                    label = cluster.label,
                    maxKhz = cluster.maxFreqKhz.coerceAtLeast(1L),
                    samples = history[cluster.policy]?.toList().orEmpty(),
                )
            }
            _state.value = _state.value.copy(
                loading = false,
                clusters = clusters,
                series = series,
                gpu = runCatching { container.gpuScanner.scan() }.getOrDefault(GpuInfo()),
            )
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    private suspend fun readCurrentFreq(cluster: CpuCluster): Long? =
        magicau.mtkcontroller.data.sysfs.Sysfs
            .read("${magicau.mtkcontroller.data.cpu.CpuScanner.CPUFREQ_ROOT}/${cluster.policy}/scaling_cur_freq")
            ?.toLongOrNull()

    fun applyProfile(profileId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val profile = container.profileRepository.profiles.first().firstOrNull { it.id == profileId }
            if (profile == null) {
                _state.value = _state.value.copy(busy = false, message = "方案不存在")
                return@launch
            }
            val clusters = _state.value.clusters.ifEmpty { container.cpuScanner.scan() }
            val outcome = container.cpuTuner.apply(clusters, profile.clusters)
            outcome.handler?.let { container.profileRepository.setCpuHandler(it) }
            if (outcome.success) container.profileRepository.setActiveProfile(profile.id)
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    fun release() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = container.cpuTuner.release(container.profileRepository.cpuHandler())
            if (outcome.success) {
                container.profileRepository.setCpuHandler(0)
                container.profileRepository.setActiveProfile(null)
            }
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    // --- card layout ---------------------------------------------------------

    fun moveCard(from: Int, to: Int) {
        viewModelScope.launch { container.settingsRepository.moveCard(from, to) }
    }

    fun removeCard(card: HomeCard) {
        viewModelScope.launch { container.settingsRepository.removeCard(card.id) }
    }

    fun addCard(type: HomeCardType) {
        viewModelScope.launch { container.settingsRepository.addCard(type) }
    }

    fun resetCards() {
        viewModelScope.launch { container.settingsRepository.resetCards() }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
