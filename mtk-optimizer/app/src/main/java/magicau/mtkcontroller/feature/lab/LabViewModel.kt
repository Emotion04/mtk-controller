package magicau.mtkcontroller.feature.lab

import kotlinx.coroutines.CancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.lab.LabPlacement
import magicau.mtkcontroller.data.lab.LabCatalog
import magicau.mtkcontroller.data.lab.LabCategory
import magicau.mtkcontroller.data.lab.LabCores
import magicau.mtkcontroller.data.lab.LabControl
import magicau.mtkcontroller.data.lab.LabController
import magicau.mtkcontroller.data.lab.ThermalPolicyCatalog
import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.thermal.ThermalPolicy
import magicau.mtkcontroller.data.thermal.ThermalPolicyReader
import magicau.mtkcontroller.data.thermal.ThermalPolicyController
import magicau.mtkcontroller.data.thermal.ThermalPolicySummary
import magicau.mtkcontroller.data.lab.LabChoice
import magicau.mtkcontroller.data.lab.LabFeature
import magicau.mtkcontroller.data.lab.THERMAL_POLICY_KEY
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.CpuCluster

/** One editable feature, plus everything known about how this device responds. */
data class LabItemState(
    val feature: LabFeature,
    /**
     * 未判定，或节点存在但读不到 —— 控件保持可点。
     *
     * **没有回读不等于不能下发。** SELinux 和 cpufreq 的权限位让大量能用的节点
     * 读不到，而它们背后的 PowerHAL opcode 照样响应。只有 `false` 才置灰。
     */
    val supported: Boolean? = null,
    val nodeValue: String? = null,
    val verdict: LabController.Verdict? = null,
    /** policy name (or [GLOBAL]) -> value. */
    val values: Map<String, Int> = emptyMap(),
    val hold: Boolean = false,
    val busy: Boolean = false,
    /**
     * 运行时解析出的选项，来自 [LabFeature.dynamicChoices]。
     * 空 = 这个功能用静态选项，或还没解析出来。
     */
    val choices: List<LabChoice> = emptyList(),
    /** 运行时读数，null = 这个功能没有读数，或还没读到。 */
    val readout: String? = null,
    /** 各档位的中文摘要（只有温度和频率），key = 档位编号。 */
    val briefs: Map<Int, String> = emptyMap(),
    /** 各档位的结构化策略；只有温控功能有。 */
    val policies: Map<Int, ThermalPolicy> = emptyMap(),
    /**
     * 控件显示的初值是不是从设备读来的。
     *
     * false = 读不到（或这个功能本来就没有可读节点）。界面必须写成「未读」，
     * **不能画成"关"** —— 那会让用户以为功能没开，点一下"打开"却什么都没发生。
     */
    val stateKnown: Boolean = false,
) {
    /** 当前选中的那一档的摘要，按 [values] 现取 —— 换档立刻跟着变。 */
    val selectedBrief: String?
        get() = if (feature.key == THERMAL_POLICY_KEY) {
            values.values.firstOrNull()?.let { briefs[it] }
        } else {
            null
        }

    /** 当前选中的那一档的结构化策略。 */
    val selectedPolicy: ThermalPolicy?
        get() = if (feature.key == THERMAL_POLICY_KEY) {
            values.values.firstOrNull()?.let { policies[it] }
        } else {
            null
        }
}

data class LabUiState(
    val loading: Boolean = true,
    val powerHalReady: Boolean = false,
    val clusters: List<CpuCluster> = emptyList(),
    /**
     * 按**归属**（[LabPlacement]）分组，组内按技术分类（[LabCategory]）排序。
     *
     * 分组键从 category 换成 placement，是因为用户要先分清"哪些是正式功能、
     * 哪些只是探测、哪些是开发工具" —— 那是使用决策；而"这块归 CPU 还是调度"
     * 是实现细节，排在第二级就够了。
     */
    val groups: List<Pair<LabPlacement, List<LabItemState>>> = emptyList(),
    /**
     * 一次性结论（释放结果一类）。
     *
     * 用 [Failure] 而不是裸 `String`：**摘要给人看、原话收起可复制**。
     * 原来这里是 `String`，`LabController.reset()` 直接 `resolve(context)` 把中文
     * 焊死返回，调用方连成功失败都分不出来，点"显示更多"也没有东西可看。
     */
    val message: Failure? = null,
    /**
     * 固件当前生效的温控档位，以及它是谁要求的。
     *
     * 来自 [ThermalPolicyController] 的共享状态，不是实验室自己读的 —— 温控档位
     * 有三个来源（CPU 页、按应用温控、实验室），只有一处真源，三处界面才不会
     * 各显示各的。
     */
    val thermalAppliedIndex: Int? = null,
    val thermalAppliedSource: ThermalPolicyController.Source? = null,
    /**
     * 编辑模式：行上显示"能用 / 没用"的标记按钮。
     *
     * 平时不显示 —— 用户大部分时间是来**用**这些东西的，不是来打分的。
     * 标记是偶尔做一次的事，藏在编辑模式里，评分按钮就不会天天占着屏幕。
     */
    val editing: Boolean = false,
)


