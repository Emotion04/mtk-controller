package magicau.mtkcontroller.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import magicau.mtkcontroller.data.settingsStore
import magicau.mtkcontroller.domain.model.DEFAULT_HOME_CARDS
import magicau.mtkcontroller.domain.model.HomeCard
import magicau.mtkcontroller.domain.model.HomeCardType
import java.util.UUID

/**
 * User preferences that are not part of a profile: which tab to open on launch,
 * and the home dashboard layout.
 *
 * Routes are stored as plain strings so this layer stays free of UI types.
 */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val defaultTabKey = stringPreferencesKey("default_tab_route")
    private val homeCardsKey = stringPreferencesKey("home_cards_json")
    private val migratedV2Key = booleanPreferencesKey("migrated_v2_quick_settings")
    private val paletteKey = stringPreferencesKey("theme_palette")
    // 自定义强调色存"色相 + 浓度"，不存 ARGB —— 真正的颜色由 MorandiAccent 推导，
    // 只存推导的输入才能保证"白字可读"这条不变量始终成立（直接存 ARGB 的话，
    // 一个越界值就能绕过它）。
    private val customHueKey = intPreferencesKey("theme_custom_hue")
    private val customConcentrationKey = intPreferencesKey("theme_custom_concentration")
    private val navBarStyleKey = stringPreferencesKey("nav_bar_style")
    private val navBarCustomKey = stringPreferencesKey("nav_bar_custom_palette")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val shapeStyleKey = stringPreferencesKey("shape_style")
    private val motionSpeedKey = stringPreferencesKey("motion_speed")
    private val tabSwipeModeKey = stringPreferencesKey("tab_swipe_mode")
    private val cpuLayoutModeKey = stringPreferencesKey("cpu_layout_mode")
    private val rangeControlModeKey = stringPreferencesKey("range_control_mode")
    private val navBarVisibilityKey = stringPreferencesKey("nav_bar_visibility")
    private val tabRestoreModeKey = stringPreferencesKey("tab_restore_mode")
    private val logLevelKey = stringPreferencesKey("log_level")
    private val applyModeKey = stringPreferencesKey("apply_mode")
    private val reapplyIntervalKey = longPreferencesKey("reapply_interval_ms")
    private val screenOffPolicyKey = stringPreferencesKey("screen_off_policy")
    private val hideFromRecentsKey = booleanPreferencesKey("hide_from_recents")
    private val applyOnBootKey = booleanPreferencesKey("apply_on_boot")
    private val updateLastCheckKey = longPreferencesKey("update_last_check_ms")
    private val updateDismissedKey = intPreferencesKey("update_dismissed_version")

    // --- 保活与常驻通知 ------------------------------------------------------
    private val keepAliveKey = booleanPreferencesKey("keep_alive")
    private val notifProfileKey = booleanPreferencesKey("notif_show_profile")
    private val notifCpuKey = booleanPreferencesKey("notif_show_cpu")
    private val notifTempKey = booleanPreferencesKey("notif_show_temp")
    private val notifQuickSwitchKey = booleanPreferencesKey("notif_quick_switch")
    private val freqThermalCompanionKey = booleanPreferencesKey("freq_thermal_companion")

    val defaultTabRoute: Flow<String?> = context.settingsStore.data.map { it[defaultTabKey] }

    val themePalette: Flow<String> = context.settingsStore.data.map { it[paletteKey] ?: "MORANDI_SAGE" }

    /** 自定义强调色的色相，0–360。 */
    val customHue: Flow<Int> =
        context.settingsStore.data.map { it[customHueKey] ?: DEFAULT_CUSTOM_HUE }

    /** 自定义强调色的浓度，0–100。 */
    val customConcentration: Flow<Int> =
        context.settingsStore.data.map { it[customConcentrationKey] ?: DEFAULT_CUSTOM_CONCENTRATION }

    suspend fun setCustomAccent(hueDeg: Int, concentration: Int) {
        context.settingsStore.edit {
            it[customHueKey] = hueDeg.coerceIn(0, 360)
            it[customConcentrationKey] = concentration.coerceIn(0, 100)
        }
    }

    suspend fun setThemePalette(name: String) {
        context.settingsStore.edit { it[paletteKey] = name }
    }

    val navBarStyle: Flow<String> = context.settingsStore.data.map { it[navBarStyleKey] ?: "COLORFUL" }

    suspend fun setNavBarStyle(name: String) {
        context.settingsStore.edit { it[navBarStyleKey] = name }
    }

    val navBarCustomPalette: Flow<String> =
        context.settingsStore.data.map { it[navBarCustomKey] ?: "TEAL" }

    suspend fun setNavBarCustomPalette(name: String) {
        context.settingsStore.edit { it[navBarCustomKey] = name }
    }

    val themeMode: Flow<String> = context.settingsStore.data.map { it[themeModeKey] ?: "SYSTEM" }

    /** 圆角规格。三档预设，见 [magicau.mtkcontroller.ui.theme.ShapeStyle]。 */
    val shapeStyle: Flow<String> = context.settingsStore.data.map { it[shapeStyleKey] ?: "STANDARD" }


    /** 动画速度：轻快 / 标准 / 舒缓。 */
    val motionSpeed: Flow<String> =
        context.settingsStore.data.map { it[motionSpeedKey] ?: "SYSTEM" }

    /** 拖底栏松手后的行为：松手即切换 / 停留后切换。 */
    val tabSwipeMode: Flow<String> =
        context.settingsStore.data.map { it[tabSwipeModeKey] ?: "NEAREST" }

    suspend fun setThemeMode(name: String) {
        context.settingsStore.edit { it[themeModeKey] = name }
    }

    suspend fun setShapeStyle(name: String) {
        context.settingsStore.edit { it[shapeStyleKey] = name }
    }


    suspend fun setMotionSpeed(name: String) {
        context.settingsStore.edit { it[motionSpeedKey] = name }
    }

    suspend fun setTabSwipeMode(name: String) {
        context.settingsStore.edit { it[tabSwipeModeKey] = name }
    }

    /** CPU 页控制区布局：分卡片 / 合并卡片。 */
    val cpuLayoutMode: Flow<String> =
        context.settingsStore.data.map { it[cpuLayoutModeKey] ?: "UNIFIED" }

    suspend fun setCpuLayoutMode(name: String) {
        context.settingsStore.edit { it[cpuLayoutModeKey] = name }
    }

    /** 频率区间的调节方式：分段条 / 双向滑块。 */
    val rangeControlMode: Flow<String> =
        context.settingsStore.data.map { it[rangeControlModeKey] ?: "SEGMENT" }

    suspend fun setRangeControlMode(name: String) {
        context.settingsStore.edit { it[rangeControlModeKey] = name }
    }

    /** 悬浮底栏的滚动行为：固定 / 上滑隐藏。 */
    val navBarVisibility: Flow<String> =
        context.settingsStore.data.map { it[navBarVisibilityKey] ?: "AUTO_HIDE" }

    suspend fun setNavBarVisibility(name: String) {
        context.settingsStore.edit { it[navBarVisibilityKey] = name }
    }

    /** 切回标签时：保留原二级页面 / 回到标签主页。 */
    val tabRestoreMode: Flow<String> =
        context.settingsStore.data.map { it[tabRestoreModeKey] ?: "KEEP" }

    suspend fun setTabRestoreMode(name: String) {
        context.settingsStore.edit { it[tabRestoreModeKey] = name }
    }

    val logLevel: Flow<String> = context.settingsStore.data.map { it[logLevelKey] ?: "INFO" }

    suspend fun setLogLevel(name: String) {
        context.settingsStore.edit { it[logLevelKey] = name }
    }

    val applyMode: Flow<String> = context.settingsStore.data.map { it[applyModeKey] ?: "SINGLE" }

    /**
     * 锁屏时的处理方式，默认“保持并重发”。
     *
     * 默认不释放：原有行为是熄屏期间只把轮询放慢，并没有主动撤销请求。
     * 需要靠熄屏省电的用户可以在设置里改成“释放”。
     */
    val screenOffPolicy: Flow<String> =
        context.settingsStore.data.map { it[screenOffPolicyKey] ?: "KEEP" }

    /** 是否把当前任务从最近任务列表里隐藏。 */
    val hideFromRecents: Flow<Boolean> =
        context.settingsStore.data.map { it[hideFromRecentsKey] ?: false }

    /** 开机后 Shizuku 就绪时，按上一次范围自动下发一次。默认关。 */
    val applyOnBoot: Flow<Boolean> =
        context.settingsStore.data.map { it[applyOnBootKey] ?: false }

    /** 上次检查更新的时间；0 = 从没检查过。 */
    val updateLastCheckMs: Flow<Long> =
        context.settingsStore.data.map { it[updateLastCheckKey] ?: 0L }

    /** 用户点过“稍后”的版本；同一版本不再弹窗。 */
    val updateDismissedVersion: Flow<Int?> =
        context.settingsStore.data.map { it[updateDismissedKey] }

    // --- 保活与常驻通知 ------------------------------------------------------

    val keepAlive: Flow<Boolean> = context.settingsStore.data.map { it[keepAliveKey] ?: false }
    val notifShowProfile: Flow<Boolean> = context.settingsStore.data.map { it[notifProfileKey] ?: true }
    val notifShowCpu: Flow<Boolean> = context.settingsStore.data.map { it[notifCpuKey] ?: true }
    val notifShowTemp: Flow<Boolean> = context.settingsStore.data.map { it[notifTempKey] ?: true }
    /** 通知栏里是否给出"切到下一个方案"的按钮。 */
    val notifQuickSwitch: Flow<Boolean> = context.settingsStore.data.map { it[notifQuickSwitchKey] ?: true }

    /**
     * 应用频率区间时是否一并切到"放开温控"的档位。
     *
     * **默认开。** 本机实测软上限被厂商温控压到 2.3 GHz 左右，只调频率区间常常看不出
     * 效果；放开温控后频率请求才能真正落地。代价是移除系统温度保护，更热也更耗电 ——
     * 这是用户明确选择的行为，不做默认路径上的“优化”。
     * 用户手动选过温控档位时，以手动档位为准（见 `CpuViewModel.apply`）。
     */
    val freqThermalCompanion: Flow<Boolean> =
        context.settingsStore.data.map { it[freqThermalCompanionKey] ?: true }

    suspend fun setKeepAlive(enabled: Boolean) = context.settingsStore.edit { it[keepAliveKey] = enabled }
    suspend fun setNotifShowProfile(enabled: Boolean) = context.settingsStore.edit { it[notifProfileKey] = enabled }
    suspend fun setNotifShowCpu(enabled: Boolean) = context.settingsStore.edit { it[notifCpuKey] = enabled }
    suspend fun setNotifShowTemp(enabled: Boolean) = context.settingsStore.edit { it[notifTempKey] = enabled }
    suspend fun setNotifQuickSwitch(enabled: Boolean) = context.settingsStore.edit { it[notifQuickSwitchKey] = enabled }
    suspend fun setFreqThermalCompanion(enabled: Boolean) =
        context.settingsStore.edit { it[freqThermalCompanionKey] = enabled }

    suspend fun setApplyMode(name: String) {
        context.settingsStore.edit { it[applyModeKey] = name }
    }

    suspend fun setScreenOffPolicy(name: String) {
        context.settingsStore.edit { it[screenOffPolicyKey] = name }
    }

    suspend fun setHideFromRecents(enabled: Boolean) {
        context.settingsStore.edit { it[hideFromRecentsKey] = enabled }
    }

    suspend fun setApplyOnBoot(enabled: Boolean) {
        context.settingsStore.edit { it[applyOnBootKey] = enabled }
    }

    suspend fun setUpdateLastCheckMs(value: Long) {
        context.settingsStore.edit { it[updateLastCheckKey] = value }
    }

    suspend fun setUpdateDismissedVersion(value: Int?) {
        context.settingsStore.edit {
            if (value == null) it.remove(updateDismissedKey) else it[updateDismissedKey] = value
        }
    }

    /** Bounded so a typo cannot turn into a binder hammering loop. */
    val reapplyIntervalMs: Flow<Long> = context.settingsStore.data.map {
        (it[reapplyIntervalKey] ?: DEFAULT_REAPPLY_INTERVAL_MS)
            .coerceIn(MIN_REAPPLY_INTERVAL_MS, MAX_REAPPLY_INTERVAL_MS)
    }

    suspend fun setReapplyIntervalMs(value: Long) {
        context.settingsStore.edit {
            it[reapplyIntervalKey] = value.coerceIn(MIN_REAPPLY_INTERVAL_MS, MAX_REAPPLY_INTERVAL_MS)
        }
    }

    companion object {
        const val DEFAULT_REAPPLY_INTERVAL_MS = 2_000L

        /** 自定义强调色的初始位置：与默认调色板（莫兰迪鼠尾草）同色。 */
        const val DEFAULT_CUSTOM_HUE = 107
        const val DEFAULT_CUSTOM_CONCENTRATION = 25

        /**
         * Each tick costs a release + acquire round trip. Below ~250 ms the
         * binder traffic stops being free and the request never settles, so
         * this is a hard floor rather than a suggestion.
         */
        const val MIN_REAPPLY_INTERVAL_MS = 250L
        const val MAX_REAPPLY_INTERVAL_MS = 600_000L
    }

    val homeCards: Flow<List<HomeCard>> = context.settingsStore.data.map { prefs ->
        prefs[homeCardsKey]
            ?.let { raw -> runCatching { json.decodeFromString<List<HomeCard>>(raw) }.getOrNull() }
            ?: DEFAULT_HOME_CARDS
    }

    /**
     * One-time upgrade for installs that predate the quick-settings card: make
     * sure it shows up without forcing the user to reset their layout.
     */
    suspend fun migrateIfNeeded() {
        // 仅升级时补入一次；用户之后移除不会在下次启动重新出现。
        val testsKey = booleanPreferencesKey("migrated_test_center")
        context.settingsStore.edit { prefs ->
            if (prefs[testsKey] != true) {
                val current = prefs[homeCardsKey]?.let { raw ->
                    runCatching { json.decodeFromString<List<HomeCard>>(raw) }.getOrNull()
                } ?: DEFAULT_HOME_CARDS
                val updated = if (current.any { it.type == HomeCardType.TEST_CENTER }) current
                    else current + HomeCard("card-test-center", HomeCardType.TEST_CENTER)
                prefs[homeCardsKey] = json.encodeToString(updated)
                prefs[testsKey] = true
            }
        }
        val prefs = context.settingsStore.data.first()
        if (prefs[migratedV2Key] == true) return
        val current = homeCards.first()
        if (current.none { it.type == HomeCardType.QUICK_SETTINGS }) {
            write(
                listOf(HomeCard(id = "card-quick-settings", type = HomeCardType.QUICK_SETTINGS)) + current,
            )
        }
        context.settingsStore.edit { it[migratedV2Key] = true }
    }

    suspend fun setDefaultTabRoute(route: String?) {
        context.settingsStore.edit {
            if (route == null) it.remove(defaultTabKey) else it[defaultTabKey] = route
        }
    }

    suspend fun addCard(type: HomeCardType) {
        val current = homeCards.first()
        if (current.any { it.type == type }) return
        write(current + HomeCard(id = UUID.randomUUID().toString(), type = type))
    }

    suspend fun removeCard(id: String) {
        write(homeCards.first().filterNot { it.id == id })
    }

    suspend fun moveCard(fromIndex: Int, toIndex: Int) {
        val current = homeCards.first().toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        current.add(toIndex, current.removeAt(fromIndex))
        write(current)
    }

    suspend fun resetCards() = write(DEFAULT_HOME_CARDS)

    /** Wholesale replace, used by backup import. */
    suspend fun replaceCards(cards: List<HomeCard>) = write(cards)

    private suspend fun write(cards: List<HomeCard>) {
        context.settingsStore.edit { it[homeCardsKey] = json.encodeToString(cards) }
    }
}
