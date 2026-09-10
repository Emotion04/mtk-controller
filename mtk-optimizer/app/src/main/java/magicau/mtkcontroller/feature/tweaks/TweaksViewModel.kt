package magicau.mtkcontroller.feature.tweaks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.privilege.PrivilegeState
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.feature.thermal.ThermalBrightnessService

data class TweaksUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val privilege: PrivilegeState = PrivilegeState(),
    val touchEnabled: Boolean = false,
    val touchRate: Int = 360,
    val frameInterpolation: Boolean = false,
    val frameInterpolationSr: Boolean = false,
    val rotationSuggestion: Boolean = false,
    val thermalBrightness: Boolean = false,
    val lastFailures: List<String> = emptyList(),
    val message: String? = null,
) {
    val canRun: Boolean get() = privilege.mode.canElevate && !busy
}

class TweaksViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(TweaksUiState())
    val state: StateFlow<TweaksUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            PrivilegeManager.state.collect { privilege ->
                _state.value = _state.value.copy(loading = false, privilege = privilege)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.touchEnabled.collect { enabled ->
                _state.value = _state.value.copy(touchEnabled = enabled)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.touchRate.collect { rate ->
                _state.value = _state.value.copy(touchRate = rate)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.frameInterpolation.collect { enabled ->
                _state.value = _state.value.copy(frameInterpolation = enabled)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.frameInterpolationSr.collect { enabled ->
                _state.value = _state.value.copy(frameInterpolationSr = enabled)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.rotationSuggestion.collect { enabled ->
                _state.value = _state.value.copy(rotationSuggestion = enabled)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.thermalBrightness.collect { enabled ->
                _state.value = _state.value.copy(thermalBrightness = enabled)
            }
        }
    }

    private fun run(block: suspend () -> magicau.mtkcontroller.data.tweaks.TweakRepository.Outcome) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null, lastFailures = emptyList())
            val outcome = block()
            _state.value = _state.value.copy(
                busy = false,
                message = outcome.message,
                lastFailures = outcome.failures,
            )
        }
    }

    fun setTouch(enabled: Boolean) =
        run { container.tweakRepository.setTouchOptimization(enabled, _state.value.touchRate) }

    fun setTouchRate(rate: Int) =
        run { container.tweakRepository.setTouchOptimization(true, rate) }

    fun setFrameInterpolation(enabled: Boolean) =
        run { container.tweakRepository.setFrameInterpolation(enabled, superResolution = false) }

    fun setFrameInterpolationSr(enabled: Boolean) =
        run { container.tweakRepository.setFrameInterpolation(enabled, superResolution = true) }

    fun setRotationSuggestion(enabled: Boolean) =
        run { container.tweakRepository.setRotationSuggestion(enabled) }

    fun setThermalBrightness(enabled: Boolean) {
        run {
            val outcome = container.tweakRepository.setThermalBrightnessWatch(enabled)
            if (outcome.success) {
                if (enabled) ThermalBrightnessService.start(container.context)
                else ThermalBrightnessService.stop(container.context)
            }
            outcome
        }
    }

    fun requestPermission() {
        PrivilegeManager.requestPermission()
        PrivilegeManager.refresh()
    }
}
