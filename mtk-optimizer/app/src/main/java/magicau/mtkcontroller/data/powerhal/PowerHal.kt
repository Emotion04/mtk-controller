package magicau.mtkcontroller.data.powerhal

import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * MediaTek's PowerHAL manager, reached through Shizuku.
 *
 * The protocol belongs to MediaTek and is documented in `docs/cpu/protocol.md` with
 * primary sources. Its shape:
 *
 *  - service name `power_hal_mgr_service`, interface
 *    `com.mediatek.powerhalmgr.IPowerHalMgr`
 *  - `transact(0x16)` acquires a "handler" covering every cluster passed in and
 *    returns its id; `transact(0x17)` releases that handler
 *  - each command id is `String.format("0x%08X", policyIndex * 0x100 + base)`;
 *    the full layout is MAJOR<<22 | MINOR<<14 | GROUP<<8, where GROUP is the
 *    low byte
 *  - the value array is `[id, value, id, value, …]`, so a floor and a ceiling
 *    are two independent entries and min != max expresses a real range
 *
 * Transaction codes are a property of the ROM's interface revision, not of the
 * device — see `docs/dev/architecture.md` for the detection plan. The values below
 * are correct for the development device and must not be assumed portable.
 */
object PowerHal {

    const val SERVICE_NAME = "power_hal_mgr_service"
    const val INTERFACE_TOKEN = "com.mediatek.powerhalmgr.IPowerHalMgr"

    const val TRANSACT_ACQUIRE = 0x16
    const val TRANSACT_RELEASE = 0x17

    // Bases for the per-cluster command ids. Names taken from MediaTek's
    // mtkperf_resource.h; each family is + 0x100 per cpufreq policy index.
    const val BASE_MIN = 0x400000      // PERF_RES_CPUFREQ_MIN_CLUSTER_n
    const val BASE_MAX = 0x404000      // PERF_RES_CPUFREQ_MAX_CLUSTER_n

    /**
     * Independent floor/ceiling in the public BSP. The backing node is ROM-dependent;
     * lack of app access to the legacy proc node does not prove HAL support is absent.
     */
    const val BASE_HARD_MIN = 0x408000 // PERF_RES_CPUFREQ_MIN_HL_CLUSTER_n
    const val BASE_HARD_MAX = 0x40c000 // PERF_RES_CPUFREQ_MAX_HL_CLUSTER_n

    /**
     * vivo 私有的调度器资源，opcode 取自本机 `/vendor/etc/powercontable.xml`。
     *
     * 厂商自己的跑分场景（`powerscntbl.xml` 里 hint 3160，
     * 注释 `benchmark mode package switch boost`）在把 policy7 的软下限顶到 3250000 的
     * **同时**下发这几条。我们只发频率从来不携它们，所以软下限一直被压在 2300。
     *
     * 完整出处与下一轮实验见 `docs/vperf-resources.md`。
     */
    const val SCHED_CLOSE_PELT_ENABLE = 0x1000c900 // PERF_RES_SCHED_CLOSE_PELT_ENABLE
    const val SCHED_ADAPTIVE_MARGIN = 0x1000c200 // PERF_RES_SCHED_ADAPTIVE_MARGIN
    const val SCHED_PACKING_CPU = 0x1000c500 // PERF_RES_SCHED_PACKING_CPU
    const val KICK_IDLE_SPEED_UP = 0x1000c800 // PERF_RES_KICK_IDLE_SPEED_UP
    const val SCHED_UCLAMP_MIN_TA = 0x01408300 // PERF_RES_SCHED_UCLAMP_MIN_TA

    // Named against a vendor command table. An earlier pass called 0x00c04000
    // GPU_MIN and 0x00c04100 GPU_MAX; both were wrong - the pair is the ceiling
    // and its hard-limit counterpart, so a UI built on those names would have
    // pinned the GPU while claiming to set a floor.
    const val GPU_FREQ_MIN = "0x00c00000"      // PERF_RES_GPU_FREQ_MIN
    const val GPU_FREQ_MAX = "0x00c04000"      // PERF_RES_GPU_FREQ_MAX
    const val GPU_FREQ_MAX_HL = "0x00c04100"   // PERF_RES_GPU_FREQ_MAX_HL
    const val GPU_FREQ_LOW_LATENCY = "0x00c08000"

    /**
     * Binder meta-transaction, answered by every Java Binder before AIDL dispatch.
     *
     * It sits outside the AIDL transaction range, so the generated stub's
     * range-guarded `enforceInterface` is skipped and an *empty* parcel is
     * enough. That makes it the one call that identifies a vendor binder with no
     * side effects at all — no permission check, no method body runs — which
     * matters because the same service name answers quite differently on an MTK
     * device and on a Qualcomm one.
     */
    const val INTERFACE_TRANSACTION = 0x5F4E5446

    /** Build a per-cluster command id string, e.g. (BASE_MIN, 0) -> "0x00400000". */
    fun commandId(base: Int, policyIndex: Int): String =
        String.format("0x%08X", policyIndex * 0x100 + base)

