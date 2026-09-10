package magicau.mtkcontroller.data.privilege

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import magicau.mtkcontroller.IRuntimeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Single source of truth for "how privileged are we right now".
 *
 * Shizuku can be started over adb (service uid 2000 = shell) or via root
 * (uid 0). Both let us reach hidden system services through
 * [ShizukuBinderWrapper], but only root can write root-owned cpufreq sysfs
 * nodes, so the UI needs to tell them apart.
 */
object PrivilegeManager {

    const val REQUEST_CODE = 4210

    private const val USER_SERVICE_TAG = "runtime"
    private const val USER_SERVICE_VERSION = 1
    private const val USER_SERVICE_SUFFIX = "runtime"
    private const val BIND_TIMEOUT_MS = 10_000L

    private val _state = MutableStateFlow(PrivilegeState())
    val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var remote: IRuntimeService? = null
    private var bound = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        if (grant == PackageManager.PERMISSION_GRANTED) {
            refresh()
            bindUserService()
        } else {
            refresh("Shizuku 权限被拒绝")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remote = null
        bound = false
        refresh("Shizuku 服务已断开")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = service?.let { IRuntimeService.Stub.asInterface(it) }
            bound = remote != null
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            bound = false
            refresh("提权服务已断开")
        }
    }

    /** Call once from Application.onCreate. Safe to call repeatedly. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        if (Shizuku.isPreV11()) {
            _state.value = PrivilegeState(message = "Shizuku 版本过旧,请升级到 v11 以上")
            return
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refresh()
        if (hasPermission()) bindUserService()
    }

    fun release() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    fun requestPermission() {
        if (Shizuku.isPreV11()) return
        if (!Shizuku.pingBinder()) {
            refresh("Shizuku 未运行,请先启动 Shizuku")
            return
        }
        if (hasPermission()) {
            bindUserService()
            refresh()
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    /** Re-read binder/permission/uid and republish state. */
    fun refresh(message: String? = null) {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = hasPermission()
        val uid = if (granted) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1
        val version = runCatching { Shizuku.getVersion() }.getOrDefault(-1)

        val mode = when {
            !alive || Shizuku.isPreV11() -> PrivilegeMode.NONE
            !granted -> PrivilegeMode.AVAILABLE
            uid == PrivilegeState.UID_ROOT -> PrivilegeMode.ROOT
            uid == PrivilegeState.UID_SHELL -> PrivilegeMode.ADB
            else -> PrivilegeMode.AVAILABLE
        }

        _state.value = PrivilegeState(
            mode = mode,
            permissionGranted = granted,
            binderAlive = alive,
            uid = uid,
            shizukuVersion = version,
            message = message,
        )
    }

    private fun hasPermission(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    private fun userServiceArgs(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, RuntimeUserService::class.java.name)
    )
        .daemon(true)
        .tag(USER_SERVICE_TAG)
        .version(USER_SERVICE_VERSION)
        .processNameSuffix(USER_SERVICE_SUFFIX)

    fun bindUserService() {
        val context = appContext ?: return
        if (bound) return
        if (!hasPermission()) return
        runCatching {
            Shizuku.bindUserService(userServiceArgs(context), connection)
        }.onFailure { refresh("绑定提权服务失败: ${it.message}") }
    }

    fun unbindUserService(remove: Boolean = false) {
        val context = appContext ?: return
        runCatching { Shizuku.unbindUserService(userServiceArgs(context), connection, remove) }
        remote = null
        bound = false
    }

    val isRemoteReady: Boolean get() = remote != null

    /**
     * Run a shell command in the elevated process. Returns null if unavailable.
     *
     * `service.exec` is a blocking AIDL transaction, and on the other side it
     * spawns a real process and waits for it — tens of milliseconds, easily
     * more. Callers sit on `Dispatchers.Main` by default (viewModelScope), so
     * without this hop every probe would stall a frame.
     */
    suspend fun exec(command: String): String? = withContext(Dispatchers.IO) {
        val service = remote ?: return@withContext null
        runCatching { service.exec(command) }.getOrNull()
    }

    /** Wait for the UserService to finish binding, e.g. right after granting. */
    suspend fun awaitRemote(timeoutMs: Long = BIND_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (remote == null) {
                bindUserService()
                suspendCancellableCoroutine<Unit> { cont ->
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        { if (cont.isActive) cont.resume(Unit) },
                        200L,
                    )
                }
            }
            true
        } ?: false

    /** uid reported by the elevated process itself; null when not bound. */
    suspend fun remoteUid(): Int? = withContext(Dispatchers.IO) {
        val service = remote ?: return@withContext null
        runCatching { service.uid }.getOrNull()
    }
}
