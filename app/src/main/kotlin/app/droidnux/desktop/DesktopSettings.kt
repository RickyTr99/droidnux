package app.droidnux.desktop

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.Properties

/**
 * The desktop's settings, shared by the app's two processes (the UI and
 * :desktop). A small file read fresh every time: SharedPreferences would
 * keep a stale copy in each process.
 */
data class DesktopSettings(
    /** Desktop size as a fraction of the screen. */
    val scale: Float = DesktopActivity.DEFAULT_SCALE,
    /** Interface size, 1 = 96 dpi at the default desktop size. */
    val uiScale: Float = 1f,
    /** Touch as a trackpad (relative pointer) instead of direct taps. */
    val trackpad: Boolean = false,
    /** X keyboard layout; empty: the one of Android's language. */
    val layout: String = "",
    /** The frame counter in the top right corner of the desktop (debug builds). */
    val stats: Boolean = true,
) {
    /** The layout the session uses. */
    fun xkbLayout(): String = layout.ifEmpty {
        val locale = Locale.getDefault()
        when (locale.language) {
            "it", "de", "fr", "es", "pt" -> locale.language
            "en" -> if (locale.country == "GB") "gb" else "us"
            else -> "us"
        }
    }

    companion object {
        const val MIN_UI_SCALE = 0.75f
        const val MAX_UI_SCALE = 2f

        /** Layouts offered in the settings (XKB names). */
        val LAYOUTS = listOf("us", "gb", "it", "de", "fr", "es", "pt")

        private const val FILE = "desktop-settings.properties"

        fun load(context: Context): DesktopSettings {
            val file = File(context.filesDir, FILE)
            if (!file.isFile) return fromOldPreferences(context)
            val props = Properties().apply { file.inputStream().use { load(it) } }
            val defaults = DesktopSettings()
            return DesktopSettings(
                scale = props.getProperty("scale")?.toFloatOrNull() ?: defaults.scale,
                uiScale = props.getProperty("uiScale")?.toFloatOrNull() ?: defaults.uiScale,
                trackpad = props.getProperty("trackpad")?.toBooleanStrictOrNull() ?: defaults.trackpad,
                layout = props.getProperty("layout")?.takeIf { it in LAYOUTS } ?: defaults.layout,
                stats = props.getProperty("stats")?.toBooleanStrictOrNull() ?: defaults.stats,
            )
        }

        /** Applies [change] and saves the result, replacing the file in one rename. */
        fun update(context: Context, change: (DesktopSettings) -> DesktopSettings): DesktopSettings {
            val settings = change(load(context))
            val props = Properties().apply {
                setProperty("scale", settings.scale.toString())
                setProperty("uiScale", settings.uiScale.toString())
                setProperty("trackpad", settings.trackpad.toString())
                setProperty("layout", settings.layout)
                setProperty("stats", settings.stats.toString())
            }
            val file = File(context.filesDir, FILE)
            val temp = File(context.filesDir, "$FILE.new")
            temp.outputStream().use { props.store(it, null) }
            temp.renameTo(file)
            return settings
        }

        // Before this file, the desktop process kept them in its own preferences.
        private fun fromOldPreferences(context: Context): DesktopSettings {
            val prefs = context.getSharedPreferences("desktop", Context.MODE_PRIVATE)
            val defaults = DesktopSettings()
            return DesktopSettings(
                scale = prefs.getFloat("desktop_scale", defaults.scale),
                uiScale = prefs.getFloat("ui_scale", defaults.uiScale),
                trackpad = prefs.getBoolean("trackpad", defaults.trackpad),
            )
        }
    }
}
