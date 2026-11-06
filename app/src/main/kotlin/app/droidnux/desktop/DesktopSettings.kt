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
    /** The statistics over the desktop (Stats.kt): shown, their style, and each style's place and values. */
    val stats: Boolean = false,
    val statsStyle: StatsStyle = StatsStyle.BAR,
    val statsPositions: Map<StatsStyle, StatsPosition> = emptyMap(),
    val statsItemsByStyle: Map<StatsStyle, List<StatsItem>> = emptyMap(),
    /** The card's background, 0.2 to 1. */
    val statsOpacity: Float = 0.9f,
    /** Values out of the normal range in the error color. */
    val statsWarnings: Boolean = true,
    /** The session's output in logcat, and KWin's and Mesa's debug messages; off: only the file to send. */
    val logs: Boolean = false,
    /** The shrunk keyboard's button: on the left edge instead of the right, and how far down (0 top, 1 bottom). */
    val keyboardButtonLeft: Boolean = false,
    val keyboardButtonY: Float = 1f,
    /** The Linux account's name, empty until the user chose one; see [Account]. */
    val user: String = "",
    /** The app's theme color (ui/theme/ThemeColor); empty: the default. */
    val themeColor: String = "",
) {
    fun statsPosition(style: StatsStyle) = statsPositions[style] ?: style.defaultPosition
    fun statsItems(style: StatsStyle) = statsItemsByStyle[style] ?: style.defaultItems

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
        const val MIN_STATS_OPACITY = 0.2f

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
                statsStyle = StatsStyle.of(props.getProperty("statsStyle")) ?: defaults.statsStyle,
                statsPositions = StatsStyle.entries.mapNotNull { style ->
                    StatsPosition.of(props.getProperty("statsPosition.${style.key}"))?.let { style to it }
                }.toMap(),
                statsItemsByStyle = StatsStyle.entries.mapNotNull { style ->
                    props.getProperty("statsItems.${style.key}")?.let { keys ->
                        val chosen = keys.split(',').mapNotNull(StatsItem::of)
                        style to StatsItem.entries.filter { it in chosen }
                    }
                }.toMap(),
                statsOpacity = props.getProperty("statsOpacity")?.toFloatOrNull()?.coerceIn(MIN_STATS_OPACITY, 1f) ?: defaults.statsOpacity,
                statsWarnings = props.getProperty("statsWarnings")?.toBooleanStrictOrNull() ?: defaults.statsWarnings,
                logs = props.getProperty("logs")?.toBooleanStrictOrNull() ?: defaults.logs,
                keyboardButtonLeft = props.getProperty("keyboardButtonLeft")?.toBooleanStrictOrNull() ?: defaults.keyboardButtonLeft,
                keyboardButtonY = props.getProperty("keyboardButtonY")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: defaults.keyboardButtonY,
                user = props.getProperty("user")?.takeIf { Account.isValidName(it) } ?: defaults.user,
                themeColor = props.getProperty("themeColor") ?: defaults.themeColor,
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
                setProperty("statsStyle", settings.statsStyle.key)
                for ((style, position) in settings.statsPositions) setProperty("statsPosition.${style.key}", position.key)
                for ((style, items) in settings.statsItemsByStyle) setProperty("statsItems.${style.key}", items.joinToString(",") { it.key })
                setProperty("statsOpacity", settings.statsOpacity.toString())
                setProperty("statsWarnings", settings.statsWarnings.toString())
                setProperty("logs", settings.logs.toString())
                setProperty("keyboardButtonLeft", settings.keyboardButtonLeft.toString())
                setProperty("keyboardButtonY", settings.keyboardButtonY.toString())
                setProperty("user", settings.user)
                setProperty("themeColor", settings.themeColor)
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
