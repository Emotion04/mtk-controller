package magicau.mtkcontroller.data.powerhal

import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.powerhal.RequestOutcome
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
 * ## 为什么这里是"先放后拿"，而温控档位是"先拿后放"
 *
 * **别看到另一个类顺序相反就顺手统一。** 两种顺序各自在防不同的失败，
 * 换一个就是引入一个现在没有的 bug：
 *
 * 先放后拿（这里）—— 防的是**两把同时生效**。频率是区间，合并规则有文档
 * （floor 取最大、ceiling 取最小），两把叠在一起的中间态是两者**交集**，
 * 用户看到"设了 2.0 G 却只给 1.5 G"。代价是中间有一小段"无请求"，
 * 对频率意味着不受限 —— 无害。
 *
 * 先拿后放（`ThermalPolicyController`）—— 真正的理由只有一个，就是失败路径：
 *
 * 先拿时如果新句柄没拿到，旧的还在，用户原设置原样保留；先放时一旦放成功而随后
 * 拿失败，就变成"什么都没有"。**这是唯一站得住的理由。**
 *
 * 曾经还给先拿后放配了第二个理由 —— "无请求时设备回落到默认温度阶梯，频率被压一下，
 * 用户看到切档时卡了一下"。那句话从初版提交起就在，**是设计期推断，没有实测依据**，
 * 已删。真实情况是两种顺序的中间态都不可推理，而且不可推理的方式不同：
 * 先拿后放是两把并存、固件选哪把无文档；先放后拿是 200 ms 的 `thermal.conf` 默认
 * （cluster2 首档 3100000），往保守档切时反而比目标档更激进。详见
 * `docs/dev/architecture.md` 的温控节。
 *
 * 本类的这个劣势已经补上了（[restoreLastRequest]），`ThermalPolicyController` 还没有。
 * 等那边也补上，两种顺序就只剩中间态差异，那时才可以重新比较 —— 而且要带真机验证。
 *
 * 它敢先拿，是因为温控档位是**单值索引**而不是区间：两把同时生效时固件选哪一把
 * 没有文档，但至少不会算出交集那种可推导的错误值。而它拿到新句柄后**立刻**放旧的，
 * 两把并存的窗口只有一趟 binder。
 *
 * ## 先放后拿的代价：放成功、拿失败 = 两手空空（已修）
 *
 * 这是本类顺序**唯一**的坏路径：旧句柄放掉了，`store` 记成 0，随后 acquire 失败，
 * 于是什么请求都没有，用户原来的限制也没了。现在 [applyLocked] 在失败分支调
 * [restoreLastRequest]，把上一次成功下发的请求原样放回去，消息说清
 * "本次设置未生效，已保留原设置"。
 *
 * **不重试，这是决定。** `PowerHal.acquire` 的三种失败 —— 提权服务没连、
 * binder 事务失败、固件回了 0 或负数 —— 没有一种是等 200 ms 就能好的，
 * 重试只是把坏消息推迟，还会把"固件在拒绝我们"这件事盖住。
 * 恢复上一份请求救的是"旧请求已经被放掉了"这个既成事实，和重试是两回事。
 *
 * 局限：恢复只在本进程内有效。句柄的寿命就是进程寿命（进程死掉时 PowerHAL 才清请求），
 * 重启后 `lastRequest` 是 null，行为和修之前一样 —— 不会更糟。
 *
 * Each independent area of control (CPU frequency, uclamp, touch boost, …) gets
 * its own instance and its own stored handle, so touching one never re-sends
 * another.
 */
