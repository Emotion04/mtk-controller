package magicau.mtkcontroller.feature.thermal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import magicau.mtkcontroller.MainActivity
import magicau.mtkcontroller.MtkApp

/**
 * Keeps the screen from staying dimmed after the SoC cools down.
 *
 * Android lowers `screen_brightness` when the device gets hot. That reduction
 * is not always undone once the thermal state recovers, leaving the screen
 * noticeably darker than the user asked for.
 *
 * Detection is event-driven rather than polled: a [ContentObserver] on
 * `Settings.System.SCREEN_BRIGHTNESS` fires the instant the system changes the
 * value, which is what the root-era modules achieve by watching the backlight
 * node with inotify. A slow timer still runs so the "desired" brightness is
 * refreshed after thermal recovery.
 *
 * The brightness read while the device is cool is treated as "what the user
 * wants"; writes only happen while the device is hot, so a deliberate manual
 * change is never fought.
 */
class ThermalBrightnessService : Service() {

    companion object {
        private const val CHANNEL_ID = "thermal_brightness"
        private const val NOTIFICATION_ID = 1001
        private const val COOL_POLL_INTERVAL_MS = 15_000L

        /** Ignore small deltas so we don't fight the slider. */
        private const val RESTORE_THRESHOLD = 8

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ThermalBrightnessService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ThermalBrightnessService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var preferredBrightness: Int? = null
    private var lastRestoreAt = 0L

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            handleBrightnessChanged()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        preferredBrightness = readBrightness()
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            observer,
        )

        val container = (application as MtkApp).container
        scope.launch {
            // Slow heartbeat: while cool, keep tracking what the user actually wants.
            while (isActive) {
                if (!isThermallyHot()) {
                    readBrightness()?.let { preferredBrightness = it }
                }
                delay(COOL_POLL_INTERVAL_MS)
            }
        }
    }

    private fun handleBrightnessChanged() {
        val wanted = preferredBrightness ?: return
        val current = readBrightness() ?: return

        if (!isThermallyHot()) {
            // Cool: this change is the user's own doing, remember it.
            preferredBrightness = current
            return
        }

        if (current < wanted - RESTORE_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastRestoreAt < 1_500) return // avoid write storms
            lastRestoreAt = now
            val container = (application as MtkApp).container
            scope.launch { container.tweakRepository.setBrightness(wanted) }
        }
    }

    private fun readBrightness(): Int? = runCatching {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrNull()

    private fun isThermallyHot(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java) ?: return false
        return powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(observer) }
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "高温降亮度恢复",
            NotificationManager.IMPORTANCE_MIN,
        ).apply { description = "在设备过热被系统压低亮度后恢复亮度" }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("高温降亮度恢复已开启")
            .setContentText("设备过热时会自动恢复屏幕亮度")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(intent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
