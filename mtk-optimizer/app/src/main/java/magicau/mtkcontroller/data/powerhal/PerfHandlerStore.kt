package magicau.mtkcontroller.data.powerhal

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first
import magicau.mtkcontroller.data.profileStore

/**
 * Live PowerHAL handles, one slot per independent area of control.
 *
 * Persisted rather than kept in memory because a handle must survive a screen
 * going away or the process being killed by the system — a request we can still
 * name is a request we can still release, and one we cannot name is stuck until
 * the process that issued it dies.
 */
class PerfHandlerStore(private val context: Context) {

    private fun keyFor(name: String) = intPreferencesKey("perf_handler_$name")

    suspend fun get(name: String): Int {
        val prefs = context.profileStore.data.first()
        prefs[keyFor(name)]?.let { return it }

        // One-time carry-over from when CPU was the only controlled area: leave
        // a live handle behind and its request can never be released again.
        if (name == CPU_LEGACY_NAME) {
            val legacy = prefs[LEGACY_INT_KEY]
            if (legacy != null && legacy > 0) {
                set(name, legacy)
                return legacy
            }
        }
        return 0
    }

    suspend fun set(name: String, value: Int) {
        context.profileStore.edit {
            if (value <= 0) it.remove(keyFor(name)) else it[keyFor(name)] = value
        }
    }

    private companion object {
        /** Both [PerfLockController] names and the legacy key use plain "cpu". */
        const val CPU_LEGACY_NAME = "cpu"

        /** The key the CPU handle was stored under before this class existed. */
        val LEGACY_INT_KEY = intPreferencesKey("cpu_handler")
    }
}
