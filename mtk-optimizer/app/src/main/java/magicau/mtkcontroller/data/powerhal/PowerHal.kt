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
 *    where the base selects which property is being set
 *
 * The reference app filled min/max/thermal-min/thermal-max with the *same*
 * frequency (a hard lock). Sending different min and max yields a real range.
 */
object PowerHal {

    const val SERVICE_NAME = "power_hal_mgr_service"
    const val INTERFACE_TOKEN = "com.mediatek.powerhalmgr.IPowerHalMgr"

    const val TRANSACT_ACQUIRE = 0x16
    const val TRANSACT_RELEASE = 0x17

    // Bases for the per-cluster command ids (recovered constants).
    const val BASE_MIN = 0x400000
    const val BASE_MAX = 0x404000
    const val BASE_THERMAL_MIN = 0x408000
    const val BASE_THERMAL_MAX = 0x40c000

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
        data class Success(val handler: Int, val message: String) : Result
        data class Failure(val message: String) : Result
    }

    /** Resolve the hidden service and wrap it so transact runs in Shizuku's process. */
    fun binder(): IBinder? = runCatching {
        val raw = SystemServiceHelper.getSystemService(SERVICE_NAME) ?: return null
        ShizukuBinderWrapper(raw)
    }.getOrNull()

    fun isAvailable(): Boolean = binder() != null

    /**
     * Acquire a handler with a flat (commandId, value) array.
     * The array must be even-sized: the reference app enforced `size % 2 == 0`.
     */
    suspend fun acquire(pairs: List<Pair<String, String>>): Result = withContext(Dispatchers.IO) {
        if (pairs.isEmpty() || pairs.size % 2 != 0) {
            return@withContext Result.Failure("命令数组必须成对且非空")
        }
        val target = binder() ?: return@withContext Result.Failure("未获取到 PowerHAL 服务")

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(0)
            data.writeInt(0)
            data.writeIntArray(pairs.map { it.second.toIntValue() }.toIntArray())

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

    /** Release a previously acquired handler. */
    suspend fun release(handler: Int): Result = withContext(Dispatchers.IO) {
        if (handler <= 0) return@withContext Result.Failure("没有可释放的请求")
        val target = binder() ?: return@withContext Result.Failure("未获取到 PowerHAL 服务")

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(handler)
            val ok = target.transact(TRANSACT_RELEASE, data, reply, 0)
            if (ok) Result.Success(0, "调频已释放") else Result.Failure("调频释放请求失败")
        } catch (t: Throwable) {
            Result.Failure("PowerHAL 调用异常: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Values arrive as decimal or "0x…" hex, matching the reference app. */
    private fun String.toIntValue(): Int = trim().let {
        if (it.startsWith("0x", ignoreCase = true)) it.substring(2).toLong(16).toInt()
        else it.toInt()
    }
}
