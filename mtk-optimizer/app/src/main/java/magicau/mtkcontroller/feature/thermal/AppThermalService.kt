package magicau.mtkcontroller.feature.thermal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import magicau.mtkcontroller.MtkApp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.AppExpiry
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.power.BatteryWhitelist
import magicau.mtkcontroller.data.privilege.ForegroundApp
import magicau.mtkcontroller.data.thermal.AppThermalStore
import magicau.mtkcontroller.data.thermal.ThermalPolicyController
import kotlinx.coroutines.withContext

/**
 * 按应用切换温控策略。
 *
 * 轮询前台应用名，命中「按应用温控」表里的一条就下发对应的温控策略索引，
 * **离开就释放**（不再持有），回到系统默认。
 *
 * ## 三条设计约束（都是踩过的坑）
 *
 * 1. **不设计抢占。** 系统自己也会按前台应用选策略（`power_app_cfg.xml`：
 *    相机→12、原神→3、跑分→8）。实测系统选的会盖掉我们的，
 *    退出后系统自己回到启动默认。我们安静让位即可 ——
 *    见 `docs/thermal/facts.md` §11。两边的目标应用也不重叠：
 *    系统配的是**已经需要高性能**的那些，我们覆盖的是**其余被限制的**。
 * 2. **本软件自己要常驻才有效。** 被杀就不切。所以启动时先把自己加进
 *    电池优化白名单（[BatteryWhitelist]），这一步失败只记日志，不阻断功能。
 * 3. **轮询只在服务活着时跑**，退到后台由系统决定是否冻；
 *    频率节点读写全在 IO 上，不碰主线程。
 */
class AppThermalService : Service() {

