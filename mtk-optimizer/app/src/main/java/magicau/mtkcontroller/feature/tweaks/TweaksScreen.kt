package magicau.mtkcontroller.feature.tweaks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.data.tweaks.TweakCommands

/**
 * Hub for the shell-based system tweaks, mirroring the reference app's
 * "实用工具" tab. Kept separate from the home dashboard so the features have a
 * findable home of their own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TweaksScreen(
    state: TweaksUiState,
    onTouch: (Boolean) -> Unit,
    onTouchRate: (Int) -> Unit,
    onFrameInterpolation: (Boolean) -> Unit,
    onFrameInterpolationSr: (Boolean) -> Unit,
    onRotationSuggestion: (Boolean) -> Unit,
    onThermalBrightness: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "这些开关通过 Shizuku 执行系统命令,部分属性是否生效取决于 ROM。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!state.canRun) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("需要 Shizuku 授权", style = MaterialTheme.typography.titleSmall)
                            AssistChip(onClick = {}, label = { Text(state.privilege.mode.name) })
                        }
                        Text(
                            state.privilege.message ?: "授权后即可使用下面的开关。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                            Text("授权 Shizuku")
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToggleRow(
                        title = "触控优化",
                        subtitle = "提高触控采样率、降低响应延迟",
                        checked = state.touchEnabled,
                        enabled = state.canRun,
                        onCheckedChange = onTouch,
                    )
                    if (state.touchEnabled) {
                        Text(
                            "采样率",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TweakCommands.TOUCH_RATES.forEach { rate ->
                                FilterChip(
                                    selected = state.touchRate == rate,
                                    onClick = { onTouchRate(rate) },
                                    enabled = state.canRun,
                                    label = { Text("${rate}Hz") },
                                )
                            }
                        }
                        Text(
                            "包含 touch.report_rate / touch_scan_press_time_delay / debug.sf.* 等约 24 条命令",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToggleRow(
                        title = "全局插帧",
                        subtitle = "写入厂商插帧参数,实际效果取决于设备是否配备插帧芯片",
                        checked = state.frameInterpolation,
                        enabled = state.canRun,
                        onCheckedChange = onFrameInterpolation,
                    )
                    ToggleRow(
                        title = "超分插帧",
                        subtitle = "使用超分辨率插帧参数",
                        checked = state.frameInterpolationSr,
                        enabled = state.canRun,
                        onCheckedChange = onFrameInterpolationSr,
                    )
                    Text(
                        "settings put system gamecube_frame_interpolation \"0:-1:0:0:0\"",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToggleRow(
                        title = "旋转建议",
                        subtitle = "检测到屏幕转向时,在导航栏显示旋转按钮(Android 原生)",
                        checked = state.rotationSuggestion,
                        enabled = state.canRun,
                        onCheckedChange = onRotationSuggestion,
                    )
                    Text(
                        "settings put secure show_rotation_suggestions",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToggleRow(
                        title = "高温降亮度恢复",
                        subtitle = "设备过热被系统压低亮度时自动恢复",
                        checked = state.thermalBrightness,
                        enabled = state.canRun,
                        onCheckedChange = onThermalBrightness,
                    )
                    Text(
                        "开启后会在通知栏常驻一个前台服务,监听亮度变化并在过热时写回你设定的值。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("通知管理", style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider()
                    Text(
                        "按应用管理 Android 原生通知。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onOpenNotifications, modifier = Modifier.fillMaxWidth()) {
                        Text("打开通知管理")
                    }
                }
            }
        }

        state.message?.let { message ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (state.lastFailures.isNotEmpty()) {
                        Text(
                            state.lastFailures.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
