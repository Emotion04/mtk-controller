package magicau.mtkcontroller.data.lab

import android.content.Context
import androidx.annotation.StringRes
import magicau.mtkcontroller.R
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.powerhal.RequestOutcome
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.powerhal.PerfLockController
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.data.profileStore
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.data.thermal.THERMAL_POLICY_RESOURCE
import magicau.mtkcontroller.data.thermal.ThermalPolicyController
import magicau.mtkcontroller.domain.model.CpuCluster

/**
 * Drives the canary features and, crucially, reports whether they did anything.
 *
 * Every other part of this app can only say what it *sent*. This says what the
 * device *did*, by reading the feature's backing node before and after and
 * comparing. That comparison is the whole point: on MediaTek the same id does
 * different things on different BSP revisions, so the only trustworthy answer
 * is an observed one.
 *
 * **One lock for the whole lab.** Applying a second feature releases the first,
 * so two experiments can never overlap and confound each other — which is what
 * you want when the question is "did *this* id do anything".
 *
 * **Graduating a feature out of the lab.** Give it its own
 * [PerfLockController] and its own controller object, the way
 * [magicau.mtkcontroller.data.cpu.CpuControl] has one. Do **not** wire a
 * production screen to the lab's lock: the lab releases whatever it held
 * whenever a different canary is tried, so a promoted feature sharing it would
 * be silently dropped the next time someone ran an experiment.
 */
