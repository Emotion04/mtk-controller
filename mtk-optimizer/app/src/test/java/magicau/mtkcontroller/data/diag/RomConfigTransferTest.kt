package magicau.mtkcontroller.data.diag

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class RomConfigTransferTest {
    /**
     * 打一个 ZIP 再原样读回来。
     *
     * **这是测试自己的工具，不是产品代码。** 它原来住在 `RomConfigTransfer` 的
     * companion 里，于是每个安装包都带着一段只有测试会走的打包逻辑。
     * 搬到这里，产品侧只留真正被产品调用的东西。
     */
    private fun archive(file: File, contents: Map<String, ByteArray>, manifest: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in contents + ("manifest.txt" to manifest.toByteArray(Charsets.UTF_8))) {
                require(name.matches(Regex("[A-Za-z0-9_.-]+")))
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        // 回读落盘 ZIP，确认压缩与文件写入没有破坏内容。
        ZipFile(file).use { zip ->
            for ((name, expected) in contents + ("manifest.txt" to manifest.toByteArray(Charsets.UTF_8))) {
                val actual = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                check(actual.size == expected.size && sha(actual) == sha(expected)) { "ZIP 回读校验失败：$name" }
            }
        }
    }

    private fun sha(bytes: ByteArray) = RomConfigTransfer.sha(bytes)

    @Test fun largeBinaryRoundTripAndArchiveRetainEveryByte() {
        val original = ByteArray(6_000_123) { (it % 251).toByte() }
        val source = RomConfigTransfer { original }
        val fields = source.open(0).split('|')
        var chunks = 0
        val received = RomConfigTransfer.verified(fields[1].toInt(), fields[2]) {
            chunks++; source.chunk(fields[0], it)
        }
        assertTrue(chunks > 10); assertArrayEquals(original, received)
        val file = Files.createTempFile("rom-config-test", ".zip").toFile()
        try {
            archive(file, mapOf("powercontable.xml" to received), "OK ${fields[2]}")
            ZipFile(file).use { zip ->
                assertArrayEquals(original, zip.getInputStream(zip.getEntry("powercontable.xml")).readBytes())
                assertTrue(zip.getInputStream(zip.getEntry("manifest.txt")).reader().readText().contains(fields[2]))
            }
        } finally { file.delete() }
    }
    @Test fun corruptionAndShortReadCannotPassVerification() {
        val good = byteArrayOf(1, 2, 3)
        assertThrows(IllegalStateException::class.java) {
            RomConfigTransfer.verified(3, RomConfigTransfer.sha(good)) { byteArrayOf(1, 2, 4) }
        }
        assertThrows(IllegalStateException::class.java) {
            RomConfigTransfer.verified(3, RomConfigTransfer.sha(good)) { byteArrayOf() }
        }
    }
    @Test fun expiredSessionsAndNonWhitelistedIndicesAreRejected() {
        val source = RomConfigTransfer { byteArrayOf(1) }
        val token = source.open(0).substringBefore('|')
        source.open(1)
        assertThrows(IllegalStateException::class.java) { source.chunk(token, 0) }
        assertThrows(IllegalArgumentException::class.java) { source.open(3) }
        val next = source.open(0).substringBefore('|')
        source.close(next)
        assertThrows(IllegalStateException::class.java) { source.chunk(next, 0) }
    }
    @Test fun failedAndOversizedFilesCannotBecomeSuccess() {
        assertThrows(java.io.IOException::class.java) { RomConfigTransfer { throw java.io.IOException("denied") }.open(0) }
        assertThrows(IllegalStateException::class.java) { RomConfigTransfer { ByteArray(RomConfigTransfer.MAX_BYTES + 1) }.open(0) }
        assertThrows(IllegalStateException::class.java) { RomConfigTransfer { byteArrayOf() }.open(0) }
    }
}
