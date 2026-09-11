package magicau.mtkcontroller.feature.gpu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.data.Message

import magicau.mtkcontroller.domain.model.GpuChannel
import magicau.mtkcontroller.domain.model.GpuInfo

data class GpuUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val info: GpuInfo = GpuInfo(),
    val minIndex: Int = 0,
    val maxIndex: Int = 0,
    /** 本次操作的结果。摘要与原始错误分两层，界面用 `FailureNote` 展示。 */
    val message: Failure? = null,
) {
    val freqs get() = info.availableFreqs
    val minKhz: Long? get() = freqs.getOrNull(minIndex)
    val maxKhz: Long? get() = freqs.getOrNull(maxIndex)
}

class GpuViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(GpuUiState())
    val state: StateFlow<GpuUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val info = container.gpuScanner.scan()
            val last = info.availableFreqs.lastIndex.coerceAtLeast(0)
            _state.value = GpuUiState(loading = false, info = info, minIndex = 0, maxIndex = last)
        }
    }

    fun setMin(value: Int) {
        val s = _state.value
        _state.value = s.copy(minIndex = value.coerceIn(0, s.maxIndex))
    }

    fun setMax(value: Int) {
        val s = _state.value
        _state.value = s.copy(maxIndex = value.coerceIn(s.minIndex, s.freqs.lastIndex.coerceAtLeast(0)))
    }

    fun apply() {
        val s = _state.value
        if (!s.info.controllable) return
        viewModelScope.launch {
            _state.value = s.copy(busy = true, message = null)
            // 写之前落盘原值：恢复靠它，进程被杀也不会丢。
            val alreadyApplied = container.profileRepository.gpuApplied()
            if (!alreadyApplied && s.info.channel == GpuChannel.DEVFREQ) {
                val baseline = container.gpuTuner.sampleDevfreqBaseline()
                container.profileRepository.setGpuBaseline(baseline?.first, baseline?.second)
            }
            val outcome = container.gpuTuner.apply(s.info, s.minKhz, s.maxKhz)
            // 第一次应用就失败：没有东西需要恢复，把刚记下的原值撤掉。
            if (!outcome.success && !alreadyApplied) container.profileRepository.clearGpu()
            _state.value = _state.value.copy(busy = false, message = Failure(outcome.message, outcome.detail))
        }
    }

    fun release() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val baseline = container.profileRepository.gpuBaseline()
            val outcome = container.gpuTuner.release(_state.value.info, baseline)
            if (outcome.success) container.profileRepository.clearGpu()
            _state.value = _state.value.copy(busy = false, message = Failure(outcome.message, outcome.detail))
        }
    }
}
