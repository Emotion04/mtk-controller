package magicau.mtkcontroller.data.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import magicau.mtkcontroller.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * 公开的更新清单。App 只读，不含密钥。
 *
 * 与 `changelog.json` 分开：更新清单回答“有没有新版、去哪里下”，
 * 更新日志回答“每个版本改了什么”。
 */
@Serializable
data class UpdateInfo(
    val schema: Int = 1,
    val channel: String = "",
    val versionCode: Int = 0,
    val versionName: String = "",
    val buildTime: Long = 0,
    val publishedAt: Long = 0,
    val minVersionCode: Int = 0,
    val notes: String = "",
    val url: String = "",
    val sha256: String = "",
    /** 包体大小（字节）；0 表示清单没给，此时不直接下载，只打开下载页。 */
    val size: Long = 0,
)

/**
 * 检查更新：主地址失败再试备用地址，都失败就当这次没检查到。
 *
 * 只接受 **https** 下载地址；清单本身也不应该放在私有空间 —— 私有外链带
 * `?e=&token=` 会过期，旧 App 过期后永远检测不到新版本。
 */
class UpdateRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val urls = listOf(BuildConfig.UPDATE_URL, BuildConfig.UPDATE_URL_BACKUP)
            .filter { it.isNotBlank() }
        if (urls.isEmpty()) return@withContext null

        for (url in urls) {
            val info = runCatching { fetch(url) }.getOrNull() ?: continue
            if (info.schema != 1) continue
            if (info.channel.isNotBlank() && info.channel != currentChannel()) continue
            // 按 versionCode 判断新旧；与 BuildConfig.DEBUG 对应的渠道之外不提示。
            if (!UpdateInstaller.isNewer(info)) return@withContext null
            if (!info.url.startsWith("https://")) continue
            return@withContext info
        }
        null
    }

    /** 只用来区分“已是最新”和“网络不通”：手动检查时两者要给不同的提示。 */
    suspend fun reachable(): Boolean = withContext(Dispatchers.IO) {
        val url = listOf(BuildConfig.UPDATE_URL, BuildConfig.UPDATE_URL_BACKUP).firstOrNull { it.isNotBlank() }
            ?: return@withContext false
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = "GET"
                useCaches = false
            }
            try {
                connection.responseCode == 200
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun currentChannel(): String = if (BuildConfig.DEBUG) "canary" else "stable"

    private fun fetch(url: String): UpdateInfo {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            useCaches = false
        }
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            val text = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(32 * 1024)
                val out = StringBuilder()
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    out.append(buffer, 0, n)
                    if (out.length > 256 * 1024) error("update json too large")
                }
                out.toString()
            }
            return json.decodeFromString<UpdateInfo>(text)
        } finally {
            connection.disconnect()
        }
    }
}
