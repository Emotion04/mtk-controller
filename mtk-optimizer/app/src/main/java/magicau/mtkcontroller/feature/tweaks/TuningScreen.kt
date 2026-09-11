package magicau.mtkcontroller.feature.tweaks

import magicau.mtkcontroller.ui.components.LocalNavBarClearance
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.ui.components.*
import magicau.mtkcontroller.ui.navigation.SubRoutes

@Composable
fun TuningScreen(onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp).padding(bottom = LocalNavBarClearance.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tweaks_tuning_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(6.dp))
        SectionLabel(stringResource(R.string.tweaks_section_system))
        SettingsCard { SettingsRow(Icons.Default.Star, MaterialTheme.colorScheme.primary, stringResource(R.string.sub_smart), stringResource(R.string.tweaks_nav_smart_sub), onClick = { onOpen(SubRoutes.SMART) }) }
        SectionLabel(stringResource(R.string.settings_tools))
        SettingsCard {
            SettingsRow(Icons.Default.Build, MaterialTheme.colorScheme.secondary, stringResource(R.string.sub_display), stringResource(R.string.tweaks_nav_display_sub), onClick = { onOpen(SubRoutes.TWEAKS) })
            InsetDivider()
            SettingsRow(Icons.Default.Settings, MaterialTheme.colorScheme.tertiary, stringResource(R.string.sub_config), stringResource(R.string.tweaks_nav_config_sub), onClick = { onOpen(SubRoutes.CONFIG) })
            InsetDivider()
            SettingsRow(Icons.Default.Refresh, MaterialTheme.colorScheme.primary, stringResource(R.string.sub_maintenance), stringResource(R.string.tweaks_nav_maintenance_sub), onClick = { onOpen(SubRoutes.MAINTENANCE) })
            InsetDivider()
            SettingsRow(Icons.Default.PlayArrow, MaterialTheme.colorScheme.error, stringResource(R.string.sub_game), stringResource(R.string.tweaks_nav_game_sub), onClick = { onOpen(SubRoutes.GAME) })
            InsetDivider()
            SettingsRow(Icons.Default.Info, MaterialTheme.colorScheme.primary, stringResource(R.string.sub_status_tools), stringResource(R.string.tweaks_nav_status_sub), onClick = { onOpen(SubRoutes.STATUS) })
        }
    }
}
