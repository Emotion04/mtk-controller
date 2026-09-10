package magicau.mtkcontroller

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    }
}
