package magicau.mtkcontroller.data.tweaks

import android.content.Context
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.settings.RecoverySnapshot
import magicau.mtkcontroller.data.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.settings.SettingChannel
import magicau.mtkcontroller.data.privilege.PrivilegeManager

enum class TouchBackend(val labelRes: Int) {
    LEGACY(R.string.touch_backend_legacy), VIVO(R.string.touch_backend_vivo)
}

enum class BrightnessBackend(val labelRes: Int) {
    LEGACY(R.string.brightness_backend_legacy), VIVO(R.string.brightness_backend_vivo)
}

/**
 * 一个 vivo 配置项生效多久。
 *
 * [PERSISTENT] 一直生效、跨重启保留，由用户手动恢复。适用于**用户能直接在系统页面
 *   看到结果**的开关 —— 那种情况下自动还原只会干扰他，所以不设期限。
 * [TIMED] 写入后固定秒数自动还原。用于没有可见反馈、忘了关会有代价的实验。
 *
 * 两者都有写入前快照和写后回读；区别只在"要不要到点自动收回"。
 */
enum class TweakLifetime {
    PERSISTENT,
    TIMED,
}

/**
 * 这个配置项的证据强度。
 *
 * 写 settings 键几乎总能写进去、也总能读回来 —— 但那只证明"写成功"，不证明系统
 * 真的消费了它。三类分开标，界面上直接挂徽标，不把"写成功"当成"生效"。
 */
/**
 * 证据等级，**声明顺序就是强弱顺序**。
 *
 * 正式包按 `-PminEvidence=` 卡一个下限，比下限低的项不列（见 `TweaksViewModel.touchItemsById`）。
 * 所以顺序不能随便调：`LIKELY_USELESS < WRITTEN < CONFIRMED`，
 * `compareTo` 等于「比谁更有把握」。
 */
enum class Evidence(val labelRes: Int) {
    /** 本机没有对应硬件或接口，预计写了也不起作用。 */
    LIKELY_USELESS(R.string.evidence_useless),

    /** 读回一致，但没有任何读数能证明系统用了它。 */
    WRITTEN(R.string.evidence_written),

    /** 有系统侧读数，写没写中能直接看出来。 */
    CONFIRMED(R.string.evidence_confirmed),
}

/**
 * 能力门禁：这项在本机是否真的有对应的实现。
 *
 * 之所以需要它，而不是只看 settings 键存不存在：同一台 ROM 可能带着消费方代码，
 * 但没给这个机型开对应的键。**键不存在就是本机不支持**，此时写进去等于新建一个
 * 没人读的键，界面还显示成功。
 *
 * 判据取实测有效的三类：
 * 查包是否安装、查 prop 是否等于某值、查键是否存在。已在 vivo PD2430A 实测过。
 */
data class FeatureGate(
    /** 必须装了这些包 */
    val packages: List<String> = emptyList(),
    /** 这些 prop 必须等于给定值 */
    val props: Map<String, String> = emptyMap(),
)

/** 门禁没通过时的原因，直接给用户看。 */
class UnsupportedFeatureException(val reason: String) : Exception(reason)

