package magicau.mtkcontroller.data.powerhal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import magicau.mtkcontroller.data.log.AppLog

/**
 * Owns exactly one PowerHAL handle, for one named purpose.
 *
 * PowerHAL requests do not replace each other — they accumulate, and
 * `libpowerhal` merges every live request (floor = max of floors, ceiling = min
 * of ceilings). A handle that is lost is a handle that can never be released,
 * and the only thing that clears it afterwards is the death of the process that
 * issued it. Every rule below exists because breaking it has already cost real
 * debugging time:
 *
 *  - release before acquire; abort the acquire if the release fails
 *  - never forget a handle whose release was not confirmed
 *  - serialise apply and release, so a background service and a screen cannot
 *    interleave into two live handles
 *
 * Each independent area of control (CPU frequency, uclamp, touch boost, …) gets
 * its own instance and its own stored handle, so touching one never re-sends
 * another.
 */
class PerfLockController(
    private val store: PerfHandlerStore,
    val name: String,
) {

    private companion object {
        const val TAG = "PerfLock"
    }

    data class Outcome(val success: Boolean, val message: String)

    private val mutex = Mutex()

    suspend fun handler(): Int = store.get(name)

    /**
     * Acquire a handle covering [pairs], releasing whatever this controller
     * held before.
     *
     * @param durationMs `0` means never expires — correct for a limit the user
     *   is deliberately holding. **Use a short non-zero value for anything
     *   experimental**, so a handle that goes missing cannot leave a permanent
     *   request behind.
     */
    suspend fun apply(pairs: List<Pair<String, String>>, durationMs: Int = 0): Outcome = mutex.withLock {
        if (pairs.isEmpty()) return@withLock Outcome(false, "没有要下发的资源")

        val previous = store.get(name)
        if (previous > 0) {
            val released = PowerHal.release(previous)
            if (released is PowerHal.Result.Failure) {
                AppLog.w(TAG, "[$name] 释放旧请求失败,放弃本次下发: ${released.message}")
                return@withLock Outcome(false, "无法释放上一次请求:${released.message}")
            }
            store.set(name, 0)
            AppLog.i(TAG, "[$name] 已释放旧请求 handler=$previous")
        }

        AppLog.i(
            TAG,
            "[$name] 下发: " + pairs.joinToString(", ") { "${it.first}=${it.second}" } +
                " (duration=${if (durationMs == 0) "无限" else "${durationMs}ms"})",
        )

        val result = PowerHal.acquire(pairs, durationMs)
        if (result is PowerHal.Result.Failure) {
            AppLog.e(TAG, "[$name] 下发失败: ${result.message}")
            return@withLock Outcome(false, result.message)
        }

        val newHandler = (result as PowerHal.Result.Success).handler
        store.set(name, newHandler)
        AppLog.i(TAG, "[$name] 已应用 handler=$newHandler")
        return@withLock Outcome(true, "已应用")
    }

    /**
     * Release the handle and report it honestly.
     *
     * The release transaction is oneway, so its return value says nothing about
     * whether the request actually went away. Callers that need certainty must
     * read the backing node back themselves.
     */
    suspend fun release(): Outcome = mutex.withLock {
        val current = store.get(name)
        if (current <= 0) {
            AppLog.w(TAG, "[$name] 释放:没有已记录的 handler")
            return@withLock Outcome(false, "没有可释放的请求")
        }

        val released = PowerHal.release(current)
        AppLog.i(TAG, "[$name] 释放请求 handler=$current -> ${released.message}")
        if (released is PowerHal.Result.Failure) return@withLock Outcome(false, released.message)

        store.set(name, 0)
        return@withLock Outcome(true, "已释放")
    }
}
