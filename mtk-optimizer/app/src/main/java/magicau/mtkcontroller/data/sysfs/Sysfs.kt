package magicau.mtkcontroller.data.sysfs

import magicau.mtkcontroller.data.privilege.PrivilegeManager
import java.io.File

/** Ownership/mode of a node, used to decide whether we may write to it. */
data class NodePerm(
    val mode: String,
    val owner: String,
    val group: String,
) {
    /** True when the mode string grants write to owner or group or other. */
    val writable: Boolean
        get() {
            val bits = mode.trim().removePrefix("0")
            // Normalise "644" / "0644" / "rw-r--r--" style input to a 3-4 digit octal.
            return when {
                bits.length >= 3 && bits.all { it in '0'..'7' } -> {
                    val g = bits.takeLast(3)
                    g[0] in '2'..'7' || g[1] in '2'..'7' || g[2] in '2'..'7'
                }

                else -> false
            }
        }
}

/**
 * Small helper over the kernel's text-file interfaces.
 *
 * Reads go through plain File I/O first — most cpufreq/procfs nodes are
 * world-readable, so no privilege is needed and it is far cheaper than a
 * shell round-trip. Writes always go through the elevated shell.
 */
object Sysfs {

    private const val TAG_EXIT = "[exit]"

    /** Direct read from the app process. Returns null when unreadable. */
    fun read(path: String): String? = runCatching {
        File(path).takeIf { it.canRead() }?.readText()?.trim()
    }.getOrNull()

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

    /** stat(1) a node to learn who owns it and whether write bits are set. */
    suspend fun stat(path: String): NodePerm? {
        val out = PrivilegeManager.exec("stat -c '%a %U %G' '${escape(path)}' 2>/dev/null")
            ?: return null
        val line = out.substringBefore(TAG_EXIT).trim().lines().firstOrNull()?.trim().orEmpty()
        if (line.isEmpty()) return null
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 3) return null
        return NodePerm(mode = parts[0], owner = parts[1], group = parts[2])
    }

    private fun escape(raw: String) = raw.replace("'", "'\\''")
}
