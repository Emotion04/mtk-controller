package magicau.mtkcontroller.data.privilege

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import magicau.mtkcontroller.IRuntimeService
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    // Bump whenever IRuntimeService changes. Shizuku keeps a daemon UserService
    // by tag/version; without this it can keep the old AIDL implementation
    // alive after an APK update and reject the new PowerHAL transactions.
    private const val USER_SERVICE_VERSION = 8
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
            refresh(Message(R.string.priv_shizuku_denied))
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh()
        if (hasPermission()) bindUserService()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remote = null
        bound = false
        refresh(Message(R.string.priv_shizuku_service_gone))
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
            refresh(Message(R.string.priv_service_gone))
        }
    }

    /** Call once from Application.onCreate. Safe to call repeatedly. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        if (Shizuku.isPreV11()) {
            _state.value = PrivilegeState(message = resolve(Message(R.string.priv_shizuku_pre_v11)))
            return
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refresh()
        if (hasPermission()) bindUserService()
    }

    fun requestPermission() {
        if (Shizuku.isPreV11()) {
            refresh(Message(R.string.priv_shizuku_too_old_retry))
            return
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            refresh(Message(R.string.priv_shizuku_not_running))
            return
        }
        if (hasPermission()) {
            bindUserService()
            refresh()
        } else {
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }
                .onFailure {
                    refresh(Message(R.string.priv_auth_request_failed, listOf(it.message ?: "null")))
                }
        }
    }

    /**
     * 把 [Message] 解析成文本。
     *
     * [PrivilegeState.message] 是给授权界面和诊断报告读的 `String`，这里当场解析 ——
     * 和 [Shortcuts] 一样，`data/` 里有 `Context` 的类就地取资源。见 [Message] 的说明。
     */
    private fun resolve(message: Message): String? = appContext?.let(message::resolve)

    /** Re-read binder/permission/uid and republish state. */
    fun refresh(message: Message? = null) {
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
            message = message?.let(::resolve),
            serviceReady = remote != null,
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
        }.onFailure { refresh(Message(R.string.priv_bind_failed, listOf(it.message ?: "null"))) }
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
    suspend fun cameraProbe(): String = withContext(Dispatchers.IO) {
        val service = remote ?: return@withContext "CAMERA_PROBE_FAILED Shizuku 未连接"
        try { service.cameraProbe() ?: "CAMERA_PROBE_FAILED 空报告" }
        catch (e: Exception) { "CAMERA_PROBE_FAILED 远程调用失败（更新后请重启 Shizuku 再授权）：${e.javaClass.simpleName}: ${e.message}" }
    }

    suspend fun romControlProbe(): String = withContext(Dispatchers.IO) {
        val service = remote ?: return@withContext "ROM_PROBE_FAILED Shizuku 未连接"
        try { service.romControlProbe() ?: "ROM_PROBE_FAILED 空报告" }
        catch (e: Exception) { "ROM_PROBE_FAILED 更新后请重启 Shizuku：${e.javaClass.simpleName}: ${e.message}" }
    }

    suspend fun discoverRomImplementation(): List<String> = withContext(Dispatchers.IO) {
        val service = remote ?: error("Shizuku 未连接")
        val result = service.discoverRomImplementation() ?: error("请重启 Shizuku 载入新版服务")
        result.lines().filter { it.isNotBlank() }.also { check(it.isNotEmpty()) { "实现文件列表为空" } }
    }

    suspend fun vivoCpuLimitProbe(start: Boolean): String = withContext(Dispatchers.IO) {
        val service = remote ?: error("Shizuku 未连接")
        service.vivoCpuLimitProbe(start) ?: error("Vivo 探测返回空，请重启 Shizuku")
    }

    suspend fun vivoPolicyProbe(start: Boolean, profile: Int): String = withContext(Dispatchers.IO) {
        val service = remote ?: error("Shizuku 未连接")
        service.vivoPolicyProbe(start, profile) ?: error("Vivo 策略返回空，请重启 Shizuku")
    }

    suspend fun readFullRomConfig(index: Int): ByteArray = withContext(Dispatchers.IO) {
        val service = remote ?: error("Shizuku 未连接")
        val metadata = service.openRomConfig(index) ?: error("接口不可用，请重启 Shizuku")
        check(!metadata.startsWith("ERROR:")) { metadata }
        val fields = metadata.split('|')
        check(fields.size == 3) { "快照元信息异常，请重启 Shizuku" }
        val context = kotlinx.coroutines.currentCoroutineContext()
        val started = System.nanoTime()
        try {
            magicau.mtkcontroller.data.diag.RomConfigTransfer.verified(fields[1].toInt(), fields[2]) {
                context.ensureActive()
                check(System.nanoTime() - started < 120_000_000_000L) { "分块传输超过 120 秒" }
                service.readRomConfig(fields[0], it) ?: error("分块为空")
            }
        } finally { runCatching { service.closeRomConfig(fields[0]) } }
    }

    suspend fun appMaintenance(kind: String, pkg: String): String = withContext(Dispatchers.IO) {
        if (!awaitRemote()) return@withContext "ERROR:请重新连接 Shizuku"
        runCatching { remote?.appMaintenance(kind, pkg) ?: "ERROR:服务未连接" }.getOrElse { "ERROR:${it.message}" }
    }

    suspend fun vivoTouchRequest(level: Int): String = withContext(Dispatchers.IO) {
        if (!awaitRemote()) return@withContext "ERROR:请重新连接 Shizuku"
        runCatching { remote?.vivoTouchRequest(level) ?: "ERROR:服务未连接" }.getOrElse { "ERROR:${it.message}" }
    }

    suspend fun vivoDisplayRequest(fps: Int): String = withContext(Dispatchers.IO) {
        if (!awaitRemote()) return@withContext "ERROR:请重新连接 Shizuku"
        runCatching { remote?.vivoDisplayRequest(fps) ?: "ERROR:服务未连接" }
            .getOrElse { "ERROR:${it.message}" }
    }

    suspend fun exec(command: String): String? = withContext(Dispatchers.IO) {
        val service = remote ?: return@withContext null
        runCatching { service.exec(command) }.getOrNull()
    }

    /** 当前前台应用包名；拿不到返回以 ERROR: 开头的说明。 */
    suspend fun foregroundApp(): String = withContext(Dispatchers.IO) {
        if (!awaitRemote()) return@withContext "ERROR:请重新连接 Shizuku"
        runCatching { remote?.foregroundApp() ?: "ERROR:服务未连接" }
            .getOrElse { "ERROR:${it.javaClass.simpleName}: ${it.message}" }
    }

    /** 假电量 / 假充电。只固定四种命令，不接受自由文本。 */
    suspend fun fakeBattery(command: String): String = withContext(Dispatchers.IO) {
        if (!awaitRemote()) return@withContext "ERROR:请重新连接 Shizuku"
        if (command !in setOf("charge", "discharge", "reset") &&
            !(command.startsWith("level:") && command.removePrefix("level:").toIntOrNull() in 1..100)
        ) return@withContext "ERROR:未知假电池命令"
        runCatching { remote?.fakeBattery(command) ?: "ERROR:服务未连接" }
            .getOrElse { "ERROR:${it.javaClass.simpleName}: ${it.message}" }
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

    /**
     * 一次提权调用的结局。**不用可空的 T 表示**，因为三种失败对用户是三件事。
     *
     * 原来 `powerHalAcquire` 返回 `Int?`，服务没连上、绑定超时、调用抛异常全缩成
     * 同一个 null。界面上于是只剩一句"提权服务不可用"—— 一次 binder 崩溃和一次
     * 忘了开 Shizuku 长得一模一样，而点"显示更多"也没有东西可看。
     */
    sealed interface CallResult<out T> {
        data class Ok<T>(val value: T) : CallResult<T>

        /** 用户服务没连上，或等绑定等超时了。 */
        data object Unavailable : CallResult<Nothing>

        /** 调用本身抛了异常。[error] 是原话。 */
        data class Failed(val error: Throwable) : CallResult<Nothing>
    }

    /** PowerHAL calls are executed by the stable daemon UserService. */
    suspend fun powerHalAcquire(commands: IntArray, durationMs: Int): CallResult<Int> =
        withContext(Dispatchers.IO) {
            if (!awaitRemote()) return@withContext CallResult.Unavailable
            val service = remote ?: return@withContext CallResult.Unavailable
            try {
                CallResult.Ok(service.powerHalAcquire(commands, durationMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                CallResult.Failed(e)
            }
        }

    /** Queue release through the same UserService that acquired the handle. */
    suspend fun powerHalRelease(handler: Int): CallResult<Boolean> =
        withContext(Dispatchers.IO) {
            if (!awaitRemote()) return@withContext CallResult.Unavailable
            val service = remote ?: return@withContext CallResult.Unavailable
            try {
                CallResult.Ok(service.powerHalRelease(handler))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                CallResult.Failed(e)
            }
        }
}