    /**
     * 已经是完整 opcode 的，只做格式化，不加 policy 偏移。
     *
     * 给非按簇的资源用（vivo 的调度器那族 `0x1000c900` 之类）。
     * 错用 [commandId] 会给它们加上 `policyIndex * 0x100`，opcode 就跑到了别的资源上，
     * 而且不报错 —— 只在日志里看到一条没人理的 hint。
     */
    fun fixedCommandId(opcode: Int): String = String.format("0x%08X", opcode)

    sealed interface Result {
        val message: Message

        /** 原始错误，默认收起、可复制。界面用 `FailureNote` 展示。 */
        val detail: String?

        data class Success(
            val handler: Int,
            override val message: Message,
            override val detail: String? = null,
        ) : Result

        data class Failure(
            override val message: Message,
            override val detail: String? = null,
        ) : Result
    }

    /** Resolve the hidden service and wrap it so transact runs in Shizuku's process. */
    fun binder(): IBinder? = runCatching {
        val raw = SystemServiceHelper.getSystemService(SERVICE_NAME) ?: return null
        ShizukuBinderWrapper(raw)
    }.getOrNull()

    fun isAvailable(): Boolean = binder() != null

    /**
     * Ask the binder to identify itself.
     *
     * Must go through [IBinder.transact] — [ShizukuBinderWrapper]'s own
     * `getInterfaceDescriptor` returns null, because the wrapper is not the
     * generated Stub. Returns null when the service is unreachable.
     */
    suspend fun descriptor(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val target = binder() ?: return@runCatching null
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                target.transact(INTERFACE_TRANSACTION, data, reply, 0)
                reply.readString()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }.getOrNull()
    }

    /** True when the binder really speaks MTK's PowerHAL, not some other vendor's. */
    suspend fun isMtkPowerHal(): Boolean = descriptor() == INTERFACE_TOKEN

    /**
     * [isAvailable] off the main thread.
     *
     * Resolving the hidden service is a blocking transaction into Shizuku; on
     * the main thread it costs frames, and it is called on every screen entry.
     */
    suspend fun isAvailableNow(): Boolean = withContext(Dispatchers.IO) { isAvailable() }

    /**
     * Acquire a handler with a flat (commandId, value) array.
     *
     * The array is the two interleaved — `["0x00c00000", "0", "0x00c00100",
     * "0", …]` — so it must be built from both halves of each pair. Sending only
     * the values produces an array of the wrong length with no command ids in
     * it, which is not a request the service can act on; that is what
     * [toIntValue]'s "0x…" branch exists for.
     *
     * @param durationMs `0` means the request never expires on its own. That is
     *   right for a limit the user is deliberately holding, and wrong for an
     *   experiment: pass a short value there so a handle that goes missing
     *   cannot leave a permanent request behind.
     */
    suspend fun acquire(
        pairs: List<Pair<String, String>>,
        durationMs: Int = 0,
    ): Result = withContext(Dispatchers.IO) {
        if (pairs.isEmpty()) {
            return@withContext Result.Failure(Message(R.string.powerhal_pairs_required))
        }
        val commands = pairs.flatMap { listOf(it.first, it.second) }.map { it.toIntValue() }
        when (val call = PrivilegeManager.powerHalAcquire(commands.toIntArray(), durationMs)) {
            is PrivilegeManager.CallResult.Ok -> {
                val handler = call.value
                if (handler > 0) Result.Success(handler, Message(R.string.powerhal_applied))
                // 固件回了 0 或负数：把那个数留下，它是唯一能拿给固件侧对质的凭据。
                else Result.Failure(
                    Message(R.string.powerhal_invalid_handler, listOf(handler)),
                    detail = "handle=$handler",
                )
            }
            // 服务没连上：摘要已经把这件事说清楚了，没有原话可看，detail 留空。
            is PrivilegeManager.CallResult.Unavailable ->
                Result.Failure(Message(R.string.powerhal_no_service_apply))
            is PrivilegeManager.CallResult.Failed -> Result.Failure(
                Message(R.string.powerhal_call_failed),
                detail = Failure.detailOf(call.error),
            )
        }
    }

    /**
     * Release a previously acquired handler.
     *
     * Firmware expects this as `transact(0x17, parcel, null, 1)` — a
     * oneway call with no reply parcel. Sending a reply and waiting for it
     * (flags = 0) does not release the request, which is why releasing
     * appeared to do nothing and the frequency limits stayed in force.
     */
    suspend fun release(handler: Int): Result = withContext(Dispatchers.IO) {
        if (handler <= 0) return@withContext Result.Failure(Message(R.string.release_nothing_pending))
        when (val call = PrivilegeManager.powerHalRelease(handler)) {
            is PrivilegeManager.CallResult.Ok ->
                if (call.value) Result.Success(0, Message(R.string.powerhal_released))
                else Result.Failure(Message(R.string.powerhal_release_failed), detail = "service returned false")
            is PrivilegeManager.CallResult.Unavailable ->
                Result.Failure(Message(R.string.powerhal_no_service_release))
            is PrivilegeManager.CallResult.Failed -> Result.Failure(
                Message(R.string.powerhal_call_failed),
                detail = Failure.detailOf(call.error),
            )
        }
    }

    /** Values arrive as decimal or "0x…" hex. */
    private fun String.toIntValue(): Int = trim().let {
        if (it.startsWith("0x", ignoreCase = true)) it.substring(2).toLong(16).toInt()
        else it.toInt()
    }
}

