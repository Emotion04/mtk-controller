package magicau.mtkcontroller.data.lab

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.powerhal.PerfLockController
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.data.profileStore
import magicau.mtkcontroller.data.sysfs.Sysfs
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

    private companion object {
        const val TAG = "Lab"

        /**
         * How long a test apply lasts before it expires on its own.
         *
         * Long enough to watch the effect, short enough that walking away from
         * the screen cannot leave an experiment running. A handle that goes
         * missing therefore costs at most this much.
         */
        const val TEST_DURATION_MS = 20_000

        /** Non-private so the raw test panel can share the settle timing. */

        /** Settlement before reading a node back, as everywhere else. */
        const val SETTLE_MS = 400L
    }

    enum class State(val label: String) {
        /** Never tried, or the observation was cleared. */
        UNTESTED("未测试"),

        /** The node moved as expected. */
        WORKING("生效"),

        /** The request was accepted but the node did not move. */
        NO_EFFECT("未见效"),

        /** The feature's node is absent, so this device does not have it. */
        UNSUPPORTED("本机不支持"),

        /** Accepted, but there is no node to judge by. */
        UNKNOWN("无法判断"),
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
    ): Verdict {
        val nodes = resolveNodes(feature, clusters)

        // 1. Capability: no node, no feature. This is the check that makes id
        //    drift a non-event — a moved id simply reports as unsupported
        //    instead of writing a value to whatever now owns that id.
        val missing = nodes.firstOrNull { nodeValue(it.second) == null }?.second
        if (missing != null) {
            val verdict = Verdict(
                state = State.UNSUPPORTED.name,
                detail = "节点不存在: $missing —— 本机没有这个功能,或节点名/ID 已随 BSP 变更",
                atMs = System.currentTimeMillis(),
            )
            AppLog.w(TAG, "[${feature.key}] ${verdict.detail}")
            record(feature.key, verdict)
            return verdict
        }

        val before = readAll(nodes)

        // 2. Build the request.
        val pairs = buildPairs(feature, clusters, values)
        if (pairs.isEmpty()) {
            return Verdict(State.NO_EFFECT.name, "没有可下发的资源(缺少簇信息?)").also {
                record(feature.key, it)
            }
        }

        val duration = if (hold) 0 else TEST_DURATION_MS
        AppLog.i(
            TAG,
            "[${feature.key}] 测试下发 " + pairs.joinToString(", ") { "${it.first}=${it.second}" } +
                " · ${if (hold) "保持(不过期)" else "${TEST_DURATION_MS / 1000} 秒后自动失效"}",
        )

        val outcome = lock.apply(pairs, duration)
        if (!outcome.success) {
            val verdict = Verdict(State.NO_EFFECT.name, "下发被拒绝: ${outcome.message}")
            AppLog.w(TAG, "[${feature.key}] ${verdict.detail}")
            record(feature.key, verdict)
            return verdict
        }

        // 3. Observe.
        if (nodes.isEmpty()) {
            val verdict = Verdict(
                state = State.UNKNOWN.name,
                detail = "已下发,但这个功能没有可读回的节点,无法自动判断是否生效。" +
                    "请靠体感或帧率判断。",
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
            after == null -> Verdict(State.UNKNOWN.name, "读回失败")
            after == before && target != null && after == target ->
                Verdict(State.WORKING.name, "节点已是目标值($after)", System.currentTimeMillis())
            after == before -> Verdict(
                State.NO_EFFECT.name,
                "节点无变化,仍是 \"$before\"。ID 可能在本机不同,或该功能需要 root。",
                System.currentTimeMillis(),
            )
            else -> Verdict(
                State.WORKING.name,
                "节点 \"$before\" → \"$after\",已生效",
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
        if (pairs.isEmpty()) return Verdict(State.NO_EFFECT.name, "没有勾选任何 ID")

        val before = readAll(readNodes)
        val duration = if (hold) 0 else TEST_DURATION_MS
        AppLog.i(
            TAG,
            "[手动] 下发 " + pairs.joinToString(", ") { "${it.first}=${it.second}" } +
                " · ${if (hold) "保持" else "${TEST_DURATION_MS / 1000}s 后失效"}",
        )

        val outcome = lock.apply(pairs, duration)
        if (!outcome.success) {
            val verdict = Verdict(State.NO_EFFECT.name, "下发被拒绝: ${outcome.message}")
            AppLog.w(TAG, "[手动] ${verdict.detail}")
            return verdict
        }

        delay(SETTLE_MS)
        val after = readAll(readNodes)
        val verdict = when {
            after == null -> Verdict(State.UNKNOWN.name, "读回失败")
            after == before -> Verdict(
                State.NO_EFFECT.name,
                "${pairs.size} 个 ID 全部无效 —— 节点仍是 \"$before\"",
                System.currentTimeMillis(),
            )
            else -> Verdict(
                State.WORKING.name,
                "节点 \"$before\" → \"$after\"",
                System.currentTimeMillis(),
            )
        }
        AppLog.i(TAG, "[手动] ${verdict.stateEnum().label}: ${verdict.detail}")
        return verdict
    }

    /** Drop every lab request and mark that the observations are now stale. */
    suspend fun reset(): String {
        val outcome = lock.release()
        AppLog.i(TAG, "实验室全部恢复: ${outcome.message}")
        return if (outcome.success) "已释放实验室的所有请求" else outcome.message
    }

    private fun buildPairs(
        feature: LabFeature,
        clusters: List<CpuCluster>,
        values: Map<String, Int>,
    ): List<Pair<String, String>> {
        val base = feature.baseId ?: return emptyList()

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

    /** Existence check used by the UI to grey out what this device lacks. */
    suspend fun nodeExists(node: String): Boolean = nodeValue(node) != null
}
