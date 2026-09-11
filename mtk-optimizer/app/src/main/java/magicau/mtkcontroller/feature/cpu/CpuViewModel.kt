package magicau.mtkcontroller.feature.cpu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.cpu.CpuControl
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.privilege.PrivilegeMode
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.ClusterSetting
import magicau.mtkcontroller.domain.model.CpuCluster
import magicau.mtkcontroller.domain.model.Profile
import java.util.UUID

/** One cluster's editable state. Indices point into [CpuCluster.availableFreqs]. */
data class ClusterEdit(
    val cluster: CpuCluster,
    val minIndex: Int,
    val maxIndex: Int,
    val governor: String?,
) {
    val minFreqKhz: Long get() = cluster.availableFreqs.getOrElse(minIndex) { cluster.minFreqKhz }
    val maxFreqKhz: Long get() = cluster.availableFreqs.getOrElse(maxIndex) { cluster.maxFreqKhz }
}

/**
 * Whether frequency control can be applied right now, and if not, why.
 *
 * This is recomputed whenever the privilege state changes instead of being
 * sampled once when the screen first opened — that snapshot was why the Apply
 * button stayed greyed out after Shizuku had in fact been authorised.
 */
data class PowerHalStatus(
    val checked: Boolean = false,
    val available: Boolean = false,
    val reason: String? = null,
)

data class CpuUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val edits: List<ClusterEdit> = emptyList(),
    val powerHal: PowerHalStatus = PowerHalStatus(),
    val activeProfileName: String? = null,
    val message: String? = null,
)

class CpuViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CpuUiState())
    val state: StateFlow<CpuUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            PrivilegeManager.state.collect { refreshPowerHalStatus(it.mode, it.permissionGranted) }
        }
        refresh()
    }

    /**
     * Resolve PowerHAL availability off the main thread. Resolving the hidden
     * service is a blocking transaction into Shizuku, so it cannot run inline.
     */
    private fun refreshPowerHalStatus(mode: PrivilegeMode, permissionGranted: Boolean) {
        viewModelScope.launch {
            val available = mode.canElevate && PowerHal.isAvailableNow()
            val reason = when {
                available -> null
                !permissionGranted -> "需要先授权 Shizuku 才能调频"
                !mode.canElevate -> "Shizuku 未运行或未授权,无法调频"
                else -> "已授权,但未找到 PowerHAL 服务 —— 非 MTK 设备无法调频"
            }
            _state.value = _state.value.copy(
                powerHal = PowerHalStatus(checked = true, available = available, reason = reason),
            )
        }
    }

    /**
     * Re-poll PowerHAL availability without rescanning the clusters, for when
     * the screen comes back to the foreground — the user may have just granted
     * Shizuku in another app.
     */
    fun recheckPowerHal() {
        PrivilegeManager.refresh()
        val current = PrivilegeManager.state.value
        refreshPowerHalStatus(current.mode, current.permissionGranted)
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val clusters = withContext(Dispatchers.IO) { container.cpuScanner.scan() }
            val edits = clusters.map { cluster ->
                val lo = cluster.availableFreqs.indexOfFirst { it >= cluster.minFreqKhz }
                    .takeIf { it >= 0 } ?: 0
                val hi = cluster.availableFreqs.indexOfLast { it <= cluster.maxFreqKhz }
                    .takeIf { it >= 0 } ?: (cluster.availableFreqs.lastIndex.coerceAtLeast(0))
                ClusterEdit(cluster, lo, hi.coerceAtLeast(lo), cluster.currentGovernor)
            }
            val activeId = container.profileRepository.activeProfileId.first()
            val activeName = activeId?.let { id ->
                container.profileRepository.profiles.first().firstOrNull { it.id == id }?.name
            }
            // Record the untouched limits while nothing of ours is applied, so
            // a later release can be judged against them.
            container.cpuControl.captureBaselineIfClean(clusters)
            _state.value = _state.value.copy(
                loading = false,
                edits = edits,
                activeProfileName = activeName,
            )
            refreshPowerHalStatus(PrivilegeManager.state.value.mode, PrivilegeManager.state.value.permissionGranted)
        }
    }

    fun setGovernor(index: Int, governor: String) = update(index) { it.copy(governor = governor) }

    /** Set an explicit [min, max] pair; order does not matter. */
    fun setRange(index: Int, minIndex: Int, maxIndex: Int) = update(index) { edit ->
        val last = edit.cluster.availableFreqs.lastIndex.coerceAtLeast(0)
        val lo = minIndex.coerceIn(0, last)
        val hi = maxIndex.coerceIn(0, last)
        edit.copy(minIndex = minOf(lo, hi), maxIndex = maxOf(lo, hi))
    }

    private fun update(index: Int, transform: (ClusterEdit) -> ClusterEdit) {
        _state.value = _state.value.copy(
            edits = _state.value.edits.mapIndexed { i, edit -> if (i == index) transform(edit) else edit },
        )
    }

    fun apply() {
        val edits = _state.value.edits
        if (edits.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val settings = edits.map {
                ClusterSetting(
                    policy = it.cluster.policy,
                    minFreqKhz = it.minFreqKhz,
                    maxFreqKhz = it.maxFreqKhz,
                    governor = it.governor,
                )
            }
            // CpuControl owns the whole release-then-acquire sequence, the
            // handle bookkeeping and the governor side of it.
            val outcome = container.cpuControl.apply(edits.map { it.cluster }, settings)
            if (outcome.success) container.syncCpuReapply()
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    /**
     * Bypass PowerHAL and write the hardware range straight to the kernel.
     * The only escape from a cluster pinned by a request whose handle was lost.
     */
    fun emergencyRestore() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val clusters = _state.value.edits.map { it.cluster }
                .ifEmpty { withContext(Dispatchers.IO) { container.cpuScanner.scan(deep = false) } }
            CpuReapplyService.stop(container.context)
            val outcome = container.cpuControl.emergencyRestore(clusters)
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    fun release() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val clusters = _state.value.edits.map { it.cluster }
                .ifEmpty { withContext(Dispatchers.IO) { container.cpuScanner.scan(deep = false) } }
            val outcome = container.cpuControl.release(clusters)
            // Polling would just re-acquire what we released.
            CpuReapplyService.stop(container.context)
            _state.value = _state.value.copy(busy = false, message = outcome.message)
        }
    }

    fun saveAsProfile(name: String) {
        val edits = _state.value.edits
        if (edits.isEmpty()) return
        viewModelScope.launch {
            val profile = Profile(
                id = UUID.randomUUID().toString(),
                name = name,
                clusters = edits.map {
                    ClusterSetting(it.cluster.policy, it.minFreqKhz, it.maxFreqKhz, it.governor)
                },
                createdAtEpochMs = System.currentTimeMillis(),
            )
            container.profileRepository.upsert(profile)
            _state.value = _state.value.copy(message = "已保存方案「$name」")
        }
    }

    fun applyProfile(profile: Profile) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val clusters = _state.value.edits.map { it.cluster }
                .ifEmpty { withContext(Dispatchers.IO) { container.cpuScanner.scan() } }
            val outcome = container.cpuControl.apply(clusters, profile.clusters)
            if (outcome.success) {
                container.profileRepository.setActiveProfile(profile.id)
                container.syncCpuReapply()
            }
            _state.value = _state.value.copy(
                busy = false,
                message = outcome.message,
                activeProfileName = if (outcome.success) profile.name else _state.value.activeProfileName,
            )
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** Exposed for the diagnostics screen. */
    suspend fun clusters(): List<CpuCluster> = container.cpuScanner.scan()
}
