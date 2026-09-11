package magicau.mtkcontroller.data.cpu

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.powerhal.PerfLockController
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.domain.model.ClusterSetting
import magicau.mtkcontroller.domain.model.CpuCluster

/**
 * The one owner of CPU frequency control.
 *
 * Everything that touches PowerHAL's CPU frequency resources goes through here —
 * the CPU screen, the home dashboard, the profile list and the re-apply service
 * all call [apply] and [release] and nothing else. That is deliberate: the
 * release-then-acquire sequence used to be copy-pasted into six call sites, and
 * every bug in this area was one of them forgetting a step.
 *
 * The request-side invariants (release before acquire, never forget an
 * unreleased handle, serialise) live in [PerfLockController], which is shared
 * with the lab so there is exactly one implementation of them.
 *
 * What is specific to CPU frequency is the *verification*: limits are read back
 * from the kernel because the service returning a handle says nothing about
 * whether the value landed, and because libpowerhal merges every live request —
 * one stale lock makes later ceilings look ignored.
 */
class CpuControl(
    private val store: CpuControlStore,
    private val lock: PerfLockController,
) {

    private companion object {
        const val TAG = "CpuControl"

        /**
         * How long to let libpowerhal settle before reading limits back.
         *
         * An acquire's commands are applied one at a time on the service side
         * and the transaction returns before that finishes, so an immediate read
         * catches a half-applied batch — which reads exactly like a "collapsed
         * range" that is not actually collapsed.
         */
        const val SETTLE_MS = 400L
    }

    /** A result worth showing the user; the detail lives in [Status]. */
    data class Outcome(val success: Boolean, val message: String)

    data class Status(
        val handler: Int = 0,
        val baseline: String? = null,
        val applied: List<ClusterSetting> = emptyList(),
        val observedLimits: String? = null,
        /** True when the kernel does not match the baseline after a release. */
        val releaseIncomplete: Boolean = false,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Pull persisted state into memory. Safe to call more than once. */
    suspend fun load() {
        val handler = lock.handler()
        val baseline = store.baseline()
        val applied = store.lastApplied()
        _status.value = _status.value.copy(handler = handler, baseline = baseline, applied = applied)
        AppLog.i(TAG, "载入状态: handler=$handler, 已应用 ${applied.size} 组设置")
    }

    suspend fun lastApplied(): List<ClusterSetting> = store.lastApplied()

    /**
     * Remember the untouched limits while nothing of ours is applied.
     *
     * This is what lets a later release be checked: "did the kernel go back to
     * where it started", which a oneway release call cannot tell us.
     */
    suspend fun captureBaselineIfClean(clusters: List<CpuCluster>) {
        if (store.baseline() != null) return
        if (lock.handler() != 0) return
        val limits = readLimits(clusters) ?: return
        store.setBaseline(limits)
        _status.value = _status.value.copy(baseline = limits)
        AppLog.i(TAG, "记录初始上下限: $limits")
    }

    /**
     * Apply [settings], replacing whatever we were holding.
     *
     * The previous request is released first and the whole sequence aborts if
     * that release fails, because acquiring on top of a live request is what
     * makes a cluster appear locked at a value nobody asked for.
     */
    suspend fun apply(clusters: List<CpuCluster>, settings: List<ClusterSetting>): Outcome {
        if (clusters.isEmpty()) return Outcome(false, "未检测到 CPU 簇")
        if (settings.isEmpty()) return Outcome(false, "未选择任何簇")

        val byPolicy = clusters.associateBy { it.policy }
        val pairs = mutableListOf<Pair<String, String>>()
        for (setting in settings) {
            val cluster = byPolicy[setting.policy] ?: continue
            val low = cluster.nearest(setting.minFreqKhz)
            val high = cluster.nearest(setting.maxFreqKhz)
            val floor = minOf(low, high)
            val ceiling = maxOf(low, high)

            // Soft floor and ceiling only. The hard-limit pair is a separate
            // mechanism — setting both halves to one value hard-locks the
            // cluster — so it is left to the platform's thermal policy.
            pairs += PowerHal.commandId(PowerHal.BASE_MIN, cluster.index) to floor.toString()
            pairs += PowerHal.commandId(PowerHal.BASE_MAX, cluster.index) to ceiling.toString()
        }
        if (pairs.isEmpty()) return Outcome(false, "没有匹配到可用的簇")

        // duration 0: a limit the user asked for should stay until released.
        val applied = lock.apply(pairs, durationMs = 0)
        if (!applied.success) return Outcome(false, applied.message)

        store.setLastApplied(settings)
        val governorNote = applyGovernors(clusters, settings)

        delay(SETTLE_MS)
        val observed = readLimits(clusters)
        if (observed != null) AppLog.i(TAG, "内核实际上下限: $observed")

        _status.value = _status.value.copy(
            handler = lock.handler(),
            applied = settings,
            observedLimits = observed,
            releaseIncomplete = false,
        )

        return Outcome(
            success = true,
            message = listOfNotNull("调频已应用", governorNote).joinToString(" · "),
        )
    }

    /**
     * Release our request and report whether the kernel came back.
     *
     * A oneway release returning success proves nothing, so the result is judged
     * by reading the limits back against the baseline recorded before we ever
     * touched anything.
     */
    suspend fun release(clusters: List<CpuCluster>): Outcome {
        val released = lock.release()
        if (!released.success) return Outcome(false, released.message)

        store.setLastApplied(emptyList())

        delay(SETTLE_MS)
        val after = readLimits(clusters)
        val baseline = store.baseline()
        val stuck = after != null && baseline != null && after != baseline
        if (stuck) AppLog.w(TAG, "释放后仍与初始值不同: $after(初始 $baseline)")
        AppLog.i(TAG, "释放后内核上下限: ${after ?: "读取失败"}")

        _status.value = _status.value.copy(
            handler = 0,
            applied = emptyList(),
            observedLimits = after,
            releaseIncomplete = stuck,
        )

        return Outcome(
            success = true,
            message = buildString {
                append("调频已释放")
                after?.let { append(" · 当前 ").append(it) }
                if (stuck) append(" · 仍与初始值不同,可能有旧的调频请求残留;重启 Shizuku 可彻底清除")
            },
        )
    }

    /**
     * Current `scaling_min_freq` / `scaling_max_freq` per cluster, in MHz.
     * Null when no cluster could be read.
     */
    suspend fun readLimits(clusters: List<CpuCluster>): String? {
        if (clusters.isEmpty()) return null
        val parts = clusters.mapNotNull { cluster ->
            val dir = "${CpuScanner.CPUFREQ_ROOT}/${cluster.policy}"
            val lo = Sysfs.read("$dir/scaling_min_freq")?.toLongOrNull()
            val hi = Sysfs.read("$dir/scaling_max_freq")?.toLongOrNull()
            if (lo == null && hi == null) null
            else "${cluster.policy} ${lo?.div(1000) ?: "?"}-${hi?.div(1000) ?: "?"}MHz"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * Governors are a kernel knob with no PowerHAL equivalent, and the node is
     * usually root-only, so this is best-effort and reported separately rather
     * than failing the whole apply.
     */
    private suspend fun applyGovernors(
        clusters: List<CpuCluster>,
        settings: List<ClusterSetting>,
    ): String? {
        val byPolicy = clusters.associateBy { it.policy }
        val results = mutableMapOf<String, Boolean>()
        for (setting in settings) {
            val governor = setting.governor ?: continue
            val cluster = byPolicy[setting.policy] ?: continue
            val node = "${CpuScanner.CPUFREQ_ROOT}/${cluster.policy}/scaling_governor"
            val ok = Sysfs.write(node, governor)
            results[cluster.policy] = ok
            if (ok) AppLog.i(TAG, "${cluster.policy} 调速器 -> $governor")
            else AppLog.w(TAG, "${cluster.policy} 调速器写入失败: $node 不可写")
        }

        if (results.isEmpty()) return null
        val failed = results.filterValues { !it }.keys
        return when {
            failed.isEmpty() -> "调速器已切换"
            failed.size == results.size -> "调速器切换失败:节点不可写,需要 root"
            else -> "调速器部分失败:${failed.joinToString(", ")} 不可写"
        }
    }
}
