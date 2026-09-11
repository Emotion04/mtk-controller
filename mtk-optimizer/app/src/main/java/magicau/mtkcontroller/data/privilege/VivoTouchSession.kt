package magicau.mtkcontroller.data.privilege

import android.os.Parcel
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** 应用停止续期后主动归零；UserService 同时被终止时仍需用户重新连接后关闭。 */
class VivoTouchSession {
    private val executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "touch-lease").apply { isDaemon = true } }
    private var reset: ScheduledFuture<*>? = null
    private var retries = 0

    @Synchronized fun request(level: Int): String {
        require(level in 0..8)
        reset?.cancel(false)
        if (level > 0) {
            retries = 0
            reset = executor.schedule({ request(0) }, 20, TimeUnit.SECONDS)
        }
        return try {
            val binder = SystemServiceHelper.getSystemService("bbk_touch_screen_service") ?: error("触控服务不可用")
            check(binder.interfaceDescriptor == "vivo.app.touchscreen.ITouchScreen") { "触控接口不匹配" }
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("vivo.app.touchscreen.ITouchScreen"); data.writeInt(level)
                check(binder.transact(17, data, reply, 0)) { "触控请求未处理" }
                reply.readException()
            } finally { data.recycle(); reply.recycle() }
            "OK:触控档位 $level"
        } catch (e: Exception) {
            if (level == 0 && retries++ < 3) reset = executor.schedule({ request(0) }, 5, TimeUnit.SECONDS)
            "ERROR:${e.message}"
        }
    }
}
