package magicau.mtkcontroller.data.cpu

import android.content.Context
import kotlinx.coroutines.delay
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import magicau.mtkcontroller.data.settings.SettingChannel
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.powerhal.PerfLockController
import magicau.mtkcontroller.data.powerhal.RequestOutcome
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.domain.model.ClusterSetting
import magicau.mtkcontroller.domain.model.CpuCluster
import magicau.mtkcontroller.data.tweaks.TweakCommands

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
    /**
     * 只用来解析资源。
     *
     * 这个类拿不到 Context 时只能把中文写成字面量；而它的结论**要同时**给界面
     * 和日志，还有几处是逐簇拼接的（见 `updateVerification`）—— 那种串没法只
     * 靠返回一个 `Message` 表达，需要当场解析。`data/` 里别的类本来就用 Context。
     */
    private val context: Context,
    private val store: CpuSettingsStore,
    private val lock: PerfLockController,
    private val readNodes: suspend (List<String>) -> Map<String, String?> = Sysfs::readMany,
    private val waitForSample: suspend (Long) -> Unit = { delay(it) },
    private val createProbeLoad: () -> AutoCloseable = { CpuProbeLoad() },
    /**
     * 频率请求要附带的调度器配合项。默认空 = 不带。
     *
     * 做成参数而不是在类里读设置：单元测试要能分别验"带"和"不带"两条路径，
     * 而读 DataStore 需要 Context，测试里没有。
     */
    private val schedCompanions: () -> List<Pair<Int, String>> = { emptyList() },
    /**
     * true = 只发下限，不发任何上限。
     *
     * 厂商高性能场景（`powerscntbl.xml` 3160）就是这么做的。我们在高频段发上限，
     * 会被 HAL 翻译成一个等于其表内 `freqMax` 的帽子 —— 详情见
     * [TweakCommands.RangeMode]。
     */
    private val rangeMode: () -> TweakCommands.RangeMode = { TweakCommands.RangeMode.RANGE },
    /**
     * true = 除了软上下限，还发硬的那一对（MIN_HL / MAX_HL）。
     *
     * **默认 false，因为实测软硬等效。** 2026-10-14 在 policy7 上逐个单独发：
     * 软 MAX 和硬 MAX_HL 给同一个值，`scaling_max_freq` 都被压到那个值；
     * 软 MIN 和硬 MIN_HL 同样都能把下限抬上去。四个单独发都生效，释放都干净。
     *
     * 早先这里是**无条件软硬齐发**，理由是 2026-09-24 那次实测里
     * 「只在软族里发下限，请求完全暴露在常驻上限之下」。那一例的真正原因是
     * 请求值超出了系统包络（本机约 2000–2300），换成硬的下限同样过不去 ——
     * 不是软硬之分。多两个资源号只多两趟 binder 和两个可能失败的释放点。
     *
     * 留这个开关：换机型时若固件只认硬的那一对，用户不用等新版本。
     */
    private val hardLimits: () -> Boolean = { false },
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
        const val SETTLE_MS = 400L

        val SAMPLE_DELAYS_MS = longArrayOf(400L, 1_500L, 3_000L)
    }

    /** success acknowledges the request operation; effectiveness lives in Status.verification. */

    /**
     * 供界面与诊断读取的当前状态。
     *
     * 曾在这里放过 `baseline` 和 `releaseIncomplete` 两个字段，都已删除：
     *
     * - `baseline` 是 [CpuControlStore.baseline] 的内存镜像，没有任何读者；
     *   而那份持久值当年存的还是**硬件能力**（`cpuinfo_*`），不是"我们动手之前的
     *   当前限制"（`scaling_*`）—— 注释写的却是后者。2026-10-14 已修正
     *   [captureBaselineIfClean]，但它仍未参与任何判断。
     * - `releaseIncomplete` 想表达"释放后内核没回到基线"，但它只能跟上面那个
     *   基线比 —— **拿两个不同的量相比，永远不可能诚实地为真**，所以三处全写
     *   `false`，注释描述的状态不存在。真要实现这个检查，得先让基线记录的是
     *   请求发出前的 `scaling_*`，那是另一件事。
     */
    data class Status(
        val handler: Int = 0,
        val applied: List<ClusterSetting> = emptyList(),
        /**
         * 上一次应用或释放之后，内核实际持久的上下限（人话形式）。
         *
         * 由 [DiagReport] 和 `DeviceSnapshot` 读出去 —— 它回答的是
         * "我们以为发了什么、内核 actually 是什么"，和 `verification` 是两件事。
         */
        val observedLimits: String? = null,
        val verification: CpuVerification = CpuVerification.UNVERIFIED,
        val verificationDetail: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val operationMutex = Mutex()
    private var lastVerificationKey: String? = null

    /** Pull persisted state into memory. Safe to call more than once. */
    suspend fun load() = operationMutex.withLock {
        val handler = lock.handler()
        val applied = store.lastApplied()
        _status.value = _status.value.copy(handler = handler, applied = applied)
        AppLog.i(TAG, "载入状态: handler=$handler, 已应用 ${applied.size} 组设置")
    }

    suspend fun lastApplied(): List<ClusterSetting> = store.lastApplied()

    /** 用户最后一次要求的范围；释放后仍保留，供快捷开关和亮屏重发使用。 */
    suspend fun lastRequested(): List<ClusterSetting> = store.lastRequested()

    /** 用户是否还希望由本软件控制频率。锁屏自动释放不会清掉它。 */
    suspend fun controlEnabled(): Boolean = store.controlEnabled()

    /**
     * 按上一次要求的范围重新下发，不先读 [lastApplied]。
     *
     * 锁屏释放后 [lastApplied] 是空的，但 [lastRequested] 还在；这就是“一键恢复”
     * 和普通 [reapply] 的区别。
     */
    suspend fun applyLastRequested(clusters: List<CpuCluster>): RequestOutcome =
        operationMutex.withLock {
            val settings = store.lastRequested()
            if (settings.isEmpty()) {
                return@withLock RequestOutcome(false, Message(R.string.cpu_no_last_range))
            }
            applyLocked(clusters, settings)
        }

    /**
     * 释放并标记“用户不再需要控制”。
     *
     * 供界面上的释放/恢复默认、快捷开关关闭使用。**锁屏自动释放不走这里** ——
     * 它只调用 [release]，保留用户意图，亮屏后才能恢复。
     */
    suspend fun disable(clusters: List<CpuCluster>): RequestOutcome = operationMutex.withLock {
        val result = releaseLocked(clusters)
        if (result.success) store.setControlEnabled(false)
        result
    }

    /** Exclusive, finite probe. Never persists an experimental setting for polling. */
    suspend fun probe(
        clusters: List<CpuCluster>, policy: String, low: Long, high: Long,
        mode: CpuProbeMode, emit: (String) -> Unit,
    ): RequestOutcome = operationMutex.withLock {
        val cluster = clusters.firstOrNull { it.policy == policy }
            ?: return@withLock RequestOutcome(false, Message(R.string.cpu_probe_no_cluster))
        val lo = minOf(cluster.nearest(low), cluster.nearest(high))
        val hi = maxOf(cluster.nearest(low), cluster.nearest(high))
        val pairs = mode.pairs(cluster.index, lo, hi)
        val saved = store.lastApplied()
        val started = System.nanoTime()
        val busy = CpuBusySampler()
        suspend fun sample(label: String) {
            val readings = readSnapshot(clusters)
            val utilization = busy.sample(readNodes(listOf("/proc/stat"))["/proc/stat"])
            emit("$label elapsed=${(System.nanoTime() - started) / 1_000_000}ms " +
                readings.joinToString { it.describe() } + " busy=[$utilization]（分次读取）")
        }
        emit("模式=${mode.name} policy=$policy 请求=$lo-$hi kHz 原设置=$saved")
        sample("开始")
        if (mode == CpuProbeMode.OBSERVE) {
            repeat(30) { waitForSample(1000); sample("观察 ${it + 1}/30") }
            return@withLock RequestOutcome(true, Message(R.string.cpu_probe_observe_done))
        }
        if (lock.handler() > 0 && saved.isEmpty())
            return@withLock RequestOutcome(false, Message(R.string.cpu_probe_handler_no_settings))
        var touched = false
        var restored = true
        var load: AutoCloseable? = null
        try {
            withContext(NonCancellable) {
                if (lock.handler() > 0) {
                    val released = lock.release()
                    check(released.success) { released.message }
                }
                touched = true
                store.setLastApplied(emptyList())
                _status.value = _status.value.copy(handler = 0, applied = emptyList(), verification = CpuVerification.UNVERIFIED)
            }
            waitForSample(1000)
            sample("释放原请求后")
            if (mode.loaded) {
                load = createProbeLoad()
                emit("计算负载开始：最多 8 个线程，不绑定核心；20 秒自动截止，阶段结束提前停止")
                waitForSample(2000)
                sample("普通负载预热后，尚未写入测试请求")
            }
            emit("测试资源=$pairs duration=8000ms")
            val acquired = lock.apply(pairs, durationMs = 8000)
            check(acquired.success) { acquired.message }
            val acceptedAt = System.nanoTime()
            emit("测试句柄=${lock.handler()}")
            repeat(8) {
                waitForSample(1000)
                val age = (System.nanoTime() - acceptedAt) / 1_000_000
                sample("测试 ${it + 1}/8 acquire返回后=${age}ms ${if (age >= 8000) "已越过请求时长，不用于约束判定" else "请求名义时长内，仍需结合实际采样耗时"}")
            }
        } finally {
            load?.close()
            if (mode.loaded) emit("计算负载已停止")
            if (touched) withContext(NonCancellable) {
                val released = if (lock.handler() > 0) {
                    lock.release()
                } else {
                    RequestOutcome(true, Message(R.string.lock_no_test_handler))
                }
                emit("测试释放=${released.success} ${AppLog.text(released.message)}")
                if (released.success) {
                    waitForSample(1000)
                    sample("测试释放后")
                    if (saved.isNotEmpty()) {
                        val result = applyLocked(clusters, saved)
                        restored = result.success
                        emit("恢复原请求=${result.success} ${AppLog.text(result.message)}")
                    } else emit("原请求为空，保持释放")
                } else {
                    restored = false
                    _status.value = _status.value.copy(handler = lock.handler())
                    emit("恢复中止：测试释放失败；有限请求应到期，请检查读回后手动释放")
                }
            }
        }
        RequestOutcome(
            restored,
            Message(if (restored) R.string.cpu_probe_done else R.string.cpu_probe_restore_failed),
        )
    }

    /**
     * 在**我们没有下发任何请求**的时候，记下内核此刻的限制。
     *
     * 有了它，将来才能回答"释放之后内核回到出发点了吗"—— 一次 oneway 释放的
     * 返回值证明不了这件事。
     *
     * **记的是 `scaling_min/max_freq`（当前限制），不是 `cpuinfo_*`（硬件能力）。**
     * 两者是不同的量：前者是系统此刻给的，后者是芯片做得到的。早先这里存的是
     * 硬件能力，于是任何"和基线比"的判断都在比两个不同的量。
     *
     * 注意调用时机：只在确认没有本项目的请求时调（见 `CpuViewModel.refresh`）。
     */
    suspend fun captureBaselineIfClean(clusters: List<CpuCluster>) {
        if (store.baseline() != null) return
        // **记的是请求发出前的当前限制（`scaling_*`），不是硬件能力（`cpuinfo_*`）。**
        //
        // 原来这里存 `hardwareRange()`，而注释写的是"Remember the untouched limits"——
        // 存的不是那个东西。这个错误让下游一切"和基线比"的判断都失去意义：
        // 拿硬件能力比当前限制，两个不同的量，比出来的差说明不了任何事。
        // `Status.releaseIncomplete` 当年就是这么变成永远为 false 的。
        //
        // 读法用批量 + 提权补读（[readSnapshot]）：要看到的是"系统此刻实际给的
        // 限制"，直读写不到就不知道该记什么。
        val rows = readSnapshot(clusters)
        if (rows.none { it.min != null || it.max != null }) return
        val limits = rows.joinToString(", ") { it.limitsText() }
        store.setBaseline(limits)
        AppLog.i(TAG, "记录请求前的当前限制: $limits")
    }

    /**
     * Last-resort recovery: write the hardware range straight into the kernel.
     *
     * PowerHAL cannot loosen anything. Its merge is `floor = max(all floors)` and
     * `ceiling = min(all ceilings)`, so a request whose handle was lost keeps
     * clamping the cluster and no new request can undo it — the only ways out
     * are releasing that exact handle (impossible once it is lost) or rebooting.
     *
     * Writing `scaling_max_freq`/`scaling_min_freq` through the elevated shell
     * bypasses PowerHAL entirely and is the one non-reboot route left. It only
     * works where those nodes are writable by the elevated uid: with Shizuku
     * started as root yes, over adb usually not. Each cluster is reported
     * separately so a partial recovery is visible rather than assumed.
     *
     * Ceiling is written before floor so `min <= max` holds at every moment.
     */
    suspend fun emergencyRestore(clusters: List<CpuCluster>): RequestOutcome = operationMutex.withLock {
        withContext(NonCancellable) { emergencyRestoreLocked(clusters) }
    }

    private suspend fun emergencyRestoreLocked(clusters: List<CpuCluster>): RequestOutcome {
        if (clusters.isEmpty()) return RequestOutcome(false, Message(R.string.cpu_no_clusters))

        val before = readLimits(clusters)
        AppLog.i(TAG, "紧急恢复开始,当前: ${before ?: "读取失败"}")

        if (lock.handler() > 0) {
            val released = lock.release()
            if (!released.success) return RequestOutcome(false, released.message, detail = released.detail)
        }
        store.setLastApplied(emptyList())
        store.setControlEnabled(false)
        _status.value = _status.value.copy(handler = 0, applied = emptyList(),
            verification = CpuVerification.UNVERIFIED, verificationDetail = null)

        // Route 1 — write the hardware range straight into the kernel. Only
        // works where those nodes are writable by the elevated uid, which means
        // Shizuku started as root; over adb (uid 2000) it usually is not.
        //
        // 失败原因必须原样留下：紧急恢复是用户已经"卡在高频下不来"的时候按的按钮，
        // 那时候最需要知道的就是"哪个簇、什么权限错误"，而不是一句"没成功"。
        val failures = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        clusters.forEach { cluster ->
            val dir = CpuFreqNodes.dir(cluster.policy)
            val hardwareMax = Sysfs.read(CpuFreqNodes.hardwareMax(cluster.policy))
            val hardwareMin = Sysfs.read(CpuFreqNodes.hardwareMin(cluster.policy))
            if (hardwareMax == null || hardwareMin == null) {
                failures += cluster.policy
                reasons += "${CpuFreqNodes.hardwareMin(cluster.policy)} / ${CpuFreqNodes.hardwareMax(cluster.policy)} 读不到"
                return@forEach
            }
            // Ceiling first so min <= max holds at every moment.
            val maxError = Sysfs.writeOrNull(CpuFreqNodes.max(cluster.policy), hardwareMax)
            val minError = Sysfs.writeOrNull(CpuFreqNodes.min(cluster.policy), hardwareMin)
            AppLog.i(
                TAG,
                "紧急恢复[直接写] ${cluster.policy}: max=$hardwareMax(${if (maxError == null) "成功" else "失败"}) " +
                    "min=$hardwareMin(${if (minError == null) "成功" else "失败"})",
            )
            if (maxError != null || minError != null) {
                failures += cluster.policy
                reasons += buildList {
                    // 报的是**真正去写的那条路径**，不是照字符串再拼一个 ——
                    // 拼错了用户拿着路径去查，查到的却是另一个节点。
                    maxError?.let { add("${CpuFreqNodes.max(cluster.policy)} → $it") }
                    minError?.let { add("${CpuFreqNodes.min(cluster.policy)} → $it") }
                }.joinToString("\n")
            }
        }
        val directWriteDetail = reasons.takeIf { it.isNotEmpty() }?.joinToString("\n")
        delay(SETTLE_MS)
        val afterDirect = readLimits(clusters)
        if (afterDirect != null && afterDirect != before) {
            _status.value = _status.value.copy(handler = 0, applied = emptyList(), observedLimits = afterDirect)
            return RequestOutcome(true, Message(R.string.cpu_direct_write, listOf(afterDirect)))
        }

        // Route 2 — make the platform rewrite the limits itself by cycling
        // power-save. `settings` is writable by the shell uid, so this works
        // over adb where the sysfs write does not: toggling the mode makes the
        // vendor power service re-evaluate and re-push every limit, which
        // overwrites the stale one.
        AppLog.i(TAG, "紧急恢复[直接写] 无效,改用切换省电模式让平台重算")
        // 走 SettingChannel：这台 ROM 上 `settings put` 退出码 0 但不落库，
        // 这条紧急恢复路径因此一直是空操作。见 SettingChannel 的说明。
        val original = SettingChannel.get("global", "low_power")?.trim()?.take(1)
        SettingChannel.put("global", "low_power", "1")
        delay(SETTLE_MS)
        SettingChannel.put("global", "low_power", "0")
        delay(SETTLE_MS * 3)
        // Put the user's own setting back rather than assuming it was off.
        if (original == "1") SettingChannel.put("global", "low_power", "1")

        val afterCycle = sampleLimits(clusters, "紧急恢复")
        _status.value = _status.value.copy(handler = 0, applied = emptyList(), observedLimits = afterCycle)

        return when {
            afterCycle != null && afterCycle != before ->
                RequestOutcome(true, Message(R.string.cpu_cycle_applied, listOf(afterCycle)))
            failures.size == clusters.size ->
                RequestOutcome(false, Message(R.string.cpu_both_failed), detail = directWriteDetail)
            else ->
                RequestOutcome(
                    false,
                    Message(
                        R.string.cpu_restore_failed,
                        listOf(afterCycle ?: context.getString(R.string.value_read_failed)),
                    ),
                    detail = directWriteDetail,
                )
        }
    }

    /**
     * Apply [settings], replacing whatever we were holding.
     *
     * The previous request is released first and the whole sequence aborts if
     * that release fails, because acquiring on top of a live request is what
     * makes a cluster appear locked at a value nobody asked for.
     */
    suspend fun apply(clusters: List<CpuCluster>, settings: List<ClusterSetting>): RequestOutcome = operationMutex.withLock {
        applyLocked(clusters, settings)
    }

    /** Read replay state only after entering the same transaction as apply/release. */
    suspend fun reapply(clusters: List<CpuCluster>): RequestOutcome = operationMutex.withLock {
        val settings = store.lastApplied()
        if (settings.isEmpty()) return@withLock RequestOutcome(true, Message(R.string.cpu_no_replay_settings))
        if (settings.any { setting -> clusters.none { it.policy == setting.policy } }) {
            return@withLock RequestOutcome(false, Message(R.string.cpu_replay_cluster_gone))
        }
        val observed = readSnapshot(clusters)
        val checks = settings.map { setting -> observed.first { it.policy == setting.policy }.verify(setting) }
        if (lock.handler() > 0 && checks.all { it == CpuVerification.VERIFIED || it == CpuVerification.CONSTRAINED }) {
            updateVerification(settings, observed)
            return@withLock RequestOutcome(true, Message(R.string.cpu_limit_still_in_range))
        }
        applyLocked(clusters, settings)
    }

    private suspend fun applyLocked(
        clusters: List<CpuCluster>,
        settings: List<ClusterSetting>,
        /** 这些簇只压上限、不锁下限。用于系统当前上限已经低于请求下限的区间。 */
        omitFloorPolicies: Set<String> = emptySet(),
    ): RequestOutcome {
        if (clusters.isEmpty()) return RequestOutcome(false, Message(R.string.cpu_no_clusters))
        if (settings.isEmpty()) return RequestOutcome(false, Message(R.string.cpu_no_cluster_selected))

        val byPolicy = clusters.associateBy { it.policy }
        if (settings.map { it.policy }.distinct().size != settings.size || settings.any { it.policy !in byPolicy }) {
            return RequestOutcome(false, Message(R.string.cpu_duplicate_cluster))
        }
        val normalized = mutableListOf<ClusterSetting>()
        val pairs = mutableListOf<Pair<String, String>>()
        for (setting in settings) {
            val cluster = byPolicy[setting.policy] ?: continue
            val low = cluster.nearest(setting.minFreqKhz)
            val high = cluster.nearest(setting.maxFreqKhz)
            val floor = minOf(low, high)
            val ceiling = maxOf(low, high)
            if (floor <= 0 || ceiling > Int.MAX_VALUE) {
                return RequestOutcome(false, Message(R.string.cpu_freq_out_of_range))
            }
            normalized += setting.copy(minFreqKhz = floor, maxFreqKhz = ceiling)
            val includeFloor = setting.policy !in omitFloorPolicies

            // Soft ceilings can be raised by another scenario's soft floor.
            // Keep an independent hard ceiling; only a point also requests a hard floor.
            if (includeFloor && rangeMode() != TweakCommands.RangeMode.CEILING_ONLY) {
                pairs += PowerHal.commandId(PowerHal.BASE_MIN, cluster.index) to floor.toString()
            }
            // 上限可省。见 [rangeMode] 与 [TweakCommands.RangeMode]。
            if (rangeMode() != TweakCommands.RangeMode.FLOOR_ONLY) {
                pairs += PowerHal.commandId(PowerHal.BASE_MAX, cluster.index) to ceiling.toString()
            }
                        // **区间必须带硬下限。**
            //
            // 2026-09-24 真机逐项实测（vivo PD2430A，「实验室 → 逐 ID 测试」）：
            //   软 MIN = 3300000                       →  无反应，仍被常驻上限压在 2300
            //   硬 MIN_HL=300000 + MAX_HL=1600000      →  上限 1600 准确，下限被硬件钳到
            //                                             600（policy7 的 cpuinfo_min_freq），
            //                                             不是钉在 300 —— 硬下限是**下限**
            //   硬 MIN_HL=800000 + MAX_HL=1600000      →  完全符合预期
            //   硬 MIN_HL=2900000 及以上               →  超出系统包络后退化成"钉在硬下限"
            //
            // 结论：硬上下限**能做区间**，只是超出系统允许的包络（本机约 2000–2300）后
            // 退化成钉死。而只在软族里发下限，请求就完全暴露在那个常驻上限之下。
            //
            // 早前这里刻意不给区间发硬下限，理由是"硬上下限凑齐就等于定点锁"——
            // 那个判断只在超出包络时成立，在包络内是错的。已改。
            // 只压上限时不发下限 —— 低频段交给系统自己调度。
            // 硬的那一对默认不发 —— 实测与软的等效，见 [hardLimits]。
            if (hardLimits()) {
                if (includeFloor && rangeMode() != TweakCommands.RangeMode.CEILING_ONLY) {
                    pairs += PowerHal.commandId(PowerHal.BASE_HARD_MIN, cluster.index) to floor.toString()
                }
                if (rangeMode() != TweakCommands.RangeMode.FLOOR_ONLY) {
                    pairs += PowerHal.commandId(PowerHal.BASE_HARD_MAX, cluster.index) to ceiling.toString()
                }
            }
            AppLog.i(TAG, "${cluster.policy} " + if (floor == ceiling) {
                "单值请求 ${floor / 1000}MHz"
            } else {
                "频率范围 ${floor / 1000}-${ceiling / 1000}MHz" +
                    if (includeFloor) "" else "（不锁下限）"
            } + "（${rangeMode().name}${if (hardLimits()) " +硬限" else ""}）")
        }
        // 附带下发调度器配合项（用户显式开启时才非空）。
        // 单独一组，不掺进 pairs 的归一化逻辑 —— 它们没有 floor/ceiling 语义，
        // 也不需要 nearest() 钳制，掺进去只会让区间判读变糊。
        val companion = schedCompanions()
        val all = if (companion.isEmpty()) pairs else pairs + companion.map { (opcode, value) ->
            PowerHal.fixedCommandId(opcode) to value
        }
        if (companion.isNotEmpty()) {
            AppLog.i(TAG, "附带调度器配合项 ${companion.size} 条: $companion")
        }
        if (all.isEmpty()) return RequestOutcome(false, Message(R.string.cpu_no_matching_cluster))

        // duration 0: a limit the user asked for should stay until released.
        // Once Binder has accepted an acquire, cancellation must not lose its handle or replay state.
        val applied = withContext(NonCancellable) {
            lock.apply(all, durationMs = 0).also { result ->
                if (result.success) {
                    store.setLastApplied(normalized)
                    // 释放后仍要记得“上次调的是什么”，快捷开关和亮屏恢复都靠它。
                    store.setLastRequested(normalized)
                    store.setControlEnabled(true)
                    _status.value = _status.value.copy(handler = lock.handler(), applied = normalized,
                        observedLimits = null, verification = CpuVerification.UNVERIFIED)
                } else {
                    val remaining = lock.handler()
                    if (remaining <= 0) {
                        store.setLastApplied(emptyList())
                        _status.value = _status.value.copy(handler = 0, applied = emptyList(),
                            verification = CpuVerification.UNVERIFIED, verificationDetail = null)
                    } else {
                        _status.value = _status.value.copy(handler = remaining)
                    }
                }
            }
        }
        if (!applied.success) return RequestOutcome(false, applied.message)

        val governorNote = applyGovernors(clusters, normalized)

        val observed = sampleSnapshot(clusters, "应用 handler=${lock.handler()}")
        val verification = updateVerification(normalized, observed)

        // 系统当前上限已经低于请求下限时，继续锁下限只会把簇钉在那个上限上。
        // 对这种区间再下发一次“只压上限”：用户仍得到上限约束，但 CPU 可以降下来。
        // 只重试一次；点请求（min == max）是用户明确要钉住，不在此列。
        val pinned = if (omitFloorPolicies.isEmpty() &&
            rangeMode() == TweakCommands.RangeMode.RANGE
        ) {
            normalized.filter { setting ->
                setting.minFreqKhz < setting.maxFreqKhz &&
                    observed.any { reading ->
                        reading.policy == setting.policy &&
                            reading.max != null &&
                            setting.minFreqKhz >= reading.max
                    }
            }
        } else {
            emptyList()
        }
        if (pinned.isNotEmpty()) {
            val names = pinned.joinToString(", ") { it.policy }
            val pinnedPolicies = pinned.map { it.policy }.toSet()
            AppLog.w(TAG, "系统当前上限低于请求下限，改为只压上限: $names")
            val retry = applyLocked(
                clusters,
                normalized,
                omitFloorPolicies = pinnedPolicies,
            )
            // 只把一句人话放进摘要；逐簇 policy 读回全部留在 detail 里，默认收起。
            val short = Message(
                if (retry.success) R.string.cpu_floor_not_enforced_short
                else R.string.cpu_floor_retry_failed_kept_short,
            )
            // 重试没成，但原请求还在（释放失败，或 PerfLockController 已把上一份放回）：
            // 不能把“请求仍然持有”报成失败，否则用户以为已经完全释放。
            if (!retry.success && lock.handler() > 0) {
                return RequestOutcome(
                    success = true,
                    message = Message(
                        R.string.cpu_outcome_join,
                        listOf(verification.summary, short),
                    ),
                    detail = listOfNotNull(verification.detail, retry.detail).joinToString("\n"),
                )
            }
            if (retry.success) {
                // 重放状态必须和**实际下发**一致：这些簇已经不再锁下限。
                // 否则轮询会拿原始下限再次下发，把刚拆掉的钉子重新钉回去。
                val effective = normalized.map { setting ->
                    if (setting.policy !in pinnedPolicies) setting
                    else setting.copy(
                        // 用可用频率表的最低档，而不是 CpuCluster.minFreqKhz ——
                        // 后者在没有单独填充时可能是 0，会写出一个伪造的下限。
                        minFreqKhz = byPolicy[setting.policy]?.availableFreqs?.firstOrNull()
                            ?: setting.minFreqKhz,
                    )
                }
                store.setLastApplied(effective)
                _status.value = _status.value.copy(applied = effective)
            }
            return RequestOutcome(
                success = retry.success,
                message = if (retry.success) {
                    Message(R.string.cpu_outcome_join, listOf(verification.summary, short))
                } else {
                    Message(R.string.cpu_outcome_join, listOf(retry.message, short))
                },
                detail = retry.detail,
            )
        }

        _status.value = _status.value.copy(
            handler = lock.handler(),
            applied = normalized,
            observedLimits = observed.joinToString(", ") { it.describe() },
        )

        return RequestOutcome(
            success = true,
            // 结论归结论。调速器提示可有可无，有就并进结论那一句里。
            // 逐簇读数走 detail —— 界面里默认收起、点"显示更多"才看。
            message = governorNote?.let {
                Message(R.string.cpu_outcome_join, listOf(verification.summary, it))
            } ?: verification.summary,
            detail = verification.detail,
        )
    }

    /**
     * Release our request and report whether the kernel came back.
     *
     * A oneway release returning success proves nothing, so the result is judged
     * by reading the limits back against the baseline recorded before we ever
     * touched anything.
     */
    suspend fun release(clusters: List<CpuCluster>): RequestOutcome = operationMutex.withLock {
        releaseLocked(clusters)
    }

    private suspend fun releaseLocked(clusters: List<CpuCluster>): RequestOutcome {
        val released = withContext(NonCancellable) {
            val result = if (lock.handler() <= 0) {
                RequestOutcome(true, Message(R.string.lock_no_recorded_request))
            } else {
                lock.release()
            }
            result.also {
                if (result.success) {
                    store.setLastApplied(emptyList())
                    _status.value = _status.value.copy(handler = 0, applied = emptyList(),
                        verification = CpuVerification.UNVERIFIED, verificationDetail = null)
                }
            }
        }
        if (!released.success) return RequestOutcome(false, released.message)

        val after = sampleLimits(clusters, "释放")

        AppLog.i(TAG, "释放后读回: ${after ?: "上下限不可读"}；平台仍可能施加限制")
        lastVerificationKey = null

        _status.value = _status.value.copy(
            handler = 0,
            applied = emptyList(),
            observedLimits = after,
        )

        return RequestOutcome(
            success = true,
            message = if (after != null) Message(R.string.cpu_release_done_current, listOf(after))
            else Message(R.string.cpu_release_done),
        )
    }

    /**
     * Read the limits back at several delays and return the settled reading.
     *
     * Every sample is logged. Changes may be settling or another platform writer;
     * the samples alone cannot attribute the cause. Only the final sample is returned.
     */
    private suspend fun sampleLimits(clusters: List<CpuCluster>, phase: String): String? =
        sampleSnapshot(clusters, phase).takeIf { rows -> rows.any { it.min != null || it.max != null } }
            ?.joinToString(", ") { it.describe() }

    private suspend fun sampleSnapshot(clusters: List<CpuCluster>, phase: String): List<CpuLimitReading> {
        var previousDelay = 0L
        var settled = emptyList<CpuLimitReading>()
        val started = System.nanoTime()
        for (delayMs in SAMPLE_DELAYS_MS) {
            waitForSample(delayMs - previousDelay)
            previousDelay = delayMs
            val sample = readSnapshot(clusters)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            AppLog.d(TAG, "内核实际上下限($phase elapsed=${elapsed}ms target=${delayMs}ms): ${sample.joinToString { it.describe() }}")
            settled = sample
        }
        return settled
    }

    /**
     * Current `scaling_min_freq` / `scaling_max_freq` per cluster, in MHz.
     * Null when no cluster could be read.
     */
    suspend fun readLimits(clusters: List<CpuCluster>): String? {
        if (clusters.isEmpty()) return null
        return readSnapshot(clusters).takeIf { rows -> rows.any { it.min != null || it.max != null } }
            ?.joinToString(", ") { it.describe() }
    }

    private suspend fun readSnapshot(clusters: List<CpuCluster>): List<CpuLimitReading> {
        // 路径来自 CpuFreqNodes，和展示那条路同一份 —— 节点改名只改一处。
        //
        // **读法必须和展示不同**：这里批量读、读不到再提权补读，因为要看到系统
        // 真正生效的值。展示那条路只按 App 身份直读，读不到就显示未知。
        // 两条路读到的可能不一样，那是事实，不是 bug。
        val paths = clusters.flatMap { CpuFreqNodes.limits(it.policy) }
        val values = readNodes(paths)
        return clusters.map { cluster ->
            CpuLimitReading(
                policy = cluster.policy,
                min = CpuFreqNodes.parseKhz(values[CpuFreqNodes.min(cluster.policy)]),
                max = CpuFreqNodes.parseKhz(values[CpuFreqNodes.max(cluster.policy)]),
                current = CpuFreqNodes.parseKhz(values[CpuFreqNodes.cur(cluster.policy)]),
            )
        }
    }

    /**
     * 结论与证据分两份返回。
     *
     * **以前这里只返回一个 `Message`，把两者用 `cpu_outcome_join` 拼在了一句里**
     * （"请求已发送，部分簇无法验证 · policy0 ?-?MHz cur=400MHz 请求=300-2000MHz
     * 上下限读回不完整; policy4 …; policy7 …"）。那直接违反了 [Failure] 写在类头
     * 的契约：summary 是**给用户看的一句结论**，detail 是**给排错看的原始读数**、
     * 默认收起。拼在一起的结果是首页底部横着三行原始读数，用户既读不懂、
     * 又看不见自己真正要的那句"成没成"。
     */
    private data class Verification(val summary: Message, val detail: String?)

    private fun updateVerification(settings: List<ClusterSetting>, observed: List<CpuLimitReading>): Verification {
        val rows = settings.map { setting ->
            val reading = observed.firstOrNull { it.policy == setting.policy }
                ?: CpuLimitReading(setting.policy, null, null, null)
            setting to reading
        }
        val checks = rows.map { (setting, reading) -> reading.verify(setting) }
        val verification = when {
            CpuVerification.CONFLICT in checks -> CpuVerification.CONFLICT
            CpuVerification.UNREADABLE in checks -> CpuVerification.UNREADABLE
            CpuVerification.CONSTRAINED in checks -> CpuVerification.CONSTRAINED
            else -> CpuVerification.VERIFIED
        }
        // 逐簇的读数行是**证据**，不是结论。
        val detail = rows.joinToString("; ") { (setting, reading) ->
            val reason = when (reading.verify(setting)) {
                CpuVerification.UNREADABLE -> context.getString(R.string.cpu_verify_unreadable)
                CpuVerification.CONFLICT -> reading.conflictReason(setting).resolve(context)
                CpuVerification.CONSTRAINED -> context.getString(R.string.cpu_verify_constrained)
                else -> context.getString(R.string.cpu_verify_ok)
            }
            context.getString(
                R.string.cpu_verify_row,
                reading.describe(),
                setting.minFreqKhz / 1000,
                setting.maxFreqKhz / 1000,
                reason,
            )
        }
        val summaryRes = when (verification) {
            CpuVerification.VERIFIED -> R.string.cpu_verify_summary_ok
            CpuVerification.UNREADABLE -> R.string.cpu_verify_summary_unreadable
            CpuVerification.CONSTRAINED -> R.string.cpu_verify_summary_constrained
            else -> R.string.cpu_verify_summary_conflict
        }
        val summary = context.getString(summaryRes)
        // Repeated polling should not evict the first useful failure from the ring buffer.
        val key = rows.joinToString { (setting, reading) ->
            "${setting.policy}:${setting.minFreqKhz}:${setting.maxFreqKhz}:${reading.min}:${reading.max}:${reading.verify(setting)}"
        }
        if (lastVerificationKey != key) {
            if (verification == CpuVerification.CONFLICT) AppLog.w(TAG, "$summary · $detail")
            else AppLog.i(TAG, "$summary · $detail")
        } else AppLog.d(TAG, "$summary · $detail")
        lastVerificationKey = key
        _status.value = _status.value.copy(verification = verification, verificationDetail = detail,
            observedLimits = observed.joinToString { it.describe() })
        return Verification(Message(summaryRes), detail)
    }

    /**
     * Governors are a kernel knob with no PowerHAL equivalent, and the node is
     * usually root-only, so this is best-effort and reported separately rather
     * than failing the whole apply.
     */
    private suspend fun applyGovernors(
        clusters: List<CpuCluster>,
        settings: List<ClusterSetting>,
    ): Message? {
        val byPolicy = clusters.associateBy { it.policy }
        val results = mutableMapOf<String, Boolean>()
        for (setting in settings) {
            val governor = setting.governor ?: continue
            val cluster = byPolicy[setting.policy] ?: continue
            val node = "${CpuFreqNodes.dir(cluster.policy)}/scaling_governor"
            val ok = Sysfs.write(node, governor)
            results[cluster.policy] = ok
            if (ok) AppLog.i(TAG, "${cluster.policy} 调速器 -> $governor")
            else AppLog.w(TAG, "${cluster.policy} 调速器写入失败: $node 不可写")
        }

        if (results.isEmpty()) return null
        val failed = results.filterValues { !it }.keys
        return when {
            failed.isEmpty() -> Message(R.string.cpu_governor_done)
            failed.size == results.size -> Message(R.string.cpu_governor_all_failed)
            else -> Message(R.string.cpu_governor_partial, listOf(failed.joinToString(", ")))
        }
    }
}
