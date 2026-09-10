package magicau.mtkcontroller.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Minimal factory so ViewModels can take [AppContainer] without Hilt.
 * Replaced by @HiltViewModel when Hilt lands; call sites stay the same.
 */
inline fun <reified VM : ViewModel> containerViewModelFactory(
    crossinline create: () -> VM,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}
