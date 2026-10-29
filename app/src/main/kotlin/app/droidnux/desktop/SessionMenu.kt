package app.droidnux.desktop

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.droidnux.R
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The menu over the desktop: a panel on the right edge with quick tiles
 * (keyboard, trackpad, screenshot, statistics), brightness and volume, the
 * desktop's resolution and interface size, the session's CPU, memory and
 * battery, and the session's actions (Home, Pause, End). On a phone the same
 * panel, narrower, without the header; its content scrolls.
 */
class SessionMenu(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onToggleKeyboard()
        fun onTrackpadChanged(trackpad: Boolean)
        fun onStatsChanged(shown: Boolean)
        fun onScreenshot()
        /** While the slider moves, so the label can show the resolution. */
        fun resolutionFor(scale: Float): String
        fun onScaleChanged(scale: Float)
        fun onUiScaleChanged(scale: Float)
        /** The screen's brightness (0 to 1) and Android's media volume, now. */
        fun brightness(): Float
        fun onBrightnessChanged(brightness: Float)
        fun volume(): Float
        /** Media volume steps: the volume slider moves by one. */
        fun volumeSteps(): Int
        fun onVolumeChanged(volume: Float)
        fun onBackToAndroid()
        fun onPauseSession()
        fun onExit()
        fun onClosed()
    }

    private enum class Size { NONE, RESOLUTION, INTERFACE }

    private val handler = Handler(Looper.getMainLooper())
    private val compact = context.resources.configuration.smallestScreenWidthDp < 600
    private var trackpad = false
    private var stats = false
    private var scale = 1f
    private var uiScale = 1f
    private var size = Size.NONE
    private var opened = false
    private val applyScale = Runnable { listener.onScaleChanged(scale) }
    private val applyUiScale = Runnable { listener.onUiScaleChanged(uiScale) }

    /** Full-screen layer: a touch outside the panel closes the menu. */
    val view: FrameLayout = FrameLayout(context).apply {
        visibility = View.GONE
        isClickable = true
        isFocusable = false
        setBackgroundColor(VEIL)
        setOnClickListener { close() }
        setOnGenericMotionListener { _, _ -> true } // hover stays here, not on the desktop under it
        // Android's own pointer, which the desktop under the menu hides (X draws its cursor there).
        pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_ARROW)
    }

    val isOpen: Boolean get() = opened

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(SURFACE)
            val radius = dp(if (compact) 20f else 24f)
            cornerRadii = floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
        }
        isClickable = true // touches on the panel's background do not close the menu
        isFocusable = false // clickable implies focusable: focus belongs to the buttons
    }
    private val subtitle = label("", ON_SURFACE_VARIANT, sp = 13f)
    private val trackpadTile: View
    private val statsTile: View
    private val resolutionBox: LinearLayout
    private val interfaceBox: LinearLayout
    private val resolutionValue = label("", sp = if (compact) 13f else 14f, medium = true)
    private val interfaceValue = label("", sp = if (compact) 13f else 14f, medium = true)
    private val sizeSlider: Slider
    private val brightnessSlider: Slider
    private val volumeSlider: Slider
    private val cpu = StatCard(R.string.menu_cpu, PRIMARY)
    private val memory = StatCard(R.string.menu_memory, PRIMARY)
    private val battery = StatCard(R.string.menu_battery, GREEN)
    private val firstTile: View

    init {
        val gap = dp(if (compact) 10 else 18)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun section(child: View, top: Int = gap) = content.addView(child, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = if (content.childCount == 0) 0 else top })

        if (!compact) {
            // The session: a live dot, what runs, its resolution and the menu's shortcut.
            panel.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(context).apply { background = rounded(GREEN, dp(5f)) }, LinearLayout.LayoutParams(dp(10), dp(10)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(context.getString(R.string.home_title), sp = 18f, medium = true))
                    addView(subtitle)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
                addView(roundButton(R.drawable.ic_menu_chevron, R.string.menu_close) { close() })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = gap
            })
        }

        // Quick tiles.
        val tiles = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        firstTile = tile(tiles, R.drawable.ic_menu_keyboard, R.string.menu_keyboard) {
            close()
            listener.onToggleKeyboard()
        }
        trackpadTile = tile(tiles, R.drawable.ic_menu_trackpad, R.string.menu_mode_trackpad) {
            trackpad = !trackpad
            updateTiles()
            listener.onTrackpadChanged(trackpad)
        }
        tile(tiles, R.drawable.ic_menu_screenshot, R.string.menu_screenshot) {
            close()
            listener.onScreenshot()
        }
        statsTile = tile(tiles, R.drawable.ic_menu_stats, R.string.menu_stats) {
            stats = !stats
            close() // the counter shows on the desktop, under the panel
            listener.onStatsChanged(stats)
        }
        section(tiles)

        // Brightness and volume, as the app's settings show a value: stacked on a tablet, side by side on a phone.
        val sliders = LinearLayout(context).apply {
            orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        brightnessSlider = Slider(STEP) { listener.onBrightnessChanged(it) }
        volumeSlider = Slider(1f / listener.volumeSteps().coerceAtLeast(1)) { listener.onVolumeChanged(it) }
        val cards = listOf(sliderCard(R.string.menu_brightness, brightnessSlider), sliderCard(R.string.menu_volume, volumeSlider))
        for ((i, card) in cards.withIndex()) {
            sliders.addView(card, if (compact) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(6) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = dp(8) }
            })
        }
        section(sliders)

        // The desktop's size: a box each, a tap shows its slider below them.
        if (!compact) section(label(context.getString(R.string.menu_screen), PRIMARY, sp = 12f, medium = true).apply { letterSpacing = 0.07f })
        resolutionBox = box(R.string.menu_resolution, resolutionValue) { showSize(if (size == Size.RESOLUTION) Size.NONE else Size.RESOLUTION) }
        interfaceBox = box(R.string.menu_interface, interfaceValue) { showSize(if (size == Size.INTERFACE) Size.NONE else Size.INTERFACE) }
        section(row(resolutionBox, interfaceBox, height = dp(if (compact) 48 else 56)), top = if (compact) gap else dp(8))
        sizeSlider = Slider(STEP) {}
        section(sizeSlider.apply { visibility = View.GONE }, top = dp(4))

        // What the session costs.
        section(row(cpu.view, memory.view, battery.view))

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // The session's actions, always in sight.
        val actionHeight = dp(if (compact) 44 else 52)
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action(R.drawable.ic_menu_home, R.string.menu_home, ON_SURFACE, SURFACE_HIGH, corners(20f, 8f)) {
            close()
            listener.onBackToAndroid()
        }, LinearLayout.LayoutParams(0, actionHeight, 1f))
        actions.addView(action(R.drawable.ic_menu_pause, R.string.menu_pause, ON_SURFACE, SURFACE_HIGH, corners(8f, 8f)) {
            close()
            listener.onPauseSession()
        }, LinearLayout.LayoutParams(0, actionHeight, 1f).apply { marginStart = dp(3) })
        val endButton = action(R.drawable.ic_menu_power, R.string.menu_end, ON_ERROR, ERROR, corners(8f, 20f)) {
            showConfirm(true)
        }.apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        actions.addView(endButton, LinearLayout.LayoutParams(0, actionHeight, 1f).apply { marginStart = dp(3) })
        panel.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = if (compact) dp(10) else dp(12)
        })

        view.addView(panel, FrameLayout.LayoutParams(dp(if (compact) 344 else 400), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
    }

    // -- Ending the session: Material 3's basic dialog, as the Home asks -----

    private val confirmCancel = textButton(R.string.action_cancel, PRIMARY) { showConfirm(false) }
    private val confirmDialog = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE_HIGH, dp(28f))
        setPadding(dp(24), dp(24), dp(24), dp(18))
        isClickable = true // a touch on the dialog does not dismiss it
        isFocusable = false
        addView(label(context.getString(R.string.menu_exit_title), sp = 24f))
        addView(label(context.getString(R.string.menu_exit_text), ON_SURFACE_VARIANT, sp = 14f).apply {
            setLineSpacing(dp(6f), 1f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(confirmCancel)
            addView(textButton(R.string.menu_exit_confirm, ERROR) {
                showConfirm(false, animate = false)
                close()
                listener.onExit()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
    }

    /** The dialog over a scrim on the whole screen; a touch on the scrim dismisses it. */
    private val confirm = FrameLayout(context).apply {
        visibility = View.GONE
        setBackgroundColor(SCRIM)
        isClickable = true
        isFocusable = false
        setOnClickListener { showConfirm(false) }
        addView(confirmDialog, FrameLayout.LayoutParams(
            dp(if (compact) 312 else 360), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
    }

    init {
        view.addView(confirm, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showConfirm(show: Boolean, animate: Boolean = true) {
        if (!animate) {
            confirm.animate().cancel()
            confirm.visibility = View.GONE
            return
        }
        fade(confirm, show)
        confirmDialog.animate().cancel()
        if (show) {
            confirmDialog.scaleX = 0.9f
            confirmDialog.scaleY = 0.9f
            confirmDialog.animate().scaleX(1f).scaleY(1f).setDuration(MEDIUM_MS).setInterpolator(DECELERATE).start()
            confirmCancel.requestFocus()
        }
    }

    fun open(trackpad: Boolean, stats: Boolean, scale: Float, uiScale: Float) {
        this.trackpad = trackpad
        this.stats = stats
        this.scale = scale
        this.uiScale = uiScale
        updateTiles()
        updateSize()
        showSize(Size.NONE)
        showConfirm(false, animate = false)
        brightnessSlider.show(listener.brightness())
        volumeSlider.show(listener.volume())
        // Clear of the camera hole on the right edge, and of a phone's held upright at the top.
        val cutout = view.rootWindowInsets?.displayCutout
        val padding = dp(if (compact) 14 else 20)
        panel.setPadding(
            padding, maxOf(dp(if (compact) 14 else 24), (cutout?.safeInsetTop ?: 0) + dp(8)),
            padding + (cutout?.safeInsetRight ?: 0), dp(if (compact) 12 else 20),
        )
        cpuTicks = -1
        handler.removeCallbacks(sampler)
        sampler.run()
        opened = true
        fade(view, true)
        panel.translationX = dp(SLIDE_DP)
        panel.animate().translationX(0f).setDuration(MEDIUM_MS).setInterpolator(DECELERATE).start()
        firstTile.requestFocus()
    }

    /** Closes the menu, or only its dialog while that is open (Back, Escape). */
    fun close() {
        if (!opened) return
        if (confirm.visibility == View.VISIBLE && confirm.alpha > 0f) {
            showConfirm(false)
            return
        }
        opened = false
        handler.removeCallbacks(sampler)
        // A size still waiting for the slider to rest applies now.
        for (pending in listOf(applyScale, applyUiScale)) {
            if (handler.hasCallbacks(pending)) {
                handler.removeCallbacks(pending)
                pending.run()
            }
        }
        fade(view, false)
        panel.animate().translationX(dp(SLIDE_DP)).setDuration(SHORT_MS).setInterpolator(ACCELERATE).start()
        listener.onClosed()
    }

    private fun updateTiles() {
        animateColor(trackpadTile, if (trackpad) SELECTED else SURFACE_HIGH)
        animateColor(statsTile, if (stats) SELECTED else SURFACE_HIGH)
    }

    private fun updateSize() {
        resolutionValue.text = listener.resolutionFor(scale)
        interfaceValue.text = percent(uiScale)
        subtitle.text = "${listener.resolutionFor(scale)} · ${context.getString(R.string.menu_shortcut)}"
    }

    private fun showSize(next: Size) {
        size = next
        animateColor(resolutionBox, if (next == Size.RESOLUTION) SELECTED else SURFACE_HIGH)
        animateColor(interfaceBox, if (next == Size.INTERFACE) SELECTED else SURFACE_HIGH)
        when (next) {
            Size.NONE -> sizeSlider.visibility = View.GONE
            Size.RESOLUTION -> sizeSlider.bind(scale, DesktopActivity.MIN_SCALE, DesktopActivity.MAX_SCALE) {
                scale = it
                updateSize()
                apply(applyScale)
            }
            Size.INTERFACE -> sizeSlider.bind(uiScale, DesktopSettings.MIN_UI_SCALE, DesktopSettings.MAX_UI_SCALE) {
                uiScale = it
                updateSize()
                apply(applyUiScale)
            }
        }
    }

    /** Applying a size costs a redraw (or a guest command): only once the slider rests. */
    private fun apply(pending: Runnable) {
        handler.removeCallbacks(pending)
        handler.postDelayed(pending, RESIZE_DELAY_MS)
    }

    private fun Slider.bind(value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
        this.min = min
        this.max = max
        this.onChange = onChange
        show(value)
        if (visibility != View.VISIBLE) fade(this, true)
    }

    // -- Statistics ----------------------------------------------------------

    private val statsThread = Executors.newSingleThreadExecutor()
    private var cpuTicks = -1L
    private var cpuAt = 0L
    private val clockTicks = Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1)
    private val cores = Runtime.getRuntime().availableProcessors()

    /** Once a second while the menu is open; /proc is read off the main thread. */
    private val sampler: Runnable = object : Runnable {
        override fun run() {
            statsThread.execute {
                val ticks = sessionTicks()
                val at = android.os.SystemClock.elapsedRealtime()
                val memoryInfo = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                val level = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                handler.post {
                    if (cpuTicks >= 0 && at > cpuAt) {
                        // Ticks of processes that ended are gone from the sum.
                        val used = (ticks - cpuTicks).coerceAtLeast(0) * 1000f / clockTicks / (at - cpuAt) / cores
                        cpu.show("${(used * 100).roundToInt()} %", used)
                    } else if (cpuTicks < 0) {
                        cpu.show("…", 0f)
                    }
                    cpuTicks = ticks
                    cpuAt = at
                    val used = memoryInfo.totalMem - memoryInfo.availMem
                    memory.show(
                        String.format(Locale.getDefault(), "%.1f / %d GB", used / GB, (memoryInfo.totalMem / GB).roundToInt()),
                        used.toFloat() / memoryInfo.totalMem,
                    )
                    battery.show("$level %", level / 100f)
                }
            }
            handler.postDelayed(this, STATS_MS)
        }
    }

    /** CPU time of the guest's processes and of this one (the display server), in clock ticks. */
    private fun sessionTicks(): Long = (SessionPause.guestPids(context) + android.os.Process.myPid()).sumOf { pid ->
        runCatching {
            // After the name: state is the first field, utime and stime the 12th and 13th.
            val fields = File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ')
            fields[11].toLong() + fields[12].toLong()
        }.getOrDefault(0L)
    }

    private inner class StatCard(title: Int, color: Int) {
        private val value = label("", sp = if (compact) 13f else 14f, medium = true)
        private val fill = ClipDrawable(rounded(color, dp(2f)), Gravity.START, ClipDrawable.HORIZONTAL)
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE_HIGH, dp(12f))
            val padding = dp(if (compact) 12 else 14)
            setPadding(padding, dp(if (compact) 8 else 10), padding, dp(if (compact) 8 else 10))
            addView(label(context.getString(title), ON_SURFACE_VARIANT, sp = if (compact) 11f else 12f))
            addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            })
            addView(View(context).apply {
                background = LayerDrawable(arrayOf(rounded(SURFACE_HIGHEST, dp(2f)), fill))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(6) })
        }

        fun show(text: String, fraction: Float) {
            value.text = text
            fill.level = (fraction.coerceIn(0f, 1f) * 10000).roundToInt()
        }
    }

    /**
     * The app's slider, as Compose's Material 3 draws it in the settings: a
     * thick track, its active part in the primary color, a bar for the thumb
     * with a gap on each side, a dot at the end. A drag or a tap (or a click)
     * sets the value where it lands, the arrow keys move it a [step].
     */
    @SuppressLint("ViewConstructor")
    private inner class Slider(private val step: Float, var onChange: (Float) -> Unit) : View(context) {
        var min = 0f
        var max = 1f
        /** Shows the value as a percentage, if any. */
        var valueLabel: TextView? = null
        private var value = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val rect = RectF()
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var dragging = false

        init {
            isFocusable = true
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
        }

        /** Shows [v] without telling [onChange]. */
        fun show(v: Float) {
            value = v.coerceIn(min, max)
            valueLabel?.text = percent(value)
            invalidate()
        }

        private fun set(v: Float) {
            val snapped = (min + ((v - min) / step).roundToInt() * step).coerceIn(min, max)
            if (snapped == value) return
            show(snapped)
            onChange(value)
        }

        private val edge get() = dp(TRACK_DP) / 2
        private fun valueAt(x: Float) = min + ((x - edge) / (width - 2 * edge)).coerceIn(0f, 1f) * (max - min)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), dp(44))

        override fun onDraw(canvas: Canvas) {
            val track = dp(TRACK_DP)
            val thumb = dp(4f)
            val gap = dp(6f)
            val inner = dp(2f)
            val outer = track / 2
            val top = (height - track) / 2
            val fraction = if (max > min) (value - min) / (max - min) else 0f
            val x = edge + fraction * (width - 2 * edge)
            // Each part rounded at the ends of the track, nearly square by the thumb.
            fun part(left: Float, right: Float, leftRadius: Float, rightRadius: Float, color: Int) {
                if (right <= left) return
                rect.set(left, top, right, top + track)
                path.reset()
                path.addRoundRect(
                    rect,
                    floatArrayOf(leftRadius, leftRadius, rightRadius, rightRadius, rightRadius, rightRadius, leftRadius, leftRadius),
                    Path.Direction.CW,
                )
                paint.color = color
                canvas.drawPath(path, paint)
            }
            part(0f, x - thumb / 2 - gap, outer, inner, PRIMARY)
            part(x + thumb / 2 + gap, width.toFloat(), inner, outer, SELECTED)
            paint.color = PRIMARY
            if (x + thumb / 2 + gap < width - outer - inner) canvas.drawCircle(width - outer, height / 2f, inner, paint)
            rect.set(x - thumb / 2, 0f, x + thumb / 2, height.toFloat())
            canvas.drawRoundRect(rect, thumb / 2, thumb / 2, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && abs(event.x - downX) > slop) {
                        dragging = true
                        parent.requestDisallowInterceptTouchEvent(true) // the panel's scroll leaves it
                    }
                    if (dragging) set(valueAt(event.x))
                }
                MotionEvent.ACTION_UP -> if (!dragging) set(valueAt(event.x))
            }
            return true
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> set(value - step)
                KeyEvent.KEYCODE_DPAD_RIGHT -> set(value + step)
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }
    }

    /** A row as in the app's settings: [title] and the value over [slider]. */
    private fun sliderCard(title: Int, slider: Slider) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE_HIGH, dp(12f))
        val side = dp(if (compact) 12 else 20)
        setPadding(side, dp(12), side, dp(4))
        val value = label("", ON_SURFACE_VARIANT, sp = if (compact) 13f else 15f)
        slider.valueLabel = value
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                label(context.getString(title), sp = if (compact) 13f else 16f, medium = true).apply { maxLines = 1 },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(value)
        })
        addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(4) })
    }

    // -- Building blocks -----------------------------------------------------

    /** A quick tile in [row]: icon over its name. */
    private fun tile(row: LinearLayout, icon: Int, name: Int, onClick: () -> Unit): View {
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ripple(SURFACE_HIGH, dp(if (compact) 16f else 20f))
            isFocusable = true
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
            setOnClickListener { onClick() }
            val size = dp(if (compact) 22 else 24)
            addView(icon(icon, ON_SURFACE), LinearLayout.LayoutParams(size, size))
            addView(label(context.getString(name), sp = if (compact) 11f else 12f).apply {
                maxLines = 1
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(if (compact) 4 else 6)
            })
        }
        row.addView(tile, LinearLayout.LayoutParams(0, dp(if (compact) 60 else 76), 1f).apply {
            if (row.childCount > 0) marginStart = dp(if (compact) 6 else 8)
        })
        return tile
    }

    /** A box with a small title over its value. */
    private fun box(title: Int, value: TextView, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        background = ripple(SURFACE_HIGH, dp(12f))
        setPadding(dp(if (compact) 12 else 14), 0, dp(12), 0)
        isFocusable = true
        pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
        setOnClickListener { onClick() }
        addView(label(context.getString(title), ON_SURFACE_VARIANT, sp = if (compact) 11f else 12f))
        addView(value)
    }

    /** [children] side by side in equal widths. */
    private fun row(vararg children: View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((i, child) in children.withIndex()) {
            addView(child, LinearLayout.LayoutParams(0, height, 1f).apply { if (i > 0) marginStart = dp(if (compact) 6 else 8) })
        }
    }

    /** Material 3's text button: a label in [color], 40 dp tall. */
    private fun textButton(text: Int, color: Int, onClick: () -> Unit) = TextView(context).apply {
        setText(text)
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        minWidth = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        background = ripple(Color.TRANSPARENT, dp(20f))
        isFocusable = true
        pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
    }

    private fun roundButton(icon: Int, description: Int, onClick: () -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        contentDescription = context.getString(description)
        background = ripple(SURFACE_HIGH, dp(22f))
        isFocusable = true
        pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
        setOnClickListener { onClick() }
        addView(icon(icon, ON_SURFACE), LinearLayout.LayoutParams(dp(20), dp(20)))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    /** A segment of the session's actions: [radii] are its outer and inner corners. */
    private fun action(icon: Int, text: Int, color: Int, fill: Int, radii: FloatArray, onClick: () -> Unit) =
        TextView(context).apply {
            setText(text)
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 14f)
            gravity = Gravity.CENTER
            maxLines = 1
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE), GradientDrawable().apply {
                setColor(fill)
                cornerRadii = radii
            }, null)
            if (!compact) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(context.getDrawable(icon)?.mutate()?.apply {
                    setTint(color)
                    setBounds(0, 0, dp(18), dp(18))
                }, null, null, null)
                compoundDrawablePadding = dp(8)
                setPadding(dp(12), 0, dp(12), 0)
            }
            isFocusable = true
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
            setOnClickListener { onClick() }
        }

    /** Corner radii (dp) of a segment: [start] on the left, [end] on the right. */
    private fun corners(start: Float, end: Float): FloatArray {
        val s = dp(start)
        val e = dp(end)
        return floatArrayOf(s, s, e, e, e, e, s, s)
    }

    private fun icon(resource: Int, tint: Int) = ImageView(context).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(tint)
    }

    private fun label(text: String, color: Int = ON_SURFACE, sp: Float = 15f, medium: Boolean = false) =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun ripple(fill: Int, radius: Float) =
        RippleDrawable(ColorStateList.valueOf(RIPPLE), rounded(fill, radius), rounded(Color.WHITE, radius))

    /** Selected state of a tile or box: its fill fades between colors. */
    private fun animateColor(button: View, to: Int) {
        val fill = ((button.background as RippleDrawable).getDrawable(0) as GradientDrawable)
        val from = fill.color?.defaultColor ?: Color.TRANSPARENT
        if (from == to) return
        ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = SHORT_MS
            addUpdateListener { fill.setColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun fade(target: View, show: Boolean) {
        target.animate().cancel()
        if (show) {
            if (target.visibility != View.VISIBLE) target.alpha = 0f
            target.visibility = View.VISIBLE
            target.animate().alpha(1f).setDuration(MEDIUM_MS).setInterpolator(DECELERATE).withEndAction(null).start()
        } else {
            target.animate().alpha(0f).setDuration(SHORT_MS).setInterpolator(ACCELERATE)
                .withEndAction { target.visibility = View.GONE }.start()
        }
    }

    private fun percent(scale: Float) = "${(scale * 100).roundToInt()} %"

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)
    private fun dp(value: Int) = dp(value.toFloat()).roundToInt()

    private object Color {
        const val TRANSPARENT = 0
        const val WHITE = -0x1
    }

    private companion object {
        // MoonVibe's dark palette (blue fallback), as in the app's theme.
        const val SURFACE = 0xF71A1B20.toInt()     // surfaceContainerLow, nearly opaque
        const val SURFACE_HIGH = 0xFF282A2F.toInt()
        const val SURFACE_HIGHEST = 0xFF33353A.toInt()
        const val SELECTED = 0xFF3E4759.toInt()    // secondaryContainer
        const val PRIMARY = 0xFF82A6E8.toInt()
        const val GREEN = 0xFF3DDC84.toInt()
        const val ON_SURFACE = 0xFFE2E2E9.toInt()
        const val ON_SURFACE_VARIANT = 0xFFC4C6D0.toInt()
        const val ERROR = 0xFFFFB4AB.toInt()
        const val ON_ERROR = 0xFF690005.toInt()
        const val VEIL = 0x52000000                // the desktop stays in sight
        const val SCRIM = 0x52000000               // Material 3's, at 32%
        const val RIPPLE = 0x33FFFFFF
        const val SHORT_MS = 150L
        const val MEDIUM_MS = 250L
        const val RESIZE_DELAY_MS = 300L
        const val STATS_MS = 1000L
        const val SLIDE_DP = 32f
        const val STEP = 0.05f
        const val TRACK_DP = 16f
        const val GB = 1e9f
        val DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    }
}
