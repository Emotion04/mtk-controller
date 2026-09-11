package magicau.mtkcontroller.data.cpu

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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
interface CpuSettingsStore {
    suspend fun baseline(): String?
    suspend fun setBaseline(value: String?)
    suspend fun lastApplied(): List<ClusterSetting>
    suspend fun setLastApplied(settings: List<ClusterSetting>)

    /**
     * 用户**最后一次要求**的范围，释放后仍保留。
     *
     * 与 [lastApplied] 的区别只在释放之后：后者是“现在持有什么”，前者是
     * “下次一键恢复该发什么”。快捷开关和亮屏重发都读这一份。
     */
    suspend fun lastRequested(): List<ClusterSetting>
    suspend fun setLastRequested(settings: List<ClusterSetting>)

    /**
     * 用户是否还希望由本软件控制频率。
     *
     * 锁屏释放是**自动**动作，不改变这个意图；用户在界面或快捷开关里释放才置 false。
     * 后台服务据此决定锁屏释放后还要不要在亮屏时恢复。
     */
    suspend fun controlEnabled(): Boolean
    suspend fun setControlEnabled(enabled: Boolean)
}

class CpuControlStore(private val context: Context) : CpuSettingsStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val baselineKey = stringPreferencesKey("cpu_baseline_limits")
    private val appliedKey = stringPreferencesKey("cpu_last_applied")
    private val requestedKey = stringPreferencesKey("cpu_last_requested")
    private val controlEnabledKey = booleanPreferencesKey("cpu_control_enabled")

    /**
     * `scaling_min_freq`/`scaling_max_freq` as they were before this app ever
     * touched anything. Null until we have managed to observe a clean state.
     */
    override suspend fun baseline(): String? = context.profileStore.data.first()[baselineKey]

    override suspend fun setBaseline(value: String?) {
        context.profileStore.edit {
            if (value == null) it.remove(baselineKey) else it[baselineKey] = value
        }
    }

    /** What to replay, so the re-apply service can work without the UI. */
    override suspend fun lastApplied(): List<ClusterSetting> =
        context.profileStore.data.first()[appliedKey]
            ?.let { raw ->
                runCatching { json.decodeFromString<List<ClusterSetting>>(raw) }.getOrDefault(emptyList())
            }
            ?: emptyList()

    override suspend fun setLastApplied(settings: List<ClusterSetting>) {
        context.profileStore.edit { it[appliedKey] = json.encodeToString(settings) }
    }

    /**
     * 旧版本没有这个键：从 [appliedKey] 回退，升级后快捷开关立刻可用。
     * 用户主动释放过（applied 为空）时回退结果也是空，不会凭空恢复一个旧范围。
     */
    override suspend fun lastRequested(): List<ClusterSetting> {
        val prefs = context.profileStore.data.first()
        return prefs[requestedKey]?.let(::decode)
            ?: prefs[appliedKey]?.let(::decode)
            ?: emptyList()
    }

    override suspend fun setLastRequested(settings: List<ClusterSetting>) {
        context.profileStore.edit { it[requestedKey] = json.encodeToString(settings) }
    }

    override suspend fun controlEnabled(): Boolean {
        val prefs = context.profileStore.data.first()
        return prefs[controlEnabledKey]
            ?: (prefs[appliedKey]?.let(::decode)?.isNotEmpty() == true)
    }

    override suspend fun setControlEnabled(enabled: Boolean) {
        context.profileStore.edit { it[controlEnabledKey] = enabled }
    }

    private fun decode(raw: String): List<ClusterSetting> =
        runCatching { json.decodeFromString<List<ClusterSetting>>(raw) }.getOrDefault(emptyList())
}
