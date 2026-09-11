package magicau.mtkcontroller.feature.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import magicau.mtkcontroller.MainActivity
import magicau.mtkcontroller.MtkApp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.AppExpiry

/**
 * 保活：一个常驻前台服务，同时充当**CPU 控制的快捷面板**。
 *
 * 通知内容只保留一行：当前方案名 + 各簇上限 + 电池温度。点击左侧应用图标打开 App，
 * 点击右侧文字等同于点「应用 / 释放」；另外三个操作是「下一方案」「应用/释放」「结束」。
 * 任何操作完成后弹一条 Toast，通知本身只负责显示最新状态。
 *
 * **通知就是保活本身**：没有第二条“保活通知”。开着保活时这条通知常驻；
 * 关闭保活就一起消失。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 1006

        const val ACTION_NEXT_PROFILE = "magicau.mtkcontroller.KEEPALIVE_NEXT"
        const val ACTION_TOGGLE_CPU = "magicau.mtkcontroller.KEEPALIVE_TOGGLE"
        const val ACTION_STOP = "magicau.mtkcontroller.KEEPALIVE_STOP"
        const val EXTRA_ACTIVE = "keepalive_active"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, KeepAliveService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    /** 最近一次读到的通知偏好。首帧用默认值，随后由 refresh 更新。 */
    private var prefs = Prefs()
    /** 电池温度由粘性广播更新，不轮询。 */
    @Volatile private var lastTemp: Int? = null
    /** 熄屏时循环挂起，等亮屏再刷新。 */
    private val screenOnSignal = Channel<Unit>(Channel.CONFLATED)
    private var lastTitle: String? = null
    private var lastText: String? = null
    private var lastActive: Boolean? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths != Int.MIN_VALUE) lastTemp = tenths / 10
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) screenOnSignal.trySend(Unit)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED)
        startForeground(NOTIFICATION_ID, buildNotification(null, emptyList(), null, false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (AppExpiry.isExpired()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                // 用户从通知里停掉 = 关掉这个设置，否则下次启动又会自己回来。
                scope.launch {
                    (application as MtkApp).container.settingsRepository.setKeepAlive(false)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_NEXT_PROFILE -> scope.launch { nextProfile() }
            ACTION_TOGGLE_CPU -> scope.launch { toggleCpu() }
        }
        startLoop()
        // 保活服务被系统回收后应自己回来；用户主动停止走 ACTION_STOP。
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                // 用户在设置里关掉保活：服务自己退场，不留一个孤立的通知。
                val container = (application as MtkApp).container
                if (container.settingsRepository.keepAlive.first() == false) {
                    stopSelf()
                    return@launch
                }
                // 熄屏时不刷新 —— 通知看不见，CPU 频率也没有变化的意义。
                val power = getSystemService(PowerManager::class.java)
                if (power?.isInteractive != true) {
                    screenOnSignal.receive()
                    continue
                }
                refresh()
                delay(5_000)
            }
        }
    }

    private suspend fun refresh() {
        val container = (application as MtkApp).container
        prefs = readPrefs()
        val profiles = container.profileRepository.profiles.first()
        val activeId = container.profileRepository.activeProfileId.first()
        val activeProfile = profiles.firstOrNull { it.id == activeId }
        val applied = container.cpuControl.lastApplied()
        // 没有活动请求时用当前方案的配置做预览；操作按钮会显示「应用」。
        val effective = if (applied.isNotEmpty()) applied else activeProfile?.clusters.orEmpty()
        val maxes = effective.map { it.maxFreqKhz }
        notify(buildNotification(activeProfile?.name, maxes, lastTemp, applied.isNotEmpty()))
    }

    private suspend fun toggleCpu() {
        val container = (application as MtkApp).container
        val active = container.cpuControl.lastApplied().isNotEmpty()
        if (active) {
            val outcome = runCatching { container.disableCpuQuickControl() }.getOrNull()
            flash(
                if (outcome?.success == true) getString(R.string.keepalive_released)
                else getString(R.string.keepalive_release_failed),
            )
        } else {
            val outcome = runCatching {
                if (container.cpuControl.lastRequested().isNotEmpty()) {
                    container.enableCpuQuickControl()
                } else {
                    val id = container.profileRepository.activeProfileId.first()
                    if (id != null) container.applyProfileById(id) else null
                }
            }.getOrNull()
            when {
                outcome == null -> flash(getString(R.string.keepalive_no_profile))
                outcome.success -> flash(getString(R.string.keepalive_applied))
                else -> flash(getString(R.string.keepalive_apply_failed))
            }
        }
        refresh()
    }

    private suspend fun nextProfile() {
        val container = (application as MtkApp).container
        val result = runCatching { container.cycleProfile() }.getOrNull()
        if (result == null) {
            flash(getString(R.string.keepalive_no_profile))
            return
        }
        val (name, outcome) = result
        if (outcome.success) flash(getString(R.string.keepalive_switched, name))
        else flash(getString(R.string.keepalive_switch_failed, name))
        refresh()
    }

    private fun flash(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        scope.launch { refresh() }
    }

    private fun notify(notification: Notification) {
        val title = notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
        val text = notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
        val active = notification.extras.getBoolean(EXTRA_ACTIVE, false)
        if (title == lastTitle && text == lastText && active == lastActive) return
        lastTitle = title
        lastText = text
        lastActive = active
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        profileName: String?,
        maxFreqsKhz: List<Long>,
        tempC: Int?,
        active: Boolean,
    ): Notification {
        // 一行：模式名 + 各簇上限 + 温度。模式名不单独当加粗标题，
        // 它只是这一行里的第一个字段。
        val parts = mutableListOf<String>()
        // trim 后再判断：空方案名或带首尾空格的方案名都不能把整行顶出一个空格。
        if (prefs.showProfile) profileName?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
        if (prefs.showCpu && maxFreqsKhz.isNotEmpty()) {
            parts += maxFreqsKhz.joinToString("/") { "%.1f".format(it / 1_000_000.0) }
        }
        if (prefs.showTemp && tempC != null) parts += "$tempC℃"
        val line = parts.joinToString("   ").trim().ifBlank {
            getString(if (active) R.string.keepalive_idle else R.string.keepalive_no_control)
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = action(ACTION_TOGGLE_CPU, 2)
        val remote = RemoteViews(packageName, R.layout.notification_keepalive).apply {
            // 不画图标：系统通知自带 App 图标。只有文字区域可点，负责应用/释放；
            // 图标/其余内容走 contentIntent = 打开 App。
            setTextViewText(R.id.notif_line, line)
            setOnClickPendingIntent(R.id.notif_line, toggle)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(line)
            .setContentIntent(open)
            .setCustomContentView(remote)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_ACTIVE, active) })

        if (prefs.quickSwitch) {
            builder.addAction(0, getString(R.string.keepalive_next), action(ACTION_NEXT_PROFILE, 1))
        }
        builder.addAction(
            0,
            getString(if (active) R.string.keepalive_release else R.string.keepalive_apply),
            toggle,
        )
        builder.addAction(0, getString(R.string.keepalive_stop), action(ACTION_STOP, 3))
        return builder.build()
    }

    private fun action(action: String, code: Int): PendingIntent {
        val intent = Intent(this, KeepAliveService::class.java).setAction(action)
        return PendingIntent.getService(
            this, code, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keepalive_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.keepalive_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private suspend fun readPrefs(): Prefs {
        val s = (application as MtkApp).container.settingsRepository
        return Prefs(
            showProfile = s.notifShowProfile.first(),
            showCpu = s.notifShowCpu.first(),
            showTemp = s.notifShowTemp.first(),
            quickSwitch = s.notifQuickSwitch.first(),
        )
    }

    data class Prefs(
        val showProfile: Boolean = true,
        val showCpu: Boolean = true,
        val showTemp: Boolean = true,
        val quickSwitch: Boolean = true,
    )
}

/** 开机自启。只在用户开过保活时恢复服务。 */
class KeepAliveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as MtkApp
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val enabled = !AppExpiry.isExpired() &&
                runCatching { app.container.settingsRepository.keepAlive.first() }.getOrDefault(false)
            if (enabled) KeepAliveService.start(context)
        }
    }
}
