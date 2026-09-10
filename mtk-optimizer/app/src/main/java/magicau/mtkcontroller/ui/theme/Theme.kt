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
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3C3),
    secondary = Color(0xFFB1CCCC),
    tertiary = Color(0xFFE8A468),
)

@Composable
fun MtkOptimizerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    palette: AppPalette = AppPalette.SYSTEM,
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
        palette.darkScheme(dynamicFallback)
    } else {
        palette.lightScheme(dynamicFallback)
    }

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