enum class VivoFeature(
    val labelRes: Int,
    /** null = 这一项没有说明；界面不显示信息按钮。 */
    val detailRes: Int?,
    val values: Map<String, String>,
    /** 界面需要额外拼一句说明时的资源；没有就传 0。 */
    val hintRes: Int = 0,
    val allowMissing: Boolean = false,
    val lifetime: TweakLifetime = TweakLifetime.PERSISTENT,
    val evidence: Evidence = Evidence.WRITTEN,
    /**
     * 能力门禁。null = 只按 settings 键判断。
     *
     * 这是"这个机型到底支不支持"的判据，跟 [evidence]（写进去了有没有读数能证明生效）
     * 是两件事：门禁回答"该不该给这个开关"，证据等级回答"这个开关有多可信"。
     */
    val gate: FeatureGate? = null,
    /**
     * 试用这项时该观察什么。给 [FeatureTrial] 用。
     *
     * 这类键写后读回永远等于写入值，没有任何自动探针能判断"系统理不理睬"，
     * 只能由人看。这句就是提示人看哪儿 —— 写用户能感知的现象，不写内部机制。
     */
    val trialHintRes: Int = 0,
) {
    ABE(R.string.vivo_feature_abe, R.string.vivo_feature_abe_detail,
        mapOf("secure abe_unified_switch" to "0"), evidence = Evidence.CONFIRMED,
        trialHintRes = R.string.trial_hint_abe),
    /**
     * 列出全部候选键，但**具体写哪几个由档位决定**（见 [TweakValues.PERF_LEVELS] 与
     * [TweakValues.MAIN_KEYS]）。
     *
     * **系统自己开性能档时写三个键**（用户在系统里开 boost 后逐表核对，2026-09-23）：
     * `system power_save_type`、`secure power_save_type`、
     * `secure system_property_power_mode_type`，三者同时 1 → 6。
     *
     * 另外两个候选键系统从不读取：`game_plus_mode_key` 恒为 0，
     * `monster_key_start` 在这台 PD2430A 上出厂就是 `null`。它们进候选列表只是为了
     * 备份覆盖，写不写由档位的 `extraKeys` 和本机存在性决定。
     *
     * `allowMissing = true`：可选键本机可以没有，别让它把整项拖成不支持。
     */
    PERFORMANCE_MODE(R.string.vivo_feature_performance_mode, R.string.vivo_feature_performance_mode_detail,
        mapOf(
            "system power_save_type" to "",
            "secure power_save_type" to "",
            "system game_plus_mode_key" to "",
            "secure system_property_power_mode_type" to "",
            "system monster_key_start" to "",
        ), allowMissing = true, evidence = Evidence.CONFIRMED,
        trialHintRes = R.string.trial_hint_performance_mode),
    FRAME_UNLOCK(R.string.vivo_feature_frame_unlock, R.string.vivo_feature_frame_unlock_detail,
        mapOf(
            "global game_mode_config_fps_unlock" to "1",
            "global game_mode_unlocked_frame_rate" to "",
            "global game_default_frame_rate" to "",
            "global game_mode_intervention_enabled" to "0",
        ), allowMissing = true, evidence = Evidence.CONFIRMED,
        trialHintRes = R.string.trial_hint_frame_unlock),
    PRELOAD(R.string.vivo_feature_preload, R.string.vivo_feature_preload_detail,
        mapOf("system ai_turbo_enabled" to "1", "system pem_in_sleepmode" to "0"),
        trialHintRes = R.string.trial_hint_preload),
    AI(R.string.vivo_feature_ai, R.string.vivo_feature_ai_detail,
        mapOf("system ai_turbo_enabled" to "1"), trialHintRes = R.string.trial_hint_ai),
    MEMORY(R.string.vivo_feature_memory, R.string.vivo_feature_memory_detail,
        mapOf("system vivo_memory_optimize" to "1", "system memory_boost" to "1"),
        // 真机实测两个键都是 null —— 本机 ROM 没给这个机型定义，写了只会建两个没人读的键。
        // 保留开关，探测后变灰。
        gate = FeatureGate(), trialHintRes = R.string.trial_hint_memory),
    ANIMATION(R.string.vivo_feature_animation, null,
        mapOf("global animator_duration_scale" to "0.5"), hintRes = R.string.vivo_hint_animation, true,
        trialHintRes = R.string.trial_hint_animation),
    WINDOW_ANIMATION(R.string.vivo_feature_window_animation, null,
        mapOf("global window_animation_scale" to "1.0"), hintRes = R.string.vivo_hint_animation, true,
        trialHintRes = R.string.trial_hint_animation),
    TRANSITION_ANIMATION(R.string.vivo_feature_transition_animation, null,
        mapOf("global transition_animation_scale" to "1.0"), hintRes = R.string.vivo_hint_animation, true,
        trialHintRes = R.string.trial_hint_animation),
    GAME_THERMAL(R.string.vivo_feature_game_thermal, R.string.vivo_feature_game_thermal_detail,
        mapOf("system vivo_gamethermal_switch" to "0"), lifetime = TweakLifetime.TIMED,
        trialHintRes = R.string.trial_hint_game_thermal),
    // `pem_htp_level` 的档位含义有实测依据（0 = 最低档，20 秒后自动还原）。
    // 配套的 `pem_htp_thred` 是六段阈值，语义未公开，且实测本机现值与常见取值不同，
    // 说明它按机型/ROM 调 —— 没有依据就只写档位，不猜阈值。
    HTP(R.string.vivo_feature_htp, R.string.vivo_feature_htp_detail,
        mapOf("system pem_htp_level" to "0"),
        allowMissing = true, lifetime = TweakLifetime.TIMED,
        trialHintRes = R.string.trial_hint_htp),
    BRIGHTNESS(R.string.vivo_feature_brightness, R.string.vivo_feature_brightness_detail,
        mapOf("system vivo_lcm_brightness_policy_disable" to "1"),
        trialHintRes = R.string.trial_hint_brightness),
    // 只放取值确定的五个。flash_charge_animation_style / vivo_qs_center_theme_style /
    // device_name / icon_blacklist 的语义没验证过 —— 空值写进去等于把设备名清空、
    // 把状态栏图标黑名单清空，所以移到 SmartPlatform 只读探测，不在这里写。
    // 原来是打包成一项、五个键一起写。拆开是因为它们服务的目的各不相同，
    // 打包只省了一次点击，代价却是用户找不到单个开关、也没法只开其中一项。
    READING_MODE(R.string.vivo_feature_reading_mode, R.string.vivo_feature_reading_mode_detail,
        mapOf("system reading_mode" to "1"), allowMissing = true,
        trialHintRes = R.string.trial_hint_reading_mode),
    ULTRA_DARK(R.string.vivo_feature_ultra_dark_mode, R.string.vivo_feature_ultra_dark_mode_detail,
        mapOf("system ultra_dark_mode" to "1"), allowMissing = true,
        trialHintRes = R.string.trial_hint_ultra_dark_mode),
    CLOCK_SECONDS(R.string.vivo_feature_clock_seconds, R.string.vivo_feature_clock_seconds_detail,
        mapOf("system status_bar_show_seconds" to "1"), allowMissing = true,
        trialHintRes = R.string.trial_hint_clock_seconds),
    GRAY_THEME(R.string.vivo_feature_gray_theme, R.string.vivo_feature_gray_theme_detail,
        mapOf("system vivo_gray_theme" to "1"), allowMissing = true,
        trialHintRes = R.string.trial_hint_gray_theme),
    CLEAR_UPDATE(R.string.vivo_feature_clear_update, R.string.vivo_feature_clear_update_detail,
        mapOf("secure vivo_clear_update_notification" to "1"), allowMissing = true,
        trialHintRes = R.string.trial_hint_clear_update),
    ZEISS_DISPLAY(R.string.vivo_feature_zeiss_display, R.string.vivo_feature_zeiss_display_detail,
        mapOf("system zeiss_display_enabled" to "3"), allowMissing = true,
        trialHintRes = R.string.trial_hint_zeiss_display),
    DNS(R.string.vivo_feature_dns, R.string.vivo_feature_dns_detail,
        linkedMapOf("global private_dns_specifier" to "", "global private_dns_mode" to "hostname"),
        hintRes = R.string.vivo_hint_dns, true, trialHintRes = R.string.trial_hint_dns),

    // 只写取值有可靠来源的那一个键。同一功能还有另外几个键，
    // power_engine_and_plugged_state_and_charging_aod / balance_charge_switch /
    // vivo_keyguard_super_speed_charge_switch / settings_battery_charge_director_game_cube，
    // 但那几个的写入值没有可靠记载 —— 猜一个值写进影响电池的键，
    // 读回也是成功的，错了也看不出来。所以先只做这一个，其余等真机确认。
    //
    // 门禁判据：装机 + prop 为 1。
    // 真机（PD2430A）实测：com.vivo.gamecube 已装、all_bypass_charge_support=1 → 通过。
    DIRECT_POWER(R.string.vivo_feature_direct_power, R.string.vivo_feature_direct_power_detail,
        mapOf("secure settings_battery_charge_director" to "1"), allowMissing = true,
        gate = FeatureGate(
            packages = listOf("com.vivo.gamecube"),
            props = mapOf("persist.vivo.all_bypass_charge_support" to "1"),
        ), trialHintRes = R.string.trial_hint_direct_power),

    // 同理：smart_charge_upper_value 的取值语义（百分比？原始值？）没有可靠来源，
    // 所以不设预设，由用户按本机 ROM 的语义填。
    CHARGE_LIMIT(R.string.vivo_feature_charge_limit, R.string.vivo_feature_charge_limit_detail,
        mapOf("secure smart_charge_upper_value" to ""), allowMissing = true,
        gate = FeatureGate(packages = listOf("com.vivo.gamecube")),
        trialHintRes = R.string.trial_hint_charge_limit),

    /**
     * `vts_game_para_adjust` 的取值是逗号分隔的几个数字，语义未公开。
     *
     * **不硬编码取值。** 真机（PD2430A）实测现值是 `1,6,6,6`，而另见的一组值是
     * `1,7,7,7`（开）和 `0,3,3,3`（关）—— 三者互不相同，说明这组数字是**按机型/ROM 调的**，
     * 不是通用语义。照抄会覆盖掉本机可能是调优过的现值。所以不设预设，由用户按本机
     * 情况填，界面默认带出当前值；恢复走本项目的快照。
     */
    TOUCH_BOOST(R.string.vivo_feature_touch_boost, R.string.vivo_feature_touch_boost_detail,
        mapOf("system vts_game_para_adjust" to ""), allowMissing = true,
        trialHintRes = R.string.trial_hint_touch_boost),

    // 下面两个键在目标 ROM 的 jar 里确实存在（78-vivo-services.jar 的
    // DISPLAY_ENHANCE_GAME_SDR2HDR_ACTIVATED、76-vivo-service-wifi-ex-impl.jar 的同名常量），
    // 说明是 ROM 自己会读的键；但所在表没有实据，按 system 写入，未生效就改成另一张表再试。
    HAWKEYE(R.string.vivo_feature_hawkeye, R.string.vivo_feature_hawkeye_detail,
        mapOf("system gamecube_hawkeye_effective" to "1"), allowMissing = true,
        trialHintRes = R.string.trial_hint_hawkeye),
    // 真机实测三张表都不存在该键；保留开关，探测后变灰。
    NETWORK_BOOST(R.string.vivo_feature_network_boost, R.string.vivo_feature_network_boost_detail,
        mapOf("system vivo_smart_cloud_acc_enable_state" to "1"), allowMissing = true,
        gate = FeatureGate(), trialHintRes = R.string.trial_hint_network_boost);

    val isAnimation get() = this in setOf(ANIMATION, WINDOW_ANIMATION, TRANSITION_ANIMATION)
}

