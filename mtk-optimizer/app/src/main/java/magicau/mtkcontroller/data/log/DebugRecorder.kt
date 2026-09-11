package magicau.mtkcontroller.data.log

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * A user-driven recording session.
 *
 * ## What it actually does
 *
 * The log is written to disk continuously whether or not a session is running —
 * that is what makes a crash or a background kill survivable. A session does
 * two things on top of that:
 *
 *  1. **Raises the disk level** for its duration, so detail that is normally
 *     filtered out (probe results, per-step state) is captured while the user
 *     reproduces a problem.
 *  2. **Marks the window**, so the export can offer "since recording started"
 *     instead of making the user reason about timestamps.
 *
 * ## Surviving a kill
 *
 * The session start is persisted to disk, not held in memory. If the process
 * dies mid-recording the marker is still there on the next launch, and the
 * lines it covers are already on disk — so the recording is not lost, it is
 * just closed by the next launch instead of by the stop button.
 *
 * ## Why the default is WARN
 *
 * Recording at VERBOSE for a long window produces a large file and buries the
 * signal. The user asked for a session because something is wrong, so the
 * default captures failures; a developer chasing something subtler can raise it.
 */
class DebugRecorder(private val context: Context) {

    data class Session(
        val startedAtMs: Long,
        val level: LogLevel,
        /** True when the app was killed while recording and we only found out later. */
        val recovered: Boolean = false,
    )

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val file: File get() = File(context.filesDir, "diag/recording.json")

    /**
     * Close any session left behind by a crash, and surface it so the user can
     * export it. Called once at startup.
     */
    fun recoverInterrupted(): Session? {
        val saved = read()
        if (saved == null) return null
        // A marker on disk with no live session means the previous process died
        // while recording. Keep it as a recovered session: the log lines are
        // already on disk and are exactly what someone needs to look at.
        val recovered = saved.copy(recovered = true)
        _session.value = recovered
        AppLog.w(TAG, "上次录制被中断（进程被杀），日志仍然保留，可导出")
        return recovered
    }

    fun start(level: LogLevel = LogLevel.WARN) {
        val now = System.currentTimeMillis()
        _session.value = Session(now, level)
        write(Session(now, level))
        AppLog.setFileLevel(level)
        // 记一条显式标记：导出时按它定位窗口起点，不必让用户回忆时间。
        AppLog.w(TAG, "RECORDING START level=${level.name}")
    }

    fun stop() {
        val current = _session.value ?: return
        AppLog.w(TAG, "RECORDING STOP duration_ms=${System.currentTimeMillis() - current.startedAtMs}")
        AppLog.setFileLevel(LogLevel.INFO)
        _session.value = null
        write(null)
        LogFileStore.flushNow()
    }

    /** Dismiss a recovered session without exporting it. */
    fun discard() {
        _session.value = null
        write(null)
    }

    private fun read(): Session? = runCatching {
        if (!file.exists()) return@runCatching null
        val obj = JSONObject(file.readText())
        Session(
            startedAtMs = obj.getLong("started_at_ms"),
            level = LogLevel.fromName(obj.optString("level")),
        )
    }.getOrNull()

    private fun write(session: Session?) = runCatching {
        file.parentFile?.mkdirs()
        if (session == null) file.delete() else file.writeText(
            JSONObject().apply {
                put("started_at_ms", session.startedAtMs)
                put("level", session.level.name)
            }.toString(),
        )
    }.onFailure { AppLog.w(TAG, "录制状态写盘失败: ${it.javaClass.simpleName}") }

    private companion object {
        const val TAG = "DebugRecorder"
    }
}
