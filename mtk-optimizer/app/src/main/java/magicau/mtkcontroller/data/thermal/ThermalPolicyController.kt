package magicau.mtkcontroller.data.thermal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.data.powerhal.RequestOutcome

/**
 * 温控档位的**唯一写入口**。
 *
 * ## 为什么必须有这一个类
 *
 * 同一个 PowerHAL 资源（[THERMAL_POLICY_RESOURCE]）原先有三条互不知情的写路径：
 *
 * | 谁 | 怎么写的 |
 *|---|---|
 * | CPU 页「应用」/ 切方案 | 跟随频率一起下发，长期保持，写进方案 |
 * | 按应用温控 | 前台命中规则就下发，离开就放 |
 * | 实验室 | 即时下发，20 秒后自行失效，可长期保持、可自动扫档 |
 *
 * 三处各自 `PowerHal.acquire`、各自持有句柄，释放时只放自己那一把。
 * 用户完全可能同时被两边持有 —— 现象是"我设了却没用 / 设完又弹回去"，
 * 而三处界面各自显示正常，谁也不报错。PowerHAL 对同一资源的多个请求怎么合并
 * 没有文档，所以唯一的稳妥答案是**一次只有一把句柄**。
 *
 * ## 规则
 *
 * 谁最后要，谁拿到；放手时回到**常驻意图**：
 *
 * ```
 * standingIndex = userIndex ?: appRuleIndex
 * ```
 *
 * `userIndex` 是用户在 CPU 页/方案里选的那一档，`appRuleIndex` 是按应用温控
 * 命中的那一档。前者优先 —— 那是用户刚做的决定，不该被轮询覆盖。
 * 实验室要的是临时档，它到期后由调用方 [releaseLab]，这里负责把常驻意图放回去；
 * 否则一次实验会把用户设好的档位吃掉，而用户看不出发生了什么。
 *
 * ## 为什么是 object 而不是注入的类
 *
 * 三个调用方分属三个层（ViewModel、前台服务、实验室控制器），服务的创建由
 * 系统负责、拿不到构造参数。而这个状态本来就该是**全局一份** ——
 * "CPU 页改了，实验室立刻看见"靠的就是所有读方都读同一个 [state]。
 * 真要多实例（测试）时把内部状态提到一个类里即可，接口不用变。
 */
object ThermalPolicyController {

    private const val TAG = "ThermalPolicy"

    /** 谁在要这个档位。用于界面显示"现在是谁在管"，以及释放时核对身份。 */
    enum class Source {
        /** CPU 页「应用」与切换方案。 */
        USER,

        /** 按应用温控：前台命中规则。 */
        APP_RULE,

        /** 实验室：临时档，到期自行失效。 */
        LAB,
    }

    data class State(
        /** 固件当前生效的档位；null = 没有请求，系统默认。 */
        val appliedIndex: Int? = null,
        /** 当前这一把是谁的。 */
        val appliedSource: Source? = null,
        /** 用户选的那一档（CPU 页 / 方案）。null = 没选或关了。 */
        val userIndex: Int? = null,
        /** 按应用温控命中的那一档。null = 没有规则命中。 */
        val appRuleIndex: Int? = null,
    ) {
        /** 撤掉临时档后应该回到的那一档。 */
        val standingIndex: Int? get() = userIndex ?: appRuleIndex
    }


    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 串行化。PowerHAL 的 acquire/release 有顺序含义，不能并发。 */
    private val mutex = Mutex()

    @Volatile
    private var handler = 0

    /** 实验室临时档的时长，由 [requestLab] 记下，[push] 下发时用。 */
    @Volatile
    private var labDurationMs = 0

