package magicau.mtkcontroller.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.feature.home.ClusterSeries

private val SERIES_COLORS = listOf(
    Color(0xFF0B6E6E),
    Color(0xFF4A6FA5),
    Color(0xFFB4651A),
    Color(0xFF6A4C93),
    Color(0xFF2E7D32),
    Color(0xFF9C4146),
)

/**
 * Line chart of per-cluster frequency over the recent sampling window.
 *
 * All series share one vertical scale (the highest frequency seen) so the
 * relative size of little/big cores stays readable instead of every curve
 * filling the box.
 */
@Composable
fun FrequencyChart(
    series: List<ClusterSeries>,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val globalMax = series.flatMap { it.samples }.maxOrNull()?.coerceAtLeast(1L) ?: 1L

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(150.dp)) {
            Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                val w = size.width
                val h = size.height

                // Horizontal grid.
                repeat(4) { i ->
                    val y = h * i / 3f
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1f,
                    )
                }

                val longest = series.maxOfOrNull { it.samples.size } ?: 0
                if (longest < 2) return@Canvas

                series.forEachIndexed { index, s ->
                    if (s.samples.size < 2) return@forEachIndexed
                    val color = SERIES_COLORS[index % SERIES_COLORS.size]
                    val path = Path()
                    val stepX = w / (longest - 1).toFloat()
                    s.samples.forEachIndexed { i, value ->
                        val x = i * stepX
                        val y = h - (value.toFloat() / globalMax) * h
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path = path, color = color, style = Stroke(width = 3f))
                }
            }
        }

        if (series.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                series.forEachIndexed { index, s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(SERIES_COLORS[index % SERIES_COLORS.size]),
                        )
                        Text(text = s.policy, style = labelStyle, color = labelColor)
                    }
                }
            }
        } else {
            Text("正在采样…", style = labelStyle, color = labelColor, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
