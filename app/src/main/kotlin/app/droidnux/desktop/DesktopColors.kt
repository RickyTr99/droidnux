package app.droidnux.desktop

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import app.droidnux.ui.theme.darkColors

/**
 * The app theme's color roles (ui/theme), as ARGB for the desktop's own
 * views: the session menu and the PC keyboard. Read when they are made.
 */
internal class DesktopColors(context: Context) {
    private val scheme = darkColors(context)
    /** surfaceContainerLow, nearly opaque. */
    val surface = scheme.surfaceContainerLow.copy(alpha = 0.97f).toArgb()
    val surfaceHigh = scheme.surfaceContainerHigh.toArgb()
    val surfaceHighest = scheme.surfaceContainerHighest.toArgb()
    /** secondaryContainer: the selected or open element. */
    val selected = scheme.secondaryContainer.toArgb()
    val onSelected = scheme.onSecondaryContainer.toArgb()
    val primary = scheme.primary.toArgb()
    val onPrimary = scheme.onPrimary.toArgb()
    val onSurface = scheme.onSurface.toArgb()
    val onSurfaceVariant = scheme.onSurfaceVariant.toArgb()
    val outlineVariant = scheme.outlineVariant.toArgb()
    val error = scheme.error.toArgb()
    val onError = scheme.onError.toArgb()
}
