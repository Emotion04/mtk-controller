package magicau.mtkcontroller.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.privilege.PrivilegeState
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.CpuCluster
import magicau.mtkcontroller.domain.model.GpuInfo
import magicau.mtkcontroller.domain.model.HomeCard
import magicau.mtkcontroller.domain.model.HomeCardType
import magicau.mtkcontroller.domain.model.Profile
import magicau.mtkcontroller.feature.cpu.CpuReapplyService
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

    private var sampleJob: Job? = null

    /**
     * Start or stop the 1 Hz sampler.
     *
     * The dashboard's ViewModel outlives the screen — navigating to another tab
     * keeps the `home` back-stack entry (and therefore this ViewModel) alive —
     * so without this the chart would keep reading sysfs forever in the
     * background for a screen nobody is looking at.
     */
    fun setSamplingActive(active: Boolean) {
        if (active) {
            if (sampleJob?.isActive == true) return
            // The gap while paused is not a real sample interval; start the
            // chart cleanly rather than drawing a line across it.
            history.clear()
            sampleJob = viewModelScope.launch { sampleLoop() }
        } else {
            sampleJob?.cancel()
            sampleJob = null
        }
    }

    /**
     * Ticks once a second to refresh the dashboard's live frequencies.
     *
     * This used to call the full [CpuScanner.scan] — a governor write probe and
     * a stat() shell round-trip per cluster — plus an uncached GPU probe, all
     * on the main thread, every second. That was the app's worst source of
     * jank: a steady 1 Hz pulse of blocking IPC that stalled whatever frame was
     * being drawn, so scrolling and tab switches stuttered. It now reads only
     * the live frequency nodes, off the main thread, and reuses the cluster
     * metadata it already has.
     */
    private suspend fun sampleLoop() {
        while (currentCoroutineContext().isActive) {
            val clusters = _state.value.clusters.ifEmpty {
                runCatching { withContext(Dispatchers.IO) { container.cpuScanner.scan(deep = false) } }
                    .getOrDefault(emptyList())
            }

            if (clusters.isNotEmpty()) {
                val freqs = runCatching { container.cpuScanner.currentFreqs() }
                    .getOrDefault(emptyMap())

                freqs.forEach { (policy, freq) ->
                    val queue = history.getOrPut(policy) { ArrayDeque() }
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
                val live = clusters.map { cluster ->
                    freqs[cluster.policy]?.let { cluster.copy(currentFreqKhz = it) } ?: cluster
                }

                _state.value = _state.value.copy(
                    loading = false,
                    clusters = live,
                    series = series,
                    gpu = runCatching { container.gpuScanner.scan() }.getOrDefault(GpuInfo()),
                )
            }
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    fun applyProfile(profileId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val profile = container.profileRepository.profiles.first().firstOrNull { it.id == profileId }
            if (profile == null) {
                _state.value = _state.value.copy(busy = false, message = "方案不存在")
                return@launch
            }
            val clusters = _state.value.clusters.ifEmpty { container.cpuScanner.scan() }
            val outcome = container.cpuControl.apply(clusters, profile.clusters)
            if (outcome.success) {
                container.profileRepository.setActiveProfile(profile.id)
                container.syncCpuReapply()
            }
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    fun release() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = container.cpuControl.release(_state.value.clusters)
            if (outcome.success) container.profileRepository.setActiveProfile(null)
            // Polling would just re-acquire what we released.
            CpuReapplyService.stop(container.context)
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
