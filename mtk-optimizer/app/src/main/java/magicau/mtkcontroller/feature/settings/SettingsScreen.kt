package magicau.mtkcontroller.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.ui.components.InsetDivider
import magicau.mtkcontroller.ui.components.SectionLabel
import magicau.mtkcontroller.ui.components.SettingsCard
import magicau.mtkcontroller.ui.components.SettingsRow
import magicau.mtkcontroller.domain.model.CpuControlStyle
import magicau.mtkcontroller.domain.model.NavBarStyle
import magicau.mtkcontroller.ui.navigation.Destination
import magicau.mtkcontroller.ui.theme.AppPalette

private val PaletteIcon = Color(0xFF6A4C93)
private val TabIcon = Color(0xFF2E7D32)
private val ToolsIcon = Color(0xFFB4531A)
private val NotifyIcon = Color(0xFF1565C0)
private val DiagIcon = Color(0xFF0B6E6E)
private val AboutIcon = Color(0xFF8A6A4F)
private val NavIcon = Color(0xFF7A8FA6)
private val ControlIcon = Color(0xFF7E9179)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSetDefaultTab: (Destination) -> Unit,
    onSetPalette: (AppPalette) -> Unit,
    onSetNavBarStyle: (NavBarStyle) -> Unit,
    onSetNavBarCustomPalette: (AppPalette) -> Unit,
    onSetCpuControlStyle: (CpuControlStyle) -> Unit,
    onOpenTweaks: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var paletteExpanded by remember { mutableStateOf(false) }
    var navStyleExpanded by remember { mutableStateOf(false) }
    var tabExpanded by remember { mutableStateOf(false) }
    var cpuStyleExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(top = 12.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
        )

        SectionLabel("外观")
        SettingsCard {
            SettingsRow(
                icon = Icons.Filled.Favorite,
                iconTint = PaletteIcon,
                title = "主题色",
                subtitle = state.palette.label,
                onClick = { paletteExpanded = !paletteExpanded },
            )
            AnimatedVisibility(visible = paletteExpanded) {
                Column(modifier = Modifier.padding(bottom = 10.dp)) {
                    AppPalette.entries.groupBy { it.group }.forEach { (group, palettes) ->
                        Text(
                            group,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 62.dp, top = 6.dp, bottom = 2.dp),
                        )
                        palettes.forEach { palette ->
                            PaletteRow(
                                palette = palette,
                                selected = state.palette == palette,
                                onClick = { onSetPalette(palette) },
                            )
                        }
                    }
                }
            }

            InsetDivider()
            SettingsRow(
                icon = Icons.Filled.List,
                iconTint = NavIcon,
                title = "底栏样式",
                subtitle = state.navBarStyle.description,
                trailingText = state.navBarStyle.label,
                onClick = { navStyleExpanded = !navStyleExpanded },
            )
            AnimatedVisibility(visible = navStyleExpanded) {
                Column(modifier = Modifier.padding(bottom = 10.dp)) {
                    NavBarStyle.entries.forEach { style ->
                        OptionRow(
                            label = style.label,
                            detail = style.description,
                            selected = state.navBarStyle == style,
                            onClick = { onSetNavBarStyle(style) },
                        )
                    }
                    if (state.navBarStyle == NavBarStyle.CUSTOM) {
                        Text(
                            "底栏色系",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 62.dp, top = 6.dp, bottom = 2.dp),
                        )
                        AppPalette.entries.forEach { palette ->
                            OptionRow(
                                label = palette.label,
                                detail = palette.group,
                                selected = state.navBarCustomPalette == palette,
                                swatch = palette.accent,
                                onClick = { onSetNavBarCustomPalette(palette) },
                            )
                        }
                    }
                }
            }
        }

        SectionLabel("通用")
        SettingsCard {
            SettingsRow(
                icon = Icons.Filled.Home,
                iconTint = TabIcon,
                title = "默认标签页",
                subtitle = "打开 App 时显示",
                trailingText = state.defaultTab.label,
                onClick = { tabExpanded = !tabExpanded },
            )
            AnimatedVisibility(visible = tabExpanded) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Destination.entries.forEach { destination ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = state.defaultTab == destination,
                                    role = Role.RadioButton,
                                    onClick = { onSetDefaultTab(destination) },
                                )
                                .padding(start = 56.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RadioButton(selected = state.defaultTab == destination, onClick = null)
                            Icon(destination.icon, contentDescription = null, modifier = Modifier.size(17.dp))
                            Text(destination.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            InsetDivider()
            SettingsRow(
                icon = Icons.Filled.Build,
                iconTint = ControlIcon,
                title = "CPU 控件样式",
                subtitle = state.cpuControlStyle.description,
                trailingText = state.cpuControlStyle.label,
                onClick = { cpuStyleExpanded = !cpuStyleExpanded },
            )
            AnimatedVisibility(visible = cpuStyleExpanded) {
                Column(modifier = Modifier.padding(bottom = 10.dp)) {
                    CpuControlStyle.entries.forEach { style ->
                        OptionRow(
                            label = style.label,
                            detail = style.description,
                            selected = state.cpuControlStyle == style,
                            onClick = { onSetCpuControlStyle(style) },
                        )
                    }
                }
            }
        }

        SectionLabel("功能")
        SettingsCard {
            SettingsRow(
                icon = Icons.Filled.Build,
                iconTint = ToolsIcon,
                title = "实用工具",
                subtitle = "触控优化、插帧、旋转建议、高温降亮度",
                onClick = onOpenTweaks,
            )
            InsetDivider()
            SettingsRow(
                icon = Icons.Filled.Notifications,
                iconTint = NotifyIcon,
                title = "通知管理",
                subtitle = "按应用管理 Android 原生通知",
                onClick = onOpenNotifications,
            )
        }

        SectionLabel("其他")
        SettingsCard {
            SettingsRow(
                icon = Icons.Filled.Info,
                iconTint = DiagIcon,
                title = "诊断信息",
                subtitle = "Shizuku 授权、PowerHAL、节点探测结果",
                onClick = onOpenDiagnostics,
            )
        }

        SectionLabel("关于")
        AboutCard()
    }
}

@Composable
private fun PaletteRow(
    palette: AppPalette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 56.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(palette.accent),
        )
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(palette.accent.copy(alpha = 0.35f)),
        )
        Text(palette.label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OptionRow(
    label: String,
    detail: String? = null,
    selected: Boolean,
    swatch: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 56.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        if (swatch != null) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(swatch),
            )
        }
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
