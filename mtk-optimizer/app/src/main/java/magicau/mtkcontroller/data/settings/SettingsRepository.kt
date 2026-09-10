package magicau.mtkcontroller.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import magicau.mtkcontroller.domain.model.DEFAULT_HOME_CARDS
import magicau.mtkcontroller.domain.model.HomeCard
import magicau.mtkcontroller.domain.model.HomeCardType
import java.util.UUID

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "mtk_optimizer_settings")

/**
 * User preferences that are not part of a profile: which tab to open on launch,
 * and the home dashboard layout.
 *
 * Routes are stored as plain strings so this layer stays free of UI types.
 */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val defaultTabKey = stringPreferencesKey("default_tab_route")
    private val homeCardsKey = stringPreferencesKey("home_cards_json")
    private val migratedV2Key = booleanPreferencesKey("migrated_v2_quick_settings")
    private val paletteKey = stringPreferencesKey("theme_palette")
    private val navBarStyleKey = stringPreferencesKey("nav_bar_style")
    private val navBarCustomKey = stringPreferencesKey("nav_bar_custom_palette")
    private val cpuControlKey = stringPreferencesKey("cpu_control_style")

    val defaultTabRoute: Flow<String?> = context.settingsStore.data.map { it[defaultTabKey] }

    val themePalette: Flow<String> = context.settingsStore.data.map { it[paletteKey] ?: "SYSTEM" }

    suspend fun setThemePalette(name: String) {
        context.settingsStore.edit { it[paletteKey] = name }
    }

    val navBarStyle: Flow<String> = context.settingsStore.data.map { it[navBarStyleKey] ?: "COLORFUL" }

    suspend fun setNavBarStyle(name: String) {
        context.settingsStore.edit { it[navBarStyleKey] = name }
    }

    val navBarCustomPalette: Flow<String> =
        context.settingsStore.data.map { it[navBarCustomKey] ?: "TEAL" }

    suspend fun setNavBarCustomPalette(name: String) {
        context.settingsStore.edit { it[navBarCustomKey] = name }
    }

    val cpuControlStyle: Flow<String> =
        context.settingsStore.data.map { it[cpuControlKey] ?: "RANGE_SLIDER" }

    suspend fun setCpuControlStyle(name: String) {
        context.settingsStore.edit { it[cpuControlKey] = name }
    }

    val homeCards: Flow<List<HomeCard>> = context.settingsStore.data.map { prefs ->
        prefs[homeCardsKey]
            ?.let { raw -> runCatching { json.decodeFromString<List<HomeCard>>(raw) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_HOME_CARDS
    }

    /**
     * One-time upgrade for installs that predate the quick-settings card: make
     * sure it shows up without forcing the user to reset their layout.
     */
    suspend fun migrateIfNeeded() {
        val prefs = context.settingsStore.data.first()
        if (prefs[migratedV2Key] == true) return
        val current = homeCards.first()
        if (current.none { it.type == HomeCardType.QUICK_SETTINGS }) {
            write(
                listOf(HomeCard(id = "card-quick-settings", type = HomeCardType.QUICK_SETTINGS)) + current,
            )
        }
        context.settingsStore.edit { it[migratedV2Key] = true }
    }

    suspend fun setDefaultTabRoute(route: String?) {
        context.settingsStore.edit {
            if (route == null) it.remove(defaultTabKey) else it[defaultTabKey] = route
        }
    }

    suspend fun addCard(type: HomeCardType) {
        val current = homeCards.first()
        if (current.any { it.type == type }) return
        write(current + HomeCard(id = UUID.randomUUID().toString(), type = type))
    }

    suspend fun removeCard(id: String) {
        write(homeCards.first().filterNot { it.id == id })
    }

    suspend fun moveCard(fromIndex: Int, toIndex: Int) {
        val current = homeCards.first().toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        current.add(toIndex, current.removeAt(fromIndex))
        write(current)
    }

    suspend fun resetCards() = write(DEFAULT_HOME_CARDS)

    private suspend fun write(cards: List<HomeCard>) {
        context.settingsStore.edit { it[homeCardsKey] = json.encodeToString(cards) }
    }
}
