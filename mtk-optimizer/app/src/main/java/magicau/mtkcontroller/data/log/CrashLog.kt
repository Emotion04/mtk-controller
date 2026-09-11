package magicau.mtkcontroller.data.log

import android.util.Log

/**
 * Records the last moments before a fatal crash.
 *
 * Without this, a crash is the one event the log cannot describe: the process
 * dies, the in-memory buffer goes with it, and the user reports "it just
 * closed". The handler writes a marker with the stack trace and forces a flush
 * *before* the default handler tears the process down.
 *
 * The report is then waiting on disk the next time the app opens, where the
 * user can export it.
 */
object CrashLog {

    private const val TAG = "CrashLog"

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLog.e(TAG, "FATAL on thread=${thread.name} ${throwable.javaClass.name}", throwable)
                AppLog.w(TAG, "process is terminating; flushing log to disk")
                // The dying thread cannot wait for the async writer, so this
                // flushes inline as well.
                LogFileStore.flushNow()
            }.onFailure { Log.w(TAG, "crash logging failed: ${it.javaClass.simpleName}") }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
