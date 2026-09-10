package magicau.mtkcontroller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import magicau.mtkcontroller.domain.model.ThemeMode
import magicau.mtkcontroller.ui.navigation.AppNavHost
import magicau.mtkcontroller.ui.theme.AppPalette
import magicau.mtkcontroller.ui.theme.MtkOptimizerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MtkApp).container
        setContent {
            val paletteName by container.settingsRepository.themePalette
                .collectAsStateWithLifecycle(initialValue = AppPalette.SYSTEM.name)
            val themeModeName by container.settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM.name)

            MtkOptimizerTheme(
                themeMode = ThemeMode.fromName(themeModeName),
                palette = AppPalette.fromName(paletteName),
            ) {
                AppNavHost(container = container, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
