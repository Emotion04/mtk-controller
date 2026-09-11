package magicau.mtkcontroller.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import magicau.mtkcontroller.R

/**
 * Bottom-navigation destinations.
 *
 * Icons come from material-icons-core (the extended set adds tens of megabytes
 * to the APK for a handful of glyphs). Each destination carries its own accent
 * colour so the bar reads as colourful without needing custom art.
 */
enum class Destination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val accent: Color,
) {
    HOME("home", R.string.nav_home, Icons.Filled.Home, Color(0xFF2E7D32)),
    CPU("cpu", R.string.nav_cpu, Icons.Filled.Build, Color(0xFFB4531A)),
    TOOLS("tools", R.string.nav_tools, Icons.Filled.Star, Color(0xFF6A4C93)),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings, Color(0xFF4A6572)),
    ;

    companion object {
        /**
         * 路由 → 标签。配置的标签如果被开关隐藏，落到第一个可见标签 ——
         * 不能回到一个用户到不了的页面。
         */
        fun fromRoute(route: String?): Destination {
            val target = if (route == "gpu") CPU else entries.firstOrNull { it.route == route }
            return target?.takeIf(FeatureFlags::tabEnabled) ?: FeatureFlags.defaultTab()
        }
    }
}

/** Sub-screens reached from Settings. */
object SubRoutes {
    /** CPU 页的「高级设置」二级页。 */
    const val CPU_ADVANCED = "cpu/advanced"
    /**
     * 设置分类的二级页。**一条路由装全部分类**，分类名走路径参数。
     *
     * 不给每个分类各开一条常量：那样每加一个分类就要动路由表、导航图和这里，
     * 而它们的行为完全一样（都是"渲染某个分类的内容"）。
     */
    const val SETTINGS_CATEGORY = "settings/cat"
    const val SETTINGS_CATEGORY_ARG = "category"
    fun settingsCategory(category: magicau.mtkcontroller.feature.settings.SettingsCategory) =
        "$SETTINGS_CATEGORY/${category.name}"
    const val SMART = "tools/smart"
    const val SMART_BACKUP = "tools/smart/backup"
    const val CONFIG = "tools/config"
    const val MAINTENANCE = "tools/maintenance"
    const val TESTS = "settings/tests"
    const val FEATURE_TRIAL = "settings/tests/trial"
    const val DIAGNOSTICS = "settings/diagnostics"
    const val NOTIFICATIONS = "settings/notifications"
    const val CHANGELOG = "settings/changelog"
    const val TWEAKS = "tools/display"
    const val GAME = "tools/game"
    const val STATUS = "tools/status"
    const val LOGS = "settings/logs"
    const val EXPORT = "settings/export"
    const val ARCHIVE = "settings/archive"
    const val LAB = "settings/lab"
    const val LAB_PANEL = "settings/lab/panel"
    const val LAB_RAW = "settings/lab/raw"
    /** 二级页：一个入口下的控件。参数是 mergeKey（有合并时）或 feature key。 */
    const val LAB_DETAIL = "settings/lab/detail/{entry}"
    fun labDetail(entry: String): String = "settings/lab/detail/$entry"
    const val APP_THERMAL = "thermal/app"
}
