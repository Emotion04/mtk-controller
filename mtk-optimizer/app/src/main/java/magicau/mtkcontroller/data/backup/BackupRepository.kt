package magicau.mtkcontroller.data.backup

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.profile.ProfileRepository
import magicau.mtkcontroller.data.settings.SettingsRepository
import magicau.mtkcontroller.domain.model.Backup
import magicau.mtkcontroller.domain.model.BackupPreferences
import magicau.mtkcontroller.domain.model.BackupCategory

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
    private val vivo: magicau.mtkcontroller.data.tweaks.VivoTweaks,
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

    /**
     * 导出到文件。
     *
     * [categories] 决定文件里带哪几类内容 —— 用户经常只想搬方案，
     * 而厂商配置备份在别的机型上是无效的，不该被迫一起带走。
     */
    suspend fun export(
        resolver: ContentResolver,
        uri: Uri,
        categories: Set<BackupCategory> = BackupCategory.ALL,
    ): Failure = withContext(Dispatchers.IO) {
        runCatching {
            val backup = Backup(
                exportedAtEpochMs = System.currentTimeMillis(),
                smartPlatform = if (BackupCategory.SMART_PLATFORM in categories) vivo.smartBackup() else null,
                profiles = if (BackupCategory.PROFILES in categories) profileRepository.profiles.first() else emptyList(),
                activeProfileId = if (BackupCategory.PROFILES in categories) profileRepository.activeProfileId.first() else null,
                preferences = if (BackupCategory.PREFERENCES !in categories) BackupPreferences() else BackupPreferences(
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
                ?: return@runCatching Failure(
                    Message(R.string.backup_write_failed),
                    detail = uri.toString(),
                )
            AppLog.i(TAG, "已导出 ${backup.profiles.size} 个方案（${categories.size} 类）到 $uri")
            Failure(Message(R.string.backup_exported, listOf(backup.profiles.size)))
        }.getOrElse {
            AppLog.e(TAG, "导出失败", it)
            Failure(Message(R.string.backup_export_failed), detail = Failure.detailOf(it))
        }
    }

    /** Read and validate a file without changing anything. */
    suspend fun parse(resolver: ContentResolver, uri: Uri): Result<Backup> = withContext(Dispatchers.IO) {
        runCatching {
            val text = resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: error("无法读取所选文件")
            val backup = json.decodeFromString<Backup>(text)
            backup.smartPlatform?.let { magicau.mtkcontroller.data.tweaks.SmartBackupCodec.validate(it) }
            require(backup.schema in Backup.SUPPORTED_SCHEMAS) {
                "备份格式版本 ${backup.schema} 不受支持(当前支持 ${Backup.SUPPORTED_SCHEMAS})"
            }
            AppLog.i(TAG, "解析备份: schema=${backup.schema}, ${backup.profiles.size} 个方案")
            backup
        }.onFailure { AppLog.e(TAG, "解析备份失败", it) }
    }

    /**
     * 应用备份内容。
     *
     * [categories] 决定实际写入哪几类 —— 与导出时的取舍分开，因为用户可能
     * 拿到别人给的备份文件，只想取其中一部分。
     */
    suspend fun apply(
        backup: Backup,
        categories: Set<BackupCategory> = BackupCategory.ALL,
    ): Failure = withContext(Dispatchers.IO) {
        runCatching {
            if (BackupCategory.SMART_PLATFORM in categories) {
                backup.smartPlatform?.let { vivo.importSmartBackup(it) }
            }
            if (BackupCategory.PROFILES in categories) {
                profileRepository.replaceAll(backup.profiles, backup.activeProfileId)
            }

            val prefs = if (BackupCategory.PREFERENCES in categories) {
                backup.preferences
            } else {
                BackupPreferences()
            }
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
            Failure(
                if (backup.smartPlatform != null) {
                    Message(R.string.backup_imported_with_smart, listOf(backup.profiles.size))
                } else {
                    Message(R.string.backup_imported, listOf(backup.profiles.size))
                },
            )
        }.getOrElse {
            AppLog.e(TAG, "导入失败", it)
            Failure(Message(R.string.backup_import_failed), detail = Failure.detailOf(it))
        }
    }
}
