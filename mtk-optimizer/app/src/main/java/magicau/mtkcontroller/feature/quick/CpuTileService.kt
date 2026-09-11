package magicau.mtkcontroller.feature.quick

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.MainActivity
import magicau.mtkcontroller.MtkApp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.AppExpiry
import magicau.mtkcontroller.data.powerhal.RequestOutcome

/**
 * 状态栏快捷开关：点一下释放 CPU 频率请求，再点一下按上一次范围恢复。
 *
 * **只切换频率请求**，不碰温控档位、实验室请求和 GPU。一次误触不应该把别的
 * 长期设置一起改掉；完整释放仍在 App 的 CPU 页。
 *
 * `onStartListening` 只读状态，不做任何写操作 —— 下拉状态栏不是执行动作的时机。
 */
class CpuTileService : TileService() {

    @Volatile
    private var busy = false

    private val container get() = (application as? MtkApp)?.container

    override fun onStartListening() {
        updateTile()
    }

    override fun onTileAdded() {
        updateTile()
    }

    override fun onClick() {
        val container = container ?: return
        if (busy) return
        busy = true

        container.applicationScope.launch {
            try {
                if (AppExpiry.isExpired()) {
                    openApp()
                    return@launch
                }
                val active = container.cpuControl.lastApplied().isNotEmpty()
                if (active) {
                    val outcome = container.disableCpuQuickControl()
                    toast(outcome, R.string.tile_released)
                } else {
                    if (container.cpuControl.lastRequested().isEmpty()) {
                        toastText(getString(R.string.tile_no_range))
                        openApp()
                    } else {
                        val outcome = container.enableCpuQuickControl()
                        toast(outcome, R.string.tile_applied)
                    }
                }
            } finally {
                busy = false
                updateTile()
            }
        }
    }

    private fun updateTile() {
        val container = container ?: return
        container.applicationScope.launch {
            val expired = AppExpiry.isExpired()
            val active = !expired && container.cpuControl.lastApplied().isNotEmpty()
            withContext(Dispatchers.Main) {
                val tile = qsTile ?: return@withContext
                tile.state = when {
                    expired -> Tile.STATE_UNAVAILABLE
                    active -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
                tile.subtitle = getString(if (active) R.string.tile_state_on else R.string.tile_state_off)
                tile.updateTile()
            }
        }
    }

    private suspend fun toast(outcome: RequestOutcome, successRes: Int) {
        val text = if (outcome.success) getString(successRes)
        else getString(R.string.tile_failed, outcome.message.resolve(this))
        toastText(text)
    }

    private suspend fun toastText(text: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@CpuTileService, text, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun openApp() {
        withContext(Dispatchers.Main) {
            val intent = Intent(this@CpuTileService, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this@CpuTileService,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
}
