package magicau.mtkcontroller.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import magicau.mtkcontroller.R

/**
 * Selectable accent colours.
 *
 * The neutral ground is deliberately *not* Material's default grey: cards are a
 * clean white sitting on a warm milky background, with hairline dividers in a
 * soft sand tone. Only the accent (primary/secondary/tertiary) changes per
 * palette, so every scheme keeps the same "paper" feel.
 */
enum class AppPalette(val labelRes: Int, val groupRes: Int, val accent: Color) {
    SYSTEM(R.string.palette_system, R.string.palette_group_standard, Color(0xFF0B6E6E)),
    BLUE(R.string.palette_blue, R.string.palette_group_standard, Color(0xFF1565C0)),
    GREEN(R.string.palette_green, R.string.palette_group_standard, Color(0xFF2E7D32)),
    PURPLE(R.string.palette_purple, R.string.palette_group_standard, Color(0xFF6A4C93)),
    ORANGE(R.string.palette_orange, R.string.palette_group_standard, Color(0xFFB4531A)),
    TEAL(R.string.palette_teal, R.string.palette_group_standard, Color(0xFF0B6E6E)),
    CRIMSON(R.string.palette_crimson, R.string.palette_group_standard, Color(0xFF9C4146)),

    // 莫兰迪: low-saturation, grey-leaning pastels. Kept dark enough to stay
    // readable as an accent on white, but never bright.
    //
    // **新增色必须落在既有亮度区间内**（约 0.22–0.27 相对亮度）。原因是
    // `onPrimary` 固定取白： accent 一亮，白字就糊。挑色时用 WCAG 相对亮度核过，
    // 别凭眼睛 —— 屏幕上"好看"和"白字压得住"不是同一件事。
    MORANDI_ROSE(R.string.palette_morandi_rose, R.string.palette_group_morandi, Color(0xFFA6797B)),
    MORANDI_MIST(R.string.palette_morandi_mist, R.string.palette_group_morandi, Color(0xFF7A8FA6)),
    MORANDI_SAGE(R.string.palette_morandi_sage, R.string.palette_group_morandi, Color(0xFF7E9179)),
    MORANDI_MAUVE(R.string.palette_morandi_mauve, R.string.palette_group_morandi, Color(0xFF8C7E96)),
    MORANDI_CLAY(R.string.palette_morandi_clay, R.string.palette_group_morandi, Color(0xFFB08064)),
    MORANDI_TAUPE(R.string.palette_morandi_taupe, R.string.palette_group_morandi, Color(0xFF9A8B76)),
    MORANDI_DUSK(R.string.palette_morandi_dusk, R.string.palette_group_morandi, Color(0xFF8A7F9B)),
    MORANDI_OLIVE(R.string.palette_morandi_olive, R.string.palette_group_morandi, Color(0xFF7A8B6A)),
    MORANDI_RUST(R.string.palette_morandi_rust, R.string.palette_group_morandi, Color(0xFFA97E63)),
    MORANDI_CELADON(R.string.palette_morandi_celadon, R.string.palette_group_morandi, Color(0xFF6A8F8C)),
    MORANDI_STONE(R.string.palette_morandi_stone, R.string.palette_group_morandi, Color(0xFF83898C)),
    MORANDI_FERN(R.string.palette_morandi_fern, R.string.palette_group_morandi, Color(0xFF74907A)),
    MORANDI_SEA(R.string.palette_morandi_sea, R.string.palette_group_morandi, Color(0xFF6F9189)),
    MORANDI_LAVENDER(R.string.palette_morandi_lavender, R.string.palette_group_morandi, Color(0xFF9188A8)),

    /**
     * 用户自己调的莫兰迪色。
     *
     * [accent] 只是**没读到存值之前的占位**（等于默认色）；真正的值来自
     * `MtkOptimizerTheme(accentOverride = …)`，见 [lightScheme] / [darkScheme]。
     * 之所以还留一个占位：枚举的 accent 到处被读（底栏、色板、状态色），
     * 让它可空会把所有调用点都拖进判空。
     */
    CUSTOM(R.string.palette_custom, R.string.palette_group_morandi, Color(0xFF7E9179)),
    ;

