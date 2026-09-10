package magicau.mtkcontroller.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.CpuControlStyle
import magicau.mtkcontroller.domain.model.NavBarStyle
import magicau.mtkcontroller.ui.navigation.Destination
import magicau.mtkcontroller.ui.theme.AppPalette

data class SettingsUiState(
    val loading: Boolean = true,
    val defaultTab: Destination = Destination.HOME,
    val palette: AppPalette = AppPalette.SYSTEM,
    val navBarStyle: NavBarStyle = NavBarStyle.COLORFUL,
    val navBarCustomPalette: AppPalette = AppPalette.TEAL,
    val cpuControlStyle: CpuControlStyle = CpuControlStyle.RANGE_SLIDER,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsRepository.defaultTabRoute.collect { route ->
                _state.value = _state.value.copy(
                    loading = false,
                    defaultTab = Destination.fromRoute(route),
                )
            }
        }
        viewModelScope.launch {
            container.settingsRepository.themePalette.collect { name ->
                _state.value = _state.value.copy(loading = false, palette = AppPalette.fromName(name))
            }
        }
        viewModelScope.launch {
            container.settingsRepository.navBarStyle.collect { name ->
                _state.value = _state.value.copy(navBarStyle = NavBarStyle.fromName(name))
            }
        }
        viewModelScope.launch {
            container.settingsRepository.navBarCustomPalette.collect { name ->
                _state.value = _state.value.copy(navBarCustomPalette = AppPalette.fromName(name))
            }
        }
        viewModelScope.launch {
            container.settingsRepository.cpuControlStyle.collect { name ->
                _state.value = _state.value.copy(cpuControlStyle = CpuControlStyle.fromName(name))
            }
        }
    }

    fun setDefaultTab(destination: Destination) {
        viewModelScope.launch { container.settingsRepository.setDefaultTabRoute(destination.route) }
    }

    fun setPalette(palette: AppPalette) {
        viewModelScope.launch { container.settingsRepository.setThemePalette(palette.name) }
    }

    fun setNavBarStyle(style: NavBarStyle) {
        viewModelScope.launch { container.settingsRepository.setNavBarStyle(style.name) }
    }

    fun setNavBarCustomPalette(palette: AppPalette) {
        viewModelScope.launch { container.settingsRepository.setNavBarCustomPalette(palette.name) }
    }

    fun setCpuControlStyle(style: CpuControlStyle) {
        viewModelScope.launch { container.settingsRepository.setCpuControlStyle(style.name) }
    }
}
