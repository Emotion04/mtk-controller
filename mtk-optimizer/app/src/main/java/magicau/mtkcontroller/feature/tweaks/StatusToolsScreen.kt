package magicau.mtkcontroller.feature.tweaks

import magicau.mtkcontroller.ui.components.SUB_SCREEN_EXTRA_PADDING
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.net.Uri
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.diag.BatteryReportParser
import magicau.mtkcontroller.data.diag.EnvironmentProbe
import magicau.mtkcontroller.data.diag.HostMetrics
import magicau.mtkcontroller.data.diag.SystemShortcuts
import magicau.mtkcontroller.data.privilege.FilePicker
import magicau.mtkcontroller.data.privilege.Shortcuts
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.ui.components.*
import magicau.mtkcontroller.ui.text

/**
 * 本机状态与工具。
 *
 * 统一的只读入口：温度 / GPU / DDR / 内存四张卡片，加上零权限的系统页面直达、
 * 拨号暗码和环境检测。
 *
 * 这些不写任何系统状态，所以不需要 Shizuku。拿不到的节点明确显示"本机未检出"，
 * 不用 0 或空白冒充读数。
 */
@Composable
fun StatusToolsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var temps by remember { mutableStateOf<List<HostMetrics.Temperature>>(emptyList()) }
    var gpu by remember { mutableStateOf<HostMetrics.Gpu?>(null) }
    var ddr by remember { mutableStateOf<HostMetrics.Reading?>(null) }
    var memory by remember { mutableStateOf<HostMetrics.Memory?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // 每项读不到的原因单独挂到对应卡片；不能合成一句笼统的"不可读"。
    var reasons by remember { mutableStateOf<Map<String, Message>>(emptyMap()) }
    var tick by remember { mutableIntStateOf(0) }
    var envFindings by remember { mutableStateOf<List<EnvironmentProbe.Finding>>(emptyList()) }
    var bugreportText by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var zipPicker by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        loadError = null
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                listOf(HostMetrics.temperatures(), HostMetrics.gpu(), HostMetrics.ddrFreqKhz(), HostMetrics.memory())
            }.getOrNull()
        }
        if (loaded == null) {
            loadError = context.getString(R.string.state_read_failed)
        } else {
            @Suppress("UNCHECKED_CAST")
            temps = loaded[0] as List<HostMetrics.Temperature>
            gpu = loaded[1] as HostMetrics.Gpu
            ddr = loaded[2] as HostMetrics.Reading
            memory = loaded[3] as HostMetrics.Memory
            reasons = HostMetrics.unavailableReasons
        }
    }

    // 环境检测读的是本机文件与端口，不需要授权。
    LaunchedEffect(Unit) {
        val pm = context.packageManager
        envFindings = withContext(Dispatchers.IO) {
            EnvironmentProbe.collect { pkg ->
                runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
            }
        }
        EnvironmentProbe.remember(envFindings)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp).padding(bottom = SUB_SCREEN_EXTRA_PADDING),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.state_realtime))
        loadError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        val notRead = stringResource(R.string.state_not_read)
        val celsiusFormat = remember { "%.1f°C" }

        MetricCard(stringResource(R.string.state_title_temperature), stringResource(R.string.metric_temperature)) {
            val tempReason = reasons[HostMetrics.TEMPERATURE]
            if (temps.isEmpty()) {
                if (tempReason != null) {
                    Text(tempReason.text(), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(notRead, style = MaterialTheme.typography.bodySmall)
                }
            } else temps.sortedByDescending { it.celsius }.take(12).forEach { t ->
                // 非 0 是 Android 的节流档，说明这一路正在被限制 —— 那本身就是"上限被压"的证据。
                val status = when (t.status) {
                    0 -> ""
                    1 -> "，" + stringResource(R.string.hal_throttle_light)
                    2 -> "，" + stringResource(R.string.hal_throttle_moderate)
                    3 -> "，" + stringResource(R.string.hal_throttle_severe)
                    else -> "，" + stringResource(R.string.hal_throttle_status, t.status)
                }
                Text(
                    stringResource(
                        R.string.state_temp_line,
                        t.name,
                        String.format(celsiusFormat, t.celsius) + status,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { tick++ }) { Text(stringResource(R.string.diag_refresh)) }
        }

        MetricCard(stringResource(R.string.state_title_gpu), stringResource(R.string.metric_gpu)) {
            val g = gpu
            when {
                g == null -> Text(notRead, style = MaterialTheme.typography.bodySmall)
                g.busyPercent != null -> {
                    Text(
                        stringResource(R.string.state_gpu_busy, g.busyPercent),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    g.freqKhz?.let {
                        Text(
                            stringResource(R.string.state_gpu_freq, it),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                g.freqKhz != null -> {
                    Text(
                        stringResource(R.string.state_gpu_freq, g.freqKhz),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(stringResource(R.string.metric_gpu_freq_only), style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    val gpuReason = reasons[HostMetrics.GPU]
                    if (gpuReason != null) {
                        Text(gpuReason.text(), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(g.source.text(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            g?.let {
                Text(
                    stringResource(R.string.state_source, it.source.text()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        MetricCard(stringResource(R.string.state_title_ddr), stringResource(R.string.metric_ddr)) {
            val d = ddr
            // note 里已经带了完整原因（含 root 提示），不要再叠加一遍 reasons。
            val ddrText = when {
                d?.value != null -> stringResource(R.string.state_khz, d.value)
                d?.note != null -> d.note.text()
                else -> reasons[HostMetrics.DDR]?.text() ?: stringResource(R.string.metric_unreadable)
            }
            Text(ddrText, style = MaterialTheme.typography.bodySmall)
            d?.node?.let { Text(stringResource(R.string.state_source, it), style = MaterialTheme.typography.bodySmall) }
        }

        MetricCard(stringResource(R.string.state_title_memory), stringResource(R.string.metric_memory)) {
            val m = memory
            fun kb(v: Long?): String =
                v?.let { "%.0f MiB".format(it / 1024.0) } ?: context.getString(R.string.state_kb_unread)
            if (m == null || m.totalKb == null) {
                Text(stringResource(R.string.state_no_meminfo), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    stringResource(R.string.state_mem_total, kb(m.totalKb), kb(m.availableKb)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.state_mem_swap, kb(m.swapTotalKb), kb(m.swapFreeKb)),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (m.zramComprKb != null && m.zramOrigKb != null && m.zramOrigKb > 0) {
                    Text(
                        stringResource(
                            R.string.state_mem_zram,
                            kb(m.zramComprKb),
                            kb(m.zramOrigKb),
                            "%.2f".format(m.zramComprKb.toDouble() / m.zramOrigKb),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        SectionLabel(stringResource(R.string.state_zero_permission))
        SettingsCard {
            SYSTEM_PAGES.forEach { (labelRes, descriptionRes, component) ->
                SettingsRow(
                    Icons.Default.Settings, MaterialTheme.colorScheme.primary,
                    stringResource(labelRes), stringResource(descriptionRes),
                    onClick = { scope.launch { Shortcuts.openPage(context, component).let { note = it } } },
                )
                InsetDivider()
            }
            SettingsRow(
                Icons.Default.Star, MaterialTheme.colorScheme.secondary,
                stringResource(R.string.state_battery_report),
                stringResource(R.string.state_battery_report_sub),
                onClick = { zipPicker = true },
            )
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        SectionLabel(stringResource(R.string.state_dark_codes))
        SettingsCard {
            SystemShortcuts.DARK_CODES.forEach { (code, descriptionRes) ->
                SettingsRow(
                    Icons.Default.Info, MaterialTheme.colorScheme.tertiary, code,
                    stringResource(descriptionRes),
                    onClick = { scope.launch { Shortcuts.dialCode(context, code).let { note = it } } },
                )
                InsetDivider()
            }
        }

        SectionLabel(stringResource(R.string.state_env_probe))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoHeading(stringResource(R.string.state_env_probe), stringResource(R.string.env_probe_info))
                if (envFindings.isEmpty()) {
                    Text(
                        stringResource(R.string.state_env_clean),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else for (f in envFindings) {
                    Text(
                        stringResource(R.string.state_env_finding, f.severity, stringResource(f.nameRes), f.detail),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (zipPicker) {
        ZipPicker(onPicked = { uri ->
            zipPicker = false
            bugreportText = FilePicker.pickAndParse(context, uri)
        }, onDismiss = { zipPicker = false })
    }

    bugreportText?.let { text ->
        AlertDialog(
            onDismissRequest = { bugreportText = null },
            title = { Text(stringResource(R.string.state_report_title)) },
            text = { SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = {
                TextButton(onClick = { bugreportText = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

private val SYSTEM_PAGES = listOf(
    Triple(R.string.state_sys_battery, R.string.state_open_system, "com.android.settings/.Settings\$BatterySettingsActivity"),
    Triple(R.string.state_sys_display, R.string.state_open_system, "com.android.settings/.Settings\$DisplaySettingsActivity"),
    Triple(R.string.state_sys_sound, R.string.state_open_system, "com.android.settings/.Settings\$SoundSettingsActivity"),
    Triple(R.string.state_sys_storage, R.string.state_open_system, "com.android.settings/.Settings\$StorageSettingsActivity"),
    Triple(R.string.state_sys_apps, R.string.state_open_system, "com.android.settings/.Settings\$ManageApplicationsSettingsActivity"),
    Triple(R.string.state_sys_developer, R.string.state_open_system, "com.android.settings/.Settings\$DevelopmentSettingsDashboardFragmentActivity"),
)

@Composable
private fun MetricCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoHeading(title, subtitle)
            content()
        }
    }
}

/** 系统 zip 选择器；返回选中的 URI，由调用方解析。 */
@Composable
private fun ZipPicker(onPicked: (android.net.Uri) -> Unit, onDismiss: () -> Unit) {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) onPicked(uri) }

    LaunchedEffect(Unit) { launcher.launch("application/zip") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.state_pick_bugreport)) },
        text = { Text(stringResource(R.string.state_bugreport_pick), style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