class PerfLockController(
    private val store: PerfHandleStorage,
    val name: String,
    private val acquireRequest: suspend (List<Pair<String, String>>, Int) -> PowerHal.Result = PowerHal::acquire,
    private val releaseRequest: suspend (Int) -> PowerHal.Result = PowerHal::release,
) {

    private companion object {
        const val TAG = "PerfLock"

        /**
         * Gap between a oneway release and the acquire that follows it.
         *
         * Oneway and synchronous binder transactions to the same target are not
         * guaranteed to be processed in submission order.
         */
        const val RELEASE_SETTLE_MS = 200L
    }

    /**
     * 上一次**成功下发**的请求。[applyLocked] 在 acquire 失败时用它把原设置放回去。
     *
     * 为什么必须在内存里而不是持久化：句柄的寿命就是进程寿命 ——
     * PowerHAL 在持有请求的进程死掉时才清掉它。进程都没了还记着要恢复什么，
     * 恢复出来也是一个没人持有的句柄。和 [store] 里那个数字同一套寿命。
     */
    private data class HeldRequest(val pairs: List<Pair<String, String>>, val durationMs: Int)

    private val mutex = Mutex()

    /** null = 没有可恢复的（从没成功过，或用户刚释放过）。只在 mutex 内读写。 */
    private var lastRequest: HeldRequest? = null

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
    suspend fun apply(pairs: List<Pair<String, String>>, durationMs: Int = 0): RequestOutcome = mutex.withLock {
        withContext(NonCancellable) { applyLocked(pairs, durationMs) }
    }

    private suspend fun applyLocked(pairs: List<Pair<String, String>>, durationMs: Int): RequestOutcome {
        if (pairs.isEmpty()) return RequestOutcome(false, Message(R.string.lock_no_resources))

        val previous = store.get(name)
        if (previous > 0) {
            val released = releaseRequest(previous)
            if (released is PowerHal.Result.Failure) {
                AppLog.w(TAG, "[$name] 释放旧请求失败,放弃本次下发: ${AppLog.text(released.message)}")
                return RequestOutcome(
                    false,
                    Message(R.string.lock_release_previous_failed, listOf(released.message)),
                    detail = released.detail,
                )
            }
            store.set(name, 0)
            AppLog.d(TAG, "[$name] 已提交旧请求释放 handler=$previous")

            // Oneway release is queued. This gap reduces overlap but is not an acknowledgment.
            delay(RELEASE_SETTLE_MS)
        }

        // INFO 而不是 DEBUG：下发了哪些 (opcode, value) 是判断"为什么没生效"的唯一依据，
        // 而默认日志级别是 INFO，用 d 会被整体滤掉 —— 于是出了问题时日志里什么都没有。
        AppLog.i(
            TAG,
            "[$name] 下发: " + pairs.joinToString(", ") { "${it.first}=${it.second}" } +
                " (duration=${if (durationMs == 0) "无限" else "${durationMs}ms"})",
        )

        val result = acquireRequest(pairs, durationMs)
        if (result is PowerHal.Result.Failure) {
            AppLog.e(TAG, "[$name] 下发失败: ${AppLog.text(result.message)}")
            // 走到这里旧请求已经放掉了。两手空空比失败本身更难恢复 ——
            // 用户原来的限制没了，而且不容易发现怎么回去。把上一份原样放回去。
            val restored = restoreLastRequest()
            return if (restored != null) {
                RequestOutcome(
                    false,
                    Message(R.string.lock_apply_failed_restored),
                    detail = result.detail,
                )
            } else {
                RequestOutcome(false, result.message, detail = result.detail)
            }
        }

        val newHandler = (result as PowerHal.Result.Success).handler
        store.set(name, newHandler)
        lastRequest = HeldRequest(pairs, durationMs)
        AppLog.d(TAG, "[$name] 请求已接受 handler=$newHandler")
        return RequestOutcome(true, Message(R.string.lock_accepted))
    }

    /**
     * 把上一次成功下发的请求原样放回去。成功返回新句柄，否则 null。
     *
     * 只在 [applyLocked] 的失败分支调用。**不重试本次请求** —— 三种失败模式
     * （提权服务没连、binder 事务失败、固件回了 0 或负数）没有一种是等 200 ms
     * 就能好的，重试只是把坏消息推迟。这里救的是"旧请求已经被放掉了"这个既成事实。
     */
    private suspend fun restoreLastRequest(): Int? {
        val held = lastRequest ?: run {
            AppLog.w(TAG, "[$name] 没有可恢复的上一份请求（从未成功过，或已释放）")
            return null
        }
        AppLog.w(TAG, "[$name] 恢复上一份请求（${held.pairs.size} 对资源）")
        val back = acquireRequest(held.pairs, held.durationMs)
        val handler = (back as? PowerHal.Result.Success)?.handler ?: 0
        if (handler <= 0) {
            AppLog.e(TAG, "[$name] 恢复也失败: ${AppLog.text(back.message)}")
            return null
        }
        store.set(name, handler)
        AppLog.i(TAG, "[$name] 已恢复上一份请求 handler=$handler")
        return handler
    }

    /**
     * Release the handle and report it honestly.
     *
     * The release transaction is oneway, so its return value says nothing about
     * whether the request actually went away. Callers that need certainty must
     * read the backing node back themselves.
     */
    suspend fun release(): RequestOutcome = mutex.withLock {
        withContext(NonCancellable) { releaseLocked() }
    }

    private suspend fun releaseLocked(): RequestOutcome {
        val current = store.get(name)
        if (current <= 0) {
            AppLog.w(TAG, "[$name] 释放:没有已记录的 handler")
            return RequestOutcome(false, Message(R.string.release_nothing_pending))
        }

        val released = releaseRequest(current)
        AppLog.i(TAG, "[$name] 释放请求 handler=$current -> ${AppLog.text(released.message)}")
        if (released is PowerHal.Result.Failure) {
            return RequestOutcome(false, released.message, detail = released.detail)
        }

        store.set(name, 0)
        // 用户主动释放的，之后不该再被"恢复"回来自动弹。
        lastRequest = null
        return RequestOutcome(true, Message(R.string.lock_released))
    }
}
