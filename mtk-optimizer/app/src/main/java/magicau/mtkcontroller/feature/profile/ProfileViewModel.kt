package magicau.mtkcontroller.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.Profile
import magicau.mtkcontroller.feature.cpu.CpuReapplyService

data class ProfileUiState(
    val loading: Boolean = true,
    val profiles: List<Profile> = emptyList(),
    val activeId: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class ProfileViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.profileRepository.profiles.collect { list ->
                _state.value = _state.value.copy(loading = false, profiles = list)
            }
        }
        viewModelScope.launch {
            container.profileRepository.activeProfileId.collect { id ->
                _state.value = _state.value.copy(activeId = id)
            }
        }
    }

    fun apply(profile: Profile) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val clusters = container.cpuScanner.scan()
            if (clusters.isEmpty()) {
                _state.value = _state.value.copy(busy = false, message = "未检测到 CPU 簇,无法套用")
                return@launch
            }
            val outcome = container.cpuControl.apply(clusters, profile.clusters)
            if (outcome.success) container.profileRepository.setActiveProfile(profile.id)
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    fun delete(profile: Profile) {
        viewModelScope.launch {
            container.profileRepository.delete(profile.id)
            _state.value = _state.value.copy(message = "已删除「${profile.name}」")
        }
    }

    fun rename(profile: Profile, newName: String) {
        viewModelScope.launch {
            container.profileRepository.rename(profile.id, newName)
            _state.value = _state.value.copy(message = "已重命名为「$newName」")
        }
    }

    fun release() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val clusters = withContext(Dispatchers.IO) { container.cpuScanner.scan(deep = false) }
            val outcome = container.cpuControl.release(clusters)
            if (outcome.success) container.profileRepository.setActiveProfile(null)
            // Polling would just re-acquire what we released.
            CpuReapplyService.stop(container.context)
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
