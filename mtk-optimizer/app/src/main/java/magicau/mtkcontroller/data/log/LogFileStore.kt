package magicau.mtkcontroller.data.log

import android.content.Context
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Append-only persistent log storage.
 *
 * ## Why this exists
 *
 * [AppLog] keeps everything in memory, so a process kill or a crash loses the
 * whole history — which is exactly the moment the log was needed. This store
 * writes every accepted entry to disk so the record survives:
 *
 *  - the app being killed in the background
 *  - a crash (see [flushNow], called from the crash handler)
 *  - the app being force-stopped days later, before the user exports anything
 *
 * ## Format
 *
 * One JSON object per line (JSONL). Field names are English and stable: the
 * exported report is parsed by tooling, not read as prose.
 *
 * ```json
 * {"ts":1759500000123,"lvl":"I","tag":"PowerHAL","msg":"acquire ok","pid":1,"tid":2}
 * ```
 *
 * ## Durability vs cost
 *
 * A crash loses at most what is still in the writer's buffer. The buffer is
 * flushed on every WARN/ERROR entry and at most [FLUSH_INTERVAL_MS] after any
 * other entry, so the common case loses nothing and heavy VERBOSE logging does
 * not turn into one fsync per line.
 *
 * ## Retention
 *
 * Files are rotated daily and trimmed oldest-first once the directory exceeds
 * [maxBytes]. The cap counts compressed size on disk, not entry count, because
 * that is what the user actually pays for.
 */
object LogFileStore {

    private const val TAG = "LogFileStore"

    /** Flush at most this long after a non-error entry. */
    private const val FLUSH_INTERVAL_MS = 2_000L

    /** Hard cap on total bytes under the log directory. */
    @Volatile
    var maxBytes: Long = DEFAULT_MAX_BYTES

    /**
     * 160 MiB.
     *
     * At roughly 150 bytes per entry that is about one million entries. For
     * normal INFO-level use that is months of history; at VERBOSE it is hours.
     * The cap is deliberately generous because the export path lets the user
     * hand over a specific window, and a trimmed window cannot be recovered.
     */
    const val DEFAULT_MAX_BYTES: Long = 160L * 1024 * 1024

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "log-file-store").apply { isDaemon = true }
    }

    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)

    private val lock = Any()
    private var dir: File? = null
    private var writer: FileOutputStream? = null
    private var openDay: String = ""
    private var pendingBytes: Long = 0
    private var lastFlushMs: Long = 0

    /** Files holding entries, oldest first. Deletable — see [trim]. */
    fun listFiles(context: Context): List<File> = logDir(context)
        .listFiles { f -> f.isFile && f.name.endsWith(FILE_SUFFIX) }
        ?.sortedBy { it.name }
        ?: emptyList()

    fun totalBytes(context: Context): Long = listFiles(context).sumOf { it.length() }

    /** Whole-disk path of the log directory, for the export fallback. */
    fun directoryPath(context: Context): String = logDir(context).absolutePath

    fun configure(context: Context) {
        synchronized(lock) { dir = logDir(context).apply { mkdirs() } }
    }

    /**
     * Append one entry. Never throws: a logging failure must not take down the
     * caller, so everything is caught and mirrored to logcat instead.
     */
    fun append(
        context: Context,
        entryLevel: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val now = System.currentTimeMillis()
        val line = runCatching { jsonLine(now, entryLevel, tag, message, throwable) }
            .getOrElse { return }
        val bytes = line.toByteArray()
        io.execute {
            synchronized(lock) {
                runCatching {
                    ensureOpen(context, now)
                    val stream = writer ?: return@runCatching
                    stream.write(bytes)
                    pendingBytes += bytes.size
                    // Errors are the entries most likely to be followed by a
                    // crash, so they go straight to disk.
                    val mustFlush = entryLevel.ordinal <= LogLevel.WARN.ordinal ||
                        now - lastFlushMs >= FLUSH_INTERVAL_MS
                    if (mustFlush) flushLocked()
                }.onFailure { Log.w(TAG, "append failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    /**
     * Force everything to disk. Called from the crash handler and before any
     * export, so neither path depends on the interval flush having fired.
     */
    fun flushNow() {
        io.execute { synchronized(lock) { runCatching { flushLocked() } } }
        // The crash path cannot wait for the executor: run one more flush inline
        // so the dying thread leaves the bytes on disk before it goes.
        synchronized(lock) { runCatching { flushLocked() } }
    }

    /** Enforce [maxBytes] by deleting whole days, oldest first. */
    fun trim(context: Context) = io.execute {
        runCatching {
            val files = listFiles(context)
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return@runCatching
            for (file in files) {
                if (total <= maxBytes) break
                val size = file.length()
                if (file.delete()) total -= size
            }
            if (total > maxBytes) Log.w(TAG, "log dir still over cap after trim: $total")
        }
    }

    /** Delete every entry file. Used by the "clear logs" action. */
    fun clear(context: Context) {
        io.execute { listFiles(context).forEach { runCatching { it.delete() } } }
    }

    // --- internals ---------------------------------------------------------

    private fun logDir(context: Context) = File(context.filesDir, "logs")

    private fun ensureOpen(context: Context, nowMs: Long) {
        val target = dir ?: logDir(context).apply { mkdirs(); dir = this }
        val day = dayFormat.format(Date(nowMs))
        if (writer != null && day == openDay) return
        writer?.let { runCatching { it.close() } }
        target.mkdirs()
        writer = FileOutputStream(File(target, "$FILE_PREFIX$day$FILE_SUFFIX"), true)
        openDay = day
        lastFlushMs = 0
    }

    private fun flushLocked() {
        writer?.let { runCatching { it.flush() } }
        lastFlushMs = System.currentTimeMillis()
        pendingBytes = 0
    }

    /**
     * One line of JSON. Field names are English and never change: the exported
     * report is machine-parsed, and a renamed field silently breaks the tooling
     * that reads older reports.
     */
    private fun jsonLine(
        nowMs: Long,
        entryLevel: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ): String {
        val obj = JSONObject()
        obj.put("ts", nowMs)
        obj.put("iso", isoFormat.format(Date(nowMs)))
        obj.put("lvl", entryLevel.name.first().toString())
        obj.put("tag", tag)
        obj.put("msg", message)
        obj.put("pid", Process.myPid())
        obj.put("tid", Process.myTid())
        if (throwable != null) {
            obj.put("stack", Log.getStackTraceString(throwable))
        }
        return obj.toString() + "\n"
    }

    private const val FILE_PREFIX = "log-"
    private const val FILE_SUFFIX = ".jsonl"
}