object VivoReply {
    /**
     * 剥掉用户服务的输出包装，返回 stdout。
     *
     * **解析只此一份**：用 `DaemonExec.parse` 拿结构化结果，这里只保留本项目的
     * 成功判据（退出 0、没有 stderr、没有致命字样）。之前有六七处各自
     * `substringBefore("[exit]")`，有的看退出码、有的不看，同一段回包在不同路径上
     * 结论不同 —— 比如一条无害的 stderr 会让写成功显示成失败。
     */
    fun output(raw: String?): String? {
        val reply = magicau.mtkcontroller.data.exec.DaemonExec.parse(raw) ?: return null
        if (reply.exit != 0 || reply.stderr.isNotEmpty()) return null
        val body = reply.stdout.trim()
        if (FATAL.containsMatchIn(body)) return null
        return body
    }

    private val FATAL = Regex(
        "(?i)(permission denial|permission denied|securityexception|^error:|exception occurred)",
    )

    // 只接受标准 Parcel 的零异常码；不把错误信息中的数字当成固件能力。
    fun parcelWords(raw: String?): List<Long>? {
        val body = output(raw) ?: return null
        val parcel = Regex("Parcel\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1) ?: return null
        val words = parcel.lineSequence().flatMap { line ->
            val data = line.substringBefore("'").replace(Regex("^\\s*0x[0-9a-fA-F]+:\\s*"), "")
            Regex("\\b[0-9a-fA-F]{8}\\b").findAll(data).map { it.value.toLong(16) }
        }.toList()
        return words.takeIf { it.firstOrNull() == 0L }
    }

    fun touchMax(raw: String?): Int? = parcelWords(raw)?.getOrNull(1)?.takeIf { it in 1L..8L }?.toInt()
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/** 独立于 CPU 控制；首次写入前持久保存原值，恢复失败保留快照供重试。 */
class VivoTweaks(
    private val context: Context,
    /**
     * 性能模式会改变平台自己的频率策略，切换前后由这里停掉/恢复本软件的 CPU 频率控制。
     *
     * 原因：停掉轮询服务不等于停掉控制 —— 循环在 `delay` 结束后仍会醒来，发现"超出范围"
     * 就释放旧请求、重新申请，把平台刚设好的状态又抢回来。必须连请求一起释放。
     */
    private val onPerformanceModePause: suspend (Boolean) -> Unit = {},
) {
    private val baselines = context.getSharedPreferences("vivo_tweak_baselines", Context.MODE_PRIVATE)

    /**
     * 原值走共享的 [RecoverySnapshot]，和触控用同一套规则、同一套持久化。
     *
     * `vivo_tweak_baselines` 现在只留两份**本功能特有**的东西：
     * `:written`（这次写进去的值，漂移保护拿它比对）和游戏加速的 `active` 标记。
     * 原值本身 —— 包括"原键不存在"—— 由快照按统一步骤存取，见 [originalOf]。
     */
    private val originals = RecoverySnapshot(
        // 每次读取都从 SharedPreferences 现取：写入方只有本类，读取是每次开关一次，
        // 不值得为它单独起一条常驻 Flow。
        store = flow { emit(baselines.all.mapNotNull { (k, v) -> if (v is String) k to v else null }.toMap()) },
        write = { map ->
            baselines.edit().apply {
                baselines.all.keys.forEach { remove(it) }
                map.forEach { (k, v) -> putString(k, v) }
            }.commit()
        },
    )
    private val mutex = Mutex()
    private val smartPrefs = context.getSharedPreferences("smart_platform_backup", Context.MODE_PRIVATE)
    private val journal = context.getSharedPreferences("tweak_journal", Context.MODE_PRIVATE)
    private fun record(message: String) {
        val entry = "${java.time.Instant.now()} $message"
        journal.edit().putString("history", (journal.getString("history", "").orEmpty() + "\n" + entry).takeLast(24000)).commit()
        AppLog.i("VivoTweaks", entry)
    }

    suspend fun currentValues(): Map<String, String?> = mutex.withLock {
        VivoFeature.entries.flatMap { it.values.keys }.distinct().associateWith { read(it) }
    }

    suspend fun diagnosticReport(): String = mutex.withLock {
        buildString {
            appendLine("调校报告 · ${java.time.Instant.now()}")
            appendLine("设备=$device ROM=$rom")
            appendLine("APP=${magicau.mtkcontroller.BuildConfig.VERSION_NAME} (${magicau.mtkcontroller.BuildConfig.VERSION_CODE})")
            appendLine("设置回读不证明算法运行；恢复快照仅代表本软件首次修改前的值。")
            for (feature in VivoFeature.entries) {
                appendLine("\n${context.getString(feature.labelRes)} · 待恢复=${hasSnapshot(feature)}")
                for (key in feature.values.keys) {
                    appendLine("$key 当前=${read(key) ?: "读取失败"} 原值=${originalOf(feature, key) ?: "未保存"}")
                }
            }
            appendLine("\n最近操作（限 24000 字符；过早记录可能已淘汰）")
            appendLine(journal.getString("history", "暂无操作"))
        }
    }
    private val device get() = "${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}/${android.os.Build.DEVICE}"
    private val rom get() = android.os.Build.FINGERPRINT
    fun smartBackup(slot: String = "initial"): SmartBackup? = smartPrefs.getString(slot, null)?.let { runCatching { SmartBackupCodec.decode(it) }.getOrNull() }
    fun remindSmartBackup() = smartPrefs.getBoolean("remind", true)
    fun setSmartReminder(enabled: Boolean) { smartPrefs.edit().putBoolean("remind", enabled).commit() }

    suspend fun captureSmartInitial(): SmartBackup = mutex.withLock {
        withContext(Dispatchers.IO) { captureSmartInitialLocked() }
    }

    private suspend fun captureSmartInitialLocked(): SmartBackup {
        smartBackup()?.let { return it }
        check(PrivilegeManager.state.value.mode.canElevate) { context.getString(R.string.tweaks_vivo_need_shizuku_initial) }
        val values = SmartPlatform.inspect().filterKeys { it in SmartPlatform.keys }
        check(values.isNotEmpty()) { context.getString(R.string.tweaks_vivo_no_config_backup) }
        val backup = SmartBackup(device = device, rom = rom, capturedAt = System.currentTimeMillis(),
            values = values, missing = SmartPlatform.keys.filter { it !in values })
        check(smartPrefs.edit().putString("initial", SmartBackupCodec.encode(backup)).commit()) { context.getString(R.string.tweaks_vivo_save_initial_failed) }
        return backup
    }

    suspend fun importSmartBackup(backup: SmartBackup) = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(smartPrefs.edit().putString("imported", SmartBackupCodec.encode(backup)).commit()) { context.getString(R.string.tweaks_vivo_save_import_failed) }
        }
    }

