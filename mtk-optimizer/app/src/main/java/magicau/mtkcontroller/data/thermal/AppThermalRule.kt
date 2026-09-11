package magicau.mtkcontroller.data.thermal

import android.content.Context
import androidx.datastore.preferences.core.edit
import magicau.mtkcontroller.data.settingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 一条「应用 → 温控策略索引」的规则。
 *
 * @param pkg 应用包名，必须经过 `pm path` 校验（由调用方保证）。
 * @param policyIndex 温控策略索引，必须是设备上真有的一个
 *   （用 [ThermalPolicyReader.availableIndices] 校验）。
 * @param label 用户给这条规则起的名字，纯展示用。
 */
data class AppThermalRule(
    val pkg: String,
    val policyIndex: Int,
    val label: String = "",
)

/**
 * 「按应用温控」的规则表存储。
 *
 * 存 DataStore，跟随备份（与 Profile 同一套 store，见 [AppThermalStore] 的 key）。
 * **不存任何"每个索引是什么意思"** —— 那是会随 ROM 变的分析结论，
 * 见 `docs/thermal/facts.md`。
 */
class AppThermalStore(private val context: Context) {

    private val store get() = context.settingsStore

    val rules: Flow<List<AppThermalRule>> = store.data.map { prefs ->
        decode(prefs[KEY]).sortedBy { it.pkg }
    }

    suspend fun add(rule: AppThermalRule) = mutate { current ->
        if (current.any { it.pkg == rule.pkg }) {
            current.map { if (it.pkg == rule.pkg) rule else it }
        } else {
            current + rule
        }
    }

    suspend fun remove(pkg: String) = mutate { current -> current.filterNot { it.pkg == pkg } }

    suspend fun clear() = mutate { emptyList() }

    private suspend fun mutate(transform: (List<AppThermalRule>) -> List<AppThermalRule>) {
        store.edit { prefs ->
            val next = transform(decode(prefs[KEY])).distinctBy { it.pkg }
            prefs[KEY] = next.encode()
        }
    }

    private companion object {
        val KEY = androidx.datastore.preferences.core.stringPreferencesKey("app_thermal_rules")

        /** 格式 `pkg|索引|标签`，一行一条。标签里不许出现 \n 和 |（写入前挡掉）。 */
        fun List<AppThermalRule>.encode(): String =
            joinToString("\n") { "${it.pkg}|${it.policyIndex}|${it.label.replace("\n", " ")}" }

        fun decode(raw: String?): List<AppThermalRule> =
            raw?.lineSequence()?.mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size < 2) return@mapNotNull null
                val pkg = parts[0].trim()
                val index = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                if (pkg.isEmpty() || index < 0) return@mapNotNull null
                AppThermalRule(pkg, index, parts.getOrNull(2)?.trim().orEmpty())
            }?.toList().orEmpty()
    }
}

