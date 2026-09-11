package magicau.mtkcontroller.data.privilege

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import rikka.shizuku.SystemServiceHelper

/** 当前 ROM 接口声明确认的独立、有限时请求；不与 MTK 句柄混用。 */
class VivoLimitSession {
    private val owner = Binder()
    private var target: IBinder? = null
    private var handle = 0
    private var expires = 0L
    private val descriptor = "com.vivo.framework.vperf.IVivoPerfManager"

    private fun call(binder: IBinder, code: Int, write: (Parcel) -> Unit): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            write(data)
            check(binder.transact(code, data, reply, 0)) { "transaction=$code 未实现" }
            reply.readException()
            return reply.readInt()
        } finally { data.recycle(); reply.recycle() }
    }

    @Synchronized fun request(start: Boolean, profile: Int = 0): String {
        val report = StringBuilder("VIVO uid=${android.os.Process.myUid()} start=$start ")
        return try {
            val policy = VivoProbePolicy.select(profile)
            if (start) {
                check(android.os.Build.DISPLAY == "PD2430D_A_16.1.12.0.W10") { "仅允许已核对接口的 ROM" }
                check(handle <= 0 || SystemClock.elapsedRealtime() >= expires) { "前一请求仍在有效期内" }
                handle = 0
                target = null
                val binder = SystemServiceHelper.getSystemService("vendor.vivoperfservice")
                    ?: error("服务不存在或不可访问")
                check(binder.interfaceDescriptor == descriptor) { "descriptor 不匹配，中止" }
                report.append("descriptor=$descriptor register=")
                val registered = call(binder, 6) { it.writeStrongBinder(owner) }
                report.append("$registered ")
                check(registered >= 0) { "客户端注册返回负值，中止" }
                // 注册返回值的业务语义未知；异常已中止，后续 acquire 单独记录。
                target = binder
                expires = SystemClock.elapsedRealtime() + policy.durationMs
                handle = call(binder, 4) {
                    it.writeInt(policy.durationMs)
                    it.writeIntArray(policy.commands.flatMap { (resource, value) -> listOf(resource, value) }.toIntArray())
                }
                expires = SystemClock.elapsedRealtime() + policy.durationMs
                report.append("resources=${policy.commands.joinToString { (resource, value) -> "0x${resource.toUInt().toString(16)}=$value" }} duration=${policy.durationMs}ms handle=$handle ")
                report.append(if (handle > 0) "ACCEPTED（不代表资源生效）" else "REJECTED")
            } else if (handle > 0) {
                val result = call(checkNotNull(target), 2) { it.writeInt(handle) }
                report.append("releaseHandle=$handle return=$result（回读另记；返回码不代替恢复验证）")
                // 不猜返回码的成功语义；保留句柄直到下一次确认请求期限已过。
            } else report.append("NO_HANDLE")
            report.toString()
        } catch (e: Exception) {
            report.append(" ERROR ${e.javaClass.simpleName}: ${e.message}").toString()
        }
    }
}