    /**
     * 用户档：CPU 页「应用」和切换方案走这里。
     *
     * @param index 要生效的档位；null = 撤掉（回到按应用规则或系统默认）。
     */
    suspend fun applyUserIndex(index: Int?): RequestOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            _state.value = _state.value.copy(userIndex = index)
            push(_state.value.standingIndex, standingSource())
            outcomeOf(_state.value.appliedIndex, _state.value.userIndex)
        }
    }

    /** 按应用温控：规则命中或离开时走这里。null = 当前没有规则命中。 */
    suspend fun applyAppRule(index: Int?): RequestOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            _state.value = _state.value.copy(appRuleIndex = index)
            // 用户明确选过的档位优先：那是我刚做的决定，不该被轮询盖掉。
            // 已经生效就更不用动。
            if (_state.value.userIndex != null && _state.value.appliedSource == Source.USER) {
                return@withContext RequestOutcome(
                    true,
                    Message(R.string.thermal_policy_user_priority),
                )
            }
            push(_state.value.standingIndex, standingSource())
            outcomeOf(_state.value.appliedIndex, _state.value.appRuleIndex)
        }
    }

    /**
     * 临时档：实验室走这里。durationMs 之后固件自行失效，
     * 调用方到期后应调 [releaseLab] 回到常驻意图。
     */
    suspend fun requestLab(index: Int, durationMs: Int): RequestOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            labDurationMs = durationMs
            push(index, Source.LAB)
            if (_state.value.appliedIndex == index) {
                RequestOutcome(
                    true,
                    when {
                        durationMs > 0 ->
                            Message(R.string.thermal_policy_lab_applied_timed, listOf(index, durationMs / 1000))
                        else ->
                            Message(R.string.thermal_policy_lab_applied_held, listOf(index))
                    },
                )
            } else {
                RequestOutcome(false, Message(R.string.thermal_policy_apply_failed))
            }
        }
    }

    /** 交还临时档，回到常驻意图。只有当前是 LAB 持有时才动作。 */
    suspend fun releaseLab(): RequestOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (_state.value.appliedSource != Source.LAB) {
                return@withContext RequestOutcome(true, Message(R.string.thermal_policy_nothing_to_release))
            }
            push(_state.value.standingIndex, standingSource())
            outcomeOf(_state.value.appliedIndex, _state.value.userIndex)
        }
    }

    /** 全撤：用户按「释放」、或服务要退出时用。常驻意图一并清空。 */
    suspend fun releaseAll(): RequestOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            // 先放句柄，再清意图。顺序不能倒：push 的早退条件看的是
            // appliedIndex/appliedSource，先把状态清成"本来就没有"会让它直接返回，
            // 句柄留在固件里没人放。
            push(null, null)
            _state.value = State()
            RequestOutcome(
                _state.value.appliedIndex == null,
                Message(
                    if (_state.value.appliedIndex == null) R.string.thermal_policy_released
                    else R.string.thermal_policy_release_failed,
                ),
            )
        }
    }

    /**
     * 服务被系统杀掉时调用：只清本地状态，不重复下发。
     *
     * 句柄可能还有效，但我们已经不知道它的值了 —— 留着旧数字只会让下次释放
     * 去放一个不属于我们的句柄。
     */
    fun forget() {
        handler = 0
        _state.value = State()
    }

    /** 常驻意图当前归谁。 */
    private fun standingSource(): Source? = when {
        _state.value.userIndex != null -> Source.USER
        _state.value.appRuleIndex != null -> Source.APP_RULE
        else -> null
    }

    /**
     * 把固件上的档位变成 [target]。
     *
     * ## 顺序：先拿新的，再放旧的
     *
     * 拿不到新句柄时**保留旧的**：失败不该把现状弄丢。这是这个顺序**唯一**的理由。
     *
     * 曾经还配过一个理由 —— "无请求时设备回落到默认温度阶梯，频率被压一下再放开"。
     * 那是设计期推断，**没有实测依据**，已删。两种顺序的中间态其实都不可推理，
     * 只是不可推理的方式不同：
     *
     * - 先拿后放（这里）：新旧两把并存，固件选哪一把没有文档。
     * - 先放后拿：200 ms 无请求，回落到 `thermal.conf`（`docs/thermal/facts.md` 的
     *   THERM-001 有干净启动对照，cluster2 首档 3100000）。
     *
     * 而"并存时选哪把"**连取大取小都推不出来** —— 见下。
     *
     * ## 为什么和 `PerfLockController` 的顺序相反，而且不能统一
     *
     * 那个类是**先放后拿**，防的是两把同时生效：频率是区间，合并规则有文档
     * （floor 取最大、ceiling 取最小），中间态是两者交集，用户会看到
     * "设了 2.0 G 却只给 1.5 G"。它付得起的代价是中间一小段"无请求"——
     * 对频率就是不受限，无害。
     *
     * 本资源付不起这个代价：无请求意味着回落到系统默认温度阶梯。
     *
     * 而先拿还有第二个理由，那个更硬：**失败路径不会变得更糟。** 新句柄没拿到时
     * 旧句柄还在，用户原设置原样保留；先放则是一旦放成功、随后拿失败，就变成
     * "什么都没有"—— 那是用户完全失去控制的状态。
     *
     * 敢先拿的前提是两把并存的窗口只有一趟 binder：拿到新句柄后**立刻**放旧的。
     * 且本资源是**单值索引**而不是区间，两把并存时固件选哪一把没有文档，
     * 但至少不会算出交集那种可推导的错误值。
     *
     * **看到顺序不一致不要"顺手统一"**，两个方向各在防一个真实的失败。
     *
     * ## 索引不在一个尺度上，所以"并存时选哪把"连取大取小都推不出来
     *
     * 各索引的 cluster2 首档（kHz，见 `docs/thermal/configs/README.md`）：
     *
     * ```
     * 00=2600000  02=2300000  03=1700000  05=2850000  07=3300000
     * 10=2800000  11=2000000  13=2600000  18=2100000
     * ```
     *
     * 03 最保守、07 最激进，**索引号大小和激进度无关**。频率那边能说"两把并存取交集"，
     * 是因为区间有大小；这里没有大小，所以连"固件大概会取哪一边"都说不出来。
     * 这正是"同一资源一次只有一把句柄"必须靠代码保证、而不能指望固件行为的原因。
     *
     * ## 相同档位也要换句柄
     *
     * 不能因为"索引没变"就只改来源标签。临时档到期是固件行为，我们收不到通知；
     * 若只是改标签，到期后用户那一档也跟着没了。
     *
     * ## 放掉的是旧句柄，不是刚设的那个
     *
     * `stale` 在 acquire **之前**取，所以它永远是上一把的句柄号。切 1 → 2 的顺序是
     * 拿到 2 的句柄、写入状态、**然后**放 1 的句柄。新档位在拿到句柄那一刻就已经
     * 在下发了，中间不需要"先释放"；释放也不会碰刚设好的那一把。
     *
     * 但释放这一步**不能省**：PowerHAL 的请求是累积的，不是替换的。不放的话上一把
     * 永远留在固件里，而我们从此丢了它的句柄号，再也放不掉 —— 只有进程死才能清。
     * 这就是"同一资源一次只有一把句柄"那条规则的原因。
     *
     * ## 两种顺序都不做成设置项
     *
     * 这里和 `PerfLockController` 的顺序相反，而且**不提供开关让用户选**：
     * 这不是用户层面的偏好，是 binder 合并语义，"两把并存时固件选哪一把没有文档"。
     * 给一个没人能判断对错的选项，只会让用户以为是自己选错了方向。
     */
    private suspend fun push(target: Int?, source: Source?) {
        val current = _state.value
        if (target == current.appliedIndex && source == current.appliedSource) return

        val stale = handler
        if (target != null) {
            val duration = if (source == Source.LAB) labDurationMs else 0
            val acquired = PowerHal.acquire(
                pairs = listOf(PowerHal.fixedCommandId(THERMAL_POLICY_RESOURCE) to target.toString()),
                durationMs = duration,
            )
            val fresh = (acquired as? PowerHal.Result.Success)?.handler ?: 0
            if (fresh <= 0) {
                AppLog.w(
                    TAG,
                    "下发温控策略索引 $target 失败: ${AppLog.text(acquired.message)}（保留原句柄 $stale）",
                )
                return
            }
            handler = fresh
            _state.value = current.copy(appliedIndex = target, appliedSource = source)
            AppLog.i(TAG, "温控策略索引 → $target（$source，句柄 $fresh）")
            if (stale > 0) {
                PowerHal.release(stale)
                AppLog.i(TAG, "释放旧句柄 $stale")
            }
        } else {
            handler = 0
            _state.value = current.copy(appliedIndex = null, appliedSource = null)
            if (stale > 0) {
                PowerHal.release(stale)
                AppLog.i(TAG, "释放温控策略句柄 $stale，回到系统默认")
            }
        }
    }

    /** 按"成了 / 本来就是这样 / 没成"给一句结论。 */
    private fun outcomeOf(applied: Int?, wanted: Int?): RequestOutcome = when {
        // 两边都 null 也是"清掉了"，不是"应用了 null"。
        applied != null && applied == wanted ->
            RequestOutcome(true, Message(R.string.thermal_policy_applied, listOf(applied)))
        applied == null -> RequestOutcome(true, Message(R.string.thermal_policy_cleared))
        else -> RequestOutcome(false, Message(R.string.thermal_policy_apply_failed))
    }
}

/** MTK `PERF_RES_THERMAL_POLICY` 的资源号。全项目只在这一处定义。 */
const val THERMAL_POLICY_RESOURCE = 0x03000000
