package magicau.mtkcontroller.data.profile

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
    private val gpuActiveKey = booleanPreferencesKey("gpu_active")
    private val gpuMinKey = longPreferencesKey("gpu_min_baseline")
    private val gpuMaxKey = longPreferencesKey("gpu_max_baseline")

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

    private companion object {
        /** 节点读不到时存这个哨兵；不能和真实的 kHz 混淆。 */
        const val NO_BASELINE = Long.MIN_VALUE
    }

    suspend fun setActiveProfile(id: String?) {
        context.profileStore.edit {
            if (id == null) it.remove(activeProfileKey) else it[activeProfileKey] = id
        }
    }

    // --- GPU 原值（devfreq） -------------------------------------------------
    //
    // 原值与"有没有应用过"一起持久化。GpuTuner 原来把基线放在内存里，
    // 进程一死就只剩"硬件范围"这个兜底 —— 用它恢复会把用户或散热压出来的上限
    // 覆盖成硬件最高频。所以写之前就落盘，恢复时按落盘的值写回。

    /** null 表示这个节点当时读不到，恢复时不动它。 */
    suspend fun gpuBaseline(): Pair<Long?, Long?>? {
        val prefs = context.profileStore.data.first()
        if (prefs[gpuActiveKey] != true) return null
        val min = prefs[gpuMinKey]?.takeIf { it != NO_BASELINE }
        val max = prefs[gpuMaxKey]?.takeIf { it != NO_BASELINE }
        return min to max
    }

    suspend fun gpuApplied(): Boolean = context.profileStore.data.first()[gpuActiveKey] == true

    suspend fun setGpuBaseline(min: Long?, max: Long?) {
        context.profileStore.edit {
            it[gpuActiveKey] = true
            it[gpuMinKey] = min ?: NO_BASELINE
            it[gpuMaxKey] = max ?: NO_BASELINE
        }
    }

    suspend fun clearGpu() {
        context.profileStore.edit {
            it.remove(gpuActiveKey)
            it.remove(gpuMinKey)
            it.remove(gpuMaxKey)
        }
    }
}
