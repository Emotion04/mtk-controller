package magicau.mtkcontroller.data.tweaks

import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.settings.SettingChannel
import java.math.BigDecimal

/** 验证固定白名单参数；不将自由输入拼进 shell。 */
object TweakValues {
    fun animation(value: String?): String? {
        val number = value?.toBigDecimalOrNull() ?: return null
        if (number < BigDecimal.ZERO || number > BigDecimal.TEN || number.remainder(BigDecimal("0.1")).compareTo(BigDecimal.ZERO) != 0) return null
        return number.setScale(1).toPlainString()
    }

    /**
     * 充电上限。
     *
     * 故意只做"正整数且不超过 100"这种粗校验：smart_charge_upper_value 的语义
     * （百分比还是原始值）没有可靠来源，这里不发明档位，只保证写进去的是个正常数字，
     * 别的交给用户在系统电池页核对。
     */
    fun chargeLimit(value: String?): String? {
        val number = value?.trim()?.toIntOrNull() ?: return null
        return if (number in 1..100) number.toString() else null
    }

    /**
     * `vts_game_para_adjust` 的取值：逗号分隔的几个数字。
     *
     * 语义未公开，而且真机现值（`1,6,6,6`）和常被引用的外部取值（`1,7,7,7` / `0,3,3,3`）
     * 三者互不相同，说明是机型相关。所以这里只做形式校验 —— 保证是 3–6 个 0–999 的
     * 整数，别的交给用户按本机情况判断。
     */
    fun touchAdjust(value: String?): String? {
        val parts = value?.trim()?.split(',')?.map { it.trim() } ?: return null
        if (parts.size !in 3..6) return null
        // 每一段都必须是 0–999 的整数；写反成 `!= true` 会把合法值全判成非法。
        if (parts.any { val n = it.toIntOrNull(); n == null || n !in 0..999 }) return null
        return parts.joinToString(",")
    }

    fun matches(key: String, expected: String, actual: String?): Boolean {
        if (expected == actual) return true
        if (key !in listOf("global animator_duration_scale", "global window_animation_scale", "global transition_animation_scale")) return false
        val a = expected.toBigDecimalOrNull() ?: return false
        val b = actual?.toBigDecimalOrNull() ?: return false
        return a.compareTo(b) == 0
    }

    fun htp(preset: String?): Map<String, String>? {
        val (level, temperature) = when (preset) {
            "low" -> "0" to "50"
            "medium" -> "1" to "48"
            "high" -> "2" to "43"
            else -> return null
        }
        return mapOf("system pem_htp_level" to level, "system pem_htp_thred" to "$temperature-52-58-1000-1000-1000")
    }

    /**
     * 游戏帧率解锁：目标帧率按屏幕实际支持的最高刷新率来选，不虚构档位。
     * 只取 60/90/120 这几个常见档；屏幕达不到的值写进去也没有意义。
     */
    fun frameUnlock(option: String?): Map<String, String>? {
        val fps = when (option) {
            "60" -> "60"
            "90" -> "90"
            "120" -> "120"
            else -> return null
        }
        return mapOf(
            "global game_mode_config_fps_unlock" to "1",
            "global game_mode_unlocked_frame_rate" to fps,
            "global game_default_frame_rate" to fps,
            "global game_mode_intervention_enabled" to "0",
        )
    }

    /**
     * 一个性能档位。新增档位只加一条数据，不加分支。
     *
     * @param value 档位编号。**外部取值，不是公开语义**：这四组数字来自对固件的实测，
     *   官方没有文档。改成 `when` 认不出的值会直接拒绝。
     * @param extraKeys 这一档除主键外还要写的键。本机没有这些键，这一档就不给用
     *   （界面上置灰），参见 [VivoTweaks.availableKeys]。
     */
    data class PerfLevel(
        val id: String,
        val value: String,
        val labelRes: Int,
        val extraKeys: List<String> = emptyList(),
    ) {
        /** 这一档本机能不能用。主键不是可选项 —— 它不在就等于整项不支持。 */
        fun supported(availableKeys: Set<String>): Boolean =
            MAIN_KEYS.all { it in availableKeys } && extraKeys.all { it in availableKeys }
    }

