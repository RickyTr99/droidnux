package app.droidnux.desktop

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Android's brightness setting (0-255, linear) and its slider's position: the
 * slider follows AOSP's curve (BrightnessUtils, Hybrid Log-Gamma), so the
 * session menu's slider sits where Android's does.
 */
object Brightness {
    private const val R = 0.5f
    private const val A = 0.17883277f
    private const val B = 0.28466892f
    private const val C = 0.55991073f

    fun toSlider(setting: Int): Float {
        val normal = setting.coerceIn(0, 255) / 255f * 12f
        return (if (normal <= 1f) sqrt(normal) * R else A * ln(normal - B) + C).coerceIn(0f, 1f)
    }

    fun toSetting(slider: Float): Int {
        val ratio = slider.coerceIn(0f, 1f)
        val normal = if (ratio <= R) (ratio / R) * (ratio / R) else exp((ratio - C) / A) + B
        return (normal / 12f * 255f).roundToInt().coerceIn(0, 255)
    }
}
