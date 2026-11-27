package top.niunaijun.blackboxa.ui

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
    primary = Color(0xFF3A5BA9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = Color(0xFF001946),
    secondary = Color(0xFF575E71),
    secondaryContainer = Color(0xFFDCE2F9),
    onSecondaryContainer = Color(0xFF141B2C),
    tertiary = Color(0xFF725572),
    tertiaryContainer = Color(0xFFFCD7FA),
    surface = Color(0xFFFAF9FF),
    surfaceContainer = Color(0xFFEEEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerLow = Color(0xFFF4F3FA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB1C5FF),
    onPrimary = Color(0xFF002C71),
    primaryContainer = Color(0xFF1C438F),
    onPrimaryContainer = Color(0xFFDAE2FF),
    secondary = Color(0xFFC0C6DD),
    secondaryContainer = Color(0xFF404659),
    onSecondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFFDFBBDE),
    tertiaryContainer = Color(0xFF593D59),
    surface = Color(0xFF121318),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerLow = Color(0xFF1A1B21),
)

/** Material 3 theme: wallpaper colours on Android 12+, a blue baseline otherwise. */
@Composable
fun BlackBoxTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
