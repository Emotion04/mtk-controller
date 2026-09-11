package magicau.mtkcontroller.data.privilege

import android.content.Context
import android.os.Parcel
import android.os.Process
import androidx.annotation.Keep
import magicau.mtkcontroller.IRuntimeService
import magicau.mtkcontroller.R
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

    /**
     * 应用维护。回包格式 `OK:<resId>` / `ERROR:<resId>`，必要时追加一行原始输出。
     *
     * **传的是资源 id 而不是中文**：这个进程与 App 是两个进程，资源表由同一个 APK
     * 产出，所以 App 侧用 R 解析得到同一句话，而且跟着系统语言走。
     * 把中文拼在串里发过去，就等于把文案焊死在了跨进程协议上。
     */
    override fun appMaintenance(kind: String, pkg: String): String {
        val outcome = runCatching { AppMaintenance.run(kind, pkg) }
            .getOrElse { return "ERROR:${R.string.maintenance_failed}" }
        val head = "${if (outcome.success) "OK" else "ERROR"}:${outcome.message.res}"
        // 参数只有"读取了多少 MiB"这一种，是纯数字，跟着 id 一起传。
        val args = outcome.message.args.joinToString(",")
        return listOfNotNull(head, args.takeIf { it.isNotEmpty() }, outcome.detail).joinToString("\n")
    }

    /**
     * 旧名，转发到 [appMaintenance]。
     *
     * **不能删。** Shizuku 可能还留着上一次启动的服务进程，里面的 AIDL stub 只认旧方法；
     * 删掉之后 App 调过去会打到别的方法上，而且不报错。等确认没有旧进程在跑再清理。
     */
    @Deprecated("用 appMaintenance")
    override fun axMaintenance(kind: String, pkg: String): String = appMaintenance(kind, pkg)
    override fun fakeBattery(command: String): String = runCatching { FakeBattery.dispatch(command) }.getOrElse { "ERROR:${it.message}" }

    override fun foregroundApp(): String = runCatching { ForegroundApp.current() }
        .getOrElse { "ERROR:${it.javaClass.simpleName}: ${it.message}" }
    private val vivoTouch = VivoTouchSession()
    override fun vivoTouchRequest(level: Int): String = vivoTouch.request(level)
    private val vivoDisplay = VivoDisplaySession()
    override fun vivoDisplayRequest(fps: Int): String = vivoDisplay.request(fps)

    override fun getUid(): Int = Process.myUid()
    override fun cameraProbe(): String = magicau.mtkcontroller.data.diag.CameraCollector.collect()
    override fun romControlProbe(): String = magicau.mtkcontroller.data.diag.RomControlProbe.collect()
    private val romConfigs = magicau.mtkcontroller.data.diag.RomConfigTransfer()
    override fun openRomConfig(index: Int): String = try { romConfigs.open(index) }
        catch (e: Exception) { "ERROR:${e.javaClass.simpleName}: ${e.message}" }
    override fun readRomConfig(token: String, offset: Int): ByteArray = romConfigs.chunk(token, offset)
    override fun closeRomConfig(token: String) = romConfigs.close(token)
    override fun discoverRomImplementation(): String = romConfigs.discover()
    private val vivoLimit = VivoLimitSession()
    override fun vivoCpuLimitProbe(start: Boolean): String = vivoLimit.request(start)
    override fun vivoPolicyProbe(start: Boolean, profile: Int): String = vivoLimit.request(start, profile)

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
