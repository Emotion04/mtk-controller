package magicau.mtkcontroller.feature.lab

import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.lab.LabController
import magicau.mtkcontroller.data.cpu.CpuFreqNodes
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.CpuCluster

/**
 * One selectable CPU frequency resource.
 *
 * The four ids are the whole CPU frequency family. Each is sent on its own so a
 * single measurement answers "what does this id do here", which is the question
 * every previous explanation of this device's behaviour has guessed at.
 */
data class RawRow(
    @StringRes val labelRes: Int,
    val baseId: Int,
    val enabled: Boolean = false,
    val value: Int = 1_800_000,
) {
    fun idFor(clusterIndex: Int): String = PowerHal.commandId(baseId, clusterIndex)
}

data class RawTestUiState(
    val loading: Boolean = true,
    val clusters: List<CpuCluster> = emptyList(),
    val policy: String? = null,
    val rows: List<RawRow> = defaultRows(),
    val hold: Boolean = false,
    val busy: Boolean = false,
    val verdict: LabController.Verdict? = null,
    val nodePreview: String = "",
) {
    val cluster: CpuCluster? get() = clusters.firstOrNull { it.policy == policy }

    companion object {
        fun defaultRows() = listOf(
            RawRow(R.string.lab_raw_row_min, PowerHal.BASE_MIN, enabled = true),
            RawRow(R.string.lab_raw_row_max, PowerHal.BASE_MAX, enabled = true),
            RawRow(R.string.lab_raw_row_hard_min, PowerHal.BASE_HARD_MIN),
            RawRow(R.string.lab_raw_row_hard_max, PowerHal.BASE_HARD_MAX),
        )
    }
}

class RawTestViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(RawTestUiState())
    val state: StateFlow<RawTestUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val clusters = withContext(Dispatchers.IO) {
                runCatching { container.cpuScanner.scan(deep = false) }.getOrDefault(emptyList())
            }
            val first = clusters.firstOrNull()
            _state.value = _state.value.copy(
                loading = false,
                clusters = clusters,
                policy = first?.policy,
                rows = RawTestUiState.defaultRows().map { row ->
                    // Seed with something inside the cluster's own range so the
                    // first press is not a guaranteed no-op.
                    row.copy(value = first?.availableFreqs?.getOrNull(first.availableFreqs.size / 2)?.toInt() ?: row.value)
                },
                nodePreview = first?.let { nodeFor(it.policy) }.orEmpty(),
            )
        }
    }

    fun setPolicy(policy: String) {
        _state.value = _state.value.copy(policy = policy, nodePreview = nodeFor(policy), verdict = null)
    }

    fun setRow(index: Int, enabled: Boolean? = null, value: Int? = null) {
        _state.value = _state.value.copy(
            rows = _state.value.rows.mapIndexed { i, row ->
                if (i != index) row
                else row.copy(enabled = enabled ?: row.enabled, value = value ?: row.value)
            },
        )
    }

    fun setHold(hold: Boolean) {
        _state.value = _state.value.copy(hold = hold)
    }

    fun send() {
        val current = _state.value
        val cluster = current.cluster ?: return
        val pairs = current.rows
            .filter { it.enabled }
            .map { it.idFor(cluster.index) to it.value.toString() }
        if (pairs.isEmpty()) {
            _state.value = current.copy(verdict = LabController.Verdict(
                LabController.State.NO_EFFECT.name,
                container.context.getString(R.string.lab_raw_no_ids),
            ))
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, verdict = null)
            val verdict = container.labController.sendRaw(
                pairs = pairs,
                readNodes = readNodesFor(cluster.policy),
                hold = current.hold,
            )
            _state.value = _state.value.copy(busy = false, verdict = verdict)
        }
    }

    fun reset() {
        viewModelScope.launch {
            val outcome = container.labController.reset()
            _state.value = _state.value.copy(
                verdict = LabController.Verdict(
                    LabController.State.UNTESTED.name,
                    outcome.message.resolve(container.context),
                ),
            )
        }
    }

    private fun readNodesFor(policy: String): List<Pair<String, String>> {
        // 这两个是 cpufreq 的具名节点，路径走 CpuFreqNodes —— 这个面板整体是
        // "读用户指定的任意节点"，但这两个不是任意的，别在这里再拼一遍。
        return listOf(
            container.context.getString(R.string.lab_raw_node_min) to CpuFreqNodes.min(policy),
            container.context.getString(R.string.lab_raw_node_max) to CpuFreqNodes.max(policy),
        )
    }

    private fun nodeFor(policy: String) =
        "${CpuFreqNodes.dir(policy)}/{scaling_min_freq, scaling_max_freq}"

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