    /**
     * 四档。编号实测对应：增强 6 / BOOST+ 8 / Monster 5 / Monster+ 7。
     *
     * Monster 两档要额外写 `monster_key_start`。**这台 PD2430A 上该键出厂就是 `null`，
     * 一个 monster 相关 prop 都没有**，所以这两档在本机置灰；别的机型键存在，照写。
     */
    val PERF_LEVELS = listOf(
        PerfLevel("enhanced", "6", R.string.perf_level_enhanced),
        PerfLevel("boost_plus", "8", R.string.perf_level_boost_plus),
        PerfLevel("monster", "5", R.string.perf_level_monster, listOf("system monster_key_start")),
        PerfLevel("monster_plus", "7", R.string.perf_level_monster_plus, listOf("system monster_key_start")),
    )

    fun perfLevel(option: String?): PerfLevel? = PERF_LEVELS.firstOrNull { it.id == option }

    /**
     * 三个主键：**系统自己开性能档时写的就是这三个。**
     *
     * 真机实测（用户在系统里开"增强"档后逐表核对）：
     * ```
     * system power_save_type                  1 → 6
     * secure power_save_type                  1 → 6
     * secure system_property_power_mode_type   1 → 6
     * ```
     * 另外两个候选键系统从不读取：`game_plus_mode_key` 恒为 0，
     * `monster_key_start` 在这台机器上根本不存在。
     *
     * **教训**：早前只写了 `system power_save_type` 一个，依据是一次 `settings list`
     * 快照对比 ——那次 grep 把 secure 表里那两行滤掉了，我读成"系统没动它们"。
     * 判断"系统写哪些键"必须逐表 `settings get` 核对，不能靠过滤后的 diff。
     */
    val MAIN_KEYS = listOf(
        "system power_save_type",
        "secure power_save_type",
        "secure system_property_power_mode_type",
    )

    /**
     * 这一档要写哪些键。三个主键必写；可选键只写本机确实存在的。
     *
     * @param availableKeys 本机存在的登记键，来自 [VivoTweaks.availableKeys]。
     */
    fun performanceMode(option: String?, availableKeys: Set<String>): Map<String, String>? {
        val level = perfLevel(option) ?: return null
        val result = MAIN_KEYS.associateWith { level.value }.toMutableMap()
        level.extraKeys.forEach { key -> if (key in availableKeys) result[key] = "1" }
        return result
    }
}

/** 清理在手机 shell 中执行，离开 APP 页面不取消定时恢复；强杀 shell 仍需手动恢复快照。 */
object SettingExperiment {
    fun script(targets: Map<String, String>, original: Map<String, String>): String {
        val allowed = (VivoFeature.GAME_THERMAL.values.keys + VivoFeature.HTP.values.keys).toSet()
        require(targets.keys == original.keys && targets.isNotEmpty() && targets.keys.all { it in allowed })
        /**
         * 生成写命令。**走 [SettingChannel.putCommand]**，见那里的说明：
         * 这台 ROM 上 `settings put` 退出码 0 但不落库，限时实验因此从来没真正写过。
         */
        fun write(key: String, value: String): String {
            val namespace = key.substringBefore(' ')
            val name = key.substringAfter(' ')
            return if (value == "null") "timeout 2 ${SettingChannel.deleteCommand(namespace, name)}"
            else "timeout 2 ${SettingChannel.putCommand(namespace, name, value)}"
        }
        return buildString {
            appendLine("cleanup() {")
            original.forEach { (key, value) -> appendLine("${write(key, value)}; printf '[restore] $key '; timeout 2 settings get $key") }
            appendLine("}")
            appendLine("trap cleanup EXIT; trap 'exit 1' HUP INT TERM")
            original.forEach { (key, value) -> appendLine("[ \"\$(timeout 2 settings get $key)\" = ${VivoReply.quote(value)} ] || exit 2") }
            targets.forEach { (key, value) ->
                appendLine("${write(key, value)} || exit 3")
                appendLine("[ \"\$(timeout 2 settings get $key)\" = ${VivoReply.quote(value)} ] || exit 4")
            }
            appendLine("echo '[experiment-applied]'; sleep 20")
        }
    }
}
