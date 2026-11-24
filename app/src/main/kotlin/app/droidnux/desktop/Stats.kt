package app.droidnux.desktop

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.system.Os
import android.system.OsConstants
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.droidnux.R
import java.io.File
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** How the statistics over the desktop look; each style keeps its own place and values (DesktopSettings). */
enum class StatsStyle(val key: String, val label: Int, val defaultPosition: StatsPosition, val defaultItems: List<StatsItem>) {
    BAR(
        "bar", R.string.stats_style_bar, StatsPosition.TOP_CENTER,
        listOf(StatsItem.FPS, StatsItem.FRAME, StatsItem.CPU, StatsItem.GPU, StatsItem.BATTERY),
    ),
    PANEL("panel", R.string.stats_style_panel, StatsPosition.TOP_LEFT, StatsItem.entries.toList()),
    MINI("mini", R.string.stats_style_mini, StatsPosition.TOP_RIGHT, listOf(StatsItem.FPS, StatsItem.FRAME)),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

enum class StatsPosition(val key: String, val label: Int, val gravity: Int) {
    TOP_LEFT("top_left", R.string.stats_position_top_left, Gravity.TOP or Gravity.LEFT),
    TOP_CENTER("top_center", R.string.stats_position_top_center, Gravity.TOP or Gravity.CENTER_HORIZONTAL),
    TOP_RIGHT("top_right", R.string.stats_position_top_right, Gravity.TOP or Gravity.RIGHT),
    BOTTOM_LEFT("bottom_left", R.string.stats_position_bottom_left, Gravity.BOTTOM or Gravity.LEFT),
    BOTTOM_CENTER("bottom_center", R.string.stats_position_bottom_center, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
    BOTTOM_RIGHT("bottom_right", R.string.stats_position_bottom_right, Gravity.BOTTOM or Gravity.RIGHT),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** What the statistics can show, in their order until the user drags them into another. */
enum class StatsItem(val key: String, val label: Int, val icon: Int) {
    FPS("fps", R.string.stats_item_fps, R.drawable.ic_menu_stats),
    FRAME("frame", R.string.stats_item_frame, R.drawable.ic_stats_frame),
    CPU("cpu", R.string.menu_cpu, R.drawable.ic_menu_cpu),
    GPU("gpu", R.string.stats_item_gpu, R.drawable.ic_stats_gpu),
    MEMORY("memory", R.string.menu_memory, R.drawable.ic_menu_memory),
    BATTERY("battery", R.string.menu_battery, R.drawable.ic_menu_battery),
    DESKTOP("desktop", R.string.menu_resolution, R.drawable.ic_menu_display),
    CLOCK("clock", R.string.stats_item_clock, R.drawable.ic_stats_clock),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/**
 * One reading, about once a second; null where it is not known (a GPU other than Adreno, no frame drawn).
 * The values of items that are not shown are not read (null or 0).
 */
data class StatsSample(
    val fps: Int,
    val frameMs: Float?,
    val frameMaxMs: Float?,
    val refreshHz: Float,
    val cpuPercent: Int?,
    val cpuGhz: Float?,
    val gpuPercent: Int?,
    val gpuMhz: Int?,
    val memoryUsedGb: Float,
    val memoryTotalGb: Float,
    val memoryLow: Boolean,
    val battery: Int,
    val batteryTempC: Float?,
    val desktopWidth: Int,
    val desktopHeight: Int,
) {
    companion object {
        /** What the settings' preview shows. */
        val SAMPLE = StatsSample(
            fps = 60, frameMs = 3.4f, frameMaxMs = 7.9f, refreshHz = 120f, cpuPercent = 34, cpuGhz = 2.8f,
            gpuPercent = 52, gpuMhz = 680, memoryUsedGb = 5.8f, memoryTotalGb = 11.2f, memoryLow = false,
            battery = 78, batteryTempC = 36f, desktopWidth = 1752, desktopHeight = 1096,
        )
    }
}

/** Reads the numbers that are not the presenter's; off the main thread (files under /proc and /sys). */
class StatsSampler(private val context: Context) {
    private var cpuTicks = -1L
    private var cpuAt = 0L
    private val clockTicks = Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1)
    private val cores = Runtime.getRuntime().availableProcessors()

    /**
     * Reads only what [items] show, and their second values (clocks) only with [notes], the panel's:
     * the statistics stay on for hours, and the rest is files and calls to Android every second.
     */
    fun sample(
        fps: Int, frameMs: Float?, frameMaxMs: Float?, refreshHz: Float, desktopWidth: Int, desktopHeight: Int,
        items: Collection<StatsItem>, notes: Boolean,
    ): StatsSample {
        var cpu: Int? = null
        if (StatsItem.CPU in items) {
            val ticks = SessionPause.cpuTicks(context)
            val at = android.os.SystemClock.elapsedRealtime()
            // Ticks of processes that ended are gone from the sum.
            if (cpuTicks >= 0 && at > cpuAt) {
                cpu = ((ticks - cpuTicks).coerceAtLeast(0) * 1000f / clockTicks / (at - cpuAt) / cores * 100).roundToInt().coerceIn(0, 100)
            }
            cpuTicks = ticks
            cpuAt = at
        } else {
            cpuTicks = -1
        }
        val gpu = StatsItem.GPU in items
        val memory = if (StatsItem.MEMORY in items) {
            ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        } else {
            null
        }
        val battery = if (StatsItem.BATTERY in items) context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) else null
        return StatsSample(
            fps = fps, frameMs = frameMs, frameMaxMs = frameMaxMs, refreshHz = refreshHz,
            cpuPercent = cpu,
            cpuGhz = if (cpu != null && notes) {
                (0 until cores).maxOfOrNull { read("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq") ?: 0L }
                    ?.takeIf { it > 0 }?.let { it / 1e6f }
            } else {
                null
            },
            // Adreno's: "52 %", and the clock in Hz.
            gpuPercent = if (gpu) read("$KGSL/gpu_busy_percentage")?.toInt() else null,
            gpuMhz = if (gpu && notes) read("$KGSL/devfreq/cur_freq")?.let { (it / 1_000_000).toInt() } else null,
            memoryUsedGb = memory?.let { (it.totalMem - it.availMem) / GB } ?: 0f,
            memoryTotalGb = memory?.let { it.totalMem / GB } ?: 0f,
            memoryLow = memory?.lowMemory == true,
            battery = if (battery != null) {
                context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } else {
                0
            },
            batteryTempC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f },
            desktopWidth = desktopWidth, desktopHeight = desktopHeight,
        )
    }

