package magicau.mtkcontroller.data.tweaks

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import magicau.mtkcontroller.domain.model.Backup
import org.junit.Assert.*
import org.junit.Test

class SmartBackupTest {
    private fun backup() = SmartBackup(device = "vivo/model/device", rom = "rom1", capturedAt = 1,
        values = mapOf(SmartBackupCodec.AI_KEY to "0", "system cpu_boost_mode" to "1"))

    @Test fun roundTripPreservesMissingAndAbsentKeys() {
        val original = backup().copy(values = mapOf(SmartBackupCodec.AI_KEY to "null"), missing = listOf("global ai_turbo_enabled"))
        assertEquals(original, SmartBackupCodec.decode(SmartBackupCodec.encode(original)))
        assertEquals("null", SmartBackupCodec.restoreValue(original, original.device, original.rom))
    }
    @Test fun rejectsUnknownKeysVersionValuesAndOversize() {
        listOf(backup().copy(schema = 99), backup().copy(kind = "other"),
            backup().copy(values = mapOf("system fake;id" to "1")),
            backup().copy(values = mapOf(SmartBackupCodec.AI_KEY to "2")),
            backup().copy(missing = listOf(SmartBackupCodec.AI_KEY))).forEach {
            assertThrows(IllegalArgumentException::class.java) { SmartBackupCodec.validate(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { SmartBackupCodec.decode(" ".repeat(SmartBackupCodec.MAX_BYTES + 1)) }
    }
    @Test fun restoreIsRestrictedToAiAndMatchingRom() {
        val b = backup()
        assertEquals("0", SmartBackupCodec.restoreValue(b, b.device, b.rom))
        assertThrows(IllegalArgumentException::class.java) { SmartBackupCodec.restoreValue(b, "other", b.rom) }
        assertThrows(IllegalArgumentException::class.java) { SmartBackupCodec.restoreValue(b, b.device, "rom2") }
        assertThrows(IllegalStateException::class.java) { SmartBackupCodec.restoreValue(b.copy(values = mapOf("system cpu_boost_mode" to "1")), b.device, b.rom) }
    }
    @Test fun savesBeforeWriteAndRollsBackOnException() = runBlocking {
        val events = mutableListOf<String>()
        val result = SmartRestore.run("1", { "0" }, { events += "saved:$it" }, {
            events += "write:$it"
            if (it == "1") error("binder died") else null
        })
        assertEquals(listOf("saved:0", "write:1", "write:0"), events)
        assertEquals(false to true, result)
    }
    @Test fun failedReadOrBackupNeverWrites() = runBlocking {
        var writes = 0
        assertTrue(runCatching { SmartRestore.run("1", { null }, {}, { writes++; null }) }.isFailure)
        assertTrue(runCatching { SmartRestore.run("1", { "0" }, { error("disk full") }, { writes++; null }) }.isFailure)
        assertEquals(0, writes)
        // 写失败要回滚；回滚也失败时 rolledBack 必须是 false，不能吞成 true。
        assertEquals(false to false, SmartRestore.run("1", { "0" }, {}, { "拒绝" }))
    }
    @Test fun totalBackupKeepsOldFormatCompatible() {
        val old = Json.decodeFromString<Backup>("{\"schema\":1}")
        assertNull(old.smartPlatform)
        assertTrue(old.schema in Backup.SUPPORTED_SCHEMAS)
        val current = Backup(smartPlatform = backup())
        assertEquals(current, Json.decodeFromString<Backup>(Json.encodeToString(current)))
    }
}