    /**
     * @param accentOverride 用户自定义的强调色。只在 [CUSTOM] 时非空；
     *   其余调色板用它自己的 [accent]。
     */
    fun lightScheme(fallback: ColorScheme, accentOverride: Color? = null): ColorScheme {
        val primary = when {
            this == SYSTEM -> fallback.primary
            this == CUSTOM -> accentOverride ?: accent
            else -> accent
        }
        return lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            secondary = if (this == SYSTEM) fallback.secondary else accent.blend(Color(0xFF8A8A8A), 0.35f),
            tertiary = if (this == SYSTEM) fallback.tertiary else accent.blend(Color(0xFFC9A227), 0.35f),
            background = CreamBackground,
            onBackground = CreamInk,
            surface = CreamSurface,
            onSurface = CreamInk,
            surfaceVariant = CreamSurfaceVariant,
            onSurfaceVariant = CreamInkMuted,
            surfaceContainer = CreamSurfaceContainer,
            surfaceContainerHigh = CreamSurfaceContainerHigh,
            surfaceContainerHighest = CreamSurfaceContainerHighest,
            outline = CreamOutline,
            outlineVariant = CreamDivider,
            // 语义状态色取共享常量，不在这里写死：换强调色板时状态色必须保持
            // 一致，否则同一个"失败"在莫兰迪主题和默认主题下是两个颜色。
            error = StatusErrorLight,
            onError = StatusOnErrorLight,
            errorContainer = StatusErrorContainerLight,
            onErrorContainer = StatusOnErrorContainerLight,
            tertiaryContainer = StatusWarnContainerLight,
            onTertiaryContainer = StatusWarnLight,
            secondaryContainer = StatusNeutralContainerLight,
            onSecondaryContainer = StatusNeutralLight,
        )
    }

    fun darkScheme(fallback: ColorScheme, accentOverride: Color? = null): ColorScheme {
        // SYSTEM 走 Material You：accent 由 fallback 提供，下面的推导一步都不该做。
        val accentColor = when {
            this == SYSTEM -> fallback.primary
            this == CUSTOM -> accentOverride ?: accent
            else -> accent
        }
        val primary = accentColor.lightenForDark()
        return darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF1A120B),
            secondary = if (this == SYSTEM) fallback.secondary else primary.blend(Color(0xFFB0B0B0), 0.3f),
            tertiary = if (this == SYSTEM) fallback.tertiary else primary.blend(Color(0xFFE8C468), 0.3f),
            background = NightBackground,
            onBackground = NightInk,
            surface = NightSurface,
            onSurface = NightInk,
            surfaceVariant = NightSurfaceVariant,
            onSurfaceVariant = NightInkMuted,
            surfaceContainer = NightSurfaceContainer,
            surfaceContainerHigh = NightSurfaceContainerHigh,
            surfaceContainerHighest = NightSurfaceContainerHighest,
            outline = NightOutline,
            outlineVariant = NightDivider,
            error = StatusErrorDark,
            onError = StatusOnErrorDark,
            errorContainer = StatusErrorContainerDark,
            onErrorContainer = StatusOnErrorContainerDark,
            tertiaryContainer = StatusWarnContainerDark,
            onTertiaryContainer = StatusWarnDark,
            secondaryContainer = StatusNeutralContainerDark,
            onSecondaryContainer = StatusNeutralDark,
        )
    }

    companion object {
        fun fromName(name: String?): AppPalette =
            entries.firstOrNull { it.name == name } ?: MORANDI_SAGE
    }
}

// --- the shared "paper" ground ------------------------------------------------

/**
 * The ground the whole app sits on: a light, faintly warm grey rather than
 * pure white, so the pure-white cards have something to lift off.
 */
val CreamBackground = Color(0xFFF0F0F2)

/** Cards are near-pure white. Every container level maps here so no card
 *  inherits Material's default cream/grey container tint. */
val CreamSurface = Color(0xFFFFFFFF)
val CreamSurfaceVariant = Color(0xFFF0EFED)
val CreamSurfaceContainer = Color(0xFFFAFAF9)
val CreamSurfaceContainerHigh = Color(0xFFFFFFFF)
val CreamSurfaceContainerHighest = Color(0xFFFFFFFF)
val CreamInk = Color(0xFF1B1B1A)
val CreamInkMuted = Color(0xFF6B6B68)

