package magicau.mtkcontroller.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Bottom-navigation destinations.
 *
 * Icons come from material-icons-core (the extended set adds tens of megabytes
 * to the APK for a handful of glyphs). Each destination carries its own accent
 * colour so the bar reads as colourful without needing custom art.
 */
enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val accent: Color,
) {
    HOME("home", "首页", Icons.Filled.Home, Color(0xFF2E7D32)),
    CPU("cpu", "CPU", Icons.Filled.Build, Color(0xFFB4531A)),
    GPU("gpu", "GPU", Icons.Filled.Star, Color(0xFF6A4C93)),
    PROFILES("profiles", "方案", Icons.Filled.List, Color(0xFF1565C0)),
    SETTINGS("settings", "设置", Icons.Filled.Settings, Color(0xFF4A6572)),
    ;

    companion object {
        fun fromRoute(route: String?): Destination =
            entries.firstOrNull { it.route == route } ?: HOME
    }
}

/** Sub-screens reached from Settings. */
object SubRoutes {
    const val DIAGNOSTICS = "settings/diagnostics"
    const val NOTIFICATIONS = "settings/notifications"
    const val TWEAKS = "settings/tweaks"
    const val LOGS = "settings/logs"
    const val LAB = "settings/lab"
    const val LAB_PANEL = "settings/lab/panel"
}
