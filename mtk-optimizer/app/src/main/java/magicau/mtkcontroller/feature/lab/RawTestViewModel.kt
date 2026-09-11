package magicau.mtkcontroller.feature.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.lab.LabController
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
    val label: String,
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
            RawRow("MIN 软下限", PowerHal.BASE_MIN, enabled = true),
            RawRow("MAX 软上限", PowerHal.BASE_MAX, enabled = true),
            RawRow("MIN_HL 硬下限", PowerHal.BASE_HARD_MIN),
            RawRow("MAX_HL 硬上限", PowerHal.BASE_HARD_MAX),
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
                LabController.State.NO_EFFECT.name, "没有勾选任何 ID",
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
            val message = container.labController.reset()
            _state.value = _state.value.copy(
                verdict = LabController.Verdict(LabController.State.UNTESTED.name, message),
            )
        }
    }

    private fun readNodesFor(policy: String): List<Pair<String, String>> {
        val dir = "/sys/devices/system/cpu/cpufreq/$policy"
        return listOf("下限" to "$dir/scaling_min_freq", "上限" to "$dir/scaling_max_freq")
    }

    private fun nodeFor(policy: String) =
        "/sys/devices/system/cpu/cpufreq/$policy/{scaling_min_freq, scaling_max_freq}"
}
