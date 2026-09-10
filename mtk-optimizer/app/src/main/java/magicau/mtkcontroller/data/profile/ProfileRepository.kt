package magicau.mtkcontroller.data.profile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import magicau.mtkcontroller.data.profileStore
import magicau.mtkcontroller.domain.model.Profile

/**
 * Saved profiles and the GPU PowerHAL handle.
 *
 * CPU control state deliberately lives elsewhere — see `CpuControlStore` — so
 * that everything the CPU coordinator must remember has one home and one
 * lifetime.
 */
class ProfileRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val profilesKey = stringPreferencesKey("profiles_json")
    private val activeProfileKey = stringPreferencesKey("active_profile_id")
    private val gpuHandlerKey = intPreferencesKey("gpu_handler")

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

    /** Wholesale replace, used by backup import. */
    suspend fun replaceAll(profiles: List<Profile>, activeId: String?) {
        writeProfiles(profiles)
        setActiveProfile(activeId?.takeIf { id -> profiles.any { it.id == id } })
    }

    private suspend fun writeProfiles(list: List<Profile>) {
        context.profileStore.edit { it[profilesKey] = json.encodeToString(list) }
    }

    suspend fun setActiveProfile(id: String?) {
        context.profileStore.edit {
            if (id == null) it.remove(activeProfileKey) else it[activeProfileKey] = id
        }
    }

    // --- GPU PowerHAL handle -------------------------------------------------

    suspend fun gpuHandler(): Int = context.profileStore.data.first()[gpuHandlerKey] ?: 0

    suspend fun setGpuHandler(handler: Int) {
        context.profileStore.edit { it[gpuHandlerKey] = handler }
    }
}
