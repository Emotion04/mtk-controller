package magicau.mtkcontroller.data.log

import android.content.Context
import android.util.Log
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Severity, ordered. A level admits itself and everything above it. */
enum class LogLevel(val labelRes: Int, val descriptionRes: Int) {
    VERBOSE(R.string.log_level_verbose, R.string.log_level_verbose_desc),
    DEBUG(R.string.log_level_debug, R.string.log_level_debug_desc),
    INFO(R.string.log_level_info, R.string.log_level_info_desc),
    WARN(R.string.log_level_warn, R.string.log_level_warn_desc),
    ERROR(R.string.log_level_error, R.string.log_level_error_desc),
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
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date(ms))
    }
}

/**
 * Small in-app ring buffer, so the app can show its own diagnostics without
 * asking the user to attach a PC and read logcat.
 *
 * Not a general-purpose logging framework. Two destinations, two purposes:
 *
 *  - **memory** (this ring buffer) — feeds the in-app log screen, bounded, cheap
 *  - **disk** ([LogFileStore]) — append-only JSONL, survives a kill or a crash,
 *    and is what the exported report is built from
 *
 * The UI level and the disk level are separate: the screen can be turned down to
 * cut noise without losing the history an adaptation report depends on.
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

    /**
     * 落盘的级别门槛，与界面显示的 [level] 相互独立。
     *
     * 分开是必须的：界面可以只看 WARN 图个清爽，但落盘要保留 INFO 以上，
     * 否则用户几天后导出报告时，之前发生过什么已经无从查证 —— 而那份历史
     * 正是适配新机型时最有价值的部分。
     *
     * 调试录制会话会把它临时降到 VERBOSE，结束后恢复。
     */
    @Volatile
    private var fileLevel: LogLevel = LogLevel.INFO

    @Volatile
    private var appContext: Context? = null

    /** 由 Application 调用一次。未接上时只记内存，不影响功能。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
        LogFileStore.configure(context)
        LogFileStore.trim(context)
    }

    /**
     * 级别名，日志正文用。
     *
     * 取资源而不是枚举名 —— 日志页面是给用户看的，`VERBOSE` 不如「详细」。
     * `attach` 之前拿不到 Context，那时退回枚举名：它至少是明确的，不会错。
     */
    private fun levelText(level: LogLevel): String =
        appContext?.getString(level.labelRes) ?: level.name

    /**
     * 把控制层的结论解析成日志正文。
     *
     * 控制层（`PowerHal`、`PerfLockController`、`CpuControl`）都拿不到 Context，
     * 而它们的结论既要给界面又要写日志。这里借 `AppLog` 自己的 Context 解析 ——
     * 这就是它持有一个 `appContext` 的第二个用途。
     *
     * `attach` 之前（极早期启动）只能退到资源编号，那种日志本身也可疑，
     * 宁可难看也不要假装解析成功。
     */
    fun text(message: Message): String =
        appContext?.let { message.resolve(it) } ?: "[msg#${message.res}]"

    /** 落盘门槛。调试录制用来临时放大记录范围。 */
    fun setFileLevel(newLevel: LogLevel) {
        fileLevel = newLevel
        i("AppLog", "落盘级别设为 ${levelText(newLevel)}")
    }

    fun currentFileLevel(): LogLevel = fileLevel

    fun setLevel(newLevel: LogLevel) {
        level = newLevel
        // A level change is itself worth recording, at the new level.
        i("AppLog", "日志级别设为 ${levelText(newLevel)}")
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
        // **界面可见和落盘是两个独立门槛，不能合成一个提前 return。**
        //
        // 曾经在这里写 `if (entryLevel.ordinal < level.ordinal) return`，于是用户把
        // 界面日志级别调到 ERROR 之后，WARN 连盘也不落 —— 而 :181 的注释说的正是
        // "落盘按自己的门槛判断，与界面级别无关"。代码和自己的注释相反。
        //
        // 后果不只是文档不符：`Failure.from` 靠 WARN 落盘来保证"失败在导出的报告里
        // 查得到"。那道保证被这个 return 静默击穿，而且只在用户调高级别之后才现形。
        val visible = entryLevel.ordinal >= level.ordinal

        if (mirrorToLogcat && visible) {
            val line = "[$tag] $message"
            when (entryLevel) {
                LogLevel.VERBOSE -> Log.v("MtkGod", line)
                LogLevel.DEBUG -> Log.d("MtkGod", line)
                LogLevel.INFO -> Log.i("MtkGod", line)
                LogLevel.WARN -> Log.w("MtkGod", line)
                LogLevel.ERROR -> Log.e("MtkGod", line)
            }
        }

        if (visible) synchronized(lock) {
            val entry = LogEntry(
                id = nextId++,
                timestampMs = System.currentTimeMillis(),
                level = entryLevel,
                tag = tag,
                message = message,
            )
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }

        // 落盘按自己的门槛判断，与界面级别无关 —— 见 [fileLevel]。
        // 没有 context（单元测试、Application 尚未 attach）时静默跳过。
        if (entryLevel.ordinal >= fileLevel.ordinal) {
            appContext?.let { LogFileStore.append(it, entryLevel, tag, message) }
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }
}
