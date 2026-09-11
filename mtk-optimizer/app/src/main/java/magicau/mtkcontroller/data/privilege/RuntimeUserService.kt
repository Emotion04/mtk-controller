package magicau.mtkcontroller.data.privilege

import android.content.Context
import android.os.Parcel
import android.os.Process
import androidx.annotation.Keep
import magicau.mtkcontroller.IRuntimeService
import rikka.shizuku.SystemServiceHelper

/**
 * Runs inside the process Shizuku spawns for us. With root that process is
 * uid 0; with adb it is uid 2000 (shell). All shell work happens here so it
 * inherits that identity.
 *
 * Shizuku requires a no-arg constructor and, on API 13+, an optional Context
 * constructor — the latter must be @Keep or R8 strips it.
 */
class RuntimeUserService : IRuntimeService.Stub {

    constructor() : super()

    @Keep
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super()

    /**
     * Shizuku tears the service down with its own transaction code, which is
     * above AIDL's maximum id, so it cannot be declared in the .aidl file.
     * The process must exit itself; Shizuku does not kill it.
     */
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == USER_SERVICE_TRANSACTION_DESTROY) {
            System.exit(0)
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun exec(command: String): String = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", command))
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        buildString {
            append(stdout)
            if (stderr.isNotBlank()) append("\n[stderr] ").append(stderr.trim())
            append("\n[exit] ").append(exit)
        }
    }.getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message}" }

    override fun getUid(): Int = Process.myUid()

    /**
     * PowerHAL retains requests under the calling process. Do the actual
     * transactions here instead of through ShizukuBinderWrapper: the daemon
     * UserService has a stable identity and can release the handles it owns.
     */
    override fun powerHalAcquire(commands: IntArray?, durationMs: Int): Int {
        if (commands == null || commands.isEmpty() || commands.size % 2 != 0) return 0
        return runCatching {
            val target = SystemServiceHelper.getSystemService(POWER_HAL_SERVICE) ?: return 0
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(POWER_HAL_INTERFACE)
                data.writeInt(0)
                data.writeInt(durationMs)
                data.writeIntArray(commands)
                if (!target.transact(POWER_HAL_ACQUIRE, data, reply, 0)) return 0
                reply.readException()
                reply.readInt().takeIf { it > 0 } ?: 0
            } finally {
                reply.recycle()
                data.recycle()
            }
        }.getOrDefault(0)
    }

    override fun powerHalRelease(handler: Int): Boolean {
        if (handler <= 0) return false
        return runCatching {
            val target = SystemServiceHelper.getSystemService(POWER_HAL_SERVICE) ?: return false
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(POWER_HAL_INTERFACE)
                data.writeInt(handler)
                target.transact(POWER_HAL_RELEASE, data, null, android.os.IBinder.FLAG_ONEWAY)
            } finally {
                data.recycle()
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val POWER_HAL_SERVICE = "power_hal_mgr_service"
        const val POWER_HAL_INTERFACE = "com.mediatek.powerhalmgr.IPowerHalMgr"
        const val POWER_HAL_ACQUIRE = 0x16
        const val POWER_HAL_RELEASE = 0x17
        /** ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy */
        const val USER_SERVICE_TRANSACTION_DESTROY = 16777115
    }
}