    /** The first number in [path], if it can be read. */
    private fun read(path: String): Long? = runCatching { File(path).readText().trim().takeWhile { it.isDigit() }.toLong() }.getOrNull()

    private companion object {
        const val KGSL = "/sys/class/kgsl/kgsl-3d0"
        const val GB = 1e9f
    }
}

/**
 * The statistics over the desktop, in the app's colors: a bar of values with icons, a panel with a
 * value per row, or a small pill. It covers the screen and lets touches through; only its card is drawn.
 */
class StatsOverlay(context: Context) : FrameLayout(context) {
    private val colors = DesktopColors(context)
    private var style = StatsStyle.BAR
    private var warnings = true
    private var card: View? = null
    private val cells = mutableMapOf<StatsItem, Cell>()
    private var sample: StatsSample? = null
    /** What the card was built from: the same again builds nothing. */
    private var built: Any? = null

    private class Cell(val view: View, val value: TextView, val icon: ImageView?)

    /** Builds the card as [settings] say; a change of style fades from one to the other. */
    fun apply(settings: DesktopSettings, animate: Boolean = false) {
        val newStyle = settings.statsStyle
        val items = settings.statsItems(newStyle)
        val key = listOf(newStyle, items, settings.statsPosition(newStyle), settings.statsOpacity, settings.statsWarnings)
        if (key == built) return
        built = key
        val changedStyle = newStyle != style
        style = newStyle
        warnings = settings.statsWarnings
        val old = card
        cells.clear()
        val view = when (style) {
            StatsStyle.BAR -> row(items, icons = true, textSp = 13f, gap = 14, height = 30, padding = 14)
            StatsStyle.MINI -> row(items, icons = false, textSp = 11f, gap = 8, height = 22, padding = 10)
            StatsStyle.PANEL -> panel(items)
        }
        view.background = GradientDrawable().apply {
            setColor((this@StatsOverlay.colors.surface and 0xFFFFFF) or ((settings.statsOpacity * 255).roundToInt().coerceIn(0, 255) shl 24))
            cornerRadius = dp(if (style == StatsStyle.PANEL) 20 else if (style == StatsStyle.BAR) 15 else 11).toFloat()
        }
        val margin = dp(8)
        addView(view, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, settings.statsPosition(style).gravity).apply {
            setMargins(margin, margin, margin, margin)
        })
        card = view
        sample?.let(::show)
        if (old != null) {
            if (animate && changedStyle) {
                old.animate().alpha(0f).setDuration(SHORT_MS).withEndAction { removeView(old) }.start()
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(MEDIUM_MS).start()
            } else {
                old.animate().cancel()
                removeView(old)
            }
        }
    }

