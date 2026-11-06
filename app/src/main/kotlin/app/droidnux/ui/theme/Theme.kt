package app.droidnux.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.droidnux.desktop.DesktopSettings

/**
 * The theme color chosen in the settings ([DesktopSettings.themeColor]): the
 * wallpaper's on Android 12+, or one of MoonVibe's fixed palettes.
 */
object ThemeColor {
    const val DYNAMIC = "dynamic"

    /** Every color this device offers, in the settings' order. */
    val choices: List<String> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(DYNAMIC) else emptyList()) + FixedPalettes.keys

    /** The one picked in this process's settings, so the open screens change with it. */
    var picked by mutableStateOf<String?>(null)

    /** [saved], or the default when it is empty or not offered here. */
    fun resolve(saved: String): String = saved.takeIf { it in choices } ?: choices.first()

    fun saved(context: Context): String = resolve(DesktopSettings.load(context).themeColor)
}

/** The app's colors in [color]; the desktop's own views (DesktopColors) read them too. */
fun darkColors(context: Context, color: String = ThemeColor.saved(context)): ColorScheme =
    if (color == ThemeColor.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        FixedPalettes[color] ?: FixedPalettes.values.first()
    }

/** DroidNux is dark-only, like MoonVibe. A new theme color fades in. */
@Composable
fun DroidNuxTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val color = ThemeColor.picked ?: remember { ThemeColor.saved(context) }
    val target = remember(color) { darkColors(context, color) }
    @Composable
    fun animated(role: Color) = animateColorAsState(role, tween(250), label = "theme").value
    val colors = target.copy(
        primary = animated(target.primary),
        onPrimary = animated(target.onPrimary),
        primaryContainer = animated(target.primaryContainer),
        onPrimaryContainer = animated(target.onPrimaryContainer),
        secondary = animated(target.secondary),
        secondaryContainer = animated(target.secondaryContainer),
        onSecondaryContainer = animated(target.onSecondaryContainer),
        tertiary = animated(target.tertiary),
        background = animated(target.background),
        surface = animated(target.surface),
        surfaceContainerLowest = animated(target.surfaceContainerLowest),
        surfaceContainerLow = animated(target.surfaceContainerLow),
        surfaceContainer = animated(target.surfaceContainer),
        surfaceContainerHigh = animated(target.surfaceContainerHigh),
        surfaceContainerHighest = animated(target.surfaceContainerHighest),
        onSurface = animated(target.onSurface),
        onSurfaceVariant = animated(target.onSurfaceVariant),
        outline = animated(target.outline),
        outlineVariant = animated(target.outlineVariant),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