class LabController(
    private val context: Context,
    private val lock: PerfLockController,
) {

    companion object {
        /**
         * 盲测开关：**只由适配测试会话打开**，正常使用永远为 false。
         *
         * 打开后，判据节点读不到时仍允许做一次 5 秒的 CPU 频率下发，结果记为 UNKNOWN，
         * 由用户体感或报告判断。只允许 CPU 频率资源族，避免写到射频/缓存等无关资源。
         */
        @Volatile
        var blindWritesEnabled: Boolean = false

        const val TAG = "Lab"

        /**
         * How long a test apply lasts before it expires on its own.
         *
         * Long enough to watch the effect, short enough that walking away from
         * the screen cannot leave an experiment running. A handle that goes
         * missing therefore costs at most this much.
         */
        const val TEST_DURATION_MS = 20_000

        /** 盲测的强制期限：比常规实验更短，读不到结果时不能留太久。 */
        const val BLIND_DURATION_MS = 5_000

        /** CPU 频率资源族；盲测只允许碰这一族。 */
        const val CPU_FAMILY_START = 0x00400000
        const val CPU_FAMILY_END = 0x0040FFFF

        /** Non-private so the raw test panel can share the settle timing. */

        /** Settlement before reading a node back, as everywhere else. */
        const val SETTLE_MS = 400L
    }

    enum class State(@StringRes val labelRes: Int) {
        /** Never tried, or the observation was cleared. */
        UNTESTED(R.string.lab_state_untested),

        /** The node moved as expected. */
        WORKING(R.string.lab_state_working),

        /** The request was accepted but the node did not move. */
        NO_EFFECT(R.string.lab_state_no_effect),

        /** The feature's node is absent, so this device does not have it. */
        UNSUPPORTED(R.string.lab_state_unsupported),

        /** Accepted, but there is no node to judge by. */
        UNKNOWN(R.string.lab_state_unknown),

        /**
         * 用户自己标的能力。
         *
         * 判据节点在 shell 身份下大多读不到（`/proc/perfmgr` 进不去、`/dev/cpuctl`
         * 是 `0660 system`），所以自动判定对这台机器上的大部分项根本不成立。
         * 而**效果本来就是体感**：能不能用，用户比任何节点读数更有发言权。
         */
        MANUAL_USABLE(R.string.lab_state_manual_usable),

        /** 用户自己标"没用"。与 [UNTESTED] 是两件事：那是没试过。 */
        MANUAL_UNUSABLE(R.string.lab_state_manual_unusable),
    }

    @Serializable
    data class Verdict(
        val state: String,
        val detail: String,
        val atMs: Long = 0L,
    ) {
        fun stateEnum(): State = runCatching { State.valueOf(state) }.getOrDefault(State.UNTESTED)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val verdictsKey = stringPreferencesKey("lab_verdicts")

    /** Feature key -> last observation. Persistent, so a device stays characterised. */
    suspend fun verdicts(): Map<String, Verdict> = withContext(Dispatchers.IO) {
        context.profileStore.data.first()[verdictsKey]
            ?.let { raw -> runCatching { json.decodeFromString<Map<String, Verdict>>(raw) }.getOrNull() }
            ?: emptyMap()
    }

    /**
     * 用户手动标一条：能用 / 没用 / 清除标记。
     *
     * 和自动判定写同一张表 —— 界面上只有"这一项行不行"一个结论，
     * 分两张表就会出现"自动说 A、手动说 B，界面显示哪个"的问题。
     */
    suspend fun markManually(key: String, state: State?) {
        if (state == null) {
            val current = verdicts().toMutableMap()
            current.remove(key)
            context.profileStore.edit { it[verdictsKey] = json.encodeToString(current) }
            return
        }
        record(key, Verdict(state = state.name, detail = "", atMs = System.currentTimeMillis()))
    }

    private suspend fun record(key: String, verdict: Verdict) {
        val current = verdicts().toMutableMap()
        current[key] = verdict
        context.profileStore.edit { it[verdictsKey] = json.encodeToString(current) }
    }

    suspend fun clearVerdicts() {
        context.profileStore.edit { it.remove(verdictsKey) }
    }

    /** Read a node directly, then through the elevated shell. Null when absent. */
    suspend fun nodeValue(node: String): String? =
        Sysfs.read(node)?.trim() ?: Sysfs.readElevated(node)?.trim()

    /**
     * Send [feature] and report what actually happened.
     *
     * @param values policy name → value. For a per-cluster feature there is one
     *   entry per cluster; otherwise a single entry keyed by the policy of the
     *   only cluster it applies to (or an empty policy for a global feature).
     * @param hold true to make the request permanent, false to let it expire.
     */
    suspend fun apply(
        feature: LabFeature,
        clusters: List<CpuCluster>,
        values: Map<String, Int>,
        hold: Boolean,
        durationMs: Int = TEST_DURATION_MS,
    ): Verdict {
        val nodes = resolveNodes(feature, clusters)

        // 1. Capability: no node, no feature. This is the check that makes id
        //    drift a non-event — a moved id simply reports as unsupported
        //    instead of writing a value to whatever now owns that id.
        //
        //    只有**确认不存在**才拦。读不到不拦：节点只是回读探针，真正的通道是
        //    PowerHAL opcode。SELinux 和 cpufreq 权限位让大量能用的节点读不到，
        //    而 SELinux 拒目录搜索时里层文件报的是 ENOENT 而不是 EACCES ——
        //    把那种 ENOENT 当成"不存在"，就会把能用的功能全毙掉。
        //    见 Sysfs.states() / Sysfs.absenceIsProven()。
        val states = Sysfs.states(nodes.map { it.second })
        val missing = nodes.firstOrNull { states[it.second] == Sysfs.NodeState.ABSENT }?.second
        if (missing != null) {
            val verdict = Verdict(
                state = State.UNSUPPORTED.name,
                detail = context.getString(R.string.lab_verdict_node_absent, missing),
                atMs = System.currentTimeMillis(),
            )
            AppLog.w(TAG, "[${feature.key}] ${verdict.detail}")
            record(feature.key, verdict)
            return verdict
        }

        val before = readAll(nodes)

        // 1b. 读不到就**别下发**。
        //
        // 节点的作用就是判据：读不到它，这次下发就没有任何办法判断结果。
        // 那还写进去干什么？只是白白改一次设备状态，换来界面上"读回失败"四个字。
        //
        // 走到这里说明节点**存在过**（步骤 1 只拦了确认不存在的），只是这台机器上
        // 读不动 —— `/proc/perfmgr/**` 就是这一族：目录在，SELinux 拒掉搜索，
        // 里层文件报 ENOENT，父目录报 EACCES。按 `Sysfs.absenceIsProven` 那套判定，
        // 这属于"读不到"而不是"不存在"，所以不该判 UNSUPPORTED；
        // 但"读不到"和"没有它"对用户是同一件事：**看不见，就判不了**。
        val blind = before == null && blindWritesEnabled &&
            feature.baseId in CPU_FAMILY_START..CPU_FAMILY_END
        if (before == null && !blind) {
            val verdict = Verdict(
                state = State.UNKNOWN.name,
                detail = context.getString(R.string.lab_verdict_unreadable_no_write),
                atMs = System.currentTimeMillis(),
            )
            AppLog.w(TAG, "[${feature.key}] ${verdict.detail}")
            record(feature.key, verdict)
            return verdict
        }

        // 2. Build the request.
        val pairs = buildPairs(feature, clusters, values)
        if (pairs.isEmpty()) {
            return Verdict(State.NO_EFFECT.name, context.getString(R.string.lab_verdict_no_resources)).also {
                record(feature.key, it)
            }
        }

        val duration = if (blind) BLIND_DURATION_MS else if (hold) 0 else durationMs
        AppLog.i(
            TAG,
            "[${feature.key}] 测试下发 " + pairs.joinToString(", ") { "${it.first}=${it.second}" } +
                " · ${if (hold) "保持(不过期)" else "${durationMs / 1000} 秒后自动失效"}",
        )

        // 温控策略索引不走实验室自己的锁，走统一的温控档位控制器。
        //
        // 那是同一个 PowerHAL 资源（PERF_RES_THERMAL_POLICY）的第三条写路径。
        // 各拿一把句柄的话，用户可能同时被实验室和 CPU 页持有 —— 现象是
        // "我设了却没用 / 设完又弹回去"，而两边界面各自显示正常。
        // 控制器保证一次只有一把句柄，并在本次实验结束后把用户选的那一档放回去。
        //
        // 判据用 baseId 而不是 key 字符串：key 是给人看的，改个名字不该悄悄
        // 把这条路静默改回旧的竞争行为。
        val outcome = if (feature.baseId == THERMAL_POLICY_RESOURCE) {
            val index = values.values.firstOrNull()
            if (index == null) {
                RequestOutcome(false, Message(R.string.lock_no_resources))
            } else {
                val thermal = ThermalPolicyController.requestLab(index, duration)
                RequestOutcome(thermal.success, thermal.message, thermal.detail)
            }
        } else {
            lock.apply(pairs, duration)
        }
        if (!outcome.success) {
            val verdict = Verdict(State.NO_EFFECT.name, context.getString(R.string.lab_verdict_rejected, AppLog.text(outcome.message)))
            AppLog.w(TAG, "[${feature.key}] ${verdict.detail}")
            record(feature.key, verdict)
            return verdict
        }

        if (blind) {
            val verdict = Verdict(
                state = State.UNKNOWN.name,
                detail = context.getString(R.string.lab_verdict_blind_sent, BLIND_DURATION_MS / 1000),
                atMs = System.currentTimeMillis(),
            )
            AppLog.w(TAG, "[${feature.key}] 盲测 ${verdict.detail}")
            record(feature.key, verdict)
            return verdict
        }

        // 3. Observe.
        if (nodes.isEmpty()) {
            val verdict = Verdict(
                state = State.UNKNOWN.name,
                detail = context.getString(R.string.lab_verdict_no_readback),
                atMs = System.currentTimeMillis(),
            )
            AppLog.i(TAG, "[${feature.key}] ${verdict.detail}")
            record(feature.key, verdict)
            return verdict
        }

        delay(SETTLE_MS)
        val after = readAll(nodes)
        val target = values.values.firstOrNull()?.toString()

        val verdict = when {
            after == null -> Verdict(State.UNKNOWN.name, context.getString(R.string.lab_verdict_readback_failed))
            after == before && target != null && after == target ->
                Verdict(State.WORKING.name, context.getString(R.string.lab_verdict_already_target, after.toString()), System.currentTimeMillis())
            after == before -> Verdict(
                State.NO_EFFECT.name,
                context.getString(R.string.lab_verdict_unchanged, before.toString()),
                System.currentTimeMillis(),
            )
            else -> Verdict(
                State.WORKING.name,
                context.getString(R.string.lab_verdict_working, before.toString(), after.toString()),
                System.currentTimeMillis(),
            )
        }

        if (verdict.stateEnum() == State.WORKING) AppLog.i(TAG, "[${feature.key}] ✅ ${verdict.detail}")
        else AppLog.w(TAG, "[${feature.key}] ❌ ${verdict.detail}")
        record(feature.key, verdict)
        return verdict
    }

    /**
     * Send an arbitrary set of CPU frequency resources and report what moved.
     *
     * This exists because the app has spent several rounds guessing which id
     * does what. Every guess produced a plausible story and none survived
     * contact with the device. Being able to send **one id at a time** and see
     * the kernel's answer turns that into a measurement.
     *
     * Deliberately restricted to the CPU frequency family by the caller — a
     * free-form id field would let a typo reach the resources that control
     * radio power and page-cache dropping.
     */
    suspend fun sendRaw(
        pairs: List<Pair<String, String>>,
        readNodes: List<Pair<String, String>>,
        hold: Boolean,
    ): Verdict {
        if (pairs.isEmpty()) return Verdict(State.NO_EFFECT.name, context.getString(R.string.lab_verdict_no_ids))

        val before = readAll(readNodes)
        // 同 [apply]：读不到判据就别写。逐 ID 测试是给排错用的，写一个自己看不见
        // 结果的资源号，只会让"读回失败"多一条记录。
        val blind = before == null && blindWritesEnabled && pairs.all { pair ->
            runCatching { pair.first.removePrefix("0x").toInt(16) in CPU_FAMILY_START..CPU_FAMILY_END }
                .getOrDefault(false)
        }
        if (before == null && !blind) {
            val verdict = Verdict(State.UNKNOWN.name, context.getString(R.string.lab_verdict_unreadable_no_write))
            AppLog.w(TAG, "[手动] ${verdict.detail}")
            return verdict
        }
        val duration = if (blind) BLIND_DURATION_MS else if (hold) 0 else TEST_DURATION_MS
        AppLog.i(
            TAG,
            "[手动] 下发 " + pairs.joinToString(", ") { "${it.first}=${it.second}" } +
                " · ${if (hold) "保持" else "${TEST_DURATION_MS / 1000}s 后失效"}",
        )

        val outcome = lock.apply(pairs, duration)
        if (!outcome.success) {
            val verdict = Verdict(State.NO_EFFECT.name, context.getString(R.string.lab_verdict_rejected, AppLog.text(outcome.message)))
            AppLog.w(TAG, "[手动] ${verdict.detail}")
            return verdict
        }

        if (blind) {
            val verdict = Verdict(
                State.UNKNOWN.name,
                context.getString(R.string.lab_verdict_blind_sent, BLIND_DURATION_MS / 1000),
                System.currentTimeMillis(),
            )
            AppLog.w(TAG, "[手动] 盲测 ${verdict.detail}")
            return verdict
        }

        delay(SETTLE_MS)
        val after = readAll(readNodes)
        val verdict = when {
            after == null -> Verdict(State.UNKNOWN.name, context.getString(R.string.lab_verdict_readback_failed))
            after == before -> Verdict(
                State.NO_EFFECT.name,
                context.getString(R.string.lab_verdict_all_invalid, pairs.size, before.toString()),
                System.currentTimeMillis(),
            )
            else -> Verdict(
                State.WORKING.name,
                context.getString(R.string.lab_verdict_changed, before.toString(), after.toString()),
                System.currentTimeMillis(),
            )
        }
        AppLog.i(TAG, "[手动] ${verdict.stateEnum().name}: ${verdict.detail}")
        return verdict
    }

    /**
     * 放掉实验室持有的一切，回到常驻意图。
     *
     * 返回 [RequestOutcome] 而不是一句拼好的 `String`：**结论要带证据等级**，
     * 而 `String` 只有一句话。界面上"哪一样没放掉"和"服务端原话"分两级展示，
     * 靠的就是这里的 `message` + `detail`。原来这里直接 `resolve(context)`
     * 把中文焊死，调用方连"成功了没有"都判断不出。
     */
    suspend fun reset(): RequestOutcome {
        val outcome = lock.release()
        AppLog.i(TAG, "实验室全部恢复: ${AppLog.text(outcome.message)}")
        val thermal = ThermalPolicyController.releaseLab()
        AppLog.i(TAG, "实验室温控档位恢复: ${AppLog.text(thermal.message)}")
        return when {
            !outcome.success -> outcome
            !thermal.success -> thermal
            else -> RequestOutcome(true, Message(R.string.lab_released_all))
        }
    }

    private fun buildPairs(
        feature: LabFeature,
        clusters: List<CpuCluster>,
        values: Map<String, Int>,
    ): List<Pair<String, String>> {
        val base = feature.baseId ?: return emptyList()

        if (feature.perCore) {
            // **偏移是 (核序号 + 1)，不是核序号。** `_CPU_n` 这一族的第一格
            // （0x0080C000 / 0x00810000）是"整簇"，cpu0 从 +0x100 开始。
            // 用 +0x100*i 会把 cpu0 写成整簇开关，而且看起来"有效果"——
            // 那是最难查的一类错。
            return (0 until LabCores.COUNT).mapNotNull { core ->
                val value = values[LabCores.key(core)] ?: return@mapNotNull null
                PowerHal.commandId(base, core + 1) to value.toString()
            }
        }

        if (!feature.perCluster) {
            val value = values.values.firstOrNull() ?: return emptyList()
            return listOf(PowerHal.commandId(base, 0) to value.toString())
        }

        return clusters.mapNotNull { cluster ->
            val value = values[cluster.policy] ?: return@mapNotNull null
            PowerHal.commandId(base, cluster.index) to value.toString()
        }
    }

    /**
     * The nodes this feature reads back, as (label, path).
     *
     * A `%s` in the template expands to one node per cpufreq policy, so a
     * per-cluster feature is judged on all of its clusters rather than the first
     * one — core counts in particular only make sense read across the cluster.
     * A template without `%s` is a single global node.
     */
    private fun resolveNodes(feature: LabFeature, clusters: List<CpuCluster>): List<Pair<String, String>> {
        val template = feature.node ?: return emptyList()
        return if (template.contains("%s")) {
            clusters.map { it.policy to template.replace("%s", it.policy) }
        } else {
            listOf("" to template)
        }
    }

    /** One comparable string for the whole feature, e.g. `policy0=0 1 2  policy4=4 5`. */
    private suspend fun readAll(nodes: List<Pair<String, String>>): String? {
        if (nodes.isEmpty()) return null
        val parts = nodes.mapNotNull { (label, path) ->
            val value = nodeValue(path) ?: return@mapNotNull null
            if (label.isEmpty()) value else "$label=$value"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("  ")
    }
}