    suspend fun restoreSmartBackup(slot: String): TweakRepository.Outcome = mutex.withLock {
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            check(PrivilegeManager.state.value.mode.canElevate) { context.getString(R.string.tweaks_vivo_restore_need_shizuku) }
            val backup = smartBackup(slot) ?: error(context.getString(R.string.tweaks_vivo_no_restorable_backup))
            check(!hasSnapshot(VivoFeature.PRELOAD)) { context.getString(R.string.tweaks_vivo_restore_preload_first) }
            val target = SmartBackupCodec.restoreValue(backup, device, rom)
            // 恢复也保留操作前值；导入文件中的候选键绝不变成写入授权。
            val (ok, rollback) = SmartRestore.run(target, read = { read(SmartBackupCodec.AI_KEY) }, save = { current ->
                val before = SmartBackup(device = device, rom = rom, capturedAt = System.currentTimeMillis(),
                    values = mapOf(SmartBackupCodec.AI_KEY to current), source = "恢复前 AI 配置")
                check(smartPrefs.edit().putString("previous", SmartBackupCodec.encode(before)).commit()) { context.getString(R.string.tweaks_vivo_save_previous_failed) }
            }, write = { write(SmartBackupCodec.AI_KEY, it) })
            val message = if (ok) Message(R.string.vivo_ai_restored)
            else if (rollback) Message(R.string.vivo_rollback_ok)
            else Message(R.string.vivo_rollback_unconfirmed)
            AppLog.i("SmartBackup", message.resolve(context))
            TweakRepository.Outcome(ok, message)
        }
    }

    private suspend fun read(key: String) = VivoReply.output(PrivilegeManager.exec("timeout 5 settings get $key"))

    /**
     * 本机实际存在哪些登记键。给 [TweakValues.performanceMode] 用：可选键本机没有就不写。
     *
     * 只读一次快照，不在每次取值时都去问设备 —— 这一步进的是写前路径，
     * 多读一次就多一次和写入竞争的窗口。
     */
    suspend fun availableKeys(): Set<String> =
        VivoFeature.PERFORMANCE_MODE.values.keys.filter { read(it) != null }.toSet()

    /**
     * 写一个 settings 值。实现统一走 [SettingChannel.put]。
     *
     * **为什么不能再用 `settings put`**：真机实测它在这台 ROM 上退出码 0 但不落库，
     * 见 [SettingChannel] 的说明。这是之前"移植过来的功能不生效"的根因。
     */
    private suspend fun writeSetting(key: String, value: String): Boolean {
        val namespace = key.substringBefore(' ')
        val name = key.substringAfter(' ')
        return SettingChannel.put(namespace, name, value)
    }

    private suspend fun deleteSetting(key: String): Boolean {
        val namespace = key.substringBefore(' ')
        val name = key.substringAfter(' ')
        return SettingChannel.delete(namespace, name)
    }

    /**
     * 写一个键并回读核对。返回 null = 写成；非 null = 没写成，内容是原话。
     *
     * 原话只用设备自己的事实拼（键名、请求值、读回值），不写排障建议 ——
     * 建议该由界面按摘要给，原始错误只负责把现场留下来。见 ui.md「报错分两层」。
     */
    private suspend fun write(key: String, value: String): String? {
        val wrote = if (value == "null") deleteSetting(key) else writeSetting(key, value)
        val actual = read(key)
        val ok = wrote && (actual == value || (value != "null" && actual == null && TweakValues.matches(key, value, value)))
        record("$key requested=$value readback=${actual ?: "不可读"} success=$ok")
        return if (ok) null else "$key requested=$value readback=${actual ?: "unreadable"}"
    }

    suspend fun describe(feature: VivoFeature, current: Map<String, String?>): String {
        val values = feature.values.keys.associateWith { current[it] }
        return when {
            values.values.any { it == null } -> context.getString(R.string.tweaks_vivo_describe_failed)
            originals.hasEntry(RecoverySnapshot.VIVO, feature.name) ->
                context.getString(R.string.tweaks_vivo_describe_saved, values.values.joinToString(" / "))
            values.values.any { it == "null" } && !feature.allowMissing ->
                context.getString(R.string.tweaks_vivo_describe_missing)
            else -> context.getString(R.string.tweaks_vivo_describe_current, values.values.joinToString(" / "))
        }
    }

    /**
     * 本机是否支持这一项。
     *
     * 判据两层：settings 键 + [FeatureGate] 门禁（包/prop）。**门禁没通过就不支持** ——
     * 这种情况下写进去是新建一个没人读的键，界面却显示成功。
     */
    suspend fun supported(feature: VivoFeature): Boolean {
        if (!gateSatisfied(feature)) return false
        return feature.values.keys.all { read(it)?.let { v -> feature.allowMissing || v != "null" } == true }
    }

    /**
     * 探测门禁，返回不支持时的原因；支持就返回 null。
     *
     * 这是给界面显示用的：不只是"不支持"，还要说清是哪一条没过。
     */
    suspend fun probeGate(feature: VivoFeature): String? {
        val gate = feature.gate ?: return null
        for (pkg in gate.packages) {
            val installed = runCatching {
                context.packageManager.getPackageInfo(pkg, 0); true
            }.getOrDefault(false)
            if (!installed) return context.getString(R.string.tweaks_vivo_gate_pkg_missing, pkg)
        }
        for ((prop, expected) in gate.props) {
            val actual = VivoReply.output(PrivilegeManager.exec("getprop $prop"))?.trim()
            if (actual != expected) return context.getString(R.string.tweaks_vivo_gate_prop_mismatch, prop, actual.toString(), expected)
        }
        // 有门禁但没配具体条件时，要求键必须真实存在。
        if (gate.packages.isEmpty() && gate.props.isEmpty()) {
            val missing = feature.values.keys.filter { key ->
                val v = read(key)
                v == null || v == "null"
            }
            if (missing.isNotEmpty()) return context.getString(
                R.string.tweaks_vivo_gate_keys_missing,
                missing.joinToString("、") { it.substringAfterLast(' ') },
            )
        }
        return null
    }

    private suspend fun gateSatisfied(feature: VivoFeature): Boolean = probeGate(feature) == null
    suspend fun hasSnapshot(feature: VivoFeature): Boolean = originals.hasEntry(RecoverySnapshot.VIVO, feature.name)

    /** 全部条目取原值的统一入口：读当前值，`null` 表示本机没有这个键。 */
    private suspend fun captureOriginals(feature: VivoFeature, keys: Collection<String>) {
        // 键名形如 `system power_save_type`，整段交给 `settings get` —— 表名和键名之间的
        // 空格正是 `settings` 命令要的。早先这里只取了空格后面的半截，表名一丢就读错表。
        originals.capture(RecoverySnapshot.VIVO, feature.name, keys) { spec ->
            read(spec)?.takeIf { it != "null" }
        }
    }

    /**
     * 恢复系统默认：删掉这个特性写过的键。
     *
     * settings 的默认值来自 ROM defaults，行不存在时系统会回退 —— 所以删除就是重置。
     * 只处理有快照的特性（本软件确实写过）。删成功后清掉快照与 `:written` 记录。
     */
    suspend fun resetToSystemDefault(feature: VivoFeature): Boolean {
        var ok = true
        for (spec in feature.values.keys) {
            val table = spec.substringBefore(' ')
            val key = spec.substringAfter(' ')
            val raw = PrivilegeManager.exec("${SettingChannel.deleteCommand(table, key)} 2>&1")
            // 退出 0 不代表删掉了（这台 ROM 上 `settings put` 就退出 0 不落库）。
            // 回读一次：键不在了才是真删掉。
            val after = read(spec)
            if (VivoReply.output(raw) == null || (after != null && after != "null")) ok = false
        }
        if (ok) {
            originals.release(RecoverySnapshot.VIVO, feature.name, feature.values.keys)
            baselines.edit().apply {
                remove(feature.name + ":saved")
                feature.values.keys.forEach { remove(feature.name + it + ":written") }
            }.commit()
        }
        return ok
    }

    /** 这个键存下的原值；`null` = 没存过，或原键当时不存在（后者见 [originalPresent]）。 */
    private suspend fun originalOf(feature: VivoFeature, spec: String): String? =
        originals.original(RecoverySnapshot.VIVO, feature.name, spec)

    /** 原键在取快照时是否存在。 */
    private suspend fun originalPresent(feature: VivoFeature, spec: String): Boolean =
        originals.isPresent(RecoverySnapshot.VIVO, feature.name, spec)

    suspend fun apply(feature: VivoFeature, restore: Boolean, option: String? = null, force: Boolean = false): TweakRepository.Outcome = mutex.withLock {
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            if (!PrivilegeManager.state.value.mode.canElevate) {
                return@withContext TweakRepository.Outcome(false, Message(R.string.tweak_need_shizuku))
            }
            if (feature == VivoFeature.AI) {
                captureSmartInitialLocked()
                val current = read(SmartBackupCodec.AI_KEY)
                    ?: return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_no_baseline))
                val previous = SmartBackup(device = device, rom = rom, capturedAt = System.currentTimeMillis(),
                    values = mapOf(SmartBackupCodec.AI_KEY to current), source = "AI 修改前状态")
                if (!smartPrefs.edit().putString("previous", SmartBackupCodec.encode(previous)).commit())
                    return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_backup_failed))
            }
            if (!restore) {
                val conflict = conflictingFeature(feature, VivoFeature.entries.filter { hasSnapshot(it) }.toSet())
                if (conflict != null) {
                    return@withContext TweakRepository.Outcome(
                        false,
                        Message(R.string.vivo_conflict, listOf(Message(conflict.labelRes))),
                    )
                }
            }
            val requested = if (restore) feature.values else when (feature) {
                VivoFeature.AI -> {
                    if (option !in listOf("0", "1")) {
                        return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_pick_on_off))
                    }
                    mapOf("system ai_turbo_enabled" to option!!)
                }
                VivoFeature.DNS -> {
                    if (!validDnsHost(option.orEmpty())) {
                        return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_dns_invalid))
                    }
                    feature.values + ("global private_dns_specifier" to option!!.trim().lowercase())
                }
                VivoFeature.CHARGE_LIMIT -> {
                    val limit = TweakValues.chargeLimit(option)
                        ?: return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_charge_range))
                    mapOf(feature.values.keys.single() to limit)
                }
                VivoFeature.TOUCH_BOOST -> {
                    val adjust = TweakValues.touchAdjust(option)
                        ?: return@withContext TweakRepository.Outcome(
                            false,
                            Message(R.string.touch_boost_value_invalid),
                        )
                    mapOf(feature.values.keys.single() to adjust)
                }
                VivoFeature.ANIMATION, VivoFeature.WINDOW_ANIMATION, VivoFeature.TRANSITION_ANIMATION -> {
                    val scale = TweakValues.animation(option)
                        ?: return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_anim_range))
                    mapOf(feature.values.keys.single() to scale)
                }
                VivoFeature.HTP -> TweakValues.htp(option)
                    ?: return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_pick_htp))
                VivoFeature.ABE -> mapOf("secure abe_unified_switch" to if (option == "balanced") "1" else "0")
                VivoFeature.PERFORMANCE_MODE -> TweakValues.performanceMode(option, availableKeys())
                    ?: return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_pick_perf))
                VivoFeature.FRAME_UNLOCK -> TweakValues.frameUnlock(option)
                    ?: return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_pick_fps))
                else -> feature.values
            }
            val marker = feature.name + ":saved"
            if (!restore && feature.lifetime == TweakLifetime.TIMED &&
                originals.hasEntry(RecoverySnapshot.VIVO, feature.name))
                return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_pending_snapshot))
            if (restore && !originals.hasEntry(RecoverySnapshot.VIVO, feature.name)) {
                // 这条必须记。早前它静默返回，用户点"恢复原值"没反应，
                // 而流水里一条记录都没有，只能靠猜走到了哪个分支。
                record("${feature.name} restore 被拦：没有原值快照")
                return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_no_snapshot))
            }

            // 持久配置可能已经开了几小时，期间系统自己改过这些键（充电策略、场景切换）。
            // 直接写回快照会覆盖掉系统的选择 —— 先报出来，让用户确认一次。
            if (restore && !force && feature.lifetime == TweakLifetime.PERSISTENT &&
                originals.hasEntry(RecoverySnapshot.VIVO, feature.name)) {
                val written = feature.values.keys.associateWith { baselines.getString(feature.name + it + ":written", null) }
                val drifted = feature.values.keys.filter { key ->
                    val expected = written[key] ?: return@filter false
                    val now = read(key)
                    now != null && now != expected
                }
                if (drifted.isNotEmpty()) {
                    // 这一条是恢复路径里最容易"看起来失败"的地方：它是有意拦的，
                    // 为的是不用一份旧值盖掉系统后来改的值。不记日志的话，
                    // 流水里连一条都没有，人和软件都不知道它被拦过。
                    val detail = drifted.associateWith { key ->
                        "写入=${written[key]} 当前=${read(key)}"
                    }
                    record("${feature.name} restore 被拦：期间系统改动了 $detail")
                    return@withContext TweakRepository.Outcome(
                        false,
                        Message(
                            R.string.vivo_drifted,
                            listOf(drifted.joinToString("、") { it.substringAfterLast(' ') }),
                        ),
                        needsConfirm = true,
                    )
                }
            }

            if (!restore && !originals.hasEntry(RecoverySnapshot.VIVO, feature.name)) {
                // 读不到就不取 —— 没有原值就恢复不到原样，宁可这一轮不写。
                val baseline = feature.values.keys.associateWith { read(it) }
                if (baseline.values.any { it == null || (!feature.allowMissing && it == "null") }) {
                    return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_config_unreadable))
                }
                captureOriginals(feature, feature.values.keys)
            }
            if (!restore) {
                // **每一次写入都要刷新 `:written`，不是只在第一次。**
                //
                // 早前它写在"建快照"那个分支里，于是只有首次写入记一次。之后再切档位，
                // `:written` 还是第一批的旧值 —— 而恢复时的漂移保护正是拿 `:written`
                // 和当前值比对，于是它把"我自己后来写进去的值"判成"期间系统改动了"，
                // 恢复被永久拦下，一条都不写。用户看到的就是"点恢复没反应"。
                val written = baselines.edit()
                requested.forEach { (key, value) -> written.putString(feature.name + key + ":written", value) }
                // 这次没写的候选键，旧记录必须清掉：留着等于声称自己写了一个没写的键，
                // 恢复时的漂移保护会继续拿它当依据。
                val stale = feature.values.keys - requested.keys
                stale.forEach { written.remove(feature.name + it + ":written") }
                if (!written.commit()) return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_record_failed))
            }
            // 恢复只恢复有快照条目的键。候选键（如不支持 Monster 的机型上的
            // monster_key_start）本机不存在，`putString(key, null)` 会把条目移除，
            // 之后这里取到 null 会被判成"恢复快照不完整"而整个拒绝执行 ——
            // 用户一个键都恢复不了。跳过它们，只写这次真正动过的。
            val targets = if (restore) feature.values.keys.mapNotNull { key ->
                originalOf(feature, key)?.let { key to it }
            }.toMap() else requested
            if (targets.values.any { it == null }) {
                return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_snapshot_incomplete))
            }
            if (restore) record("${feature.name} restore=true 写回 targets=$targets")
            if (feature.lifetime == TweakLifetime.TIMED && !restore) {
                val original = feature.values.keys.associateWith { originalOf(feature, it)!! }
                if (feature == VivoFeature.GAME_THERMAL && original.values.any { it !in listOf("0", "1") })
                    return@withContext TweakRepository.Outcome(false, Message(R.string.vivo_thermal_unknown))
                record("${feature.name} 实验开始 targets=$requested original=$original")
                val raw = PrivilegeManager.exec("timeout 50 sh -c ${VivoReply.quote(SettingExperiment.script(requested, original))}")
                val restored = original.all { (key, value) -> read(key) == value }
                val completed = VivoReply.output(raw)?.contains("[experiment-applied]") == true
                val cleared = restored && baselines.edit().remove(marker).apply {
                    feature.values.keys.forEach { remove(feature.name + it + ":written") }
                }.commit()
                record("${feature.name} completed=$completed restored=$restored snapshotCleared=$cleared output=${raw?.take(6000)}")
                return@withContext TweakRepository.Outcome(
                    completed && cleared,
                    if (!cleared) Message(R.string.vivo_exp_unrestored)
                    else if (!completed) Message(R.string.vivo_exp_write_failed)
                    else Message(R.string.vivo_exp_done),
                )
            }

            // 到这里所有"还没写就先返回"的分支都走完了，交出去的控制权不会悬空。
            // 性能模式让平台重推自己的一套频率策略：先交出本软件的控制权，否则持有中的
            // 请求会和平台的新限制叠在一起，用户此后释放时上限收不回来。
            if (feature == VivoFeature.PERFORMANCE_MODE && !restore) onPerformanceModePause(true)
            val batch = SettingBatch.write(targets.mapValues { it.value!! }, ::write)
            val failed = batch.failures.toMutableList()
            val reasons = batch.reasons.toMutableList()
            if (!restore && failed.isEmpty() && feature == VivoFeature.DNS) {
                val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
                var validated = false
                repeat(8) {
                    if (!validated) {
                        kotlinx.coroutines.delay(1500)
                        validated = runCatching {
                            val network = connectivity?.activeNetwork
                            val props = network?.let { connectivity.getLinkProperties(it) }
                            val caps = network?.let { connectivity.getNetworkCapabilities(it) }
                            props?.isPrivateDnsActive == true && props.privateDnsServerName == option?.trim()?.lowercase() &&
                                caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                        }.getOrDefault(false)
                    }
                }
                if (!validated) {
                    failed += "DNS"
                    reasons += "DNS → 系统未联网验证（私有 DNS 未激活或网络未通过校验）"
                }
            }
            var message = if (failed.isEmpty()) {
                if (restore) {
                    val editor = baselines.edit().remove(marker)
                    feature.values.keys.forEach { editor.remove(feature.name + it); editor.remove(feature.name + it + ":written") }
                    if (!editor.commit()) Message(R.string.vivo_restored_cleanup_failed)
                    else if (feature == VivoFeature.PERFORMANCE_MODE) Message(R.string.vivo_perf_restored)
                    else Message(R.string.vivo_restored)
                } else if (feature == VivoFeature.DNS) Message(R.string.vivo_dns_verified)
                else if (feature == VivoFeature.PERFORMANCE_MODE) Message(R.string.vivo_perf_applied)
                else if (feature == VivoFeature.AI) Message(R.string.vivo_ai_applied)
                // 读回一致只能证明写进去了。有系统读数的才敢说"已写入"，
                // 没有读数的把证据等级直接讲出来，不拿"效果待验证"糊过去。
                else when (feature.evidence) {
                    Evidence.CONFIRMED -> Message(R.string.vivo_applied)
                    Evidence.WRITTEN -> Message(R.string.vivo_applied_unverified)
                    Evidence.LIKELY_USELESS -> Message(R.string.vivo_applied_useless)
                }
            } else Message(R.string.vivo_write_failed_keep_snapshot)
            if (failed.isNotEmpty() && !restore) {
                val original = feature.values.keys.associateWith { originalOf(feature, it) }
                val rollback = SettingBatch.write(original.mapValues { it.value!! }, ::write)
                val rolledBack = original.values.none { it == null } && rollback.ok
                message = if (rolledBack) Message(R.string.vivo_rolled_back, listOf(message))
                else Message(R.string.vivo_rollback_incomplete, listOf(message))
                // 性能模式没换成功，就把交出去的控制权还回来，别让用户两边都空着。
                if (feature == VivoFeature.PERFORMANCE_MODE) onPerformanceModePause(false)
            }
            // 成功写入后把 CPU 控制交还回来；失败分支已在 rollback 里交还。
            if (feature == VivoFeature.PERFORMANCE_MODE && failed.isEmpty()) {
                onPerformanceModePause(false)
            }
            val log = "${feature.name} restore=$restore ${message.resolve(context)} failures=$failed"
            record(log)
            if (failed.isEmpty()) AppLog.i("VivoTweaks", log) else AppLog.w("VivoTweaks", log)
            TweakRepository.Outcome(
                success = failed.isEmpty(),
                message = message,
                detail = reasons.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                failures = failed,
            )
        }
    }

    companion object {
        fun conflictingFeature(feature: VivoFeature, saved: Set<VivoFeature>): VivoFeature? = saved.firstOrNull { other ->
            other != feature && other.values.keys.any { it in feature.values }
        }

        fun validDnsHost(host: String): Boolean {
            val value = host.trim()
            return value.length in 3..253 && value.contains('.') && !value.all { it.isDigit() || it == '.' } &&
                value.split('.').all { it.length in 1..63 && Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?").matches(it) }
        }
    }

    suspend fun touchMax(): Int? = VivoReply.touchMax(PrivilegeManager.exec("timeout 5 service call bbk_touch_screen_service 16"))

    suspend fun touch(enable: Boolean): TweakRepository.Outcome {
        val level = if (enable) {
            touchMax() ?: return TweakRepository.Outcome(false, Message(R.string.vivo_touch_no_level))
        } else 0
        val reply = PrivilegeManager.vivoTouchRequest(level)
        val accepted = reply.startsWith("OK:")
        val maxText = touchMax()?.toString() ?: context.getString(R.string.value_unreadable)
        val message = if (accepted) {
            if (enable) Message(R.string.vivo_touch_level_requested, listOf(level, maxText))
            else Message(R.string.vivo_touch_default_requested, listOf(maxText))
        } else {
            Message(R.string.vivo_touch_rejected)
        }
        val rendered = message.resolve(context)
        if (accepted) AppLog.i("VivoTweaks", "$rendered reply=$reply")
        else AppLog.w("VivoTweaks", "$rendered reply=$reply")
        return TweakRepository.Outcome(accepted, message)
    }
}