    /** New numbers; a value that is not known leaves its place. */
    fun show(sample: StatsSample) {
        this.sample = sample
        val panel = style == StatsStyle.PANEL
        val unitSp = if (style == StatsStyle.MINI) 9f else 11f
        for ((item, cell) in cells) {
            var warn = false
            val text: CharSequence? = when (item) {
                StatsItem.FPS -> value("${sample.fps}", "FPS", null, unitSp)
                StatsItem.FRAME -> sample.frameMs?.let { ms ->
                    warn = (sample.frameMaxMs ?: 0f) > 1000f / sample.refreshHz.coerceAtLeast(1f)
                    value(number(ms), "ms", if (panel) sample.frameMaxMs?.let { context.getString(R.string.stats_max, number(it)) } else null, unitSp)
                } ?: value("–", "ms", null, unitSp)
                StatsItem.CPU -> sample.cpuPercent?.let {
                    warn = it >= 90
                    value("$it", "%", if (panel) sample.cpuGhz?.let { ghz -> "${number(ghz)} GHz" } else null, unitSp)
                }
                StatsItem.GPU -> sample.gpuPercent?.let {
                    warn = it >= 95
                    value("$it", "%", if (panel) sample.gpuMhz?.let { mhz -> "$mhz MHz" } else null, unitSp)
                }
                StatsItem.MEMORY -> {
                    warn = sample.memoryLow
                    value(number(sample.memoryUsedGb), "GB", if (panel) context.getString(R.string.stats_of, number(sample.memoryTotalGb)) else null, unitSp)
                }
                StatsItem.BATTERY -> {
                    warn = sample.battery <= 15 || (sample.batteryTempC ?: 0f) >= 45f
                    value("${sample.battery}", "%", if (panel) sample.batteryTempC?.let { "${it.roundToInt()} °C" } else null, unitSp)
                }
                StatsItem.DESKTOP -> value(
                    "${sample.desktopWidth}×${sample.desktopHeight}", null,
                    if (panel) "${sample.refreshHz.roundToInt()} Hz" else null, unitSp,
                )
                StatsItem.CLOCK -> value(android.text.format.DateFormat.getTimeFormat(context).format(Date()), null, null, unitSp)
            }
            cell.view.visibility = if (text != null) View.VISIBLE else View.GONE
            if (text == null) continue
            val alert = warnings && warn
            cell.value.text = text
            cell.value.setTextColor(if (alert) colors.error else colors.onSurface)
            cell.icon?.imageTintList = ColorStateList.valueOf(if (alert) colors.error else colors.primary)
        }
        card?.visibility = if (cells.values.any { it.view.visibility == View.VISIBLE }) View.VISIBLE else View.INVISIBLE
    }

    private fun row(items: List<StatsItem>, icons: Boolean, textSp: Float, gap: Int, height: Int, padding: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(height)
        setPadding(dp(padding), 0, dp(padding), 0)
        for (item in items) {
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val icon = if (icons) icon(item, 15).also {
                cell.addView(it, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(5) })
            } else {
                null
            }
            val value = text(textSp)
            cell.addView(value)
            addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (childCount > 0) marginStart = dp(gap)
            })
            cells[item] = Cell(cell, value, icon)
        }
    }

    private fun panel(items: List<StatsItem>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        minimumWidth = dp(220)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        for (item in items) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(26)
            }
            val icon = icon(item, 16)
            row.addView(icon, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(10) })
            row.addView(
                text(12f).apply {
                    setText(item.label)
                    setTextColor(colors.onSurfaceVariant)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val value = text(13f).apply {
                gravity = Gravity.END
                setPadding(dp(16), 0, 0, 0)
            }
            row.addView(value)
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            cells[item] = Cell(row, value, icon)
        }
    }

    private fun icon(item: StatsItem, size: Int) = ImageView(context).apply {
        setImageResource(item.icon)
        imageTintList = ColorStateList.valueOf(colors.primary)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun text(sp: Float) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(colors.onSurface)
        includeFontPadding = false
        isSingleLine = true
    }

    /** [value] in medium, its unit smaller in the variant color, and a note after it. */
    private fun value(value: String, unit: String?, note: String?, unitSp: Float): CharSequence = SpannableStringBuilder(value).apply {
        setSpan(TypefaceSpan("sans-serif-medium"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (unit != null) {
            val start = length
            append(' ').append(unit)
            setSpan(AbsoluteSizeSpan(unitSp.roundToInt(), true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(colors.onSurfaceVariant), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (note != null) {
            val start = length
            append("  ").append(note)
            setSpan(AbsoluteSizeSpan(11, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(colors.onSurfaceVariant), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun number(value: Float) = String.format(Locale.getDefault(), "%.1f", value)

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).roundToInt()

    private companion object {
        const val SHORT_MS = 150L
        const val MEDIUM_MS = 250L
    }
}
