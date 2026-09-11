package magicau.mtkcontroller.data.cpu

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import magicau.mtkcontroller.data.profileStore
import magicau.mtkcontroller.domain.model.ClusterSetting

/**
 * Everything [CpuControl] must remember across process death or a reboot.
 *
 * These three used to be split across two classes with different lifetimes —
 * the handler and the replay settings in DataStore, the pre-touch baseline in
 * an in-memory field that silently vanished on restart. Keeping them together
 * is what makes "verify a release against the baseline" trustworthy.
 */
class CpuControlStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val baselineKey = stringPreferencesKey("cpu_baseline_limits")
    private val appliedKey = stringPreferencesKey("cpu_last_applied")

    /**
     * `scaling_min_freq`/`scaling_max_freq` as they were before this app ever
     * touched anything. Null until we have managed to observe a clean state.
     */
    suspend fun baseline(): String? = context.profileStore.data.first()[baselineKey]

    suspend fun setBaseline(value: String?) {
        context.profileStore.edit {
            if (value == null) it.remove(baselineKey) else it[baselineKey] = value
        }
    }

    /** What to replay, so the re-apply service can work without the UI. */
    suspend fun lastApplied(): List<ClusterSetting> =
        context.profileStore.data.first()[appliedKey]
            ?.let { raw ->
                runCatching { json.decodeFromString<List<ClusterSetting>>(raw) }.getOrDefault(emptyList())
            }
            ?: emptyList()

    suspend fun setLastApplied(settings: List<ClusterSetting>) {
        context.profileStore.edit { it[appliedKey] = json.encodeToString(settings) }
    }
}
