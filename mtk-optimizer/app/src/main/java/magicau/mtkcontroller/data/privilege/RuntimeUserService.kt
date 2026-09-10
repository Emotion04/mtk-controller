package magicau.mtkcontroller.data.privilege

import android.content.Context
import android.os.Parcel
import android.os.Process
import androidx.annotation.Keep
import magicau.mtkcontroller.IRuntimeService

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

    private companion object {
        /** ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy */
        const val USER_SERVICE_TRANSACTION_DESTROY = 16777115
    }
}
