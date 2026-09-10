package magicau.mtkcontroller.data.backup

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.profile.ProfileRepository
import magicau.mtkcontroller.data.settings.SettingsRepository
import magicau.mtkcontroller.domain.model.Backup
import magicau.mtkcontroller.domain.model.BackupPreferences

/**
 * Reads and writes the user's configuration as a single JSON file.
 *
 * All file access goes through the Storage Access Framework, so nothing here
 * needs a storage permission and the user picks the location.
 *
 * Import is two-phase on purpose: [parse] loads and validates the file and
 * hands it back for the UI to describe, and only [apply] writes anything. That
 * way the user sees how many profiles a file holds before it replaces theirs.
 */
class BackupRepository(
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
) {

    private companion object {
        const val TAG = "Backup"
    }

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** Suggested file name for the picker, e.g. `mtk-god-backup-20260911-1907.json`. */
    fun suggestedFileName(nowMs: Long): String {
        val totalMinutes = nowMs / 60_000
        val days = totalMinutes / (60 * 24)
        val minutesOfDay = totalMinutes % (60 * 24)
        val hh = minutesOfDay / 60
        val mm = minutesOfDay % 60
        // Days since epoch keeps the name sortable without pulling in a date API.
        return "mtk-god-backup-%d-%02d%02d.json".format(days, hh, mm)
    }

    suspend fun export(resolver: ContentResolver, uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            val backup = Backup(
                exportedAtEpochMs = System.currentTimeMillis(),
                profiles = profileRepository.profiles.first(),
                activeProfileId = profileRepository.activeProfileId.first(),
                preferences = BackupPreferences(
                    themeMode = settingsRepository.themeMode.first(),
                    palette = settingsRepository.themePalette.first(),
                    navBarStyle = settingsRepository.navBarStyle.first(),
                    navBarCustomPalette = settingsRepository.navBarCustomPalette.first(),
                    defaultTabRoute = settingsRepository.defaultTabRoute.first(),
                    applyMode = settingsRepository.applyMode.first(),
                    reapplyIntervalMs = settingsRepository.reapplyIntervalMs.first(),
                    logLevel = settingsRepository.logLevel.first(),
                    homeCards = settingsRepository.homeCards.first(),
                ),
            )
            val bytes = json.encodeToString(backup).toByteArray()
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return@runCatching "无法写入所选位置"
            AppLog.i(TAG, "已导出 ${backup.profiles.size} 个方案到 $uri")
            "已导出 ${backup.profiles.size} 个方案 · ${backup.preferences.homeCards?.size ?: 0} 张主页卡片"
        }.getOrElse {
            AppLog.e(TAG, "导出失败", it)
            "导出失败: ${it.javaClass.simpleName}: ${it.message}"
        }
    }

    /** Read and validate a file without changing anything. */
    suspend fun parse(resolver: ContentResolver, uri: Uri): Result<Backup> = withContext(Dispatchers.IO) {
        runCatching {
            val text = resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: error("无法读取所选文件")
            val backup = json.decodeFromString<Backup>(text)
            require(backup.schema in Backup.SUPPORTED_SCHEMAS) {
                "备份格式版本 ${backup.schema} 不受支持(当前支持 ${Backup.SUPPORTED_SCHEMAS})"
            }
            AppLog.i(TAG, "解析备份: schema=${backup.schema}, ${backup.profiles.size} 个方案")
            backup
        }.onFailure { AppLog.e(TAG, "解析备份失败", it) }
    }

    /** Replace the profiles and preferences with the contents of [backup]. */
    suspend fun apply(backup: Backup): String = withContext(Dispatchers.IO) {
        runCatching {
            profileRepository.replaceAll(backup.profiles, backup.activeProfileId)

            val prefs = backup.preferences
            prefs.themeMode?.let { settingsRepository.setThemeMode(it) }
            prefs.palette?.let { settingsRepository.setThemePalette(it) }
            prefs.navBarStyle?.let { settingsRepository.setNavBarStyle(it) }
            prefs.navBarCustomPalette?.let { settingsRepository.setNavBarCustomPalette(it) }
            prefs.defaultTabRoute?.let { settingsRepository.setDefaultTabRoute(it) }
            prefs.applyMode?.let { settingsRepository.setApplyMode(it) }
            prefs.reapplyIntervalMs?.let { settingsRepository.setReapplyIntervalMs(it) }
            prefs.logLevel?.let { settingsRepository.setLogLevel(it) }
            prefs.homeCards?.takeIf { it.isNotEmpty() }?.let { settingsRepository.replaceCards(it) }

            AppLog.i(TAG, "导入完成: ${backup.profiles.size} 个方案")
            "已导入 ${backup.profiles.size} 个方案"
        }.getOrElse {
            AppLog.e(TAG, "导入失败", it)
            "导入失败: ${it.javaClass.simpleName}: ${it.message}"
        }
    }
}