    companion object {
        private const val CHANNEL_ID = "app_thermal"
        private const val NOTIFICATION_ID = 1010
        private const val TAG = "AppThermal"

        /**
         * 轮询间隔。
         *
         * **不是越快越好。** 每一轮都要跑一次 `dumpsys activity activities`
         * （见 [ForegroundApp.current]），那是整棵活动栈的转储，输出上限 40 KB。
         * 1 秒一次等于一小时 3600 次，而它是个前台服务 —— 用户在别的 App 里时
         * 也照跑。实测那一份 dump 要几十毫秒的 CPU，累积起来是可观的耗电。
         *
         * 2 秒对"切前台应用就换温控档位"这个用途没有可感差异：游戏从点图标到
         * 真正开始渲染要好几年秒，档位晚一两秒生效没人能察觉。而轮询变稀一半，
         * 耗电也少一半。
         *
         * 想再降一档，正确的做法是**加屏幕状态门控**而不是继续拉长间隔：
         * 熄屏时前台应用不可能变，那时可以直接睡到亮屏。
         */
        private const val POLL_MS = 2_000L

        /** 服务是否真的在跑。界面开关读它，不读"上次发的命令"。 */
        private val _running = kotlinx.coroutines.flow.MutableStateFlow(false)
        val running: kotlinx.coroutines.flow.StateFlow<Boolean> = _running

        const val ACTION_START = "magicau.mtkcontroller.action.APP_THERMAL_START"
        const val ACTION_STOP = "magicau.mtkcontroller.action.APP_THERMAL_STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AppThermalService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AppThermalService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: kotlinx.coroutines.Job? = null
    private var currentPkg: String? = null
    /** 熄屏时这个循环挂起，等亮屏广播再继续 —— 熄屏后前台应用不可能变。 */
    private val screenOnSignal = Channel<Unit>(Channel.CONFLATED)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) screenOnSignal.trySend(Unit)
        }
    }
    /** 规则表的当前版本；改了规则就让下一轮重新判断，不必重启服务。 */
    @Volatile private var rulesVersion = 0
    @Volatile private var rules: List<magicau.mtkcontroller.data.thermal.AppThermalRule> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _running.value = true
        registerReceiver(
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON),
            Context.RECEIVER_NOT_EXPORTED,
        )
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.app_thermal_notification_starting)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || AppExpiry.isExpired()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startLoop()
        // 不 sticky：进程死了就没有句柄可释放，静默重启只会基于过期状态重新下发。
        return START_NOT_STICKY
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            val container = (application as MtkApp).container
            val store = AppThermalStore(applicationContext)

            // 先保证自己不被冻掉。失败不阻断 —— 功能照样跑，只是被杀概率高些。
            if (!BatteryWhitelist.isWhitelisted(this@AppThermalService)) {
                AppLog.i(TAG, "本软件不在电池优化白名单，尝试加入")
                BatteryWhitelist.set(true)
            }

            // 规则热更新：运行中改规则不必重启服务。
            // 收集和轮询各用一个协程，收集只改这两个 @Volatile 字段。
            scope.launch {
                store.rules.collect { updated ->
                    val wasEmpty = rules.isEmpty()
                    rules = updated
                    rulesVersion++
                    if (updated.isEmpty()) {
                        AppLog.w(TAG, "规则已清空，服务停止")
                        stopSelf()
                    } else if (wasEmpty) {
                        AppLog.i(TAG, "开始按应用温控，${updated.size} 条规则，间隔 ${POLL_MS}ms")
                    }
                }
            }
            // 等第一次规则到达再开始轮询。
            while (isActive && rules.isEmpty() && rulesVersion == 0) delay(50)
            if (rules.isEmpty()) {
                stopSelf()
                return@launch
            }
            AppLog.i(TAG, "开始按应用温控，${rules.size} 条规则，间隔 ${POLL_MS}ms")

            var lastForeground = ""
            var seenVersion = rulesVersion
            while (isActive) {
                // 熄屏：先撤掉当前规则，然后挂起等亮屏。`dumpsys activity` 不再跑。
                if (!isScreenOn()) {
                    if (currentPkg != null) applyRule(null, null)
                    lastForeground = ""
                    screenOnSignal.receive()
                    continue
                }
                delay(POLL_MS)
                // 规则变了：强制重新判断当前前台应用，哪怕包名没变。
                if (rulesVersion != seenVersion) {
                    seenVersion = rulesVersion
                    lastForeground = ""
                }
                val snapshot = rules
                val pkg = ForegroundApp.current()
                // 读不到时保持上一状态，不要因为一次抖动就释放掉正在用的策略。
                if (pkg.startsWith("ERROR:")) {
                    if (pkg != lastForeground) AppLog.w(TAG, pkg)
                    lastForeground = pkg
                    continue
                }
                if (pkg == lastForeground) continue
                lastForeground = pkg

                val rule = snapshot.firstOrNull { it.pkg == pkg }
                if (rule == null) {
                    if (currentPkg != null) {
                        applyRule(null, null)
                        AppLog.i(TAG, "$pkg 不在规则表，释放温控策略")
                    }
                    continue
                }
                // 同一条规则重复命中不重发。
                if (currentPkg == pkg) continue
                AppLog.i(TAG, "$pkg 命中规则，温控策略索引 ${rule.policyIndex}")
                applyRule(pkg, rule.policyIndex)
            }
        }
    }

    /**
     * 把当前命中的规则告诉温控档位控制器，由它决定实际下发什么。
     *
     * **这里不再自己持句柄。** 按应用温控只是温控档位的来源之一，自己 acquire 会和
     * CPU 页、实验室抢同一个资源，而释放时只放自己那一把 —— 用户可能同时被两边持有，
     * 现象是"设了却没用"，而三处界面各自显示正常。控制器保证一次只有一把句柄，
     * 并在本条规则失效时把用户选的那一档放回去。
     */
    private suspend fun applyRule(pkg: String?, index: Int?) {
        val outcome = ThermalPolicyController.applyAppRule(index)
        currentPkg = pkg?.takeIf { outcome.success }
        if (!outcome.success) AppLog.w(TAG, "按应用温控下发失败")
        updateNotification()
    }

    private fun isScreenOn(): Boolean =
        getSystemService(android.os.PowerManager::class.java)?.isInteractive == true

    override fun onDestroy() {
        _running.value = false
        runCatching { unregisterReceiver(screenReceiver) }
        // 服务停下时把规则撤掉 —— 否则会留下一个没人释放的长期请求。
        // 必须 NonCancellable：下一行就 scope.cancel()，普通 launch 很可能还没开始跑，
        // 于是请求留在固件里，用户此后自己怎么设都不对。
        scope.launch {
            withContext(kotlinx.coroutines.NonCancellable) {
                ThermalPolicyController.applyAppRule(null)
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 通知里写**实际生效的那一档**，不是本条规则想要的那一档。
     *
     * 曾经直接把 `rule.policyIndex` 写进通知。但控制器的优先级是
     * 「用户刚做的决定 > 按应用规则」，用户那一档生效时本条规则会被让位 ——
     * 通知却照旧印规则里的编号，用户对着一个并没有生效的编号排查。
     * 反过来离开这个应用时也印过"未应用策略"，而用户那一档明明还在生效。
     *
     * 生效值只有一个来源：`ThermalPolicyController.state`。这里只是读它。
     */
    private fun updateNotification() {
        val applied = ThermalPolicyController.state.value.appliedIndex
        val pkg = currentPkg
        val text = when {
            pkg == null -> getString(R.string.app_thermal_notification_none)
            applied == null -> getString(R.string.app_thermal_notification_none)
            else -> getString(R.string.app_thermal_notification_applied, pkg, applied)
        }
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = android.app.PendingIntent.getActivity(
            this, 0, launch,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.app_thermal_channel_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_thermal_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

