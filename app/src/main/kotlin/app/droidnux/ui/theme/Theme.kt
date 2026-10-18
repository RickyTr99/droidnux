package app.droidnux.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette shared with MoonVibe (blue theme), used when the device
// has no dynamic colors.
private val FallbackDark: ColorScheme = darkColorScheme(
    primary = Color(0xFF82A6E8),
    secondaryContainer = Color(0xFF3E4759),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
    surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    onSurface = Color(0xFFE2E2E9),
    onSurfaceVariant = Color(0xFFC4C6D0),
)

/** DroidNux is dark-only, like MoonVibe. */
@Composable
fun DroidNuxTheme(content: @Composable () -> Unit) {
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        FallbackDark
    }
    MaterialTheme(colorScheme = colors, content = content)
}
