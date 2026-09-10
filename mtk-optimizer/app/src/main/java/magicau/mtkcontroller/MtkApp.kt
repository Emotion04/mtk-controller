package magicau.mtkcontroller

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.log.LogLevel
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.di.AppContainer

/**
 * Application entry point.
 *
 * The Shizuku binder can arrive at any time, so the privilege manager starts
 * listening here rather than in an Activity.
 */
class MtkApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        PrivilegeManager.init(this)
        appScope.launch { container.settingsRepository.migrateIfNeeded() }
        appScope.launch {
            container.settingsRepository.logLevel.collect { AppLog.setLevel(LogLevel.fromName(it)) }
        }
        // Restore the CPU control bookkeeping before any screen can act on it.
        appScope.launch { container.cpuControl.load() }
        AppLog.i("App", "MTK God 启动")
    }
}
