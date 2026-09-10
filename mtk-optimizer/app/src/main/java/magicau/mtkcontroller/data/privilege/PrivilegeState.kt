package magicau.mtkcontroller.data.privilege

/**
 * How much privilege we currently hold. Drives which controls are enabled.
 *
 * ROOT and ADB both come from Shizuku; the difference matters because cpufreq
 * sysfs nodes are usually root-owned, so governor writes only work under ROOT.
 */
enum class PrivilegeMode {
    /** No Shizuku binder: read-only telemetry, every control disabled. */
    NONE,

    /** Shizuku reachable but permission not granted yet. */
    AVAILABLE,

    /** Shizuku running as shell (uid 2000). Binder calls work; sysfs writes may not. */
    ADB,

    /** Shizuku running as root (uid 0). Everything works. */
    ROOT,
    ;

    val canElevate: Boolean get() = this == ROOT || this == ADB
    val canWriteSysfs: Boolean get() = this == ROOT
}

data class PrivilegeState(
    val mode: PrivilegeMode = PrivilegeMode.NONE,
    val permissionGranted: Boolean = false,
    val binderAlive: Boolean = false,
    val uid: Int = -1,
    val shizukuVersion: Int = -1,
    /** Human-readable reason, shown in the auth screen when something is missing. */
    val message: String? = null,
) {
    companion object {
        /** uid of the `shell` user; Shizuku uses it when started over adb. */
        const val UID_SHELL = 2000
        const val UID_ROOT = 0
    }
}
