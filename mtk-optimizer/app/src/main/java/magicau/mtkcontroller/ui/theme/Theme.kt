package magicau.mtkcontroller.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: AppPalette = AppPalette.SYSTEM,
    content: @Composable () -> Unit,
) {
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

    MaterialTheme(colorScheme = colorScheme, content = content)
}
