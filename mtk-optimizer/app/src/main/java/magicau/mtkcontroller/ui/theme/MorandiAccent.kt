package magicau.mtkcontroller.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

/**
 * 莫兰迪强调色的取值。
 *
 * ## 不变量是相对亮度，不是 HSL 亮度
 *
 * 项目里所有强调色都配**白色** `onPrimary`（按钮文字）。白字压不压得住，只由
 * **WCAG 相对亮度**决定，和 HSL 的 L 不是一回事 —— 同一个 L 下，黄绿比蓝紫亮得多。
 * 实测 L=0.55、饱和度 0.40 的黄绿色相对亮度 0.404，白字对比度只有 2.3:1，
 * 而内置那十四种都在 0.227–0.266（约 3.0–3.3:1）。
 *
 * 所以自定义色用「色相 + 浓度」两个输入，**亮度由可读性反推**：在 HSL 空间里
 * 二分查找，直到相对亮度落进目标区间。这样用户任选色相都不会调出白字糊掉的组合。
 *
 * ## 为什么不用系统取色器
 *
 * `MaterialColorPicker` 之类让用户在整块色域里拖，拖出来必然是高饱和高亮度 ——
 * 那不是莫兰迪，也不满足上面的不变量。这里只给两个轴：色相全开，浓度受限，
 * 亮度不给（给了就会破坏不变量）。
 */
object MorandiAccent {

    /** 白字可读的相对亮度区间。内置十四种实测落在 0.227–0.266。 */
    private const val LUM_TARGET = 0.245f
    private const val LUM_TOLERANCE = 0.030f

    /** 浓度映射到 HSL 饱和度。上限取自实测区间（内置最高 0.325）。 */
    private const val SAT_MAX = 0.30f
    private const val SAT_MIN = 0.03f

    /** 二分范围。HSL 亮度。 */
    private const val L_SEARCH_MIN = 0.20f
    private const val L_SEARCH_MAX = 0.80f

    private const val LUM_MIN = LUM_TARGET - LUM_TOLERANCE
    private const val LUM_MAX = LUM_TARGET + LUM_TOLERANCE

    /**
     * @param hueDeg 色相，0–360
     * @param concentration 浓度，0–1（0 = 几乎灰，1 = 区间内最浓）
     */
    fun fromHueSaturation(hueDeg: Float, concentration: Float): Color {
        val hue = ((hueDeg % 360f) + 360f) % 360f / 360f
        val saturation = SAT_MIN + (SAT_MAX - SAT_MIN) * concentration.coerceIn(0f, 1f)

        // 相对亮度对 L 单调递增，所以能二分。容差不必卡死 —— 落进区间就收工，
        // 多迭代几次只是让结果更接近目标值，对观感没有分别。
        var lo = L_SEARCH_MIN
        var hi = L_SEARCH_MAX
        var best = Color.hsl(hue, saturation, (lo + hi) / 2f)
        repeat(14) {
            val mid = (lo + hi) / 2f
            val candidate = Color.hsl(hue, saturation, mid)
            best = candidate
            val lum = candidate.luminance().toFloat()
            if (lum < LUM_MIN) {
                lo = mid
            } else if (lum > LUM_MAX) {
                hi = mid
            } else {
                return candidate
            }
        }
        // 理论上到不了这里（区间宽度 0.06，二分 14 次远够）；真到了就退回最接近目标的值。
        return best
    }

    /** 反向：给一个色，取回色相度数。用于把已存的值还原成滑杆位置。 */
    fun hueDegrees(color: Color): Float {
        val (h, _, _) = rgbToHsl(color.red, color.green, color.blue)
        return h
    }

    /** 反向：给一个色，取回浓度（0–1）。 */
    fun concentration(color: Color): Float {
        val (_, s, _) = rgbToHsl(color.red, color.green, color.blue)
        return ((s - SAT_MIN) / (SAT_MAX - SAT_MIN)).coerceIn(0f, 1f)
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        var h = 0f
        if (delta != 0f) {
            h = when (max) {
                r -> 60f * (((g - b) / delta) % 6f)
                g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }
        }
        if (h < 0f) h += 360f
        val l = (max + min) / 2f
        val s = if (delta == 0f) 0f else delta / (1f - abs(2f * l - 1f))
        return Triple(h, s, l)
    }
}
