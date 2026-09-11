package magicau.mtkcontroller.data.privilege

import android.os.Parcel
import android.os.Process
import rikka.shizuku.SystemServiceHelper
import magicau.mtkcontroller.data.probe.ProbeProcess

/** 仅用于已核对的 ROM 布局；独立句柄，服务端限时防止应用退出后残留。 */
class VivoDisplaySession {
    private val handle = (Process.myPid().toLong() shl 32) or 0x4d544bL
    private var requested = false

    @Synchronized fun request(fps: Int): String = try {
        val binder = SystemServiceHelper.getSystemService("rms") ?: error("未找到高刷服务")
        check(binder.interfaceDescriptor == "com.vivo.rms.IRM") { "接口不匹配" }
        if (fps >= 0) {
            val rom = ProbeProcess.run(listOf("/system/bin/getprop", "ro.vivo.product.version"), 1500, 2048)
            check(rom.output.trim() == "PD2430D_A_16.1.12.0.W10") { "当前系统版本尚未适配 RMS" }
        }
        if (fps == 0) "OK:支持限时高刷请求"
        else {
            if (requested) {
                transact(binder, 24) { writeInt(0); writeLong(handle) }
                requested = false
            }
            if (fps > 0) {
                require(fps in 30..240)
                requested = true
                transact(binder, 36) {
                    writeLong(handle); writeString("FixRate"); writeString("MTK God")
                    // display/resolution/fps/dfps/config/states/priority/duration/caller/client/flags/arg1..3。
                    DisplayRequest.arguments(fps).forEach(::writeInt)
                }
            }
            "OK:${if (fps > 0) "已请求 ${fps}Hz，15 秒到期" else "已释放高刷请求"}"
        }
    } catch (e: Exception) { "ERROR:${e.message}" }

    private fun transact(binder: android.os.IBinder, code: Int, body: Parcel.() -> Unit) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.body()
            check(binder.transact(code, data, reply, 0)) { "系统未处理高刷请求" }
            // RMS 成功返回空 Parcel；异常时有标准异常头。
            if (reply.dataSize() > 0) reply.readException()
        } finally { data.recycle(); reply.recycle() }
    }
}
