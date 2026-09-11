package magicau.mtkcontroller.data.tweaks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import magicau.mtkcontroller.BuildConfig
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.settings.RecoverySnapshot
import magicau.mtkcontroller.data.settings.SettingChannel

private val Context.tweakStore: DataStore<Preferences> by preferencesDataStore(name = "mtk_optimizer_tweaks")

/**
 * Runs the shell-based tweaks and remembers which ones are on.
 *
 * Every command goes through the elevated UserService, so all of this is a
 * no-op (with a clear message) until Shizuku is granted.
 */
class TweakRepository(
    private val context: Context,
    /** 性能模式切换时停掉/恢复 CPU 频率控制；由 AppContainer 接上 CpuControl。 */
    onPerformanceModePause: suspend (Boolean) -> Unit = {},
) {

    private val brightnessBackendKey = stringPreferencesKey("brightness_backend")
    val brightnessBackend: Flow<BrightnessBackend> = context.tweakStore.data.map { prefs ->
        BrightnessBackend.entries.firstOrNull { it.name == prefs[brightnessBackendKey] } ?: BrightnessBackend.LEGACY
    }
    suspend fun setBrightnessBackend(backend: BrightnessBackend): Outcome = touchMutex.withLock {
        if (thermalBrightness.first() || vivo.hasSnapshot(VivoFeature.BRIGHTNESS)) {
            return@withLock Outcome(false, Message(R.string.tweak_brightness_restore_first))
        }
        if (backend == BrightnessBackend.VIVO && !vivo.supported(VivoFeature.BRIGHTNESS)) {
            return@withLock Outcome(false, Message(R.string.tweak_brightness_unsupported))
        }
        context.tweakStore.edit { it[brightnessBackendKey] = backend.name }
        Outcome(true, Message(R.string.tweak_backend_selected, listOf(Message(backend.labelRes))))
    }

    val vivo = VivoTweaks(context, onPerformanceModePause)
    val gameBoost = GameBoost(context) { entry, table, key -> rememberOwned(entry, table, key) }
    private val touchMutex = Mutex()
    @Volatile var touchRevision: Long = 0
        private set
    private val touchBackendKey = stringPreferencesKey("touch_backend")
    val touchBackend: Flow<TouchBackend> = context.tweakStore.data.map { prefs ->
        TouchBackend.entries.firstOrNull { it.name == prefs[touchBackendKey] } ?: TouchBackend.LEGACY
    }

    suspend fun setTouchBackend(backend: TouchBackend): Outcome = touchMutex.withLock {
        if (touchItems.first().isNotEmpty()) {
            return@withLock Outcome(false, Message(R.string.tweak_touch_close_first))
        }
        if (backend == TouchBackend.VIVO && vivo.touchMax() == null) {
            return@withLock Outcome(false, Message(R.string.tweak_touch_no_vivo))
        }
        context.tweakStore.edit { it[touchBackendKey] = backend.name }
        Outcome(true, Message(R.string.tweak_backend_selected, listOf(Message(backend.labelRes))))
    }

    private val freqCompanionKey = stringPreferencesKey("freq_sched_companion")
    /**
     * 频率范围模式。旧版是布尔 `freq_floor_only`，只够表达"区间/只下限"两种。
     * 换成字符串存枚举名，并读一次旧键做迁移 —— 老用户升上来不会被打回默认值。
     */
    private val freqRangeModeKey = stringPreferencesKey("freq_range_mode")
    private val freqHardLimitsKey = booleanPreferencesKey("freq_hard_limits")
    private val legacyFreqFloorOnlyKey = booleanPreferencesKey("freq_floor_only")
    private val touchItemsKey = stringSetPreferencesKey("touch_items")
    /** 打开前存下的旋钮原值，编码成 "itemId|NS|key=值"。关闭时写回它。 */
    /**
     * 触控原值的持久存储，交给共享的 [RecoverySnapshot] 管。
     *
     * 原来这里自己读、自己合并、自己删，和厂商功能、游戏加速各写了一遍同样的规则。
     * 现在只保留编码（一个键一行），快照/释放的逻辑全在 `RecoverySnapshot`。
     */
    private val touchOriginalsKey = stringSetPreferencesKey("touch_originals")
    private val snapshots = RecoverySnapshot(
        store = context.tweakStore.data.map { prefs ->
            prefs[touchOriginalsKey].orEmpty().mapNotNull { entry ->
                val sep = entry.indexOf('=')
                if (sep <= 0) null else entry.substring(0, sep) to entry.substring(sep + 1)
            }.toMap()
        },
        write = { map ->
            context.tweakStore.edit { it[touchOriginalsKey] = map.map { (k, v) -> "$k=$v" }.toSet() }
        },
    )
    /**
     * 频率请求是否附带下发调度器配合项。**默认关。**
     *
     * 配合项出处见 [TweakCommands.SCHED_COMPANIONS]：厂商跑分场景在把 policy7
     * 软下限顶到 3250000 的同时也发了那几条，我们此前只发频率所以一直被压在 2300。
     *
     * **未在本机验证**，所以做成显式开关而不是默认行为 —— 打开才能测，测完能一键关回去，
     * 不留残留。用户报告结论后再决定是否默认启用、以及默认带哪一套。
     */
    val freqSchedCompanion: Flow<TweakCommands.Companion?> = context.tweakStore.data.map { prefs ->
        val id = prefs[freqCompanionKey] ?: return@map null
        runCatching { TweakCommands.Companion.valueOf(id) }.getOrNull()
    }

    /** 频率请求是否只发下限。默认 false（软硬上限都发）。 */
    val freqRangeMode: Flow<TweakCommands.RangeMode> = context.tweakStore.data.map { prefs ->
        prefs[freqRangeModeKey]?.let { name ->
            TweakCommands.RangeMode.entries.firstOrNull { it.name == name }
        } ?: if (prefs[legacyFreqFloorOnlyKey] == true) {
            TweakCommands.RangeMode.FLOOR_ONLY
        } else {
            TweakCommands.RangeMode.RANGE
        }
    }

    /**
     * 频率请求是否附带硬上下限（MIN_HL / MAX_HL）。
     *
     * 默认 false：2026-10-14 真机逐个单独发，软硬等效，见 `CpuControl.hardLimits`。
     */
    val freqHardLimits: Flow<Boolean> = context.tweakStore.data.map { it[freqHardLimitsKey] ?: false }

    suspend fun setFreqHardLimits(enabled: Boolean): Outcome = touchMutex.withLock {
        context.tweakStore.edit { it[freqHardLimitsKey] = enabled }
        Outcome(true, Message(if (enabled) R.string.freq_hard_limits_on else R.string.freq_hard_limits_off))
    }

    suspend fun setFreqRangeMode(mode: TweakCommands.RangeMode): Outcome {
        context.tweakStore.edit { it[freqRangeModeKey] = mode.name }
        return Outcome(true, Message(R.string.tweak_mode_changed, listOf(Message(mode.labelRes))))
    }

    suspend fun setFreqSchedCompanion(id: String?): Outcome {
        context.tweakStore.edit { prefs ->
            if (id == null) prefs.remove(freqCompanionKey)
            else prefs[freqCompanionKey] = id
        }
        // id 认不出来（旧版本存下的、或已被移除的配合项）当成"没开"，不拼出一个空名。
        val companion = id?.let { TweakCommands.companionMessage(it) }
        return Outcome(
            true,
            if (companion != null) Message(R.string.tweak_companion_on, listOf(companion))
            else Message(R.string.tweak_companion_off),
        )
    }

    private val touchRateKey = intPreferencesKey("touch_rate")
    /** 用户在数值项里填过的值，键为 `itemId|旋钮键名`。没填过的旋钮不在表里。 */
    private val touchValuesKey = stringPreferencesKey("touch_values")
    private val thermalBrightnessKey = booleanPreferencesKey("thermal_brightness")
    private val autoRotateKey = booleanPreferencesKey("auto_rotate")
    private val rotationSuggestionKey = booleanPreferencesKey("rotation_suggestion")
    private val interpKey = booleanPreferencesKey("frame_interpolation")
    private val interpSrKey = booleanPreferencesKey("frame_interpolation_sr")

    /** 已开启的子项 id。总开关的"开/关"由它派生，不再单独存一份。 */
    val touchItems: Flow<Set<String>> = context.tweakStore.data.map { it[touchItemsKey] ?: emptySet() }

    /** 派生状态：有任何一项开着就算触控优化开着。清理代码只认这一个。 */
    val touchEnabled: Flow<Boolean> = touchItems.map { it.isNotEmpty() }

    val touchRate: Flow<Int> = context.tweakStore.data.map { it[touchRateKey] ?: TweakCommands.DEFAULT_TOUCH_RATE }
    val thermalBrightness: Flow<Boolean> = context.tweakStore.data.map { it[thermalBrightnessKey] ?: false }

    /**
     * 亮度守护的**服务**是否该跑。**不是偏好本身** —— 两者可以为真而不一致。
     *
     * 后端是 VIVO 时根因已经被 `vivo_lcm_brightness_policy_disable` 掐掉：系统不再
     * 压暗亮度。而遗产守卫服务做的是"已经压暗之后把用户选的亮度放回去"——
     * 那一层纯属白烧一个前台服务加每 15 秒的轮询。所以只有 LEGACY 后端才起服务。
     *
     * ## 为什么判据只能有一份
     *
     * 起停原来在两个界面各写一遍，而且写得不一样：首页无条件起、工具页只认 LEGACY。
     * 同一个开关两种行为，用户在两个页面看到的状态会互相矛盾 —— 而两边都不报错。
     * 派生状态的清理代码只认这一个，和上面 `touchEnabled` 同一条规矩。
     */
    val brightnessGuardRuns: Flow<Boolean> =
        combine(brightnessBackend, thermalBrightness) { backend, on -> on && backend == BrightnessBackend.LEGACY }

    /** [brightnessGuardRuns] 的当前值。刚写完偏好、要立刻起停服务时用。 */
    suspend fun brightnessGuardShouldRun(): Boolean = brightnessGuardRuns.first()
    val autoRotate: Flow<Boolean> = context.tweakStore.data.map { it[autoRotateKey] ?: false }
    val rotationSuggestion: Flow<Boolean> = context.tweakStore.data.map { it[rotationSuggestionKey] ?: false }
    val frameInterpolation: Flow<Boolean> = context.tweakStore.data.map { it[interpKey] ?: false }
    val frameInterpolationSr: Flow<Boolean> = context.tweakStore.data.map { it[interpSrKey] ?: false }

    /**
     * 把下面几个开关的**本地记录**换成设备的真实状态。
     *
     * ## 为什么必须做
     *
     * 本地存的是"本软件上次把它设成什么"，不是"设备现在是什么"。两者会分叉：
     * 用户在系统设置里改过、别的工具改过、或者重装之后，本地记录就和设备对不上了。
     * 分叉的后果是界面显示假状态 —— 开关看着是关的，用户点一下"打开"，而设备
     * 本来就是开的，于是什么都没发生，用户以为功能坏了。
     *
     * 设备是事实来源，所以每次进页面读一次。**本地只在存"改之前的原值"时才需要**，
     * 那部分不能也不该从设备推导。
     *
     * 读不到的项保持原值不动 —— 读不到不等于关着。
     */
    suspend fun syncSwitchStateFromDevice() = touchMutex.withLock {
        // **整个函数体切 IO。** 这里面是四次 `PrivilegeManager.exec`，每次一趟
        // Shizuku 往返加一次起进程；调用方有首页 init、调校页刷新等，都在
        // `viewModelScope`（Main）上。不切就等于在首屏组合期间卡住主线程
        // 几十毫秒，表现是"切标签卡一下"。
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val reads = mapOf(
                rotationSuggestionKey to TweakCommands.readRotationSuggestion(),
                autoRotateKey to TweakCommands.readAutoRotate(),
                interpKey to "settings get system ${TweakCommands.KEY_INTERP}",
                interpSrKey to "settings get system ${TweakCommands.KEY_INTERP_SR}",
            )
            val device = reads.mapValues { (_, command) ->
                // 必须用 VivoReply.output 剥掉用户服务的退出标记包装。直接用 exec 的
                // 返回值会带上 `[exit] 0` 尾巴，拿它和 "1" 比对永远不相等，于是
                // "设备明明开着"被读成关着 —— 这就是开关显示假状态的根源。
                VivoReply.output(PrivilegeManager.exec(command))?.takeIf { it.isNotEmpty() && it != "null" }
            }
            context.tweakStore.edit { prefs ->
                device[rotationSuggestionKey]?.let { prefs[rotationSuggestionKey] = it == "1" }
                device[autoRotateKey]?.let { prefs[autoRotateKey] = it == "1" }
                // 插帧关闭时写的是哨兵值 `0:-1:…`，其余都算开着。第三段是运行时会变的
                // 数，判定只看前两段。
                device[interpKey]?.let { prefs[interpKey] = !TweakCommands.isInterpOff(it) }
                device[interpSrKey]?.let { prefs[interpSrKey] = !TweakCommands.isInterpOff(it) }
            }
        }
    }

    data class Outcome(
        val success: Boolean,
        val message: Message,
        /** 原始错误，默认收起、可复制。界面用 `FailureNote` 展示。 */
        val detail: String? = null,
        val failures: List<String> = emptyList(),
        val needsConfirm: Boolean = false,
    )

    /** Runs every command; reports how many failed rather than aborting on the first. */
    private suspend fun runAll(commands: List<String>): Outcome {
        if (!PrivilegeManager.state.value.mode.canElevate) {
            return Outcome(false, Message(R.string.tweak_need_shizuku))
        }
        val failures = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        for (command in commands) {
            val raw = PrivilegeManager.exec("$command 2>&1")
            if (VivoReply.output(raw) == null) {
                failures += command
                val reply = magicau.mtkcontroller.data.exec.DaemonExec.parse(raw)
                reasons += if (reply == null) {
                    "$command → exec returned null — privilege service unavailable"
                } else {
                    "$command → " + (reply.stderr.ifBlank { reply.stdout }).trim().ifBlank { "<empty reply>" }
                }
            }
        }
        return if (failures.isEmpty()) {
            Outcome(true, Message(R.string.tweak_commands_done, listOf(commands.size)))
        } else {
            Outcome(
                success = false,
                message = Message(
                    R.string.tweak_commands_result,
                    listOf(commands.size - failures.size, commands.size, failures.size),
                ),
                detail = reasons.joinToString("\n"),
                failures = failures,
            )
        }
    }

    /**
     * 逐项应用触控子项。目标状态由 [onIds] 完整描述，不区分"开"和"关"两条路径。
     *
     * **为什么逐项而不是整族一起跑**：原来二十几条命令一个开关，一条被 SELinux 拒
     * （`device_config put`，退出码 255）就让整个开关打不开；用户也分不出二十几条里
     * 哪一条真的动了。现在每项自己写、自己回读，一项失败只报那一项。
     *
     * 回读用 [TouchKnob.expectedRead]：写进去不等于系统消费，读回一致至少证明
     * "这一项确实落在系统里了"，比只查退出码强 —— `settings put` 退出码 0 但不落库。
     *
     * 关闭写 [TouchKnob.offTarget]（快照优先），不写数据里的兜底值 —— 见 [TouchKnob] 的说明。
     */
    private suspend fun runTouchItems(
        onIds: Set<String>,
        rateHz: Int,
        originals: TouchOriginals,
        numbers: Map<String, Int> = emptyMap(),
    ): Pair<Outcome, List<KnobChange>> {
        if (!PrivilegeManager.state.value.mode.canElevate) {
            return Outcome(false, Message(R.string.tweak_need_shizuku)) to emptyList()
        }
        val failures = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        val changes = mutableListOf<KnobChange>()
        var items = 0
        for (item in TouchItems.all(rateHz)) {
            val on = item.id in onIds
            items++
            for (knob in item.knobs) {
                val offValue = knob.offTarget(item.id, originals)
                val onValue = onValueOf(knob, item.id, numbers)
                val value = if (on) onValue else offValue
                // 写之前先读一次现值。不是从快照取 —— 快照是"我们上次存的"，
                // 而系统可能已经改过它。要报告的是**实际发生了什么**，
                // 不是"我们认为应该发生什么"。
                val before = VivoReply.output(PrivilegeManager.exec(knob.readCommand()))?.let {
                    if (it == "null") "" else it
                }.orEmpty()
                // 目标就是"空"时不要真去写。两个原因，都在真机上见过：
                // prop 那边 setprop 给一个本来没设过的属性写空串会报
                // "Failed to set property ... to ''" 并非零退出，会被误判成失败；
                // settings 那边则会把一个本来不存在的键凭空创建出来。
                // 而空值本来就是目标状态，所以跳过写入、只核对读回。
                if (value.isEmpty()) {
                    val read = VivoReply.output(PrivilegeManager.exec(knob.readCommand()))?.let {
                        if (it == "null") "" else it
                    }
                    if (!read.isNullOrEmpty()) failures += "${item.id}:${knob.key}(应当为空，读到 $read)"
                    // 没写也要记录。目标本来就是空，前后必然相同，报出来是"无变化"——
                    // 少报一条，界面上的列表就和实际核对的数量对不上。
                    changes += KnobChange(item.id, knob.key, before, read.orEmpty())
                    continue
                }
                val raw = PrivilegeManager.exec("${knob.command(on, offValue, onValue)} 2>&1")
                val reply = VivoReply.output(raw)
                if (reply == null) {
                    failures += "${item.id}:${knob.key}"
                    // "没能送到用户服务"和"送了但被拒"必须分开：前者用户要去连 Shizuku，
                    // 后者是命令本身的事。原来两者都缩成同一个 null，于是一次服务中断
                    // 会被数成"N 处未生效"，用户照着这个数字逐项排查，什么都查不到。
                    reasons += "${item.id}:${knob.key} → " + if (raw == null) {
                        "exec returned null — privilege service unavailable"
                    } else {
                        raw.trim().substringBeforeLast("[exit]").trim().ifBlank { "<empty reply>" }
                    }
                    continue
                }
                // 回读核对。`settings get` 读不到返回 "null"，与 prop 的空串都算"空"。
                val read = VivoReply.output(PrivilegeManager.exec(knob.readCommand()))?.let {
                    if (it == "null") "" else it
                }
                if (read != knob.expectedRead(on, offValue)) {
                    failures += "${item.id}:${knob.key}(回读 ${read ?: "空"})"
                    reasons += "${item.id}:${knob.key} expected=${knob.expectedRead(on, offValue)} read=${read ?: "<null>"}"
                }
                changes += KnobChange(
                    itemId = item.id,
                    key = knob.key,
                    before = before,
                    after = read.orEmpty(),
                )
            }
        }
        // 一项都没选，等于全部关掉 —— 那也是有用的（清干净再切方案）。
        val applied = if (onIds.isEmpty()) {
            Message(R.string.tweak_applied_all_off)
        } else {
            Message(R.string.tweak_applied_items, listOf(onIds.size))
        }
        val outcome = if (failures.isEmpty()) {
            Outcome(true, Message(R.string.tweak_applied_checked, listOf(applied, items)))
        } else {
            Outcome(
                success = false,
                message = Message(R.string.tweak_applied_ineffective, listOf(applied, failures.size)),
                detail = reasons.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                failures = failures,
            )
        }
        return outcome to changes
    }

    /**
     * 一个旋钮这次开关前后的实际值。
     *
     * **为什么需要它**：写后回读只能证明"写进去了"，证明不了"系统理不理睬"。
     * 而人能判断的输入正是这个 —— 开着某项、看到哪些值真的动了，
     * 才知道该去感受哪儿。原先 `runTouchItems` 只收集失败项，
     * 前后值读出来就扔了，等于把唯一有用的信息丢掉了。
     */
    /** 最近一次触控操作里每个旋钮的前后值。界面拿它显示"哪些真的动了"。 */
    @Volatile var lastTouchChanges: List<KnobChange> = emptyList()
        private set

    data class KnobChange(
        val itemId: String,
        val key: String,
        val before: String,
        val after: String,
    ) {
        val changed: Boolean get() = before != after

        /** 给人看的一行：`键 旧值 → 新值`，没有任何变化时简明说没变。 */
        fun describe(): String =
            if (changed) "$key：$before → $after" else "$key：无变化（$before）"
    }

    /**
     * 总开关：开 = 打开全部子项，关 = 关掉全部子项。
     *
     * 逐项开关见 [setTouchItem]。两个入口都归到 [runTouchItems] 一条路，
     * 目标是状态而不是动作，所以不存在"只写开不写关"的半边状态。
     */
    suspend fun setTouchOptimization(enabled: Boolean, rateHz: Int = TweakCommands.DEFAULT_TOUCH_RATE): Outcome =
        touchMutex.withLock {
            if (!PrivilegeManager.state.value.mode.canElevate) return@withLock Outcome(false, Message(R.string.tweak_need_shizuku))
            applyTouchState(
                onIds = if (enabled) TouchItems.all(rateHz).map { it.id }.toSet() else emptySet(),
                rateHz = rateHz,
                numbers = readTouchValues(),
            )
        }

    /** 单个子项开关。只改这一项，其他项保持原状。 */
    suspend fun setTouchItem(id: String, enabled: Boolean, rateHz: Int = TweakCommands.DEFAULT_TOUCH_RATE): Outcome =
        touchMutex.withLock {
            if (!PrivilegeManager.state.value.mode.canElevate) return@withLock Outcome(false, Message(R.string.tweak_need_shizuku))
            // 固件触控方案是一次原子服务调用（`service call bbk_touch_screen_service`），
            // 没有"逐项"可言。放行的话，只点一项也会打开超出请求的那一整套。
            if (touchBackend.first() == TouchBackend.VIVO) {
                return@withLock Outcome(false, Message(R.string.tweak_touch_no_per_item))
            }
            val current = touchItemIds().toMutableSet()
            if (enabled) current += id else current -= id
            applyTouchState(current, rateHz, readTouchValues())
        }

    /**
     * 用户填了一个数值，项已经开着时立刻按新值重写；项关着时只记下来，等开启时用。
     *
     * 重写走和开关同一条 [applyTouchState]，所以快照、读回、失败报告一个都不少：
     * 数值项的"关闭"仍然是恢复打开前的原值，不是恢复成这个新数。
     */
    suspend fun setTouchNumber(id: String, knobKey: String, value: Int, rateHz: Int = TweakCommands.DEFAULT_TOUCH_RATE): Outcome =
        touchMutex.withLock {
            if (!PrivilegeManager.state.value.mode.canElevate) return@withLock Outcome(false, Message(R.string.tweak_need_shizuku))
            if (touchBackend.first() == TouchBackend.VIVO) {
                return@withLock Outcome(false, Message(R.string.tweak_touch_no_per_item))
            }
            val item = TouchItems.all(rateHz).firstOrNull { it.id == id }
            val knob = item?.numericKnobs?.firstOrNull { it.key == knobKey }
                ?: return@withLock Outcome(false, Message(R.string.tweak_touch_no_field))
            val range = knob.numberRange ?: return@withLock Outcome(false, Message(R.string.tweak_touch_no_field))
            if (value !in range) {
                return@withLock Outcome(false, Message(R.string.tweak_touch_out_of_range, listOf(range.first, range.last)))
            }
            val numbers = readTouchValues().toMutableMap()
            numbers["$id|$knobKey"] = value
            context.tweakStore.edit { it[touchValuesKey] = encodeTouchValues(numbers) }
            val onIds = touchItemIds()
            if (id !in onIds) return@withLock Outcome(true, Message(R.string.tweak_touch_value_saved))
            applyTouchState(onIds, rateHz, numbers)
        }

    /**
     * 每次进页面时对一遍：本机**根本没有**这些键的项直接不列出来。
     *
     * 判据是"键不存在而且这组键一个都不存在"。系统对不认识的键既不读也不消费，
     * 写进去只是凭空建一个没人看的设置项 —— 界面显示成功，用户以为调了。
     * 只在**整项**都缺时才隐藏：`_single` / `_double` 这种部分缺失可能是 ROM 只做了分支，
     * 隐藏整项会连带删掉真能用的那几个。只读到空串的键不算缺 —— 可能只是没设过。
     */
    suspend fun presentTouchItemIds(): Set<String> = withContext(Dispatchers.IO) {
        TouchItems.all().filter { item ->
            item.knobs.any { knob ->
                // prop 空串 = 没设过；settings 那边 `get` 对"值本身为空"也会返回空。
                // 两种都不算缺 —— 缺的判据是**读命令给回了 "null"**
                // （`CmdSettingsProvider.getSetting` 对不存在的键返回字面量 null），
                // 不是"读到了空"。
                val read = VivoReply.output(PrivilegeManager.exec(knob.readCommand()))
                read != null && read.trim().lowercase() != "null"
            }
        }.map { it.id }.toSet()
    }

    /** 用户填过的全部数值。界面用它回显，没填过的旋钮保持出厂预设。 */
    suspend fun touchValues(): Map<String, Int> = readTouchValues()

    private suspend fun readTouchValues(): Map<String, Int> =
        decodeTouchValues(context.tweakStore.data.first()[touchValuesKey])

    private fun encodeTouchValues(values: Map<String, Int>): String =
        values.entries.joinToString(",") { "${it.key}=${it.value}" }

    private fun decodeTouchValues(raw: String?): Map<String, Int> =
        raw.orEmpty().split(',').mapNotNull { pair ->
            val sep = pair.lastIndexOf('=')
            if (sep <= 0) return@mapNotNull null
            pair.substring(sep + 1).toIntOrNull()?.let { pair.substring(0, sep) to it }
        }.toMap()

    /** 某个旋钮实际要写的开启值：用户填过就用填的，否则用数据里的预设。 */
    private fun onValueOf(knob: TouchKnob, itemId: String, numbers: Map<String, Int>): String =
        numbers["$itemId|${knob.key}"]?.toString() ?: knob.onValue

    /**
     * 落库 + 执行。执行失败不回填状态，否则界面显示"开着"而系统里没写。
     *
     * 打开一项之前先存下每个旋钮的现值 —— 关闭时写回它，不写兜底值。原因见 [TouchKnob]：
     * 这台机器 `touch_scan_press_time_delay` 出厂就是 300，写死 0 会把调好的机器改差，
     * 而且读回成功，看不出异常。
     *
     * vivo 后端（`TouchBackend.VIVO`）走另一条路：取值按本机实测定，由 [VivoTweaks]
     * 自己写，不经过 [TouchItems] 的逐项拆分。
     */
    private suspend fun applyTouchState(
        onIds: Set<String>,
        rateHz: Int,
        numbers: Map<String, Int> = emptyMap(),
    ): Outcome {
        touchRevision++
        // 只给"即将打开且没有快照"的项取原值。已有快照的不能再取 —— 那时读到的是上一轮
        // 写入值，覆盖掉它就永远恢复不到出厂值了。[RecoverySnapshot.capture] 自己保证这一点。
        for (item in TouchItems.all(rateHz).filter { it.id in onIds }) {
            snapshots.capture(RecoverySnapshot.TOUCH, item.id, item.knobs.map { it.storeKey }) { stored ->
                readKnobValue(item, stored)
            }
        }
        val fresh = readTouchOriginals()

        // 先记下目标状态：部分失败或进程退出后也要知道该清理什么。
        context.tweakStore.edit { prefs ->
            prefs[touchItemsKey] = onIds
            if (TouchItems.REPORT_RATE in onIds) prefs[touchRateKey] = rateHz
        }
        val backend = touchBackend.first()
        val (outcome, changes) = if (backend == TouchBackend.VIVO) {
            // 固件方案是一次原子服务调用，没有旋钮可逐条核对，所以没有前后值可报。
            (if (onIds.isEmpty()) vivo.touch(false) else vivo.touch(true)) to emptyList()
        } else {
            runTouchItems(onIds, rateHz, fresh, numbers)
        }
        lastTouchChanges = changes
        if (outcome.success && backend != TouchBackend.VIVO) dropRestoredOriginals(onIds, fresh)
        val withVerdict = if (outcome.success) outcome.copy(message = noopNote(onIds, fresh, outcome.message)) else outcome
        return if (withVerdict.success) withVerdict else withVerdict.copy(
            message = Message(R.string.tweak_need_manual_cleanup, listOf(withVerdict.message)),
        )
    }

    /**
     * 出厂值已经等于目标值的子项，打开它是个空动作。
     *
     * 这台机器 `touch_scan_press_time_delay` 出厂就是 300，而开启值正是 300 —— 写进去
     * 等于没写，读回还是成功的。不告诉用户的话，他会把"没变化"记成"这项无效"，
     * 而这个结论是错的：这项在别的 ROM 上可能真的有用。
     *
     * 判据直接来自数据：快照值 == 开启值，全部旋钮都是，就是空动作。
     */
    private fun noopNote(onIds: Set<String>, originals: TouchOriginals, message: Message): Message {
        val noops = TouchItems.all().filter { it.id in onIds }.filter { item ->
            item.knobs.isNotEmpty() && item.knobs.all { knob ->
                originals[knob.originalKey(item.id)] == knob.onValue
            }
        }
        if (noops.isEmpty()) return message
        val names = noops.joinToString("、") { context.getString(it.labelRes) }
        return Message(R.string.tweak_noop_note, listOf(message, names))
    }

    /**
     * 关掉的项不再需要原值了，删掉。
     *
     * 删而不是留着：留着的话下次再打开这一项会读到"上一轮的关闭值"当成原值存下来，
     * 于是恢复来恢复去都是自己的值，永远回不到出厂值。
     */
    private suspend fun dropRestoredOriginals(onIds: Set<String>, originals: TouchOriginals) {
        for (item in TouchItems.all().filter { it.id in onIds }) {
            snapshots.release(RecoverySnapshot.TOUCH, item.id, item.knobs.map { it.storeKey })
        }
    }

    /** 用共享快照的存储键（`命名空间.键名`）读回原值。 */
    private suspend fun readKnobValue(item: TouchItem, stored: String): String? {
        val knob = item.knobs.firstOrNull { it.storeKey == stored } ?: return null
        return VivoReply.output(PrivilegeManager.exec(knob.readCommand()))?.let { if (it == "null") "" else it }
    }

    private fun Map<String, String>.toEncoded(): Set<String> =
        map { "${it.key}=${it.value}" }.toSet()

    /**
     * 全部触控项的设备状态，一次读完。
     *
     * 逐项一条命令的话最贵的一项要四个旋钮四条 shell 往返；这里合成一条 `sh -c`，
     * 一次往返读完全部。**不读本地集合** —— 本地集合是"上次点了什么"，
     * 用户或系统把键改掉之后它就和设备分叉了。
     */
    /** 不带参数的入口：读当前上报率和用户填过的数。 */
    suspend fun touchDeviceState(): Map<String, Boolean?> =
        touchDeviceState(currentTouchRate(), readTouchValues())

    suspend fun touchDeviceState(rateHz: Int, onValues: Map<String, Int>): Map<String, Boolean?> =
        withContext(Dispatchers.IO) {
            val items = TouchItems.all(rateHz)
            val knobs = items.flatMap { it.knobs }
            if (knobs.isEmpty()) return@withContext emptyMap()
            val script = knobs.joinToString("; ") { knob ->
                "echo '${knob.storeKey}'; ${if (knob.ns == TouchNs.PROP) "getprop ${knob.key}" else "settings get ${knob.ns.name.lowercase()} ${knob.key}"}"
            }
            val raw = VivoReply.output(PrivilegeManager.exec("sh -c ${VivoReply.quote(script)}"))
            val read = mutableMapOf<String, String?>()
            if (raw != null) {
                val lines = raw.lines()
                var i = 0
                while (i < lines.size) {
                    val marker = lines[i].trim()
                    if (knobs.any { it.storeKey == marker }) {
                        read[marker] = lines.getOrNull(i + 1)?.trim()
                        i += 2
                    } else {
                        i++
                    }
                }
            }
            items.associate { item ->
                val values = item.knobs.map { read[it.storeKey] }
                item.id to if (values.any { it == null }) {
                    null // 有一个旋钮没读到，整项状态就是未知
                } else {
                    item.knobs.indices.any { index ->
                        val knob = item.knobs[index]
                        val text = values[index]!!.takeIf { it != "null" }.orEmpty()
                        text.isNotEmpty() &&
                            text == (onValues["${item.id}|${knob.key}"]?.toString() ?: knob.onValue)
                    }
                }
            }
        }

    /**
     * 全部恢复系统默认：触控 + 厂商功能。
     *
     * 判据是**有原值快照**，不是"界面记着开着" —— 快照才证明本软件写过这个键。
     * 删掉之后系统用自己的 defaults 回退；删不掉的项保留快照，可以再试。
     */
    suspend fun resetAllToSystemDefaults(): Outcome {
        val touch = resetTouchToSystemDefaults(currentTouchRate())
        var vivoOk = 0
        var vivoFailed = 0
        for (feature in VivoFeature.entries) {
            if (!vivo.hasSnapshot(feature)) continue
            if (vivo.resetToSystemDefault(feature)) vivoOk++ else vivoFailed++
        }
        // 直接写的开关只有在**有原值记录**时才删 —— 记录才是"本软件写过它"的证据。
        // 没有记录的键属于用户或系统，恢复默认不该动它。
        for (owned in OwnedKeys.directKeys()) {
            if (!snapshots.hasEntry(RecoverySnapshot.DIRECT, owned.key)) continue
            val raw = PrivilegeManager.exec("${SettingChannel.deleteCommand(owned.table, owned.key)} 2>&1")
            val after = SettingChannel.get(owned.table, owned.key)
            if (VivoReply.output(raw) == null || after != null) {
                vivoFailed++
            } else {
                snapshots.release(RecoverySnapshot.DIRECT, owned.key, listOf(owned.key))
                vivoOk++
            }
        }
        val failed = (if (touch.success) 0 else 1) + vivoFailed
        return Outcome(
            failed == 0,
            if (failed == 0) {
                Message(R.string.reset_defaults_done, listOf(vivoOk))
            } else {
                Message(R.string.reset_defaults_partial, listOf(vivoOk, failed))
            },
        )
    }

    /**
     * 恢复系统默认：删掉本软件写进去的键，让系统回到自己的默认值。
     *
     * **为什么删而不是写一个数值**：settings 的默认值不是存成行的，而是 ROM 里
     * 的 defaults；行不存在时系统自动回退到默认值。所以"删除"才是最准确的重置。
     * prop 用 `setprop ''` 清空，效果等同未设置。
     *
     * 只处理**有原值快照**的项 —— 那是本软件确实写过的证据。没写过的键不碰。
     * 删除成功后释放快照；删除失败的保留快照，用户还能再试一次。
     */
    suspend fun resetTouchToSystemDefaults(rateHz: Int): Outcome = touchMutex.withLock {
        if (!PrivilegeManager.state.value.mode.canElevate) {
            return@withLock Outcome(false, Message(R.string.tweak_need_shizuku))
        }
        var ok = 0
        var failed = 0
        val on = touchItemIds()
        for (item in TouchItems.all(rateHz).filter { it.id in on }) {
            var itemOk = true
            for (knob in item.knobs) {
                val delete = if (knob.ns == TouchNs.PROP) {
                    "setprop ${knob.key} ''"
                } else {
                    SettingChannel.deleteCommand(knob.ns.name.lowercase(), knob.key)
                }
                val raw = PrivilegeManager.exec("$delete 2>&1")
                // 回读确认：键真的不在了才算删掉。只看退出码会把静默失败当成功。
                val after = VivoReply.output(PrivilegeManager.exec(knob.readCommand()))
                    ?.trim()?.let { if (it == "null") "" else it }.orEmpty()
                if (VivoReply.output(raw) == null || after.isNotEmpty()) itemOk = false
            }
            if (itemOk) {
                snapshots.release(RecoverySnapshot.TOUCH, item.id, item.knobs.map { it.storeKey })
                ok++
            } else failed++
        }
        if (ok > 0) context.tweakStore.edit { prefs -> prefs[touchItemsKey] = emptySet() }
        Outcome(
            failed == 0,
            if (failed == 0) Message(R.string.touch_reset_done, listOf(ok))
            else Message(R.string.touch_reset_partial, listOf(ok, failed)),
        )
    }

    private suspend fun readTouchOriginals(): TouchOriginals =
        context.tweakStore.data.first()[touchOriginalsKey].orEmpty()
            .mapNotNull { entry ->
                val key = entry.substringBefore('=')
                val value = entry.substringAfter('=')
                if (key.isBlank()) null else key to value
            }.toMap()

    suspend fun stopVivoTouchIfUnchanged(revision: Long) = touchMutex.withLock {
        if (touchRevision == revision && touchBackend.first() == TouchBackend.VIVO) {
            val result = vivo.touch(false)
            if (result.success) context.tweakStore.edit { it[touchItemsKey] = emptySet() }
        }
    }

    suspend fun renewVivoTouch(): Outcome? = touchMutex.withLock {
        if (touchBackend.first() != TouchBackend.VIVO || !touchEnabled.first()) null else vivo.touch(true)
    }

    suspend fun currentTouchRate(): Int = context.tweakStore.data.first()[touchRateKey]
        ?: TweakCommands.DEFAULT_TOUCH_RATE

    /** 当前已开启的子项 id。读一次，用于单个开关的增量修改。 */
    suspend fun touchItemIds(): Set<String> = touchItems.first()

    suspend fun setFrameInterpolation(enabled: Boolean, superResolution: Boolean): Outcome {
        // 写之前记原值：这是"本软件写过这个键"的唯一证据，恢复默认靠它。
        val key = TweakCommands.keyFor(superResolution)
        rememberOwned(key, "system", key)
        // 之前这里只查 `settings put` 的退出码，而它对任何值都返回 0，于是"开启"写的是
        // 关闭哨兵也照样报成功。现在写后必须回读，读到的值不是目标值就算失败。
        val command = TweakCommands.frameInterpolation(enabled, superResolution)
        val raw = PrivilegeManager.exec(command) ?: return Outcome(false, Message(R.string.tweak_no_service_interp))
        val expected = TweakCommands.interpValue(enabled)
        val actual = VivoReply.output(PrivilegeManager.exec("settings get system $key"))
        if (actual != expected) {
            return Outcome(
                false,
                Message(
                    R.string.tweak_interp_unconfirmed,
                    listOf(expected, actual ?: context.getString(R.string.value_unreadable)),
                ),
            )
        }
        context.tweakStore.edit {
            if (superResolution) it[interpSrKey] = enabled else it[interpKey] = enabled
        }
        return Outcome(
            true,
            Message(if (enabled) R.string.tweak_interp_on else R.string.tweak_interp_off),
        )
    }

    suspend fun setRotationSuggestion(enabled: Boolean): Outcome {
        rememberOwned("show_rotation_suggestions", "secure", "show_rotation_suggestions")
        val outcome = runAll(listOf(TweakCommands.rotationSuggestion(enabled)))
        if (outcome.success) context.tweakStore.edit { it[rotationSuggestionKey] = enabled }
        return outcome
    }

    suspend fun setAutoRotate(enabled: Boolean): Outcome {
        rememberOwned("accelerometer_rotation", "system", "accelerometer_rotation")
        val outcome = runAll(listOf(TweakCommands.autoRotate(enabled)))
        if (outcome.success) context.tweakStore.edit { it[autoRotateKey] = enabled }
        return outcome
    }

    suspend fun forceRotation(rotation: Int): Outcome {
        rememberOwned("accelerometer_rotation", "system", "accelerometer_rotation")
        return runAll(TweakCommands.forceRotation(rotation))
    }

    /**
     * 直接写系统键之前，把这个键的原值记下来。
     *
     * **这是"本软件写过它"的唯一证据**：恢复默认只删有记录的键，
     * 没有记录的键属于用户或系统，不碰。新增同类开关时记得在写之前调它。
     */
    private suspend fun rememberOwned(entry: String, table: String, key: String) {
        runCatching {
            snapshots.capture(RecoverySnapshot.DIRECT, entry, listOf(key)) {
                SettingChannel.get(table, key)?.takeIf { v -> v != "null" }
            }
        }.onFailure {
            // 不阻断写入（用户要的动作仍然执行），但必须留痕：
            // 没有记录 = 这一项以后不会被"恢复默认"删掉。
            magicau.mtkcontroller.data.log.AppLog.w(
                "Tweaks",
                "原值记录失败 $table $key: ${it.javaClass.simpleName}: ${it.message}",
            )
        }
    }

    // --- 相位偏移原值 -------------------------------------------------------
    //
    // 这些属性的原值必须在**第一次写之前**取，之后不再取：第二次读到的已经是我们
    // 写进去的值。原来 ViewModel 每次进页面都覆盖一次内存快照，于是"恢复原值"
    // 恢复的是自己写进去的值（按钮看起来没反应）；进程一死则连这份错的都没有。
    // 现在用共享快照的首次写入语义，持久化在 DataStore 里。

    suspend fun capturePhaseOffsets() {
        for (key in PhaseOffset.keys) {
            snapshots.capture(RecoverySnapshot.DIRECT, key.prop, listOf(key.prop)) {
                PhaseOffset.readProp(it)
            }
        }
    }

    /** 有记录的原值；没记录的 prop 不出现在结果里。 */
    suspend fun phaseOriginals(): Map<String, String?> = buildMap {
        for (key in PhaseOffset.keys) {
            if (!snapshots.hasEntry(RecoverySnapshot.DIRECT, key.prop)) continue
            put(key.prop, snapshots.original(RecoverySnapshot.DIRECT, key.prop, key.prop))
        }
    }

    /** 恢复成功后释放记录。 */
    suspend fun releasePhaseOffsets() {
        for (key in PhaseOffset.keys) {
            snapshots.release(RecoverySnapshot.DIRECT, key.prop, listOf(key.prop))
        }
    }

    /** 亮度守护在设备上的状态（VIVO 后端读系统键）。读不到返回 null。 */
    suspend fun vivoBrightnessOn(): Boolean? =
        vivo.currentValues()["system vivo_lcm_brightness_policy_disable"]
            ?.trim()?.let { it == "1" }

    suspend fun setThermalBrightnessWatch(enabled: Boolean): Outcome = touchMutex.withLock {
        if (brightnessBackend.first() == BrightnessBackend.VIVO) {
            val result = vivo.apply(VivoFeature.BRIGHTNESS, restore = !enabled)
            context.tweakStore.edit { it[thermalBrightnessKey] = vivo.hasSnapshot(VivoFeature.BRIGHTNESS) }
            return@withLock result
        }
        context.tweakStore.edit { it[thermalBrightnessKey] = enabled }
        Outcome(true, Message(if (enabled) R.string.tweak_brightness_on else R.string.tweak_brightness_off))
    }

    suspend fun currentBrightness(): Int? {
        val out = PrivilegeManager.exec(TweakCommands.readBrightness()) ?: return null
        return out.substringBefore("[exit]").trim().toIntOrNull()
    }

    suspend fun setBrightness(value: Int): Outcome = runAll(listOf(TweakCommands.setBrightness(value)))
}
