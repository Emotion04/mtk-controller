package magicau.mtkcontroller.feature.changelog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.changelog.ChangelogEntry
import magicau.mtkcontroller.data.changelog.ChangelogRepository
import magicau.mtkcontroller.di.AppContainer

class ChangelogViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val entries: List<ChangelogEntry> = emptyList(),
        val remote: Boolean = false,
        val error: Failure? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val repository = ChangelogRepository(container.context)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val result = repository.load()
            _state.value = State(
                loading = false,
                entries = result.entries,
                remote = result.remote,
                error = result.error?.let { Failure(Message(magicau.mtkcontroller.R.string.changelog_remote_failed), it) },
            )
        }
    }
}
