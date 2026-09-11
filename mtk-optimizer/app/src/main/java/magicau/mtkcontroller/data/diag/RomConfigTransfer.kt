package magicau.mtkcontroller.data.diag

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 只允许固定配置和系统目录枚举结果，快照按小块传输。 */
class RomConfigTransfer(private val read: (String) -> ByteArray = { path ->
    val file = File(path)
    check(file.isFile) { "不是可读取的普通文件：$path" }
    val size = file.length()
    check(size in 1..MAX_BYTES.toLong()) { "文件为空或超过 64 MiB，未截断保存：$size" }
    file.inputStream().use { input ->
        val bytes = ByteArray(size.toInt())
        var offset = 0
        val started = System.nanoTime()
        while (offset < bytes.size) {
            check(System.nanoTime() - started < 30_000_000_000L) { "文件读取超过 30 秒" }
            val n = input.read(bytes, offset, minOf(32768, bytes.size - offset))
            check(n > 0) { "文件提前结束" }
            offset += n
        }
        check(input.read() == -1) { "读取期间文件长度改变" }
        bytes
    }
}) {
    companion object {
        const val MAX_BYTES = 64 * 1024 * 1024
        val paths = listOf("/vendor/etc/powercontable.xml", "/vendor/etc/powerscntbl.xml",
            "/vendor/etc/vperf/vperfmaps.xml")
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        fun verified(size: Int, hash: String, chunk: (Int) -> ByteArray): ByteArray {
            require(size in 1..MAX_BYTES)
            val output = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val part = chunk(offset)
                check(part.isNotEmpty() && part.size <= 32768 && offset + part.size <= size) { "分块长度异常" }
                part.copyInto(output, offset)
                offset += part.size
            }
            return output.also { check(sha(it) == hash) { "SHA256 校验失败" } }
        }
    }
    private var token = ""
    private var data = byteArrayOf()
    private var selectedPaths = paths
    private var discovery = byteArrayOf()
    @Synchronized fun discover(): String {
        token = ""; data = byteArrayOf()
        val result = RomImplementationFiles.discover()
        discovery = result.second.toByteArray(Charsets.UTF_8)
        selectedPaths = listOf("discovery.txt") + paths + result.first
        return selectedPaths.joinToString("\n")
    }
    @Synchronized fun open(index: Int): String {
        require(index in selectedPaths.indices)
        data = byteArrayOf(); token = ""
        val bytes = if (selectedPaths[index] == "discovery.txt") discovery else read(selectedPaths[index])
        check(bytes.size in 1..MAX_BYTES) { "配置为空或过大" }
        data = bytes; token = UUID.randomUUID().toString()
        return "$token|${data.size}|${sha(data)}"
    }
    @Synchronized fun chunk(id: String, offset: Int): ByteArray {
        check(id == token && id.isNotEmpty()) { "快照已失效，请重新导出" }
        require(offset in 0 until data.size)
        return data.copyOfRange(offset, minOf(offset + 32768, data.size))
    }
    @Synchronized fun close(id: String) { if (id == token) { token = ""; data = byteArrayOf() } }
}
