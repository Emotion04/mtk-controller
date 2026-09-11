package magicau.mtkcontroller.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import magicau.mtkcontroller.domain.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6E6E),
    secondary = Color(0xFF4A6363),
    tertiary = Color(0xFF9A4A0F),

    // 语义状态色。此前这三组是空的，于是一半的界面用 Material 默认红
    // (#B3261E)、另一半自己写死 #9C4146，同一个"失败"在两个地方是两个颜色。
    // 取值对齐 ui.md 的实验室徽章表：失败 #9C4146、注意 #B4531A、未判断 #6B6B68。
    error = Color(0xFF9C4146),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6DDE0),
    onErrorContainer = Color(0xFF9C4146),

    // "未验证 / 待确认"走 tertiary 系 —— Material3 没有 success 这个 role，
    // 所以"生效"仍然需要一个扩展色（见 LocalStatusAccent），但其余状态
    // 都应该落在标准 role 上，不再各写各的。
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5E6D8),
    onTertiaryContainer = Color(0xFFB4531A),

    secondaryContainer = Color(0xFFDDE4E4),
    onSecondaryContainer = Color(0xFF4A6363),

    outline = Color(0xFF6B6B68),
    outlineVariant = Color(0x1F1B1B1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3C3),
    secondary = Color(0xFFB1CCCC),
    tertiary = Color(0xFFE8A468),

    // 深色下容器色压低、内容色提亮。直接把浅色那组搬过来会让深色底上
    // 出现一块刺眼的亮红，且文字对比度不足。
    error = Color(0xFFE8A0A6),
    onError = Color(0xFF3A1518),
    errorContainer = Color(0xFF5C2B2F),
    onErrorContainer = Color(0xFFF6DDE0),

    onTertiary = Color(0xFF3A1F0A),
    tertiaryContainer = Color(0xFF5C3A1E),
    onTertiaryContainer = Color(0xFFF5E6D8),

    secondaryContainer = Color(0xFF3A4A4A),
    onSecondaryContainer = Color(0xFFB1CCCC),

    outline = Color(0xFF9E9E9A),
    outlineVariant = Color(0x24E8E8E6),
)

@Composable
fun MtkOptimizerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    palette: AppPalette = AppPalette.SYSTEM,
    /**
     * 用户自己调的强调色。只在 [palette] 是 [AppPalette.CUSTOM] 时非空。
     *
     * 单独传而不是塞进枚举：枚举的 accent 是编译期常量，到处被读（底栏、色板、
     * 状态色）；让它可变就得给每一处加同步。覆盖只影响配色方案的生成，
     * 那一层本来就是每次组合重算的。
     */
    accentOverride: Color? = null,
    shapeStyle: ShapeStyle = ShapeStyle.STANDARD,
    motionSpeed: MotionSpeed = MotionSpeed.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val dynamicFallback = if (darkTheme) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(LocalContext.current)
        } else {
            DarkColors
        }
    } else {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicLightColorScheme(LocalContext.current)
        } else {
            LightColors
        }
    }

    // SYSTEM = Material You dynamic colour; every other palette is explicit.
    val colorScheme = if (darkTheme) {
        palette.darkScheme(dynamicFallback, accentOverride)
    } else {
        palette.lightScheme(dynamicFallback, accentOverride)
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalShapeStyle provides shapeStyle,
        LocalMotion provides MotionSpec(motionSpeed),
        LocalStatusOk provides
            if (darkTheme) StatusOkDark to StatusOkContainerDark
            else StatusOkLight to StatusOkContainerLight,
    ) {
    MaterialTheme(colorScheme = colorScheme) {
        // MaterialTheme itself does not publish LocalContentColor — only Surface
        // does, and its default is Color.Black. Without this wrapper every Text
        // that does not name a colour rendered black, which looked fine in light
        // mode and left dark-mode titles invisible against the dark ground.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colorScheme.background,
            contentColor = colorScheme.onBackground,
            content = content,
        )
    }
    }
}
