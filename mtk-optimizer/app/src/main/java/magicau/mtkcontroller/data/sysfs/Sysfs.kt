package magicau.mtkcontroller.data.sysfs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import java.io.File

/**
 * Small helper over the kernel's text-file interfaces.
 *
 * Reads go through plain File I/O first — most cpufreq/procfs nodes are
 * world-readable, so no privilege is needed and it is far cheaper than a
 * shell round-trip. Writes always go through the elevated shell.
 */
object Sysfs {

    private const val TAG_EXIT = "[exit]"

    /** Announces each file in a batched read. */
    private const val MARKER = "@@@node "

    /**
     * Direct read from the app process. Returns null when unreadable.
     *
     * Suspends onto IO because these are real kernel file reads: on the main
     * thread a handful of them per frame is enough to drop frames.
     */
    suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            File(path).takeIf { it.canRead() }?.readText()?.trim()
        }.getOrNull()
    }

    /**
     * Read many nodes in one go.
     *
     * Direct reads first; whatever failed is fetched in a **single** elevated
     * call. The obvious loop — read, fall back to the shell, read the next —
     * spawns a process per unreadable node, and on a device that lacks most of
     * the nodes an exploratory screen probes, that is dozens of process spawns
     * per visit. That is expensive enough to be felt as stutter everywhere, not
     * just on that screen.
     *
     * Returns null for a path that could not be read by either route.
     */
    suspend fun readMany(paths: List<String>): Map<String, String?> = withContext(Dispatchers.IO) {
        val result = paths.distinct().associateWith { path ->
            runCatching { File(path).takeIf { it.canRead() }?.readText()?.trim() }.getOrNull()
        }.toMutableMap()

        val missing = result.filterValues { it == null }.keys.toList()
        if (missing.isEmpty()) return@withContext result

        // One script, one process. Each block is announced by a marker line so
        // the values can be told apart without relying on their contents.
        val script = missing.joinToString("; ") { path ->
            "echo '$MARKER$path'; cat '${escape(path)}' 2>/dev/null"
        }
        val out = PrivilegeManager.exec(script) ?: return@withContext result

        var current: String? = null
        val buffer = StringBuilder()
        fun flush() {
            val key = current ?: return
            result[key] = buffer.toString().trim().ifBlank { null }
            buffer.clear()
        }
        out.substringBefore(TAG_EXIT).lines().forEach { line ->
            if (line.startsWith(MARKER)) {
                flush()
                current = line.removePrefix(MARKER).trim()
            } else if (current != null) {
                if (buffer.isNotEmpty()) buffer.appendLine()
                buffer.append(line)
            }
        }
        flush()
        result
    }

    /** Read through the elevated shell (for nodes our own uid cannot open). */
    suspend fun readElevated(path: String): String? {
        val out = PrivilegeManager.exec("cat '${escape(path)}' 2>/dev/null") ?: return null
        return out.substringBefore(TAG_EXIT).trim().ifBlank { null }
    }

    suspend fun exists(path: String): Boolean {
        val out = PrivilegeManager.exec("test -e '${escape(path)}' && echo yes || echo no")
        return out?.substringBefore(TAG_EXIT)?.trim() == "yes"
    }

    /** Write a value through the elevated shell. Returns true on success. */
    suspend fun write(path: String, value: String): Boolean {
        val cmd = "echo '${escape(value)}' > '${escape(path)}'"
        val out = PrivilegeManager.exec("$cmd 2>&1") ?: return false
        val body = out.substringBefore(TAG_EXIT)
        return body.isBlank()
    }

    /** List a directory via the elevated shell. */
    suspend fun list(path: String): List<String> {
        val out = PrivilegeManager.exec("ls -1 '${escape(path)}' 2>/dev/null") ?: return emptyList()
        return out.substringBefore(TAG_EXIT).lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Decide whether we can really write [path] by writing its own current
     * value back to it.
     *
     * Reading the mode bits is not a reliable proxy: a node can be mode 664
     * root:system and still be unwritable for us once SELinux and the actual
     * group membership are accounted for. Only an attempted write tells the
     * truth. Writing the value the file already holds is a no-op for every
     * cpufreq node we probe — `store_scaling_governor` returns early when the
     * requested governor is the active one — so this is safe to call.
     *
     * Returns false when the node is unreadable or the write is rejected.
     */
    suspend fun probeWritable(path: String): Boolean {
        val current = readElevated(path)?.trim()?.lineSequence()?.firstOrNull()?.trim()
        if (current.isNullOrEmpty()) return false
        return write(path, current)
    }

    private fun escape(raw: String) = raw.replace("'", "'\\''")
}
