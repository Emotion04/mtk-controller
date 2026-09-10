package magicau.mtkcontroller.data.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Severity, ordered. A level admits itself and everything above it. */
enum class LogLevel(val label: String, val description: String) {
    VERBOSE("详细", "记录全部细节,含每次探测"),
    DEBUG("调试", "记录探测结果与状态变化"),
    INFO("信息", "只记录关键操作,默认"),
    WARN("警告", "只记录异常与失败"),
    ERROR("错误", "只记录错误"),
    ;

    companion object {
        fun fromName(name: String?): LogLevel =
            entries.firstOrNull { it.name == name } ?: INFO
    }
}

data class LogEntry(
    /** Monotonic, so it is a stable list key even within the same millisecond. */
    val id: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    /** "14:03:22.481  I  PowerHAL  调频已应用" */
    fun format(): String = buildString {
        append(timeOf(timestampMs))
        append("  ")
        append(level.name.first())
        append("  ")
        append(tag)
        append("  ")
        append(message)
    }

    private fun timeOf(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = (totalSeconds / 3600) % 24
        val m = (totalSeconds / 60) % 60
        val s = totalSeconds % 60
        val millis = ms % 1000
        return "%02d:%02d:%02d.%03d".format(h, m, s, millis)
    }
}

/**
 * Small in-app ring buffer, so the app can show its own diagnostics without
 * asking the user to attach a PC and read logcat.
 *
 * Deliberately not a logging framework: everything is in memory, bounded, and
 * mirrored to logcat. Nothing is written to disk.
 */
object AppLog {

    private const val CAPACITY = 500

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(CAPACITY)
    private var nextId = 0L

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Volatile
    private var level: LogLevel = LogLevel.INFO

    @Volatile
    private var mirrorToLogcat: Boolean = true

    fun setLevel(newLevel: LogLevel) {
        level = newLevel
        // A level change is itself worth recording, at the new level.
        i("AppLog", "日志级别设为 ${newLevel.label}")
    }

    fun currentLevel(): LogLevel = level

    fun setMirrorToLogcat(enabled: Boolean) {
        mirrorToLogcat = enabled
    }

    fun v(tag: String, message: String) = log(LogLevel.VERBOSE, tag, message)
    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, tag, throwable?.let { "$message: ${it.javaClass.simpleName}: ${it.message}" } ?: message)

    fun log(entryLevel: LogLevel, tag: String, message: String) {
        if (entryLevel.ordinal < level.ordinal) return

        if (mirrorToLogcat) {
            val line = "[$tag] $message"
            when (entryLevel) {
                LogLevel.VERBOSE -> Log.v("MtkGod", line)
                LogLevel.DEBUG -> Log.d("MtkGod", line)
                LogLevel.INFO -> Log.i("MtkGod", line)
                LogLevel.WARN -> Log.w("MtkGod", line)
                LogLevel.ERROR -> Log.e("MtkGod", line)
            }
        }

        val entry = LogEntry(
            id = nextId++,
            timestampMs = System.currentTimeMillis(),
            level = entryLevel,
            tag = tag,
            message = message,
        )
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }
}
