package magicau.mtkcontroller.data.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import magicau.mtkcontroller.BuildConfig
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.di.AppContainer

/**
 * 更新检查的唯一状态源：启动时自动查一次，关于页也能手动查。
 *
 * 检查结果放这里给界面弹窗；同一版本用户点过“稍后”就记下来，不再自动打扰
 * （手动检查仍然会显示）。网络失败不弹任何东西 —— 用户正在用的是能用的版本，
 * 检查失败不是他的问题。
 */
object UpdateCenter {

    private const val TAG = "Update"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    enum class Stage { IDLE, CHECKING, FOUND, UP_TO_DATE, ERROR }

    data class State(
        val stage: Stage = Stage.IDLE,
        val info: UpdateInfo? = null,
        val required: Boolean = false,
        val progress: UpdateInstaller.Progress = UpdateInstaller.Progress.Idle,
        /** 手动检查时即使点过“稍后”也弹窗；是否弹窗由界面读 [showDialog] 决定。 */
        val manual: Boolean = false,
        /** 弹窗是否打开。点“稍后”或关闭后置 false，但下载状态继续保留。 */
        val dialogOpen: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()

    /** 应用启动时调用；24 小时内只查一次。 */
    fun maybeCheck(container: AppContainer) {
        container.applicationScope.launch {
            mutex.withLock {
                val settings = container.settingsRepository
                val now = System.currentTimeMillis()
                val last = settings.updateLastCheckMs.first()
                if (last > 0 && now - last < CHECK_INTERVAL_MS) return@withLock
                settings.setUpdateLastCheckMs(now)
                check(container, manual = false)
            }
        }
    }

    /** 关于页的「检查更新」。不受 24 小时节流，也不受“稍后”影响。 */
    fun checkNow(container: AppContainer) {
        container.applicationScope.launch {
            check(container, manual = true)
        }
    }

    private suspend fun check(container: AppContainer, manual: Boolean) {
        _state.value = _state.value.copy(stage = Stage.CHECKING, manual = manual)
        val info = runCatching { UpdateRepository(container.context).check() }
            .onFailure { AppLog.w(TAG, "检查更新失败: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        if (info == null) {
            // 网络失败与“已是最新”在 repository 里都返回 null；手动检查时再探一次区分。
            val reachable = if (manual) runCatching { UpdateRepository(container.context).reachable() }
                .getOrDefault(false) else true
            _state.value = _state.value.copy(
                stage = if (reachable) Stage.UP_TO_DATE else Stage.ERROR,
                info = null,
                dialogOpen = false,
            )
            return
        }
        val dismissed = container.settingsRepository.updateDismissedVersion.first()
        _state.value = State(
            stage = Stage.FOUND,
            info = info,
            required = BuildConfig.VERSION_CODE < info.minVersionCode,
            progress = _state.value.progress,
            manual = manual,
            // 自动检查时用户点过的“稍后”仍然有效；手动检查一定要让他看到结果。
            dialogOpen = manual || dismissed != info.versionCode,
        )
    }

    fun openDialog() {
        _state.value = _state.value.copy(dialogOpen = true)
    }

    /** 用户点“稍后”或关闭：记住这个版本，下次自动检查不再弹。 */
    fun dismiss(container: AppContainer) {
        val info = _state.value.info ?: return
        _state.value = _state.value.copy(dialogOpen = false)
        container.applicationScope.launch {
            container.settingsRepository.setUpdateDismissedVersion(info.versionCode)
        }
    }

    /**
     * 下载并（用户点“安装”后）交给系统安装器。
     * 下载状态放在这里，弹窗关掉再打开不会丢进度。
     */
    fun download(container: AppContainer) {
        val info = _state.value.info ?: return
        if (_state.value.progress is UpdateInstaller.Progress.Downloading) return
        container.applicationScope.launch {
            runCatching {
                UpdateInstaller.download(container.context, info) { progress ->
                    _state.value = _state.value.copy(progress = progress)
                }
            }.onSuccess { file ->
                _state.value = _state.value.copy(progress = UpdateInstaller.Progress.Ready(file))
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    progress = UpdateInstaller.Progress.Failed(R.string.update_download_failed),
                )
            }
        }
    }

    fun install(container: AppContainer) {
        val file = (_state.value.progress as? UpdateInstaller.Progress.Ready)?.file ?: return
        UpdateInstaller.install(container.context, file)
    }
}
