package magicau.mtkcontroller.data.powerhal

import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * MediaTek's PowerHAL manager, reached through Shizuku.
 *
 * This is the channel the original app used. Recovered from the reference
 * APK (classes `y91`, `s91`, `e91`):
 *
 *  - service name `power_hal_mgr_service`, interface
 *    `com.mediatek.powerhalmgr.IPowerHalMgr`
 *  - `transact(0x16)` acquires a "handler" covering every cluster passed in,
 *    and returns its id; `transact(0x17)` releases that handler
 *  - each command id is `String.format("0x%08X", policyIndex * 0x100 + base)`,
 *    which matches MediaTek's own layout, id = MAJOR<<22 | MINOR<<8 | INDEX
 *  - the value array is `[id, value, id, value, …]`, so a floor and a ceiling
 *    are two independent entries; sending min != max is how a range is asked
 *    for, and MediaTek's own unit tests and powerhint XMLs do exactly that
 *
 * The reference app filled all four ids with the *same* frequency, which is a
 * hard lock rather than a range — it had no range UI, not an API that lacks one.
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

    // GPU commands seen in the reference app's "GPU 拉满" path. The pairing
    // (which one is min, which is max) is inferred, not verified on-device.
    const val GPU_CMD_A = "0x00c00000"
    const val GPU_CMD_B = "0x00c00100"
    const val GPU_MIN = "0x00c04000"
    const val GPU_MAX = "0x00c04100"

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
     * [isAvailable] off the main thread.
     *
     * Resolving the hidden service is a blocking transaction into Shizuku; on
     * the main thread it costs frames, and it is called on every screen entry.
     */
    suspend fun isAvailableNow(): Boolean = withContext(Dispatchers.IO) { isAvailable() }

    /**
     * Acquire a handler with a flat (commandId, value) array.
     *
     * The array is the two interleaved — the reference app sent
     * `["0x00c00000", "0", "0x00c00100", "0", …]` — so it must be built from
     * both halves of each pair. Sending only the values produced an array of
     * the wrong length with no command ids in it, which is not a request the
     * service can act on; that is what [toIntValue]'s "0x…" branch exists for.
     */
    suspend fun acquire(pairs: List<Pair<String, String>>): Result = withContext(Dispatchers.IO) {
        if (pairs.isEmpty() || pairs.size % 2 != 0) {
            return@withContext Result.Failure("命令数组必须成对且非空")
        }
        val target = binder() ?: return@withContext Result.Failure("未获取到 PowerHAL 服务")

        val commands = pairs.flatMap { listOf(it.first, it.second) }.map { it.toIntValue() }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(0)
            data.writeInt(0)
            data.writeIntArray(commands.toIntArray())

            val ok = target.transact(TRANSACT_ACQUIRE, data, reply, 0)
            if (!ok) return@withContext Result.Failure("PowerHAL 调频请求失败")

            reply.readException()
            val handler = reply.readInt()
            if (handler > 0) {
                Result.Success(handler, "调频已应用")
            } else {
                Result.Failure("PowerHAL 返回无效句柄: $handler")
            }
        } catch (t: Throwable) {
            Result.Failure("PowerHAL 调用异常: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            data.recycle()
            reply.recycle()
        }
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
        val target = binder() ?: return@withContext Result.Failure("未获取到 PowerHAL 服务")

        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(handler)
            val ok = target.transact(TRANSACT_RELEASE, data, null, IBinder.FLAG_ONEWAY)
            if (ok) Result.Success(0, "调频已释放") else Result.Failure("调频释放请求失败")
        } catch (t: Throwable) {
            Result.Failure("PowerHAL 调用异常: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            data.recycle()
        }
    }

    /** Values arrive as decimal or "0x…" hex, matching the reference app. */
    private fun String.toIntValue(): Int = trim().let {
        if (it.startsWith("0x", ignoreCase = true)) it.substring(2).toLong(16).toInt()
        else it.toInt()
    }
}
