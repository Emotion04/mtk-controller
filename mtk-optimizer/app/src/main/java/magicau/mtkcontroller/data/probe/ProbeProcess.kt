package magicau.mtkcontroller.data.probe

import java.util.concurrent.TimeUnit

/** Always drains merged output, retaining bounded head and tail bytes. */
object ProbeProcess {
    data class Result(val output: String, val bytes: Long, val truncated: Boolean,
        val exit: Int?, val timedOut: Boolean, val error: String? = null) {
        val status: String get() = when {
            timedOut || output.contains("DUMP TIMEOUT", true) -> "TIMEOUT"
            error != null -> "IO_ERROR"
            output.contains("Permission denied", true) || output.contains("Permission Denial", true) -> "DENIED"
            output.contains("Can't find service", true) || output.contains("No such file", true) -> "UNAVAILABLE"
            exit != 0 -> "COMMAND_FAILED"
            output.isBlank() -> "EMPTY"
            truncated -> "TRUNCATED"
            else -> "OK"
        }
        fun render() = "status=$status exit=$exit timeout=$timedOut bytes=$bytes truncated=$truncated error=$error\n$output"
    }

    fun run(command: List<String>, timeoutMs: Long = 2500, limit: Int = 12000): Result {
        require(timeoutMs > 0 && limit >= 2)
        val process = try { ProcessBuilder(command).redirectErrorStream(true).start() }
            catch (e: Exception) { return Result("", 0, false, null, false, e.toString()) }
        val head = java.io.ByteArrayOutputStream()
        val tail = ByteArray(limit / 2)
        var count = 0L
        var readError: String? = null
        val guard = Any()
        val reader = Thread({
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        synchronized(guard) {
                            for (i in 0 until n) {
                                if (count < limit - tail.size) head.write(buffer[i].toInt())
                                else tail[((count - (limit - tail.size)) % tail.size).toInt()] = buffer[i]
                                count++
                            }
                        }
                    }
                }
            } catch (e: Exception) { synchronized(guard) { readError = e.toString() } }
        }, "probe-output").apply { isDaemon = true; start() }
        val ended = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ended) process.destroyForcibly()
        reader.join(500)
        if (reader.isAlive) synchronized(guard) { readError = "output pipe did not close" }
        return synchronized(guard) {
            val tailCount = (count - head.size()).coerceIn(0, tail.size.toLong()).toInt()
            val offset = if (count > limit) ((count - head.size()) % tail.size).toInt() else 0
            val end = ByteArray(tailCount) { tail[(offset + it) % tail.size] }
            val text = head.toByteArray().toString(Charsets.UTF_8) +
                (if (count > limit) "\n[... OUTPUT TRUNCATED; HEAD AND TAIL RETAINED ...]\n" else "") + end.toString(Charsets.UTF_8)
            Result(text, count, count > limit, if (ended) process.exitValue() else null, !ended, readError)
        }
    }
}
