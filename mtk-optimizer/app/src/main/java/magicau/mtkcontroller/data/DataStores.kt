package magicau.mtkcontroller.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * The app's preference stores, declared once.
 *
 * `preferencesDataStore` keys off the file name, so a second delegate for the
 * same name throws at runtime rather than at compile time. Keeping them in one
 * place means a new repository shares the existing file instead of racing it.
 */
internal val Context.profileStore: DataStore<Preferences> by preferencesDataStore(name = "mtk_optimizer")

internal val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "mtk_optimizer_settings")
