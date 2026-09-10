package magicau.mtkcontroller.domain.model

import kotlinx.serialization.Serializable

/**
 * A portable snapshot of everything the user configured.
 *
 * Deliberately plain JSON with defaults on every field: the file is meant to be
 * readable and hand-editable, and an older export must still import after new
 * preferences are added. Unknown keys are ignored on the way in, missing ones
 * fall back to null, and null means "leave that preference alone".
 */
@Serializable
data class Backup(
    val schema: Int = CURRENT_SCHEMA,
    val app: String = "MTK God",
    val exportedAtEpochMs: Long = 0L,
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String? = null,
    val preferences: BackupPreferences = BackupPreferences(),
) {
    companion object {
        const val CURRENT_SCHEMA = 1

        /** Refuse files we cannot understand rather than importing them halfway. */
        val SUPPORTED_SCHEMAS = 1..CURRENT_SCHEMA
    }
}

/** Preferences worth carrying between installs. null = do not touch. */
@Serializable
data class BackupPreferences(
    val themeMode: String? = null,
    val palette: String? = null,
    val navBarStyle: String? = null,
    val navBarCustomPalette: String? = null,
    val defaultTabRoute: String? = null,
    val applyMode: String? = null,
    val reapplyIntervalMs: Long? = null,
    val logLevel: String? = null,
    val homeCards: List<HomeCard>? = null,
)
