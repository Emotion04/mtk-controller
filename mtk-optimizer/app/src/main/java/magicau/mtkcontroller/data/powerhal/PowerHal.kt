package magicau.mtkcontroller.data.powerhal

import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * MediaTek's PowerHAL manager, reached through Shizuku.
 *
 * The protocol belongs to MediaTek and is documented in `docs/protocol.md` with
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
 * device — see `docs/architecture.md` for the detection plan. The values below
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
     * Hard limits — a separate mechanism from the soft pair above, written by
     * libpowerhal to /proc/ppm/policy/hard_userlimit_cpu_freq only. Setting
     * both halves to one value hard-locks the cluster. Unused today; kept
     * because the protocol is worth recording.
     */
    const val BASE_HARD_MIN = 0x408000 // PERF_RES_CPUFREQ_MIN_HL_CLUSTER_n
    const val BASE_HARD_MAX = 0x40c000 // PERF_RES_CPUFREQ_MAX_HL_CLUSTER_n

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

    sealed interface Result {
        val message: String

        data class Success(val handler: Int, override val message: String) : Result
        data class Failure(override val message: String) : Result
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
                reply.readException()
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
        if (pairs.isEmpty() || pairs.size % 2 != 0) {
            return@withContext Result.Failure("命令数组必须成对且非空")
        }
        val commands = pairs.flatMap { listOf(it.first, it.second) }.map { it.toIntValue() }
        val handler = PrivilegeManager.powerHalAcquire(commands.toIntArray(), durationMs)
            ?: return@withContext Result.Failure("提权服务未连接，无法下发 PowerHAL 请求")
        if (handler > 0) Result.Success(handler, "调频已应用")
        else Result.Failure("PowerHAL 返回无效句柄: $handler")
    }

    /**
     * Release a previously acquired handler.
     *
     * The reference app calls this as `transact(0x17, parcel, null, 1)` — a
     * oneway call with no reply parcel. Sending a reply and waiting for it
     * (flags = 0) does not release the request, which is why releasing
     * appeared to do nothing and the frequency limits stayed in force.
     */
    suspend fun release(handler: Int): Result = withContext(Dispatchers.IO) {
        if (handler <= 0) return@withContext Result.Failure("没有可释放的请求")
        when (PrivilegeManager.powerHalRelease(handler)) {
            true -> Result.Success(0, "调频已释放")
            false -> Result.Failure("调频释放请求失败")
            null -> Result.Failure("提权服务未连接，无法释放 PowerHAL 请求")
        }
    }

    /** Values arrive as decimal or "0x…" hex, matching the reference app. */
    private fun String.toIntValue(): Int = trim().let {
        if (it.startsWith("0x", ignoreCase = true)) it.substring(2).toLong(16).toInt()
        else it.toInt()
    }
}
