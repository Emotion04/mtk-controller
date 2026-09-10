package magicau.mtkcontroller.data.cpu

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.domain.model.ClusterSetting
import magicau.mtkcontroller.domain.model.CpuCluster

/**
 * The one owner of CPU frequency control.
 *
 * Everything that touches PowerHAL goes through here — the CPU screen, the home
 * dashboard, the profile list and the re-apply service all call [apply] and
 * [release] and nothing else. That is deliberate: the same five-step sequence
 * (release the old handle, acquire, keep the new handle, persist what was
 * applied, verify) used to be copy-pasted into six call sites, and every bug in
 * this area was one of them forgetting a step. Now the sequence exists once and
 * the invariants are structural:
 *
 *  - a handle is only forgotten once its release was confirmed, because a live
 *    request we no longer know about keeps clamping the cluster forever
 *  - an acquire is never issued while we still hold a handle, because PowerHAL
 *    requests accumulate rather than replace, and because libpowerhal merges
 *    every enabled scenario (floor = max of floors, ceiling = min of ceilings)
 *    one stale lock makes later ceilings look ignored
 *  - [apply] and [release] are serialised by a mutex, so the re-apply service
 *    and a ViewModel cannot interleave into two live handles
 *
 * Protocol notes and the command-id table live in [PowerHal].
 */
class CpuControl(private val store: CpuControlStore) {

    private companion object {
        const val TAG = "CpuControl"

        /**
         * How long to let libpowerhal settle before reading limits back.
         *
         * An acquire's commands are applied one at a time on the service side
         * and the transaction returns before that finishes, so an immediate
         * read catches a half-applied batch — which reads exactly like a
         * "collapsed range" that is not actually collapsed.
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

    private val mutex = Mutex()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Pull persisted state into memory. Safe to call more than once. */
    suspend fun load() {
        val handler = store.handler()
        val baseline = store.baseline()
        val applied = store.lastApplied()
        _status.value = _status.value.copy(
            handler = handler,
            baseline = baseline,
            applied = applied,
        )
        AppLog.i(TAG, "载入状态: handler=$handler, 已应用 ${applied.size} 组设置")
    }

    suspend fun lastApplied(): List<ClusterSetting> = store.lastApplied()

    /**
     * Remember the untouched limits while nothing of ours is applied.
     *
     * This is what lets a later release be checked: "did the kernel actually go
     * back to where it started", which a oneway release call cannot tell us.
     */
    suspend fun captureBaselineIfClean(clusters: List<CpuCluster>) {
        if (store.baseline() != null) return
        if (store.handler() != 0) return
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
    suspend fun apply(clusters: List<CpuCluster>, settings: List<ClusterSetting>): Outcome = mutex.withLock {
        if (clusters.isEmpty()) return@withLock Outcome(false, "未检测到 CPU 簇")
        if (settings.isEmpty()) return@withLock Outcome(false, "未选择任何簇")

        val current = store.handler()
        if (current > 0) {
            val released = PowerHal.release(current)
            if (released is PowerHal.Result.Failure) {
                AppLog.w(TAG, "释放旧请求失败,放弃本次应用: ${released.message}")
                return@withLock Outcome(false, "无法释放上一次调频请求:${released.message}")
            }
            store.setHandler(0)
            _status.value = _status.value.copy(handler = 0)
            AppLog.i(TAG, "已释放上一次请求 handler=$current")
        }

        val byPolicy = clusters.associateBy { it.policy }
        val pairs = mutableListOf<Pair<String, String>>()
        for (setting in settings) {
            val cluster = byPolicy[setting.policy] ?: continue
            val low = cluster.nearest(setting.minFreqKhz)
            val high = cluster.nearest(setting.maxFreqKhz)
            val floor = minOf(low, high)
            val ceiling = maxOf(low, high)

            // Soft floor and ceiling only. The hard-limit pair (see PowerHal)
            // is a separate mechanism: setting both halves to one value is the
            // documented way to hard-lock a cluster, so pushing a user range
            // there pins instead of bounding. Hard limits stay with the
            // platform's thermal policy.
            pairs += PowerHal.commandId(PowerHal.BASE_MIN, cluster.index) to floor.toString()
            pairs += PowerHal.commandId(PowerHal.BASE_MAX, cluster.index) to ceiling.toString()
        }
        if (pairs.isEmpty()) return@withLock Outcome(false, "没有匹配到可用的簇")

        AppLog.i(TAG, "应用调频: " + pairs.joinToString(", ") { "${it.first}=${it.second}" })

        val acquired = PowerHal.acquire(pairs)
        if (acquired is PowerHal.Result.Failure) {
            AppLog.e(TAG, "调频失败: ${acquired.message}")
            return@withLock Outcome(false, acquired.message)
        }
        val handler = (acquired as PowerHal.Result.Success).handler
        store.setHandler(handler)
        store.setLastApplied(settings)
        AppLog.i(TAG, "调频已应用, handler=$handler")

        val governorNote = applyGovernors(clusters, settings)

        delay(SETTLE_MS)
        val observed = readLimits(clusters)
        if (observed != null) AppLog.i(TAG, "内核实际上下限: $observed")

        _status.value = _status.value.copy(
            handler = handler,
            applied = settings,
            observedLimits = observed,
            releaseIncomplete = false,
        )

        return@withLock Outcome(
            success = true,
            message = listOfNotNull("调频已应用", governorNote).joinToString(" · "),
        )
    }

    /**
     * Release our request and report whether the kernel came back.
     *
     * A oneway release returning success proves nothing, so the result is
     * judged by reading the limits back against the baseline recorded before we
     * ever touched anything.
     */
    suspend fun release(clusters: List<CpuCluster>): Outcome = mutex.withLock {
        val handler = store.handler()
        if (handler <= 0) {
            AppLog.w(TAG, "释放请求:没有已记录的 handler")
            return@withLock Outcome(false, "没有可释放的请求")
        }

        val released = PowerHal.release(handler)
        AppLog.i(TAG, "释放请求 handler=$handler -> ${released.message}")
        if (released is PowerHal.Result.Failure) return@withLock Outcome(false, released.message)

        store.setHandler(0)
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

        return@withLock Outcome(
            success = true,
            message = buildString {
                append("调频已释放")
                after?.let { append(" · 当前 ").append(it) }
                if (stuck) append(" · 仍与初始值不同,可能有旧的调频请求残留;重启 Shizuku 可彻底清除")
            },
        )
    }

    /** Drop our bookkeeping without touching the service, e.g. after a reboot. */
    suspend fun forget() {
        store.setHandler(0)
        store.setLastApplied(emptyList())
        _status.value = _status.value.copy(handler = 0, applied = emptyList())
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