/** Hairline dividers: barely-there grey, no hard outline anywhere. */
val CreamDivider = Color(0x1F1B1B1A)
val CreamOutline = Color(0x1F1B1B1A)

val NightBackground = Color(0xFF121212)
val NightSurface = Color(0xFF1E1E1E)
val NightSurfaceVariant = Color(0xFF262626)
val NightSurfaceContainer = Color(0xFF1A1A1A)
val NightSurfaceContainerHigh = Color(0xFF1E1E1E)
val NightSurfaceContainerHighest = Color(0xFF1E1E1E)
val NightInk = Color(0xFFE8E8E6)
val NightInkMuted = Color(0xFF9E9E9A)
val NightDivider = Color(0x24E8E8E6)
val NightOutline = Color(0x24E8E8E6)

// --- semantic status colours ------------------------------------------------
//
// 四种状态：失败、注意（未验证/待确认）、中性（读不到/未测试）、生效。
// 取值对齐 ui.md 的实验室徽章表，四个色板共用同一组 —— 换强调色时状态色不能变。
//
// 每个状态给"容器色 + 内容色"一对：容器做底色，内容做图标与文字。
// 只给一个色值的做法会逼使用方自己调透明度，而那在深浅两种底上不一样。

/** 失败 / 不可用 / 拒绝。浅色底上的暗红底与红字。 */
val StatusErrorContainerLight = Color(0xFFF6DDE0)
val StatusErrorLight = Color(0xFF9C4146)
val StatusOnErrorLight = Color(0xFFFFFFFF)
val StatusOnErrorContainerLight = Color(0xFF9C4146)

/** 注意 / 生效未验证。用 Material3 的 tertiary 容器承载，因为 M3 没有 warning role。 */
val StatusWarnContainerLight = Color(0xFFF5E6D8)
val StatusWarnLight = Color(0xFFB4531A)

/** 中性 / 读不到 / 未测试。 */
val StatusNeutralContainerLight = Color(0xFFE4E4E1)
val StatusNeutralLight = Color(0xFF6B6B68)

/** 生效 / 已确认。深色下容器压暗、文字提亮，不然在深底上是一片糊。 */
val StatusOkContainerLight = Color(0xFFDDEDDD)
val StatusOkLight = Color(0xFF2E7D32)

val StatusErrorContainerDark = Color(0xFF5C2B2F)
val StatusErrorDark = Color(0xFFE8A0A6)
val StatusOnErrorDark = Color(0xFF3A1518)
val StatusOnErrorContainerDark = Color(0xFFF6DDE0)

val StatusWarnContainerDark = Color(0xFF5C3A1E)
val StatusWarnDark = Color(0xFFE8A468)

val StatusNeutralContainerDark = Color(0xFF33332F)
val StatusNeutralDark = Color(0xFF9E9E9A)

val StatusOkContainerDark = Color(0xFF1F3D22)
val StatusOkDark = Color(0xFF8FD194)

/**
 * Material3 的 ColorScheme 里没有"成功"这个角色，而"生效 / 已确认"是这个应用
 * 里最常出现的正面状态（调频生效、写入已读回、授权成功）。
 *
 * 只缺这一个，所以只为它开一个 CompositionLocal；其余状态全部落在标准
 * error / tertiary / secondary 容器上。避免为了一个颜色引入一层抽象，也避免
 * 出现第二套平行的颜色系统。
 */
val LocalStatusOk = androidx.compose.runtime.staticCompositionLocalOf {
    StatusOkLight to StatusOkContainerLight
}

private fun Color.blend(other: Color, amount: Float): Color = Color(
    red = red * (1 - amount) + other.red * amount,
    green = green * (1 - amount) + other.green * amount,
    blue = blue * (1 - amount) + other.blue * amount,
    alpha = 1f,
)

/** Deep accents are unreadable on a dark ground; lift them. */
private fun Color.lightenForDark(): Color = blend(Color(0xFFFFFFFF), 0.45f)

/**
 * Section accents in the settings list are picked to sit on a white card.
 * On a dark card the same values read as muddy near-black, so lift them.
 */
fun Color.adaptiveAccent(isDark: Boolean): Color = if (isDark) lightenForDark() else this

/** Whether the current scheme is a dark one, judged by its background. */
@Composable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
