package magicau.mtkcontroller.data.changelog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonTransformingSerializer
import magicau.mtkcontroller.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一条更新内容。`type` 是 feat / fix / perf / chore 这类标签，界面用彩色徽章显示；
 * 空类型就是普通条目。
 */
@Serializable
data class ChangelogNote(
    val type: String = "",
    val text: String,
)

/** 兼容旧格式：`notes` 里直接写字符串时，当成没有类型的条目。 */
object NotesSerializer : JsonTransformingSerializer<List<ChangelogNote>>(
    ListSerializer(ChangelogNote.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonArray) return element
        return buildJsonArray {
            element.forEach { item ->
                if (item is JsonPrimitive) {
                    add(buildJsonObject {
                        put("type", "")
                        put("text", item.content)
                    })
                } else {
                    add(item)
                }
            }
        }
    }
}

@Serializable
data class ChangelogEntry(
    val versionCode: Int = 0,
    val versionName: String = "",
    val date: String = "",
    /** 版本标题下的一句话摘要，没有就不显示。 */
    val summary: String = "",
    @Serializable(with = NotesSerializer::class)
    val notes: List<ChangelogNote> = emptyList(),
)

@Serializable
private data class ChangelogFile(
    val schema: Int = 1,
    val entries: List<ChangelogEntry> = emptyList(),
)

/**
 * 更新日志：优先读远端静态 JSON，失败回退 APK 内置的那份。
 *
 * **只读、无设备标识。** 每次打开页面拉一次；没有轮询，也不缓存到磁盘 ——
 * 用户点开就是要看最新的，缓存只会显示旧内容。远端失败时用内置版本，
 * 并把失败原因显示出来（灰色详情），不假装是最新。
 */
class ChangelogRepository(private val context: Context) {

    data class Result(
        val entries: List<ChangelogEntry>,
        val remote: Boolean,
        val error: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): Result = withContext(Dispatchers.IO) {
        val urls = listOf(BuildConfig.CHANGELOG_URL, BuildConfig.CHANGELOG_URL_BACKUP)
            .filter { it.isNotBlank() }
        for (url in urls) {
            runCatching { fetch(url) }.getOrNull()?.let { entries ->
                return@withContext Result(entries, remote = true)
            }
        }
        if (urls.isNotEmpty()) {
            // 主备都失败：回退本地，但把原因带出去。
            val local = runCatching { readAsset() }.getOrNull().orEmpty()
            return@withContext Result(local, remote = false, error = "remote fetch failed")
        }
        val local = runCatching { readAsset() }.getOrNull().orEmpty()
        Result(local, remote = false)
    }

    private fun fetch(url: String): List<ChangelogEntry> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            useCaches = false
        }
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            // 限长，避免一个坏地址把内存读爆。
            val text = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(64 * 1024)
                val out = StringBuilder()
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    out.append(buffer, 0, n)
                    if (out.length > 256 * 1024) error("changelog too large")
                }
                out.toString()
            }
            return parse(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun readAsset(): List<ChangelogEntry> =
        context.assets.open("changelog.json").bufferedReader().use { parse(it.readText()) }

    private fun parse(text: String): List<ChangelogEntry> {
        val file = json.decodeFromString<ChangelogFile>(text)
        require(file.schema == 1) { "unsupported changelog schema" }
        require(file.entries.isNotEmpty()) { "changelog empty" }
        return file.entries
            .filter { it.notes.isNotEmpty() }
            .sortedByDescending { it.versionCode }
    }
}
