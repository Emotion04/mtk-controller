package magicau.mtkcontroller.feature.cpu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.thermal.FreqLadder
import magicau.mtkcontroller.data.thermal.ThermalPolicy
import magicau.mtkcontroller.ui.theme.LocalShapeStyle
import kotlin.math.abs

/**
 * 温控档位的说明页。
 *
 * ## 两种图，各回答一个问题
 *
 * - **阶梯矩阵表**（[ThermalLadderTable]）：行是温度、列是核簇，格子里是频率条和数值。
 *   它回答“**同一温度下三个核簇分别被压到多少**”，列与列可以对照。
 * - **阶梯折线图**（[ThermalLadderChart]）：横轴温度、纵轴频率，每个核簇一条线。
 *   它回答“**温度升高时这一簇怎么掉**”。
 *
 * 配置是阶梯函数，所以折线必须画成**水平段 + 垂直跳变**，不能做平滑曲线 ——
 * 平滑会在两个配置点之间插出根本不存在的频率。拖动/点击图上的任意位置，
 * 会吸附到最近的配置温度，并在表里高亮同一行。
 */
@Composable
fun ThermalPolicySheet(
    indices: List<Int>,
    policies: Map<Int, ThermalPolicy>,
    full: Map<Int, String>,
    currentIndex: Int?,
    showDetail: Boolean,
    clusterLabel: @Composable (Int) -> String,
    physicalMaxByCluster: Map<Int, Int>,
    onToggleDetail: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.thermal_policy_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                    TextButton(onClick = onToggleDetail) {
                        Text(
                            stringResource(
                                if (showDetail) R.string.thermal_detail_hide
                                else R.string.thermal_detail_show,
                            ),
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(indices) { index ->
                        val policy = policies[index]
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "%02d".format(index),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (index == currentIndex) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                if (index == currentIndex) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.thermal_current),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            if (policy == null) {
                                Text(
                                    stringResource(R.string.thermal_read_failed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else if (policy.decodeError != null) {
                                Text(
                                    policy.decodeError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else {
                                ThermalPolicyOverview(
                                    policy = policy,
                                    clusterLabel = clusterLabel,
                                    showDetail = showDetail,
                                    fullText = full[index],
                                    physicalMaxByCluster = physicalMaxByCluster,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一个档位的完整可视化：曲线 + 矩阵表 + 非频率参数。 */
@Composable
fun ThermalPolicyOverview(
    policy: ThermalPolicy,
    clusterLabel: @Composable (Int) -> String,
    modifier: Modifier = Modifier,
    showDetail: Boolean = false,
    fullText: String? = null,
    /** 各核簇的物理最高频率（MHz）；纵轴取超大核上限，并用于百分比。 */
    physicalMaxByCluster: Map<Int, Int> = emptyMap(),
) {
    val ladders = policy.cpuLadders.toSortedMap()
    val enabled = policy.cpuMappingEnabled != false
    val thresholds = ladders.values.flatMap { it.thresholdsC }.distinct().sorted()
    var selectedTemp by remember(policy) { mutableStateOf<Int?>(null) }
    var hiddenClusters by remember(policy) { mutableStateOf<Set<Int>>(emptySet()) }
    var normalized by remember(policy) { mutableStateOf(false) }
    val canNormalize = physicalMaxByCluster.isNotEmpty()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ladders.isEmpty() || !enabled || thresholds.isEmpty()) {
            Text(
                stringResource(
                    if (!enabled) R.string.thermal_mapping_disabled else R.string.thermal_no_pressure,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ladders.values.firstNotNullOfOrNull { it.sensor }?.let { sensor ->
                Text(
                    stringResource(R.string.thermal_sensor_from, sensor),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canNormalize) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !normalized,
                        onClick = { normalized = false },
                        label = { Text(stringResource(R.string.thermal_view_absolute)) },
                    )
                    FilterChip(
                        selected = normalized,
                        onClick = { normalized = true },
                        label = { Text(stringResource(R.string.thermal_view_ratio)) },
                    )
                }
            }
            ThermalLadderChart(
                ladders = ladders,
                thresholds = thresholds,
                clusterLabel = clusterLabel,
                selectedTemp = selectedTemp,
                onSelectTemp = { selectedTemp = it },
                physicalMaxByCluster = physicalMaxByCluster,
                normalized = normalized,
                hiddenClusters = hiddenClusters,
                onToggleCluster = { key ->
                    hiddenClusters = if (key in hiddenClusters) hiddenClusters - key else hiddenClusters + key
                },
            )
            if (selectedTemp != null) {
                SelectedFrequencyTable(
                    tempC = selectedTemp!!,
                    ladders = ladders.filterKeys { it !in hiddenClusters },
                    clusterLabel = clusterLabel,
                    physicalMaxByCluster = physicalMaxByCluster,
                )
            } else {
                Text(
                    stringResource(R.string.thermal_chart_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ThermalLadderTable(
                policy = policy,
                clusterLabel = clusterLabel,
                selectedTemp = selectedTemp,
                onSelectTemp = { selectedTemp = it },
                physicalMaxByCluster = physicalMaxByCluster,
                normalized = normalized,
            )
        }
        ThermalPolicyNotes(policy, showDetail, fullText)
    }
}

/**
 * 阶梯折线图：横轴温度，纵轴允许频率，每个核簇一条线。
 *
 * 只画水平段和垂直跳变；不插值、不平滑。拖动或点击图上任意位置会吸附到最近的
 * 配置温度，并高亮对应的表格行。
 */
@Composable
private fun ThermalLadderChart(
    ladders: Map<Int, FreqLadder>,
    thresholds: List<Int>,
    clusterLabel: @Composable (Int) -> String,
    selectedTemp: Int?,
    onSelectTemp: (Int) -> Unit,
    physicalMaxByCluster: Map<Int, Int>,
    normalized: Boolean,
    hiddenClusters: Set<Int>,
    onToggleCluster: (Int) -> Unit,
) {
    // 纵轴顶端取硬件最高频率（超大核），不是当前表里的最大档 —— 否则小核很低、
    // 大核的表会把图撑满，看不出“相对这台机器的物理上限还剩多少”。
    val tableMax = ladders.values.flatMap { it.freqsMHz }.maxOrNull() ?: 1
    val axisMax = if (normalized) 100 else
        maxOf(tableMax, physicalMaxByCluster.values.maxOrNull() ?: 0).coerceAtLeast(1)
    val minT = thresholds.first()
    val maxT = thresholds.last().coerceAtLeast(minT + 1)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val colors = THERMAL_SERIES_COLORS
    val bg = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    val crosshair = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    // 横轴固定 5 个刻度；纵轴 5 档频率。数值直接写在轴边，读者不用猜比例。
    val xTicks = (0..4).map { i -> minT + ((maxT - minT) * i / 4f).toInt() }.distinct()
    val visible = ladders.filterKeys { it !in hiddenClusters }

    fun snap(x: Float, width: Int): Int {
        if (width <= 0) return thresholds.first()
        val ratio = (x / width).coerceIn(0f, 1f)
        val raw = minT + ratio * (maxT - minT)
        return thresholds.minByOrNull { abs(it - raw) } ?: thresholds.first()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(176.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(
                modifier = Modifier.width(42.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                for (i in 4 downTo 0) {
                    Text(
                        if (normalized) "${i * 25}%" else formatFreq(axisMax * i / 4),
                        style = MaterialTheme.typography.labelSmall,
                        color = label,
                    )
                }
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(LocalShapeStyle.current.chip)
                    .background(bg)
                    .pointerInput(thresholds) {
                        detectTapGestures { offset -> onSelectTemp(snap(offset.x, size.width)) }
                    }
                    .pointerInput(thresholds) {
                        detectDragGestures(
                            onDragStart = { offset -> onSelectTemp(snap(offset.x, size.width)) },
                            onDrag = { change, _ -> onSelectTemp(snap(change.position.x, size.width)) },
                        )
                    },
            ) {
                val w = size.width
                val h = size.height
                // 上下左右都留出余量：最高点和最右点不要贴着边框。
                val leftPad = 4.dp.toPx()
                val rightPad = 14.dp.toPx()
                val topPad = 10.dp.toPx()
                val bottomPad = 6.dp.toPx()
                fun xFor(temp: Int): Float =
                    leftPad + ((temp - minT).toFloat() / (maxT - minT).toFloat())
                        .coerceIn(0f, 1f) * (w - leftPad - rightPad)
                fun yFor(freq: Int, key: Int): Float {
                    val denom = if (normalized) {
                        (physicalMaxByCluster[key] ?: tableMax).coerceAtLeast(1)
                    } else axisMax
                    val value = if (normalized) freq.toFloat() * 100f / denom else freq.toFloat()
                    return topPad + (1f - (value / axisMax.toFloat()).coerceIn(0f, 1f)) *
                        (h - topPad - bottomPad)
                }

                // 横向 + 纵向网格，对应轴上的 5 个刻度。
                repeat(5) { i ->
                    val y = topPad + (h - topPad - bottomPad) * i / 4f
                    drawLine(grid, Offset(leftPad, y), Offset(w - rightPad, y), strokeWidth = 1f)
                }
                xTicks.forEach { temp ->
                    val x = xFor(temp)
                    drawLine(
                        grid.copy(alpha = 0.6f),
                        Offset(x, topPad), Offset(x, h - bottomPad), strokeWidth = 1f,
                    )
                }

                visible.entries.forEach { (key, ladder) ->
                    val lineIndex = ladders.keys.indexOf(key).coerceAtLeast(0)
                    val color = colors[lineIndex % colors.size]
                    val pairs = ladder.thresholdsC.zip(ladder.freqsMHz).sortedBy { it.first }
                    if (pairs.isEmpty()) return@forEach
                    val path = Path()
                    var current = pairs.first().second
                    path.moveTo(xFor(minT), yFor(current, key))
                    pairs.forEach { (temp, freq) ->
                        val x = xFor(temp)
                        path.lineTo(x, yFor(current, key))
                        path.lineTo(x, yFor(freq, key))
                        current = freq
                    }
                    path.lineTo(xFor(maxT), yFor(current, key))
                    // 重合的线用不同虚线样式，即使颜色相近也能分辨。
                    val effect = when (lineIndex % 3) {
                        1 -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 7f))
                        2 -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
                        else -> null
                    }
                    drawPath(path, color, style = Stroke(width = 3f, pathEffect = effect))
                    pairs.forEach { (temp, freq) ->
                        drawCircle(color, radius = 3.25f, center = Offset(xFor(temp), yFor(freq, key)))
                    }
                }

                selectedTemp?.let { temp ->
                    val x = xFor(temp)
                    drawLine(crosshair, Offset(x, topPad), Offset(x, h - bottomPad), strokeWidth = 1.5f)
                    visible.entries.forEach { (key, ladder) ->
                        ladder.freqAt(temp)?.let { freq ->
                            val lineIndex = ladders.keys.indexOf(key).coerceAtLeast(0)
                            drawCircle(
                                colors[lineIndex % colors.size],
                                radius = 5f,
                                center = Offset(x, yFor(freq, key)),
                            )
                        }
                    }
                }
            }
        }

        // 横轴刻度
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(48.dp))
            Row(Modifier.weight(1f)) {
                xTicks.forEach { temp ->
                    Text(
                        "$temp°",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = label,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.thermal_axis_temp),
            style = MaterialTheme.typography.labelSmall,
            color = label,
            modifier = Modifier.padding(start = 48.dp),
        )

        // 图例可点：重合时先隐藏一两条，剩下的关系就看得清。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ladders.keys.forEach { key ->
                val lineIndex = ladders.keys.indexOf(key).coerceAtLeast(0)
                val shown = key !in hiddenClusters
                Row(
                    modifier = Modifier.clickable { onToggleCluster(key) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.size(8.dp).clip(LocalShapeStyle.current.chip)
                            .background(colors[lineIndex % colors.size].copy(alpha = if (shown) 1f else 0.25f)),
                    )
                    Text(
                        clusterLabel(key),
                        style = MaterialTheme.typography.labelSmall,
                        color = label.copy(alpha = if (shown) 1f else 0.4f),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.thermal_chart_hint),
            style = MaterialTheme.typography.labelSmall,
            color = label,
        )
    }
}

/** 拖动曲线后显示的那一行：温度 + 每个核簇的具体上限。 */
@Composable
private fun SelectedFrequencyTable(
    tempC: Int,
    ladders: Map<Int, FreqLadder>,
    clusterLabel: @Composable (Int) -> String,
    physicalMaxByCluster: Map<Int, Int>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.thermal_selected_temp, tempC),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        ladders.forEach { (key, ladder) ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    clusterLabel(key),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val freq = ladder.freqAt(tempC)
                val pct = freq?.let { f ->
                    physicalMaxByCluster[key]?.takeIf { it > 0 }?.let { max -> f * 100 / max }
                }
                Text(
                    (freq?.let(::formatFreq) ?: "—") + (pct?.let { "  $it%" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * 一张“温度 × 核簇”的矩阵表。
 *
 * 横轴是核簇，纵轴是温度。每格画一根横条：长度是该频率相对本档最高频率的比例，
 * 颜色按温度从冷到暖。**保留它**：曲线看趋势，表格看同一温度下的横向对比和精确值。
 */
@Composable
fun ThermalLadderTable(
    policy: ThermalPolicy,
    clusterLabel: @Composable (Int) -> String,
    modifier: Modifier = Modifier,
    selectedTemp: Int? = null,
    onSelectTemp: ((Int) -> Unit)? = null,
    physicalMaxByCluster: Map<Int, Int> = emptyMap(),
    normalized: Boolean = false,
) {
    val ladders = policy.cpuLadders.toSortedMap()
    if (ladders.isEmpty() || policy.cpuMappingEnabled == false) {
        Text(
            stringResource(
                if (policy.cpuMappingEnabled == false) R.string.thermal_mapping_disabled
                else R.string.thermal_no_pressure,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val thresholds = ladders.values.flatMap { it.thresholdsC }.distinct().sorted()
    val maxFreq = ladders.values.flatMap { it.freqsMHz }.maxOrNull()?.coerceAtLeast(1) ?: 1
    val minT = thresholds.firstOrNull() ?: 0
    val maxT = thresholds.lastOrNull() ?: 1
    val cool = MaterialTheme.colorScheme.tertiary
    val hot = MaterialTheme.colorScheme.error
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Spacer(Modifier.width(LABEL_COLUMN_WIDTH))
            ladders.keys.forEach { key ->
                Text(
                    clusterLabel(key),
                    modifier = Modifier.weight(1f).padding(horizontal = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }

        thresholds.forEach { temp ->
            val heat = if (maxT > minT) (temp - minT).toFloat() / (maxT - minT) else 0f
            val rowModifier = Modifier
                .fillMaxWidth()
                .then(if (temp == selectedTemp) Modifier.background(highlight) else Modifier)
                .then(
                    if (onSelectTemp != null) Modifier.clickable { onSelectTemp(temp) }
                    else Modifier,
                )
                .padding(vertical = 1.dp)
            Row(
                modifier = rowModifier,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$temp°C",
                    modifier = Modifier.width(LABEL_COLUMN_WIDTH),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ladders.forEach { (key, ladder) ->
                    FreqCell(
                        mhz = ladder.freqAt(temp),
                        maxFreqMHz = maxFreq,
                        barColor = lerp(cool, hot, heat),
                        modifier = Modifier.weight(1f),
                        physicalMaxMHz = physicalMaxByCluster[key],
                        normalized = normalized,
                    )
                }
            }
        }
    }
}

private val THERMAL_SERIES_COLORS = listOf(
    Color(0xFF0B6E6E),
    Color(0xFF4A6FA5),
    Color(0xFFB4651A),
    Color(0xFF6A4C93),
    Color(0xFF2E7D32),
    Color(0xFF9C4146),
)

/** 统一用 GHz，避免同一张图里一半 0.85G、一半 850M。 */
private fun formatFreq(mhz: Int): String {
    if (mhz <= 0) return "0G"
    val giga = mhz / 1000.0
    val text = "%.2f".format(giga).trimEnd('0').trimEnd('.')
    return "${text}G"
}

/** 温度那一列的固定宽度。等宽字体下 "105°C" 是最宽的情况。 */
private val LABEL_COLUMN_WIDTH = 46.dp

/**
 * 表格里的一格：一根按比例伸缩的横条 + 数值。
 */
@Composable
private fun FreqCell(
    mhz: Int?,
    maxFreqMHz: Int,
    barColor: Color,
    modifier: Modifier = Modifier,
    physicalMaxMHz: Int? = null,
    normalized: Boolean = false,
) {
    Column(modifier.padding(horizontal = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (mhz == null) {
            Text(
                "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }
        val denom = if (normalized) (physicalMaxMHz ?: maxFreqMHz) else maxFreqMHz
        val fraction = (mhz.toFloat() / denom).coerceIn(0.05f, 1f)
        // 占比模式直接用“限制严重程度”着色：低占比红，高占比青。
        val shownBar = if (normalized) lerp(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.tertiary, fraction) else barColor
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(LocalShapeStyle.current.chip),
        ) {
            Box(
                Modifier.fillMaxWidth(fraction).height(6.dp)
                    .clip(LocalShapeStyle.current.chip)
                    .background(shownBar),
            )
        }
        Text(
            formatFreq(mhz),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (fraction > 0.7f) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
        physicalMaxMHz?.takeIf { it > 0 }?.let { max ->
            Text(
                "${mhz * 100 / max}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 表格下面的非“温度→频率”参数。GPU 和充电限流也改成两列表格，不再用箭头串文字。
 */
@Composable
private fun ThermalPolicyNotes(
    policy: ThermalPolicy,
    showDetail: Boolean,
    fullText: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        policy.skinControlSensor?.let { sensor ->
            Text(
                stringResource(R.string.thermal_sensor_from, sensor),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        policy.steadyTargetTJC?.let { target ->
            val lo = policy.minTargetTJC
            val hi = policy.maxTargetTJC
            Text(
                if (lo != null && hi != null) {
                    stringResource(R.string.thermal_target_temp_range, target, lo, hi)
                } else {
                    stringResource(R.string.thermal_target_temp, target)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (policy.isolateEnabled == true && policy.isolateThresholdsC.isNotEmpty()) {
            Text(
                stringResource(R.string.thermal_isolate_from, policy.isolateThresholdsC.joinToString("/")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        policy.gpuLadder?.takeIf { !it.isEmpty }?.let { ladder ->
            PairTable(
                title = stringResource(R.string.thermal_gpu_title),
                temperatures = ladder.thresholdsC,
                values = ladder.freqsMHz,
                unit = "MHz",
            )
        }
        if (policy.chargerThresholdsC.isNotEmpty() && policy.chargerCurrentsMA.isNotEmpty()) {
            PairTable(
                title = stringResource(R.string.thermal_charger_title),
                temperatures = policy.chargerThresholdsC,
                values = policy.chargerCurrentsMA,
                unit = "mA",
            )
        }
        if (showDetail && !fullText.isNullOrBlank()) {
            Text(
                fullText,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 两列小表：温度 / 数值。GPU、充电限流共用。 */
@Composable
private fun PairTable(
    title: String,
    temperatures: List<Int>,
    values: List<Int>,
    unit: String,
) {
    val rows = temperatures.zip(values)
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.thermal_table_temp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.forEach { (temp, value) ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "$temp°C",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    value.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
