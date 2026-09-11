package magicau.mtkcontroller.data

import magicau.mtkcontroller.AppBuildTime
import magicau.mtkcontroller.BuildConfig

/**
 * 版本有效期。
 *
 * **从编译时间算，不从安装时间算。** 时间戳编译进 APK，卸载重装同一个包不会重置；
 * 清数据、换用户也不会。想继续用只能装新版本。
 *
 * 已知边界：改系统时间可以绕过。这是离线方案的固有缺陷；真要做到不可绕过只能联网
 * 取时或服务端校验，代价和这三十天的目的不匹配。把时间拨到编译之前会被判为异常
 * （见 [CLOCK_TOLERANCE_MS]），拨到编译之后但还没到期的区间无法识别。
 *
 * **debug 构建也受这条约束。** Canary 就是 debug 包；如果 debug 豁免，用户改系统时间
 * 就会"验证不到过期"，而这正是要验证的行为。开发时重新构建会刷新编译时间。
 */
object AppExpiry {

    const val VERSION_LIFETIME_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** 允许构建机与设备有 1 天的时钟差；超过就当作被拨过时间。 */
    private const val CLOCK_TOLERANCE_MS: Long = 24L * 60 * 60 * 1000

    fun isExpired(
        now: Long = System.currentTimeMillis(),
        buildTime: Long = AppBuildTime.MS,
        force: Boolean = BuildConfig.FORCE_EXPIRY,
    ): Boolean {
        if (force) return true
        // 构建时间没写进去：不因此把所有人挡在外面，但也意味着这一条没在保护。
        if (buildTime <= 0L) return false
        // 设备时间早于构建时间超过容差：视为被拨回。
        if (now < buildTime - CLOCK_TOLERANCE_MS) return true
        return now - buildTime >= VERSION_LIFETIME_MS
    }
}
