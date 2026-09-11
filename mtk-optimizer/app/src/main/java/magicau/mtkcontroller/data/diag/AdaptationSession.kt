package magicau.mtkcontroller.data.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import magicau.mtkcontroller.data.lab.LabController
import magicau.mtkcontroller.data.log.AppLog

/**
 * 适配测试会话。**比页面活得久。**
 *
 * 用户点开始后要离开诊断页去 CPU 或实验室复现问题，再回来点结束。
 * 如果状态挂在 `ExportViewModel` 上，返回时页面被弹出、ViewModel 被清掉，
 * 会话就丢了：界面重新显示“开始”，盲测开关也被关掉。
 *
 * 所以状态放在这个单例里，诊断页只读取和发起动作。进程被杀时会话自然结束，
 * 盲测旗标也随进程消失。
 */
object AdaptationSession {

    data class State(
        val running: Boolean = false,
        val startedAtMs: Long? = null,
        /** 本次会话是否允许无回读下发。 */
        val blind: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setBlind(enabled: Boolean) {
        if (_state.value.running) return
        _state.value = _state.value.copy(blind = enabled)
    }

    fun start() {
        if (_state.value.running) return
        val blind = _state.value.blind
        LabController.blindWritesEnabled = blind
        AppLog.i(TAG, "适配测试开始 blind=$blind")
        _state.value = State(running = true, startedAtMs = System.currentTimeMillis(), blind = blind)
    }

    /** 结束并返回本次会话的毫秒数；没在跑时返回 null。 */
    fun stop(): Long? {
        val started = _state.value.startedAtMs ?: return null
        if (!_state.value.running) return null
        val elapsed = System.currentTimeMillis() - started
        LabController.blindWritesEnabled = false
        AppLog.i(TAG, "适配测试结束 用时 ${elapsed / 1000}s")
        _state.value = State(running = false, startedAtMs = null, blind = _state.value.blind)
        return elapsed
    }

    private const val TAG = "Adapt"
}
