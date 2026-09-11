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
         * When to read the limits back after an apply or a release.
         *
         * libpowerhal applies a command array **one entry at a time** and the
         * transaction returns before that finishes, so a single early read
         * cannot tell "not applied yet" from "never applied".
         *
         * A single 400 ms read was the previous behaviour, and it left exactly
         * that ambiguity open: on-device logs showed a batch accepted with a
         * valid handle whose values had not moved 400 ms later. Sampling more
         * than once separates the two cases, and the last sample is the one
         * reported as the result.
         */
        val SAMPLE_DELAYS_MS = longArrayOf(400L, 1_500L, 3_000L)
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
        val limits = hardwareRange(clusters) ?: return
        store.setBaseline(limits)
        _status.value = _status.value.copy(baseline = limits)
        AppLog.i(TAG, "记录硬件范围: $limits")
    }

    /**
     * `cpuinfo_min_freq`/`cpuinfo_max_freq` — the silicon's own range.
     *
     * The reference for "did the kernel come back" has to be something that was
     * never ours to move. An earlier version snapshotted `scaling_min_freq` at
     * whatever moment the screen first opened, which on an already-clamped
     * device records the *clamped* value as normal and then never notices that
     * releases do nothing.
     */
    suspend fun hardwareRange(clusters: List<CpuCluster>): String? {
        if (clusters.isEmpty()) return null
        val parts = clusters.mapNotNull { cluster ->
            val dir = "${CpuScanner.CPUFREQ_ROOT}/${cluster.policy}"
            val lo = Sysfs.read("$dir/cpuinfo_min_freq")?.toLongOrNull()
            val hi = Sysfs.read("$dir/cpuinfo_max_freq")?.toLongOrNull()
            if (lo == null || hi == null) null
            else "${cluster.policy} ${lo / 1000}-${hi / 1000}MHz"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
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

        val observed = sampleLimits(clusters, "应用")

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

        val after = sampleLimits(clusters, "释放")

        // Deliberately NOT reported as a warning. The ceiling legitimately sits
        // below the hardware maximum whenever the platform's own thermal or
        // power-saving logic clamps — on-device readings of the same device gave
        // 2200 MHz on one release and 2400 MHz on another with nothing of ours
        // applied. Flagging every such gap would be noise, and calling it a
        // stale request would be wrong. The comparison lives in the read-only
        // panel, where the hardware range and the power/thermal state are shown
        // side by side and the user can judge it.
        val baseline = store.baseline()
        if (after != null && baseline != null && after != baseline) {
            AppLog.i(TAG, "上限低于硬件范围: $after(硬件 $baseline)· 平台温控/省电也会造成同样结果")
        }

        _status.value = _status.value.copy(
            handler = 0,
            applied = emptyList(),
            observedLimits = after,
            releaseIncomplete = false,
        )

        return Outcome(
            success = true,
            message = buildString {
                append("调频已释放")
                after?.let { append(" · 当前 ").append(it) }
            },
        )
    }

    /**
     * Read the limits back at several delays and return the settled reading.
     *
     * Every sample is logged. If they all agree, the request simply did not
     * land; if they differ, the batch was still being applied and the earlier
     * reading was a half-applied batch rather than a result. Without this the
     * two are indistinguishable in a log, which is precisely how a working
     * range was once misdiagnosed as unsupported.
     */
    private suspend fun sampleLimits(clusters: List<CpuCluster>, phase: String): String? {
        var previousDelay = 0L
        var settled: String? = null
        for (delayMs in SAMPLE_DELAYS_MS) {
            delay(delayMs - previousDelay)
            previousDelay = delayMs
            val sample = readLimits(clusters)
            AppLog.i(TAG, "内核实际上下限($phase t+${delayMs}ms): ${sample ?: "读取失败"}")
            settled = sample ?: settled
        }
        return settled
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
