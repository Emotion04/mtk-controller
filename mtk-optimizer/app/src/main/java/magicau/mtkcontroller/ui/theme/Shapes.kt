package magicau.mtkcontroller.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R

/**
 * 圆角规格。三档预设，不给任意值。
 *
 * ## 为什么是预设而不是可调
 *
 * 圆角不是独立参数 —— 卡片、行内小色块、芯片的圆角要按同一个比例缩放才好看。
 * 放开任意数值，用户很容易调出"卡片很圆、里面的色块还是方的"这种组合，
 * 而这看着像 bug 而不是个性化。
 *
 * 三档都验过比例关系，选哪档都是一套完整的形。
 *
 * ## 为什么放在主题里
 *
 * 之前 `RoundedCornerShape(18.dp)` 写死在五个地方。要换形状就得逐个改，
 * 而且必然漏。收进主题后，改一处全应用跟着变 —— 和颜色收敛是同一个道理。
 */
enum class ShapeStyle(val labelRes: Int, private val cardDp: Int) {

    /** 紧凑：接近方形，信息密度感更强。 */
    COMPACT(R.string.shape_compact, 10),

    /** 标准：当前的观感，也是默认。 */
    STANDARD(R.string.shape_standard, 18),

    /** 圆润：更柔和的轮廓。 */
    ROUNDED(R.string.shape_rounded, 26),
    ;

    /** 卡片、面板一类大容器。 */
    val card: RoundedCornerShape get() = RoundedCornerShape(cardDp.dp)

    /** 行内小色块（图标底、状态方框），按卡片半径等比缩小。 */
    val chip: RoundedCornerShape get() = RoundedCornerShape((cardDp / 3).dp)

    /** 图标方块。 */
    val iconBox: RoundedCornerShape get() = RoundedCornerShape((cardDp / 2).dp)

    /** 完全圆角，用于药丸形的标签与导航栏。 */
    val pill: RoundedCornerShape get() = RoundedCornerShape(percent = 50)

    companion object {
        fun fromName(name: String?): ShapeStyle =
            entries.firstOrNull { it.name == name } ?: STANDARD
    }
}

/**
 * 当前形状规格。组件通过它取圆角，不自己写 `RoundedCornerShape(n.dp)`。
 *
 * 默认值取 STANDARD，这样在主题之外渲染（预览、测试）也不会崩。
 */
val LocalShapeStyle = staticCompositionLocalOf { ShapeStyle.STANDARD }
