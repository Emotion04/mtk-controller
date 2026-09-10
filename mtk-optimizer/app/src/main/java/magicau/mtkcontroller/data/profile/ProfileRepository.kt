package magicau.mtkcontroller.data.profile

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import magicau.mtkcontroller.domain.model.Profile

private val Context.profileStore: DataStore<Preferences> by preferencesDataStore(name = "mtk_optimizer")

/**
 * Persists saved profiles plus the small bits of runtime state we must not lose
 * across process death: the PowerHAL handler ids returned by acquire, and the
 * cluster limits observed before we first touched anything.
 */
class ProfileRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val profilesKey = stringPreferencesKey("profiles_json")
    private val activeProfileKey = stringPreferencesKey("active_profile_id")
    private val cpuHandlerKey = intPreferencesKey("cpu_handler")
    private val gpuHandlerKey = intPreferencesKey("gpu_handler")
    private val baselineKey = stringPreferencesKey("baseline_json")

    val profiles: Flow<List<Profile>> = context.profileStore.data.map { prefs ->
        prefs[profilesKey]
            ?.let { raw -> runCatching { json.decodeFromString<List<Profile>>(raw) }.getOrDefault(emptyList()) }
            ?: emptyList()
    }

    val activeProfileId: Flow<String?> = context.profileStore.data.map { it[activeProfileKey] }

    suspend fun upsert(profile: Profile) {
        val current = profiles.first().filterNot { it.id == profile.id }
        writeProfiles(current + profile)
    }

    suspend fun delete(id: String) {
        writeProfiles(profiles.first().filterNot { it.id == id })
        if (activeProfileId.first() == id) setActiveProfile(null)
    }

    suspend fun rename(id: String, newName: String) {
        writeProfiles(profiles.first().map { if (it.id == id) it.copy(name = newName) else it })
    }

    private suspend fun writeProfiles(list: List<Profile>) {
        context.profileStore.edit { it[profilesKey] = json.encodeToString(list) }
    }

    suspend fun setActiveProfile(id: String?) {
        context.profileStore.edit {
            if (id == null) it.remove(activeProfileKey) else it[activeProfileKey] = id
        }
    }

    // --- PowerHAL handler ids -------------------------------------------------

    suspend fun cpuHandler(): Int = context.profileStore.data.first()[cpuHandlerKey] ?: 0

    suspend fun setCpuHandler(handler: Int) {
        context.profileStore.edit { it[cpuHandlerKey] = handler }
    }

    suspend fun gpuHandler(): Int = context.profileStore.data.first()[gpuHandlerKey] ?: 0

    suspend fun setGpuHandler(handler: Int) {
        context.profileStore.edit { it[gpuHandlerKey] = handler }
    }

    // --- baseline (pre-first-touch limits), for a faithful release ------------

    suspend fun saveBaseline(entries: Map<String, String>) {
        context.profileStore.edit { it[baselineKey] = json.encodeToString(entries) }
    }

    suspend fun baseline(): Map<String, String> = context.profileStore.data.first()[baselineKey]
        ?.let { raw -> runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap()) }
        ?: emptyMap()
}
