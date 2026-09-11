package magicau.mtkcontroller.feature.expiry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.di.AppContainer

/**
 * 过期页只做两件"安全出口"的事：撤掉还在生效的请求、把本软件写过的设置恢复默认。
 *
 * 它不是功能页：不读设备状态、不展示方案、不提供任何进入主界面的路径。
 */
class ExpiryViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val busy: Boolean = false,
        val message: Failure? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun releaseAll() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val result = runCatching {
                val clusters = container.cpuScanner.scan(deep = false)
                container.releaseAllRequests(clusters)
            }.getOrNull()
            _state.value = _state.value.copy(
                busy = false,
                message = if (result == null) {
                    Failure(magicau.mtkcontroller.data.Message(magicau.mtkcontroller.R.string.expiry_action_failed))
                } else {
                    Failure(result.message, result.detail)
                },
            )
        }
    }

    fun restoreDefaults() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val result = runCatching { container.tweakRepository.resetAllToSystemDefaults() }.getOrNull()
            _state.value = _state.value.copy(
                busy = false,
                message = if (result == null) {
                    Failure(magicau.mtkcontroller.data.Message(magicau.mtkcontroller.R.string.expiry_action_failed))
                } else {
                    Failure(result.message, result.detail)
                },
            )
        }
    }
}
