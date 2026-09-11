package magicau.mtkcontroller.data.update

import android.content.Context
import android.content.res.Resources
import android.content.Intent
import android.net.Uri
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.log.AppLog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本比较与 APK 下载安装。
 *
 * **比较用 versionCode，不用 versionName。** versionName 只是显示用的名字，
 * 可能出现 0.5.10 这样的两位数段，字符串比较会把 "0.5.10" 排到 "0.5.9" 前面。
 * 同一渠道内 versionCode 单调递增，是唯一可靠的“谁更新”依据。
 */
object UpdateInstaller {

    private const val TAG = "Update"
    private const val MAX_BYTES = 200L * 1024 * 1024

    /** 下载中的结果，直接驱动界面上的按钮文案。 */
    sealed interface Progress {
        data object Idle : Progress
        data class Downloading(val bytes: Long, val total: Long, val percent: Int) : Progress
        data class Ready(val file: File) : Progress
        /** reasonRes：给界面显示失败原因的字符串资源；原始异常只进日志。 */
        data class Failed(val reasonRes: Int) : Progress
    }

    fun isNewer(info: UpdateInfo): Boolean = info.versionCode > magicau.mtkcontroller.BuildConfig.VERSION_CODE

    /** 字节数 → 带单位的短文本。单位用系统本地化字符串，不在这里拼。 */
    fun formatSize(context: Context, bytes: Long): String {
        val res = context.resources
        return when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f %s".format(bytes / 1024.0 / 1024 / 1024, res.getString(R.string.unit_gb))
            bytes >= 1024L * 1024 -> "%.1f %s".format(bytes / 1024.0 / 1024, res.getString(R.string.unit_mb))
            bytes >= 1024L -> "%.0f %s".format(bytes / 1024.0, res.getString(R.string.unit_kb))
            else -> "%d %s".format(bytes, res.getString(R.string.unit_b))
        }
    }

    private fun cacheFile(context: Context, info: UpdateInfo): File =
        File(context.cacheDir, "update/app-${info.versionCode}.apk")

    /**
     * 直接下载 APK。只有清单声明了 `size` 和 `sha256` 才下载：
     * 没有尺寸就不知道进度，没有校验值就无法确认下到的是不是完整包。
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = cacheFile(context, info)
        val part = File(target.path + ".part")
        if (target.isFile && target.length() == info.size && info.size > 0) {
            onProgress(Progress.Downloading(info.size, info.size, 100))
            return@withContext target
        }
        target.parentFile?.mkdirs()
        part.delete()
        val connection = (URL(info.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.android.package-archive")
            useCaches = false
        }
        try {
            if (connection.responseCode != 200) throw Resources.NotFoundException("HTTP ${connection.responseCode}")
            val total = info.size.takeIf { it > 0 } ?: connection.contentLengthLong
            if (total > MAX_BYTES) throw Resources.NotFoundException("too large")
            var copied = 0L
            var lastReport = 0L
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_BYTES) throw Resources.NotFoundException("too large")
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 150 || (total > 0 && copied >= total)) {
                            lastReport = now
                            val percent = if (total > 0) (copied * 100 / total).toInt() else -1
                            onProgress(Progress.Downloading(copied, total, percent))
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256.isNotBlank() && !actual.equals(info.sha256, ignoreCase = true)) {
                part.delete()
                throw Resources.NotFoundException("sha256 mismatch")
            }
            part.renameTo(target)
            onProgress(Progress.Downloading(copied, copied, 100))
            target
        } catch (e: Exception) {
            part.delete()
            AppLog.w(TAG, "下载失败: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 交给系统安装器。Android 8 起需要「安装未知应用」权限，
     * 没给时先跳到本应用的这项设置，用户回来后再点安装。
     */
    fun install(context: Context, file: File): Boolean {
        if (!file.isFile) return false
        val canInstall = context.packageManager.canRequestPackageInstalls()
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
        if (!canInstall) {
            val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(settings) }.isSuccess
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
