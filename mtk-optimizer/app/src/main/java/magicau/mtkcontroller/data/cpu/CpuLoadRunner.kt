package magicau.mtkcontroller.data.cpu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手动启停的满载负载，**必须在 App 进程里跑**。
 *
 * ## 为什么不能用 adb 的 busy loop
 *
 * 2026-09-24 实测：`adb shell 'taskset 80 sh -c "while :; do :; done" &'`
 * 起的进程 `Cpus_allowed_list` 确实是 `[7]`、`/proc/<pid>/stat` 状态是 `R`、
 * `utime` 在涨，**但 cpu7 频率纹丝不动停在 600MHz，`/proc/stat` 算出 cpu7 占用率只有 6%**。
 *
 * 原因：那些进程落在根 cpuset，而本 App 在 `/top-app`。根组的有效利用率被压到极低，
 * governor 于是按最低频跑。**同样的计算在 App 进程里跑就不会有这个问题。**
 *
 * 所以这是个"能不能测"的问题，不是"机器行不行"的问题 —— 用错误的负载去验证频率控制，
 * 会把好功能判成坏功能。本项目已经因此得出过错误结论，见
 * `docs/archive/cpu/frequency-ceiling-investigation-through-2026-09-26.md`。
 */
object CpuLoadRunner {

    private val running = AtomicBoolean(false)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(
        val active: Boolean = false,
        /** 已运行秒数；只在 active 时有意义。 */
        val seconds: Int = 0,
        /** 这次负载起了几个线程。 */
        val threads: Int = 0,
        /**
         * 这次负载占用的核编号（Linux CPU 号），顺序即线程顺序。
         *
         * **为什么先做底层**：单簇观察必须能把负载只压在某个簇上，否则 policy4 和
         * policy7 的结论会互相污染。界面选择留到重构时做，这里先能跑。
         */
        val cores: List<Int> = emptyList(),
    )

    private val threads = mutableListOf<Thread>()
    @Volatile private var sink = 0L

    /** 默认占满全部核。 */
    fun allCores(): List<Int> = (0 until Runtime.getRuntime().availableProcessors()).toList()

    /**
     * 开始满载。已在跑则忽略。
     *
     * @param cores 占用的核编号（Linux CPU 号）。默认全部。
     *   只给一个簇的核（如 `listOf(7)`）可以单簇观察，避免多簇结论互相污染。
     */
    fun start(cores: List<Int> = allCores()) {
        if (running.get()) return
        running.set(true)
        _state.value = State(active = true, seconds = 0, threads = 0)
        val picked = cores.distinct().sorted()
        picked.forEach { core ->
            threads += Thread({
                var value = core.toLong() + 1
                while (running.get()) {
                    repeat(4096) { value = (value xor (value shl 13)) * 6364136223846793005L + 1 }
                    sink = value
                }
            }, "cpu-load-$core").apply { isDaemon = true; priority = Thread.NORM_PRIORITY; start() }
        }
        _state.value = _state.value.copy(threads = picked.size, cores = picked)
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        threads.forEach { it.join(500) }
        threads.clear()
        _state.value = State()
    }

    /** 每秒由调用方调一次，只更新计数。 */
    fun tick() {
        if (!running.get()) return
        _state.value = _state.value.copy(seconds = _state.value.seconds + 1)
    }
}
