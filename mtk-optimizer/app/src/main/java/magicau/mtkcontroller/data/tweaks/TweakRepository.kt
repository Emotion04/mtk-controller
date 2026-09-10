package magicau.mtkcontroller.data.tweaks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import magicau.mtkcontroller.data.privilege.PrivilegeManager

private val Context.tweakStore: DataStore<Preferences> by preferencesDataStore(name = "mtk_optimizer_tweaks")

/**
 * Runs the shell-based tweaks and remembers which ones are on.
 *
 * Every command goes through the elevated UserService, so all of this is a
 * no-op (with a clear message) until Shizuku is granted.
 */
class TweakRepository(private val context: Context) {

    private val touchEnabledKey = booleanPreferencesKey("touch_enabled")
    private val touchRateKey = intPreferencesKey("touch_rate")
    private val thermalBrightnessKey = booleanPreferencesKey("thermal_brightness")
    private val autoRotateKey = booleanPreferencesKey("auto_rotate")
    private val rotationSuggestionKey = booleanPreferencesKey("rotation_suggestion")
    private val interpKey = booleanPreferencesKey("frame_interpolation")
    private val interpSrKey = booleanPreferencesKey("frame_interpolation_sr")

    val touchEnabled: Flow<Boolean> = context.tweakStore.data.map { it[touchEnabledKey] ?: false }
    val touchRate: Flow<Int> = context.tweakStore.data.map { it[touchRateKey] ?: TweakCommands.DEFAULT_TOUCH_RATE }
    val thermalBrightness: Flow<Boolean> = context.tweakStore.data.map { it[thermalBrightnessKey] ?: false }
    val autoRotate: Flow<Boolean> = context.tweakStore.data.map { it[autoRotateKey] ?: false }
    val rotationSuggestion: Flow<Boolean> = context.tweakStore.data.map { it[rotationSuggestionKey] ?: false }
    val frameInterpolation: Flow<Boolean> = context.tweakStore.data.map { it[interpKey] ?: false }
    val frameInterpolationSr: Flow<Boolean> = context.tweakStore.data.map { it[interpSrKey] ?: false }

    data class Outcome(val success: Boolean, val message: String, val failures: List<String> = emptyList())

    /** Runs every command; reports how many failed rather than aborting on the first. */
    private suspend fun runAll(commands: List<String>): Outcome {
        if (!PrivilegeManager.state.value.mode.canElevate) {
            return Outcome(false, "需要先授权 Shizuku")
        }
        val failures = mutableListOf<String>()
        for (command in commands) {
            val output = PrivilegeManager.exec("$command 2>&1")
            if (output == null || output.contains("[exit] 1") || output.contains("[stderr]")) {
                failures += command
            }
        }
        return if (failures.isEmpty()) {
            Outcome(true, "已执行 ${commands.size} 条命令")
        } else {
            Outcome(
                success = false,
                message = "${commands.size - failures.size}/${commands.size} 条成功,${failures.size} 条失败",
                failures = failures,
            )
        }
    }

    suspend fun setTouchOptimization(enabled: Boolean, rateHz: Int = TweakCommands.DEFAULT_TOUCH_RATE): Outcome {
        val outcome = runAll(
            if (enabled) TweakCommands.touchEnable(rateHz) else TweakCommands.touchDisable()
        )
        if (outcome.success) {
            context.tweakStore.edit {
                it[touchEnabledKey] = enabled
                if (enabled) it[touchRateKey] = rateHz
            }
        }
        return outcome
    }

    suspend fun currentTouchRate(): Int = context.tweakStore.data.first()[touchRateKey]
        ?: TweakCommands.DEFAULT_TOUCH_RATE

    suspend fun setFrameInterpolation(enabled: Boolean, superResolution: Boolean): Outcome {
        val outcome = runAll(listOf(TweakCommands.frameInterpolation(enabled, superResolution)))
        if (outcome.success) {
            context.tweakStore.edit {
                if (superResolution) it[interpSrKey] = enabled else it[interpKey] = enabled
            }
        }
        return outcome
    }

    suspend fun setRotationSuggestion(enabled: Boolean): Outcome {
        val outcome = runAll(listOf(TweakCommands.rotationSuggestion(enabled)))
        if (outcome.success) context.tweakStore.edit { it[rotationSuggestionKey] = enabled }
        return outcome
    }

    suspend fun setAutoRotate(enabled: Boolean): Outcome {
        val outcome = runAll(listOf(TweakCommands.autoRotate(enabled)))
        if (outcome.success) context.tweakStore.edit { it[autoRotateKey] = enabled }
        return outcome
    }

    suspend fun forceRotation(rotation: Int): Outcome = runAll(TweakCommands.forceRotation(rotation))

    suspend fun readAutoRotate(): Boolean {
        val out = PrivilegeManager.exec(TweakCommands.readAutoRotate()) ?: return false
        return out.substringBefore("[exit]").trim() == "1"
    }

    suspend fun setThermalBrightnessWatch(enabled: Boolean): Outcome {
        context.tweakStore.edit { it[thermalBrightnessKey] = enabled }
        return Outcome(true, if (enabled) "已开启高温降亮度恢复" else "已关闭高温降亮度恢复")
    }

    suspend fun currentBrightness(): Int? {
        val out = PrivilegeManager.exec(TweakCommands.readBrightness()) ?: return null
        return out.substringBefore("[exit]").trim().toIntOrNull()
    }

    suspend fun setBrightness(value: Int): Outcome = runAll(listOf(TweakCommands.setBrightness(value)))
}
