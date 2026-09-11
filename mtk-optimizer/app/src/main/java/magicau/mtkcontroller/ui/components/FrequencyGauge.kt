package magicau.mtkcontroller.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.res.stringResource
import magicau.mtkcontroller.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.ui.theme.LocalMotion
import magicau.mtkcontroller.ui.theme.LocalStatusOk

/**
 * 频率仪表：**半圆**（180°），左端 0、右端硬件上限。双环。
 *
 * ## 布局：弧在上，文字在下
 *
 * 数値放在弧**下方**而不是圆心里。圆心看着优雅，但那是给大尺寸留的 ——
 * 三簇并排时每张只有屏宽的三分之一，圆心那点空间放不下数字，
 * 于是被 Box 裁掉一半（这就是上一版"GHz 被截断"的原因）。
 *
 * 贴在弧下面就没有这个问题：弧占多高由宽度算出，文字要多少给多少，
 * **两者不争同一块空间**。
 *
 * ## 尺寸：由宽度推半径，不用 aspectRatio 硬撑
 *
 * 半圆的半径 = 可用宽度的一半，弧高 = 半径 + 环宽。用 `aspectRatio` 猜比例
 * 会让弧和容器的比例对不上，于是要么溢出要么留白。这里显式算：
 * `弧高 = 宽 / 2 + 环宽`。
 *
 * 环宽还要满足一条来自 Material 的硬约束：**指示器直径不得小于环宽的两倍**。
 * 三簇并排时宽度最小，环宽必须跟着收 —— 固定 dp 会在大屏好看、小屏糊成一团。
 *
 * ## 两环
 *
 * | 环 | 画什么 |
 * |---|---|
 * | 外环（细） | **上限之内是绿（够得着），之外是红（被挡住）** |
 * | 内环（粗） | 0 → 当前频率 |
 *
 * **下限不画红。** 下限是"不许低于"，不是"够不着" —— 把它染红会让弧的起点
 * 出现一段红，看着像"低频有问题"，而低频本来就是正常工作区间。
 * 下限只在文字里报个数。
 *
 * ## 外环只在读得到真值时才上色
 *
 * 上限（`scaling_max_freq`）在本机经常读不到（见 DEV-003）。读不到时**整圈画中性灰**：
 * 灰的意思是"这里没有可信数据"，而不是"全部够得着"或"全部够不着"。
 *
 * **不要拿别的量顶替它。** 曾经试过用 `time_in_state` 里"开机以来到过的最高频率"
 * 当量程 —— 那是历史最大值，不是当前上限。它跟"今天是几号"没有任何关系，
 * 画上去等于对用户断言一个我们并不知道的事。宁可空着。
 *
 * ## 下限不画红
 *
 * 下限是"不许低于"，不是"够不着" —— 染红会让弧的起点出现一段红，
 * 看着像"低频有问题"，而低频本来就是正常工作区间。下限只在文字里报个数。
 *
 * ## 动画
 *
 * 当前频率与上限都走 `animateFloatAsState`。频率是每秒都在变的量，
 * 硬切会让弧不停抖；给个 220ms 的过渡，读到新值时弧是"长过去"的。
 */
@Composable
fun FrequencyGauge(
    currentKhz: Long?,
    maxLimitKhz: Long?,
    minLimitKhz: Long?,
    hardwareMaxKhz: Long,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (hardwareMaxKhz <= 0) return
    val ok = LocalStatusOk.current
    val blocked = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.surfaceVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val spec = LocalMotion.current

    fun fraction(khz: Long?): Float =
        khz?.let { (it.toFloat() / hardwareMaxKhz).coerceIn(0f, 1f) } ?: 0f

    // 频率是每秒变的量，硬切会抖；让弧长过去而不是跳过去。
    val currentFraction by animateFloatAsState(
        targetValue = fraction(currentKhz),
        animationSpec = spec.floatBase(),
        label = "gaugeCurrent",
    )
    val maxFraction by animateFloatAsState(
        targetValue = fraction(maxLimitKhz),
        animationSpec = spec.floatBase(),
        label = "gaugeMax",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().aspectRatio(2.1f),
        ) {
            // 弧宽按**容器宽度**算，不是 minDimension —— 半圆的量程就是宽度决定的。
            // 环宽取宽度的一个比例并设上下限：下限保证小尺寸下还看得见，
            // 上限保证大尺寸下不显得笨重。
            // 三者的比例按观感调过：外环细、两环贴近、内环明显更粗。
            // 内环是主角（当前频率），外环只是背景信息（哪段够不着），
            // 两者一样粗会分不出主次。
            val outerStrokePx = (constraints.maxWidth * if (compact) 0.038f else 0.030f)
                .coerceIn(3f, 9f)
            val gapPx = outerStrokePx * 0.8f
            val innerStrokePx = (outerStrokePx * 3.4f).coerceAtMost(constraints.maxWidth * 0.16f)

            Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(2.1f)) {
                // 圆心落在底边中点：这样画出来的正好是上半圆。
                val cx = size.width / 2f
                val cy = size.height
                val outerRadius = (size.width / 2f) - outerStrokePx / 2f
                val innerRadius = outerRadius - outerStrokePx / 2f - gapPx - innerStrokePx / 2f
                if (innerRadius <= 0f) return@Canvas

                val start = 180f
                val sweep = 180f

                fun arc(
                    radius: Float,
                    strokeWidth: Float,
                    color: Color,
                    from: Float,
                    to: Float,
                    cap: StrokeCap = StrokeCap.Butt,
                ) {
                    val s = to - from
                    if (s <= 0.002f) return
                    val d = radius * 2f
                    drawArc(
                        color = color,
                        startAngle = start + sweep * from,
                        sweepAngle = sweep * s,
                        useCenter = false,
                        topLeft = Offset(cx - radius, cy - radius),
                        size = Size(d, d),
                        style = Stroke(width = strokeWidth, cap = cap),
                    )
                }

                // 外环：**上限之后**全部红，上限之内绿。
                //
                // 一个坑记在这里：曾经无条件按 maxFraction 分色，而上限读不到时
                // 它是 0，于是红弧变成"从 0 画到 1"，整圈全红 —— 现象是"环全红"。
                // 所以没有上限时必须走中性分支，不能让它落到 0。
                if (maxLimitKhz != null) {
                    arc(outerRadius, outerStrokePx, ok.first, 0f, maxFraction)
                    arc(outerRadius, outerStrokePx, blocked, maxFraction, 1f)
                } else {
                    arc(outerRadius, outerStrokePx, track, 0f, 1f)
                }

                // 内环：量程底 + 当前值。
                arc(innerRadius, innerStrokePx, track, 0f, 1f)
                arc(innerRadius, innerStrokePx, ok.first, 0f, currentFraction)
            }
        }

        // 数值贴在弧下方，不与弧争空间。
        Text(
            text = currentKhz?.let { "%.2f GHz".format(it / 1_000_000.0) } ?: "—",
            style = if (compact) MaterialTheme.typography.labelLarge
            else MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = if (currentKhz != null) ok.first else label,
            maxLines = 1,
        )
        // 上限用半透明小字。它是这一屏真正要读的数，但不必抢数字的视线。
        // 读不到就是一个破折号 —— 不拿别的量顶替。
        Text(
            text = stringResource(
                R.string.gauge_ceiling,
                maxLimitKhz?.let { "%.2f".format(it / 1_000_000.0) }
                    ?: stringResource(R.string.value_dash_short),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = label.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}
