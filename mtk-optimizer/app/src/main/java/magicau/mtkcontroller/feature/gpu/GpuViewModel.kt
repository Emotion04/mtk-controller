package magicau.mtkcontroller.feature.gpu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.GpuInfo

data class GpuUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val info: GpuInfo = GpuInfo(),
    val minIndex: Int = 0,
    val maxIndex: Int = 0,
    val message: String? = null,
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
            val outcome = container.gpuTuner.apply(s.info, s.minKhz, s.maxKhz)
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    fun release() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val handler = container.profileRepository.gpuHandler()
            val outcome = container.gpuTuner.release(_state.value.info, handler)
            if (outcome.success) container.profileRepository.setGpuHandler(0)
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }
}
