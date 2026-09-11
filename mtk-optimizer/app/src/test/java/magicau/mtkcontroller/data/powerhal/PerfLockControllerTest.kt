package magicau.mtkcontroller.data.powerhal

import kotlinx.coroutines.runBlocking
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.log.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 「先放后拿」的坏路径：放成功、拿失败。
 *
 * 这条路径**在真机上构造不出来** —— 「应用」按钮的 `enabled` 带着
 * `state.powerHal.available`，Shizuku 一断按钮就灰了，用户根本点不下去。
 * 而它恰恰是本类顺序唯一的坏路径：旧句柄已经放掉、`store` 记成 0，
 * 用户原来的限制静默消失。
 *
 * 所以不靠真机，靠注入假的 acquire / release：让第 N 次 acquire 回失败，
 * 断言上一份请求被原样放回去了。
 */
@RunWith(RobolectricTestRunner::class)
class PerfLockControllerTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    @Before fun quietLogcat() { AppLog.setMirrorToLogcat(false) }

    /** 句柄号从 [nextHandle] 递增分配，避免测试里两个句柄撞号导致假通过。 */
    private class Memory(private var nextHandle: Int = 100) : PerfHandleStorage {
        var handle = 0
        override suspend fun get(name: String) = handle
        override suspend fun set(name: String, value: Int) { handle = value }
        fun allocate(): Int = nextHandle++
    }

    private val pairsA = listOf("0x00404200" to "1100000")
    private val pairsB = listOf("0x00404200" to "2000000")

    private fun success(mem: Memory) =
        { _: List<Pair<String, String>>, _: Int -> PowerHal.Result.Success(mem.allocate(), Message(R.string.powerhal_applied)) }

    private fun failure() =
        { _: List<Pair<String, String>>, _: Int -> PowerHal.Result.Failure(Message(R.string.powerhal_call_failed)) }

    // ---------------------------------------------------------------- 正常路径

    @Test fun `apply stores the new handle`() = runBlocking {
        val mem = Memory()
        val lock = PerfLockController(mem, "cpu", success(mem), { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) })

        val result = lock.apply(pairsA)

        assertTrue(result.success)
        assertEquals(100, mem.handle)
    }

    // ------------------------------------------------- 坏路径：恢复上一份请求

    @Test fun `acquire failure restores the previous request`() = runBlocking {
        val mem = Memory()
        var attempt = 0
        val lock = PerfLockController(
            mem,
            "cpu",
            { _: List<Pair<String, String>>, _: Int ->
                // 只有第 2 次（用户这次的新请求）失败；恢复那次必须成功 ——
                // 否则测的是"恢复也失败"那条分支，不是这一条。
                if (++attempt == 2) PowerHal.Result.Failure(Message(R.string.powerhal_call_failed))
                else PowerHal.Result.Success(mem.allocate(), Message(R.string.powerhal_applied))
            },
            { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) },
        )

        assertTrue(lock.apply(pairsA).success)   // 第一次成功，lastRequest = pairsA
        val second = lock.apply(pairsB)          // 放掉旧的 → 拿新的失败 → 应恢复 pairsA

        assertFalse(second.success)
        // 消息必须是"已保留原设置"，不是裸的失败 —— 两者对用户的含义完全不同。
        assertEquals(
            context.getString(R.string.lock_apply_failed_restored),
            second.message.resolve(context),
        )
        assertEquals("恢复后必须重新持有句柄", 101, mem.handle)
    }

    @Test fun `when the restore itself fails nothing is held`() = runBlocking {
        val mem = Memory()
        var attempt = 0
        val lock = PerfLockController(
            mem,
            "cpu",
            { _: List<Pair<String, String>>, _: Int ->
                // 第 2、3 次都失败：新请求失败，恢复也失败。
                if (++attempt >= 2) PowerHal.Result.Failure(Message(R.string.powerhal_call_failed))
                else PowerHal.Result.Success(mem.allocate(), Message(R.string.powerhal_applied))
            },
            { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) },
        )

        lock.apply(pairsA)
        val second = lock.apply(pairsB)

        assertFalse(second.success)
        // 恢复也失败时不能谎称"已保留原设置"—— 那是假消息。
        assertEquals(
            context.getString(R.string.powerhal_call_failed),
            second.message.resolve(context),
        )
        assertEquals("恢复失败就不该留下句柄记录", 0, mem.handle)
    }

    @Test fun `restored request is the same pairs as the last successful one`() = runBlocking {
        val mem = Memory()
        var lastSent: List<Pair<String, String>>? = null
        var attempt = 0
        val lock = PerfLockController(
            mem,
            "cpu",
            { p, _ ->
                lastSent = p
                if (++attempt == 1) PowerHal.Result.Success(mem.allocate(), Message(R.string.powerhal_applied))
                else PowerHal.Result.Failure(Message(R.string.powerhal_call_failed))
            },
            { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) },
        )

        lock.apply(pairsA)
        lock.apply(pairsB)

        // 第三次调用就是 restore —— 发出去的必须是 pairsA，不是刚失败的 pairsB。
        assertEquals("恢复的必须是上一份成功请求，而不是本次失败的请求", pairsA, lastSent)
    }

    @Test fun `first ever acquire failure has nothing to restore`() = runBlocking {
        val mem = Memory()
        val lock = PerfLockController(mem, "cpu", failure(), { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) })

        val result = lock.apply(pairsA)

        assertFalse(result.success)
        // 没有上一份可恢复时，必须原样报底层失败，不能谎称"已保留原设置"。
        assertEquals(
            context.getString(R.string.powerhal_call_failed),
            result.message.resolve(context),
        )
        assertEquals(0, mem.handle)
    }

    @Test fun `release failure aborts without acquiring`() = runBlocking {
        val mem = Memory()
        var acquireCalls = 0
        val lock = PerfLockController(
            mem,
            "cpu",
            { _: List<Pair<String, String>>, _: Int -> acquireCalls++; PowerHal.Result.Success(mem.allocate(), Message(R.string.powerhal_applied)) },
            { PowerHal.Result.Failure(Message(R.string.powerhal_call_failed)) },
        )

        assertTrue(lock.apply(pairsA).success)
        acquireCalls = 0
        val second = lock.apply(pairsB)

        assertFalse(second.success)
        assertEquals("释放失败就该放弃本次下发，不是硬上", 0, acquireCalls)
        assertEquals("旧句柄释放失败时不能被抹掉 —— 抹掉就再也放不掉了", 100, mem.handle)
    }

    // --------------------------------------------------------- 释放之后不恢复

    @Test fun `explicit release clears what would be restored`() = runBlocking {
        val mem = Memory()
        var attempt = 0
        val lock = PerfLockController(
            mem,
            "cpu",
            { _: List<Pair<String, String>>, _: Int ->
                if (++attempt == 1) PowerHal.Result.Success(mem.allocate(), Message(R.string.powerhal_applied))
                else PowerHal.Result.Failure(Message(R.string.powerhal_call_failed))
            },
            { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) },
        )

        lock.apply(pairsA)
        lock.release()          // 用户明确要求不带限制了
        val after = lock.apply(pairsB)

        // 用户主动释放过，之后失败不能把旧限制"悄悄恢复"回来自动弹。
        assertFalse(after.success)
        assertEquals(
            context.getString(R.string.powerhal_call_failed),
            after.message.resolve(context),
        )
        assertEquals(0, mem.handle)
    }

    @Test fun `release with no handle reports nothing pending`() = runBlocking {
        val mem = Memory()
        val lock = PerfLockController(mem, "cpu", success(mem), { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) })

        val result = lock.release()

        assertFalse(result.success)
        assertEquals(context.getString(R.string.release_nothing_pending), result.message.resolve(context))
    }

    // ------------------------------------------------------------- 空请求

    @Test fun `empty pairs is rejected before touching the device`() = runBlocking {
        val mem = Memory()
        var acquireCalls = 0
        val lock = PerfLockController(
            mem,
            "cpu",
            { _: List<Pair<String, String>>, _: Int -> acquireCalls++; PowerHal.Result.Success(mem.allocate(), Message(R.string.powerhal_applied)) },
            { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) },
        )

        val result = lock.apply(emptyList())

        assertFalse(result.success)
        assertEquals(0, acquireCalls)
    }
}