/**
 * Backs the canary lab.
 *
 * The screen has two halves with different risk profiles, and they are kept
 * apart deliberately: the read-only panel never writes, and the canary list only
 * writes when the user moves a control on a card that says what it will do.
 */
/** 温控策略索引这个功能的 key。只有它需要"每档的中文摘要"。 */


class LabViewModel(private val container: AppContainer) : ViewModel() {


    companion object {
        /** Key used for features that are not per-cluster. */
        const val GLOBAL = ""
    }

    private val _state = MutableStateFlow(LabUiState())
    val state: StateFlow<LabUiState> = _state.asStateFlow()

    private var clusters: List<CpuCluster> = emptyList()

    init {
        refresh()
        // 收共享的温控档位状态。**不自己读节点**：那一档有三个来源，
        // 各自读会各显示各的，而用户只看到一个"当前生效"。
        // CPU 页改了这里立刻跟着变，不需要任何同步代码。
        viewModelScope.launch {
            ThermalPolicyController.state.collect { thermal ->
                _state.value = _state.value.copy(
                    thermalAppliedIndex = thermal.appliedIndex,
                    thermalAppliedSource = thermal.appliedSource,
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)

            clusters = withContext(Dispatchers.IO) {
                runCatching { container.cpuScanner.scan(deep = false) }.getOrDefault(emptyList())
            }
            val powerHalReady = PowerHal.isAvailableNow()
            val verdicts = container.labController.verdicts()

            // One batched read for every declared node, so the UI can grey out
            // what this device lacks instead of offering a control that cannot
            // work — without paying a shell spawn per feature.
            val nodes = (
                LabCatalog.features.mapNotNull { displayNode(it, clusters) } +
                    // 逐核功能要看**每一簇**的 affected_cpus：只看第一簇会把别的簇掉核当成没掉。
                    LabCatalog.features.filter { it.perCore }
                        .flatMap { feature -> clusters.mapNotNull { feature.node?.replace("%s", it.policy) } }
            ).distinct()
            val values = Sysfs.readMany(nodes)

            // A node we cannot read is not a node the device lacks. Only a
            // plain "no such file" disables a control; SELinux and the
            // cpufreq permission bits make plenty of working nodes unreadable,
            // and the PowerHAL opcode behind them answers regardless.
            val states = Sysfs.states(nodes.filter { values[it] == null })

            // 选项来自设备本身的功能（温控策略索引）要列目录才知道有哪些。
            val dynamic = mutableMapOf<String, List<LabChoice>>()
            for (feature in LabCatalog.features) {
                val provider = feature.dynamicChoices ?: continue
                dynamic[feature.key] = provider()
            }
            // 读数逐个取。都在 IO 上，且只有声明了 readout 的功能才会有。
            val readouts = mutableMapOf<String, String>()
            for (feature in LabCatalog.features) {
                val reader = feature.readout ?: continue
                readouts[feature.key] = reader() ?: continue
            }
            // 温控策略索引各档的中文摘要：现读现解，不预写任何档位的含义。
            val briefs = mutableMapOf<Int, String>()
            val thermalPolicies = mutableMapOf<Int, ThermalPolicy>()
            runCatching {
                for (index in ThermalPolicyReader.availableIndices()) {
                    val policy = ThermalPolicyReader.read(index)
                    briefs[index] = ThermalPolicySummary.brief(policy)
                    thermalPolicies[index] = policy
                }
            }

            val items = LabCatalog.features.map { feature ->
                val node = displayNode(feature, clusters)
                val supported = when {
                    node == null -> null
                    values[node] != null -> true
                    states[node] == Sysfs.NodeState.ABSENT -> false
                    else -> null
                }
                // 开关类控件读设备：读得到就用设备值当初值，读不到标成未知。
                // 滑杆/选项不在这里读 —— 它们的量程和节点单位不是一回事，
                // 硬套会把"读完是 2350 MHz"塞进 0..3500000 的滑杆里。
                val deviceValues = if (feature.control == LabControl.TOGGLE) {
                    toggleValuesFromDevice(feature, clusters, values)
                } else null
                LabItemState(
                    feature = feature,
                    supported = supported,
                    nodeValue = node,
                    verdict = verdicts[feature.key],
                    values = deviceValues ?: defaultValues(feature, dynamic[feature.key]),
                    stateKnown = feature.control != LabControl.TOGGLE || deviceValues != null,
                    choices = dynamic[feature.key] ?: emptyList(),
                    readout = readouts[feature.key],
                    briefs = if (feature.key == THERMAL_POLICY_KEY) briefs else emptyMap(),
                    policies = if (feature.key == THERMAL_POLICY_KEY) thermalPolicies else emptyMap(),
                )
            }

            _state.value = LabUiState(
                loading = false,
                powerHalReady = powerHalReady,
                clusters = clusters,
                // 一级按归属，二级按技术分类。compareBy 用两个 order 字段，
                // 不要在组内再分组 —— 那会把一屏又切碎。
                // 标为「没用」的直接不显示 —— **除非在编辑模式**（不然用户没法取消标记）。
                // 这是 iOS 那种"清理掉用不上的东西"的做法：列表越用越干净，
                // 剩下的都是这台机器上真能用的。
                groups = items
                    .filter { _state.value.editing || it.verdict?.stateEnum() != LabController.State.MANUAL_UNUSABLE }
                    .groupBy { it.feature.placement }
                    .toSortedMap(compareBy { it.order })
                    .map { (placement, list) ->
                        placement to list.sortedWith(
                            // 只有分类一个键。以前第二个键是 label，那是中文串；
                            // 现在它是资源 id，排序会变成按 id 数字排（毫无意义）。
                            // 去掉即可 —— sortedWith 是稳定排序，同类保持声明顺序。
                            compareBy({ it.feature.category.order }),
                        )
                    },
            )
        }
    }

    /**
     * 开关类功能的设备现状。
     *
     * - `core_online`：读各簇 `affected_cpus`，核心在任一簇的列表里就算在线。
     * - `core_pause`：`affected_cpus` 看不出"暂停"，只能算未知 —— 暂停的核仍可能
     *   留在列表里。不拿它冒充在线状态。
     * - 其余（fpsgo、perf_mode、syslimiter…）节点为 null，本来就没有判据。
     *
     * @return 读得到时返回每个 key 的值；读不到返回 null。
     */
    private fun toggleValuesFromDevice(
        feature: LabFeature,
        clusters: List<CpuCluster>,
        batch: Map<String, String?>,
    ): Map<String, Int>? {
        if (feature.key != "core_online") {
            val node = displayNode(feature, clusters) ?: return null
            // 无论怎么分区，初值都是 0 或 1；节点里除了 0/1 还可能带空白。
            val raw = batch[node]?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: return null
            val value = raw.toIntOrNull() ?: return null
            return mapOf(GLOBAL to value)
        }
        val online = mutableSetOf<Int>()
        for (cluster in clusters) {
            val node = feature.node?.replace("%s", cluster.policy) ?: return null
            val list = batch[node]?.trim() ?: return null
            list.split(Regex("\\s+")).forEach { token -> token.toIntOrNull()?.let(online::add) }
        }
        if (online.isEmpty()) return null
        return LabCores.keys.associateWith { key ->
            val core = key.removePrefix("cpu").toIntOrNull() ?: return@associateWith 0
            if (core in online) 1 else 0
        }
    }

    /**
     * Representative node for a feature's capability check.
     *
     * A per-cluster template is resolved against the first cluster: existence is
     * a property of the platform, not of one policy.
     */
    private fun displayNode(feature: LabFeature, clusters: List<CpuCluster>): String? {
        val template = feature.node ?: return null
        return if (template.contains("%s")) {
            clusters.firstOrNull()?.let { template.replace("%s", it.policy) }
        } else {
            template
        }
    }

    private fun defaultValues(
        feature: LabFeature,
        choices: List<LabChoice>? = null,
    ): Map<String, Int> {
        // 运行时选项交给 catalogue 自己决定首选，代码里不写"哪一档好" ——
        // 那是实测结论，ROM 升级就可能失效。
        val initial = if (choices != null && choices.isNotEmpty()) {
            ThermalPolicyCatalog.initialIndex(choices.map { it.value })
        } else {
            feature.range.first
        }
        return when {
            feature.perCore -> LabCores.keys.associateWith { initial }
            feature.perCluster -> clusters.associate { it.policy to initial }
            else -> mapOf(GLOBAL to initial)
        }
    }

    fun setValue(feature: LabFeature, policy: String, value: Int) {
        updateItem(feature.key) { item ->
            // 运行时选项（温控策略索引）不在静态 range 里，coerceIn 会把它们全压成同一个值。
            val allowed = if (item.choices.isNotEmpty()) {
                item.choices.any { it.value == value }
            } else {
                value in feature.range
            }
            val next = if (allowed) value else item.values[policy] ?: value
            item.copy(values = item.values + (policy to next))
        }
    }

    fun setHold(feature: LabFeature, hold: Boolean) {
        updateItem(feature.key) { it.copy(hold = hold) }
    }

    /** Send the current values and record what the device did. */
    fun test(feature: LabFeature) {
        val item = currentItem(feature.key) ?: return
        if (item.supported == false) return

        viewModelScope.launch {
            updateItem(feature.key) { it.copy(busy = true) }
            val verdict = container.labController.apply(
                feature = feature,
                clusters = clusters,
                values = item.values,
                hold = item.hold,
            )
            updateItem(feature.key) {
                it.copy(busy = false, verdict = verdict)
            }
            _state.value = _state.value.copy(message = null)
        }
    }

    fun setEditing(on: Boolean) {
        _state.value = _state.value.copy(editing = on)
    }

    /**
     * 手动标一条：能用 / 没用 / 清除。
     *
     * 传 null 表示清除标记，回到"未测试"。
     */
    /**
     * 编辑期间改本地草稿，**不落盘、不重扫**。
     *
     * 这里原来调 `refresh()`，而 `refresh` 会置 `loading = true` 把整页换成转圈 ——
     * 每点一次标记就闪一下、等半秒，连续标几项根本没法用。
     * 标记只是改本地一个字段，和设备无关，不该触发重新扫描。
     */
    fun mark(feature: LabFeature, state: LabController.State?) {
        pending[feature.key] = state
        updateItem(feature.key) { it.copy(verdict = state?.let { s -> LabController.Verdict(s.name, "") }) }
    }

    /** 编辑过程中改过的项，等「完成」时一次性落盘。 */
    private val pending = mutableMapOf<String, LabController.State?>()

    /**
     * 左上角的「×」或返回：放弃本次编辑，把草稿丢掉。
     *
     * 草稿只活在内存里，丢掉不会影响已保存的标记。
     */
    fun discardEditing() {
        pending.clear()
        _state.value = _state.value.copy(editing = false)
        refresh()
    }

    /** 右上角的「√」：把这次改过的项一次性写进去。 */
    fun commitEditing() {
        viewModelScope.launch {
            pending.forEach { (key, state) -> container.labController.markManually(key, state) }
            pending.clear()
            _state.value = _state.value.copy(editing = false)
            refresh()
        }
    }

    /** Release everything the lab holds and re-read the panel. */
    fun resetAll() {
        viewModelScope.launch {
            val outcome = container.labController.reset()
            _state.value = _state.value.copy(message = Failure(outcome.message, outcome.detail))
        }
    }

    fun clearObservations() {
        viewModelScope.launch {
            container.labController.clearVerdicts()
            refresh()
        }
    }

    private fun currentItem(key: String): LabItemState? =
        _state.value.groups.flatMap { it.second }.firstOrNull { it.feature.key == key }

    private fun updateItem(key: String, transform: (LabItemState) -> LabItemState) {
        _state.value = _state.value.copy(
            groups = _state.value.groups.map { (placement, items) ->
                placement to items.map { if (it.feature.key == key) transform(it) else it }
            },
        )
    }

    /**
     * 离开页面就放掉本页持有的请求。
     *
     * **为什么必须有这个**：实验室的请求是 daemon UserService 发的、句柄存在
     * DataStore 里，离开页面不会自动消失。原来三个 ViewModel 一个 onCleared
     * 都没有，于是"长期保持"的请求在用户离开后继续压着频率，而两边界面都不报错 ——
     * 正是"我设了却没用 / 设完又弹回去"那类故障的成因。
     *
     * `reset()` 同时放 lock 和温控档位（[LabController.reset]），
     * 与 CPU 页「释放」撤的是同一套东西。
     */
    override fun onCleared() {
        // **不能在这个 ViewModel 的 scope 上 launch**：`viewModelScope` 在 clear() 时
        // 先于 onCleared 被取消，launch 出来的协程来不及跑第一句就被取消 ——
        // 释放看起来执行了，实际一个请求都没撤。用容器级作用域，ViewModel 死了它也继续跑。
        container.applicationScope.launch {
            runCatching { container.labController.reset() }
        }
        super.onCleared()
    }
}


private const val TAG = "Lab"

