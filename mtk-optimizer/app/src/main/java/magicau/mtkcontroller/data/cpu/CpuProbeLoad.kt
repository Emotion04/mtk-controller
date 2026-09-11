package magicau.mtkcontroller.data.cpu

import java.util.concurrent.atomic.AtomicBoolean

/** Ordinary app workload, without affinity or vendor hints. Independent finite deadline. */
class CpuProbeLoad(private val durationMs: Long = 20_000) : AutoCloseable {
    private val running = AtomicBoolean(true)
    @Volatile private var sink = 0L
    private val deadline = System.nanoTime() + durationMs * 1_000_000
    private val threads = mutableListOf<Thread>()

    init {
        try {
            repeat(Runtime.getRuntime().availableProcessors().coerceIn(1, 8)) { index ->
                threads += Thread({
                    var value = index.toLong() + 1
                    while (running.get() && System.nanoTime() < deadline) {
                        repeat(4096) { value = (value xor (value shl 13)) * 6364136223846793005L + 1 }
                        sink = value
                    }
                }, "cpu-probe-$index").apply { isDaemon = true; start() }
            }
        } catch (e: Throwable) { close(); throw e }
    }

    override fun close() {
        running.set(false)
        threads.forEach { it.join() }
    }
}

/** /proc/stat guest columns are already included in user/nice. Never count them twice. */
class CpuBusySampler {
    private var previous = emptyMap<String, List<Long>>()

    fun sample(raw: String?): String {
        val current = raw.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (!parts.first().matches(Regex("cpu[0-9]*")) || parts.size < 9) return@mapNotNull null
            val ticks = parts.drop(1).take(8).map { it.toLongOrNull() ?: return@mapNotNull null }
            parts[0] to ticks
        }.toMap()
        val result = current.map { (cpu, ticks) ->
            val old = previous[cpu]
            val delta = old?.let { ticks.zip(it) { now, before -> now - before } }
            val total = delta?.sum() ?: 0
            val percent = if (delta == null || delta.any { it < 0 } || total <= 0) "?"
                else "${100 * (total - delta[3] - delta[4]) / total}%"
            "$cpu=$percent"
        }.joinToString(" ")
        previous = current
        return result.ifEmpty { "不可读（不代表空闲）" }
    }
}
