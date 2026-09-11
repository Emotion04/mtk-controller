package magicau.mtkcontroller.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原值快照的三条规则：只取一次、不存在要单独记、恢复后释放。
 *
 * 这三条原来在触控和厂商功能里各实现一遍，任何一处写错都会在真机上表现为
 * "关掉之后设备回不到原来的样子"，而不是一个能看见的错误。
 */
class RecoverySnapshotTest {

    private val storage = MutableStateFlow<Map<String, String>>(emptyMap())
    private val snapshot = RecoverySnapshot(
        store = storage.map { it },
        write = { storage.value = it },
    )

    @Test
    fun `第二次取快照不覆盖第一次的值`() = runBlocking {
        var device = "300"
        assertEquals(1, snapshot.capture("touch", "press", listOf("system.a")) { device })
        device = "250" // 本软件写进去的值
        assertEquals(0, snapshot.capture("touch", "press", listOf("system.a")) { device })
        assertEquals("300", snapshot.original("touch", "press", "system.a"))
    }

    @Test
    fun `原键不存在和值为空是两件事`() = runBlocking {
        snapshot.capture("touch", "x", listOf("system.missing", "system.empty")) { key ->
            if (key == "system.empty") "" else null
        }
        assertFalse(snapshot.isPresent("touch", "x", "system.missing"))
        assertNull(snapshot.original("touch", "x", "system.missing"))
        assertTrue(snapshot.isPresent("touch", "x", "system.empty"))
        assertEquals("", snapshot.original("touch", "x", "system.empty"))
    }

    @Test
    fun `释放只删这一个条目的记录`() = runBlocking {
        snapshot.capture("touch", "a", listOf("system.k")) { "1" }
        snapshot.capture("touch", "b", listOf("system.k")) { "2" }
        assertEquals(1, snapshot.release("touch", "a", listOf("system.k")))
        assertFalse(snapshot.hasEntry("touch", "a"))
        assertTrue(snapshot.hasEntry("touch", "b"))
        assertEquals("2", snapshot.original("touch", "b", "system.k"))
    }

    @Test
    fun `不同功能的空间互不覆盖`() = runBlocking {
        snapshot.capture(RecoverySnapshot.TOUCH, "e", listOf("system.k")) { "t" }
        snapshot.capture(RecoverySnapshot.VIVO, "e", listOf("system.k")) { "v" }
        assertEquals("t", snapshot.original(RecoverySnapshot.TOUCH, "e", "system.k"))
        assertEquals("v", snapshot.original(RecoverySnapshot.VIVO, "e", "system.k"))
    }
}
