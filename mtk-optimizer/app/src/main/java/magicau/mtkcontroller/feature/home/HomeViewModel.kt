package magicau.mtkcontroller.feature.home

import magicau.mtkcontroller.R
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
import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.data.Message

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
    val maxKhz: Long,
    val samples: List<Long>,
)

data class TweakUiState(
    val touchEnabled: Boolean = false,
    val touchRate: Int = 360,
    val autoRotate: Boolean = false,
    val rotationSuggestion: Boolean = false,
    val thermalBrightness: Boolean = false,
    val brightnessBackend: magicau.mtkcontroller.data.tweaks.BrightnessBackend =
        magicau.mtkcontroller.data.tweaks.BrightnessBackend.LEGACY,
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
    /** 本次操作的结果。摘要与原始错误分两层，界面用 `FailureNote` 展示。 */
    val message: Failure? = null,
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
        // 进首页先把开关的本地记录换成设备真实状态。首页和调校页共用同一份
        // DataStore，两处任一同步过，另一处就是对的。
        //
        // **必须切 IO。** viewModelScope 挂在 Main 上，而这一步是四次
        // `PrivilegeManager.exec`（每次都是一趟 Shizuku 往返 + 起进程读 settings）。
        // 在 Main 上跑实测把首帧拖到 119 ms —— 切标签会"卡一下"就是它。
        // 首次进某个标签时才组合它的内容，所以这一下全落在切换动画的头几帧里。
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.tweakRepository.syncSwitchStateFromDevice() }
        }
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
        // 触控总开关读设备。本地集合是"上次点了什么"，键被系统或用户改掉之后会分叉。
        viewModelScope.launch {
            container.tweakRepository.touchItems.collect {
                val device = withContext(Dispatchers.IO) {
                    runCatching { container.tweakRepository.touchDeviceState() }.getOrNull()
                } ?: return@collect
                val anyOn = device.values.any { it == true }
                _state.value = _state.value.copy(tweaks = _state.value.tweaks.copy(touchEnabled = anyOn))
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
        // 亮度守护读真实状态：LEGACY 看服务是否在跑，VIVO 读系统键。
        // 原来读的是偏好 —— 进程被杀后服务已经停了，首页还显示"开"。
        viewModelScope.launch {
            ThermalBrightnessService.running.collect { running ->
                if (_state.value.tweaks.brightnessBackend ==
                    magicau.mtkcontroller.data.tweaks.BrightnessBackend.LEGACY
                ) {
                    _state.value = _state.value.copy(tweaks = _state.value.tweaks.copy(thermalBrightness = running))
                }
            }
        }
        viewModelScope.launch {
            container.tweakRepository.brightnessBackend.collect { backend ->
                val on = if (backend == magicau.mtkcontroller.data.tweaks.BrightnessBackend.LEGACY) {
                    ThermalBrightnessService.running.value
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching { container.tweakRepository.vivoBrightnessOn() }.getOrNull()
                    } == true
                }
                _state.value = _state.value.copy(
                    tweaks = _state.value.tweaks.copy(brightnessBackend = backend, thermalBrightness = on),
                )
            }
        }
    }

    // --- tweaks --------------------------------------------------------------

    fun setTouchOptimization(enabled: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = container.tweakRepository.setTouchOptimization(enabled, _state.value.tweaks.touchRate)
            _state.value = _state.value.copy(busy = false, message = Failure(outcome.message, outcome.detail))
        }
    }

    fun setTouchRate(rate: Int) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = container.tweakRepository.setTouchOptimization(true, rate)
            _state.value = _state.value.copy(busy = false, message = Failure(outcome.message, outcome.detail))
        }
    }

    fun setAutoRotate(enabled: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = container.tweakRepository.setAutoRotate(enabled)
            _state.value = _state.value.copy(busy = false, message = Failure(outcome.message, outcome.detail))
        }
    }

    fun setRotationSuggestion(enabled: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = container.tweakRepository.setRotationSuggestion(enabled)
            _state.value = _state.value.copy(busy = false, message = Failure(outcome.message, outcome.detail))
        }
    }

    fun setThermalBrightness(enabled: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = container.tweakRepository.setThermalBrightnessWatch(enabled)
            if (outcome.success) {
                // 起停判据只有一份，见 TweakRepository.brightnessGuardShouldRun()。
                // 这里原来无条件起 —— 后端是 VIVO 时根因已被掐掉，服务纯属白跑，
                // 而工具页同一个开关只在 LEGACY 后端才起，两个页面行为互相矛盾。
                if (container.tweakRepository.brightnessGuardShouldRun()) {
                    ThermalBrightnessService.start(container.context)
                } else {
                    ThermalBrightnessService.stop(container.context)
                }
            }
            _state.value = _state.value.copy(busy = false, message = Failure(outcome.message, outcome.detail))
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
                _state.value = _state.value.copy(
                    busy = false,
                    message = Failure(Message(R.string.profile_missing)),
                )
                return@launch
            }
            val clusters = _state.value.clusters.ifEmpty { container.cpuScanner.scan() }
            val outcome = container.cpuControl.apply(clusters, profile.clusters)
            if (outcome.success) {
                container.profileRepository.setActiveProfile(profile.id)
                container.syncCpuReapply()
            }
            _state.value = _state.value.copy(busy = false, message = Failure(outcome.message, outcome.detail))
        }
    }

    /**
     * 一键释放全部。
     *
     * 这里原来只放频率、停轮询服务，**漏了温控档位、按应用温控和实验室那一把**。
     * 现在是 `AppContainer.releaseAllRequests()` 一份实现，和 CPU 页「释放」同一条路。
     */
    fun release() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = container.releaseAllRequests(_state.value.clusters)
            if (outcome.success) container.profileRepository.setActiveProfile(null)
            _state.value = _state.value.copy(busy = false, message = Failure(outcome.message, outcome.detail))
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
