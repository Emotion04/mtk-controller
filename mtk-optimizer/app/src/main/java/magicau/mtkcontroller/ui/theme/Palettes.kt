package magicau.mtkcontroller.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Selectable accent colours.
 *
 * The neutral ground is deliberately *not* Material's default grey: cards are a
 * clean white sitting on a warm milky background, with hairline dividers in a
 * soft sand tone. Only the accent (primary/secondary/tertiary) changes per
 * palette, so every scheme keeps the same "paper" feel.
 */
enum class AppPalette(val label: String, val group: String, val accent: Color) {
    SYSTEM("跟随系统(Material You)", "标准", Color(0xFF0B6E6E)),
    BLUE("蓝", "标准", Color(0xFF1565C0)),
    GREEN("绿", "标准", Color(0xFF2E7D32)),
    PURPLE("紫", "标准", Color(0xFF6A4C93)),
    ORANGE("橙", "标准", Color(0xFFB4531A)),
    TEAL("青", "标准", Color(0xFF0B6E6E)),
    CRIMSON("红", "标准", Color(0xFF9C4146)),

    // 莫兰迪: low-saturation, grey-leaning pastels. Kept dark enough to stay
    // readable as an accent on white, but never bright.
    MORANDI_ROSE("莫兰迪玫瑰", "莫兰迪", Color(0xFFA6797B)),
    MORANDI_MIST("莫兰迪雾蓝", "莫兰迪", Color(0xFF7A8FA6)),
    MORANDI_SAGE("莫兰迪鼠尾草", "莫兰迪", Color(0xFF7E9179)),
    MORANDI_MAUVE("莫兰迪藕紫", "莫兰迪", Color(0xFF8C7E96)),
    MORANDI_CLAY("莫兰迪陶土", "莫兰迪", Color(0xFFB08064)),
    MORANDI_TAUPE("莫兰迪灰米", "莫兰迪", Color(0xFF9A8B76)),
    ;

    fun lightScheme(fallback: ColorScheme): ColorScheme {
        val primary = if (this == SYSTEM) fallback.primary else accent
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
            error = Color(0xFFB3261E),
        )
    }

    fun darkScheme(fallback: ColorScheme): ColorScheme {
        val primary = if (this == SYSTEM) fallback.primary else accent.lightenForDark()
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
            error = Color(0xFFF2B8B5),
        )
    }

    companion object {
        fun fromName(name: String?): AppPalette =
            entries.firstOrNull { it.name == name } ?: SYSTEM
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

private fun Color.blend(other: Color, amount: Float): Color = Color(
    red = red * (1 - amount) + other.red * amount,
    green = green * (1 - amount) + other.green * amount,
    blue = blue * (1 - amount) + other.blue * amount,
    alpha = 1f,
)

/** Deep accents are unreadable on a dark ground; lift them. */
private fun Color.lightenForDark(): Color = blend(Color(0xFFFFFFFF), 0.45f)
