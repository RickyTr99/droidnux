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
import android.graphics.drawable.GradientDrawable
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
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.droidnux.R
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The menu over the desktop: a bar at the top with the quick toggles
 * (keyboard, trackpad, screenshot, statistics), buttons that open a popup
 * with brightness, volume or the desktop's size (the settings' slider rows),
 * gauges of the session's CPU, memory and battery, and the session's actions
 * (Home, Pause, End). On a phone the same bar, smaller; where it is still too
 * wide the gauges drop their text, then the whole bar shrinks to fit.
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

    private val colors = DesktopColors(context)
    private val handler = Handler(Looper.getMainLooper())
    private val compact = context.resources.configuration.smallestScreenWidthDp < 600
    private val buttonSize = dp(if (compact) 44 else 56)
    private val barGap = dp(if (compact) 4 else 8)
    private val barPadding = dp(if (compact) 6 else 8)
    private var trackpad = false
    private var stats = false
    private var scale = 1f
    private var uiScale = 1f
    private var opened = false
    /** The bar at the bottom of the screen, its popup and dialog above it (DesktopSettings.menuBottom). */
    var atBottom = false
    private val applyScale = Runnable { listener.onScaleChanged(scale) }
    private val applyUiScale = Runnable { listener.onUiScaleChanged(uiScale) }

    /** Full-screen layer: a touch outside the bar closes its popup, or the menu. */
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

    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(colors.surface, buttonSize / 2f + barPadding)
        setPadding(barPadding, barPadding, barPadding, barPadding)
        isClickable = true // touches on the bar's background do not close the menu
        isFocusable = false // clickable implies focusable: focus belongs to the buttons
        // Shrunk to fit (open), from the center of its screen edge.
        addOnLayoutChangeListener { bar, _, _, _, _, _, _, _, _ ->
            bar.pivotX = bar.width / 2f
            bar.pivotY = if (atBottom) bar.height.toFloat() else 0f
        }
    }
    private val keyboardButton: View
    private val trackpadButton: View
    private val statsButton: View
    private val endButton: View
    private val cpu = Gauge(R.drawable.ic_menu_cpu, R.string.menu_cpu, "100 %")
    private val memory = Gauge(R.drawable.ic_menu_memory, R.string.menu_memory, "00,0 GB")
    private val battery = Gauge(R.drawable.ic_menu_battery, R.string.menu_battery, "100 %")

    // -- The popup: one page at a time, under its button --------------------

    private val brightnessSlider = Slider(STEP) { listener.onBrightnessChanged(it) }
    private val volumeSlider = Slider(1f / listener.volumeSteps().coerceAtLeast(1)) { listener.onVolumeChanged(it) }
    private val resolutionSlider = Slider(STEP, DesktopActivity.MIN_SCALE, DesktopActivity.MAX_SCALE, listener::resolutionFor) {
        scale = it
        interfaceSlider.show(kwinScale()) // the elements keep their size: KWin's scale follows
        apply(applyScale)
    }
    // KWin's scale, as Plasma's display settings show it.
    private val interfaceSlider: Slider = Slider(STEP, MIN_KWIN_SCALE, MAX_KWIN_SCALE, { percent((it * 20).roundToInt() / 20f) }) {
        uiScale = it * DesktopActivity.DEFAULT_SCALE / scale
        apply(applyUiScale)
    }
    private val popup = FrameLayout(context).apply {
        visibility = View.GONE
        background = rounded(colors.surface, dp(24f))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        isClickable = true // a touch on the popup does not close it
        isFocusable = false
        addOnLayoutChangeListener { popup, _, _, _, _, _, _, _, _ -> if (atBottom) popup.pivotY = popup.height.toFloat() }
    }
    /** Each popup button and its page. */
    private val pages = mutableListOf<Pair<View, LinearLayout>>()
    /** The page shown, null while the popup is closed (or closing). */
    private var shownPage: LinearLayout? = null

    init {
        keyboardButton = button(R.drawable.ic_menu_keyboard, R.string.menu_keyboard) {
            close()
            listener.onToggleKeyboard()
        }
        trackpadButton = button(R.drawable.ic_menu_trackpad, R.string.menu_mode_trackpad) {
            trackpad = !trackpad
            updateButtons()
            listener.onTrackpadChanged(trackpad)
        }
        button(R.drawable.ic_menu_screenshot, R.string.menu_screenshot) {
            close()
            listener.onScreenshot()
        }
        statsButton = button(R.drawable.ic_menu_stats, R.string.menu_stats) {
            stats = !stats
            updateButtons()
            listener.onStatsChanged(stats)
        }
        separator()
        page(R.drawable.ic_menu_brightness, R.string.menu_brightness, sliderRow(R.string.menu_brightness, brightnessSlider))
        page(R.drawable.ic_menu_volume, R.string.menu_volume, sliderRow(R.string.menu_volume, volumeSlider))
        page(
            R.drawable.ic_menu_display, R.string.menu_screen,
            sliderRow(R.string.menu_resolution, resolutionSlider), sliderRow(R.string.menu_interface, interfaceSlider),
        )
        separator()
        for (gauge in listOf(cpu, memory, battery)) addToBar(gauge.view, ViewGroup.LayoutParams.WRAP_CONTENT)
        separator()
        button(R.drawable.ic_menu_home, R.string.menu_home) {
            close()
            listener.onBackToAndroid()
        }
        button(R.drawable.ic_menu_pause, R.string.menu_pause) {
            close()
            listener.onPauseSession()
        }
        endButton = button(R.drawable.ic_menu_power, R.string.menu_end, colors.error, colors.onError) { showConfirm(true) }

        view.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ))
        view.addView(popup, FrameLayout.LayoutParams(dp(if (compact) 320 else 360), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
    }

    /** A button opening [rows] in the popup. */
    private fun page(icon: Int, description: Int, vararg rows: View) {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            for (row in rows) addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (childCount > 0) topMargin = dp(4)
            })
        }
        popup.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        lateinit var button: View
        button = button(icon, description) { if (shownPage === page) hidePopup() else showPopup(button, page) }
        pages += button to page
    }

    /**
     * Opens [page] under [button]: the popup grows out of it, or, when another
     * page is open, glides there while the pages fade through.
     */
    private fun showPopup(button: View, page: LinearLayout) {
        val previous = shownPage
        shownPage = page
        updateButtons()
        val width = popup.layoutParams.width
        val center = barX(button.left + button.width / 2f)
        val x = (center - width / 2f).coerceIn(dp(8f), (view.width - width - dp(8)).toFloat().coerceAtLeast(dp(8f)))
        // Under the bar, or above it from the bottom (its height changes with the page).
        (popup.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = (if (atBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.START
            bottomMargin = if (atBottom) (view.height - barY(0f)).roundToInt() + dp(8) else 0
        }
        popup.requestLayout()
        popup.translationY = if (atBottom) 0f else barY(bar.height.toFloat()) + dp(8f)
        popup.pivotX = center - x
        popup.pivotY = if (atBottom) popup.height.toFloat() else 0f
        popup.animate().cancel()
        for ((_, other) in pages) if (other !== page && other !== previous) {
            other.animate().cancel()
            other.visibility = View.GONE
        }
        if (previous == null) {
            page.animate().cancel()
            page.alpha = 1f
            page.translationY = 0f
            page.visibility = View.VISIBLE
            if (popup.visibility != View.VISIBLE) {
                popup.alpha = 0f
                popup.scaleX = POPUP_FROM
                popup.scaleY = POPUP_FROM
                popup.visibility = View.VISIBLE
            }
            popup.translationX = x
            popup.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(MEDIUM_MS).setInterpolator(DECELERATE).start()
        } else {
            popup.animate().translationX(x).alpha(1f).scaleX(1f).scaleY(1f).setDuration(GLIDE_MS).setInterpolator(EMPHASIZED).start()
            previous.animate().cancel()
            previous.animate().alpha(0f).setDuration(FADE_OUT_MS).setInterpolator(ACCELERATE).withEndAction {
                previous.visibility = View.GONE
                page.alpha = 0f
                page.translationY = dp(6f)
                page.visibility = View.VISIBLE
                page.animate().alpha(1f).translationY(0f).setDuration(FADE_IN_MS).setInterpolator(EMPHASIZED).start()
            }.start()
        }
        page.getChildAt(0).findFocusable()?.requestFocus()
    }

    private fun hidePopup(animate: Boolean = true) {
        val page = shownPage ?: return
        shownPage = null
        updateButtons()
        pages.firstOrNull { it.second === page }?.first?.requestFocus()
        popup.animate().cancel()
        if (!animate) {
            popup.visibility = View.GONE
            return
        }
        popup.animate().alpha(0f).scaleX(POPUP_FROM).scaleY(POPUP_FROM).setDuration(SHORT_MS).setInterpolator(ACCELERATE)
            .withEndAction { popup.visibility = View.GONE }.start()
    }

    private fun View.findFocusable(): View? = when {
        this is Slider -> this
        this is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findFocusable() }
        else -> null
    }

    // -- Ending the session: Material 3's basic dialog, as the Home asks -----

    private val confirmCancel = textButton(R.string.action_cancel, colors.primary) { showConfirm(false) }
    private val confirmDialog = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(colors.surfaceHigh, dp(20f))
        setPadding(dp(20), dp(18), dp(12), dp(8))
        isClickable = true // a touch on the dialog does not dismiss it
        isFocusable = false
        addView(label(context.getString(R.string.menu_exit_title), sp = 16f, medium = true))
        addView(label(context.getString(R.string.menu_exit_text), colors.onSurfaceVariant, sp = 13f).apply {
            setPadding(0, 0, dp(8), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(confirmCancel)
            addView(textButton(R.string.menu_exit_confirm, colors.error) {
                showConfirm(false, animate = false)
                close()
                listener.onExit()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        // It grows out of the End button, at its top right (bottom right under a bar at the bottom).
        addOnLayoutChangeListener { dialog, _, _, _, _, _, _, _, _ ->
            dialog.pivotX = dialog.width.toFloat()
            dialog.pivotY = if (atBottom) dialog.height.toFloat() else 0f
        }
    }

    /** The dialog over a scrim on the whole screen, under the End button; a touch on the scrim dismisses it. */
    private val confirm = FrameLayout(context).apply {
        visibility = View.GONE
        setBackgroundColor(SCRIM)
        isClickable = true
        isFocusable = false
        setOnClickListener { showConfirm(false) }
        addView(confirmDialog, FrameLayout.LayoutParams(dp(CONFIRM_DP), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
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
        if (show) hidePopup()
        fade(confirm, show)
        confirmDialog.animate().cancel()
        if (show) {
            (confirmDialog.layoutParams as FrameLayout.LayoutParams).apply {
                // Its right edge under the End button's; leftMargin: a marginEnd set after layout is never resolved.
                leftMargin = (barX(endButton.right.toFloat()).roundToInt() - dp(CONFIRM_DP)).coerceAtLeast(dp(8))
                gravity = (if (atBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.LEFT
                topMargin = if (atBottom) 0 else barY(bar.height.toFloat()).roundToInt() + dp(8)
                bottomMargin = if (atBottom) (view.height - barY(0f)).roundToInt() + dp(8) else 0
            }
            confirmDialog.requestLayout()
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
        hidePopup(animate = false)
        updateButtons()
        resolutionSlider.show(scale)
        interfaceSlider.show(kwinScale())
        showConfirm(false, animate = false)
        brightnessSlider.show(listener.brightness())
        volumeSlider.show(listener.volume())
        // Clear of the camera hole, and of the screen's edges.
        val cutout = view.rootWindowInsets?.displayCutout
        (bar.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = (if (atBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
            topMargin = if (atBottom) 0 else (cutout?.safeInsetTop ?: 0) + dp(if (compact) 12 else 24)
            bottomMargin = if (atBottom) (cutout?.safeInsetBottom ?: 0) + dp(if (compact) 12 else 24) else 0
        }
        bar.requestLayout()
        fitBar((view.parent as View).width - (cutout?.safeInsetLeft ?: 0) - (cutout?.safeInsetRight ?: 0) - 2 * dp(16))
        cpuTicks = -1
        handler.removeCallbacks(sampler)
        sampler.run()
        opened = true
        fade(view, true)
        bar.translationY = slide()
        bar.animate().translationY(0f).setDuration(MEDIUM_MS).setInterpolator(DECELERATE).start()
        keyboardButton.requestFocus()
    }

    /** The gauges' text goes first, then the bar shrinks, until it fits in [available] pixels. */
    private fun fitBar(available: Int) {
        val unbounded = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        fun width(): Int {
            bar.measure(unbounded, unbounded)
            return bar.measuredWidth
        }
        for (gauge in listOf(cpu, memory, battery)) gauge.showText(true)
        if (width() > available) for (gauge in listOf(cpu, memory, battery)) gauge.showText(false)
        val natural = width()
        // Laid out whole, wider than the screen if it must, then shrunk.
        bar.layoutParams.width = natural
        val fit = if (available > 0) (available.toFloat() / natural).coerceAtMost(1f) else 1f
        bar.scaleX = fit
        bar.scaleY = fit
    }

    /** Closes the menu, or only its dialog or popup while one is open (Back, Escape, a touch outside). */
    fun close() {
        if (!opened) return
        if (confirm.visibility == View.VISIBLE && confirm.alpha > 0f) {
            showConfirm(false)
            return
        }
        if (shownPage != null) {
            hidePopup()
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
        bar.animate().translationY(slide()).setDuration(SHORT_MS).setInterpolator(ACCELERATE).start()
        listener.onClosed()
    }

    /** The interface size changed elsewhere (Plasma's display settings). */
    fun showUiScale(uiScale: Float) {
        this.uiScale = uiScale
        interfaceSlider.show(kwinScale())
    }

    private fun kwinScale() = uiScale * scale / DesktopActivity.DEFAULT_SCALE

    /** Toggles on in the primary color, the open popup's button in the selected one. */
    private fun updateButtons() {
        for ((button, on) in listOf(trackpadButton to trackpad, statsButton to stats)) {
            animateColors(button, if (on) colors.primary else colors.surfaceHigh, if (on) colors.onPrimary else colors.onSurface)
        }
        for ((button, page) in pages) {
            val open = page === shownPage
            animateColors(button, if (open) colors.selected else colors.surfaceHigh, if (open) colors.onSelected else colors.onSurface)
        }
    }

    /** Applying a size costs a redraw (or a guest command): only once the slider rests. */
    private fun apply(pending: Runnable) {
        handler.removeCallbacks(pending)
        handler.postDelayed(pending, RESIZE_DELAY_MS)
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
                    memory.show(String.format(Locale.getDefault(), "%.1f GB", used / GB), used.toFloat() / memoryInfo.totalMem)
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

    /** A gauge in the bar: Material 3's circular indicator around [icon], its value and [name] beside it. */
    private inner class Gauge(icon: Int, private val name: Int, widest: String) {
        private val ring = Ring(icon)
        private val value = label("", sp = if (compact) 13f else 14f, medium = true).apply {
            isSingleLine = true
            minWidth = paint.measureText(widest).roundToInt() // the bar keeps its width as values change
        }
        private val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(value)
            addView(label(context.getString(name), colors.onSurfaceVariant, sp = 11f).apply { isSingleLine = true })
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(colors.surfaceHigh, buttonSize / 2f)
            addView(ring, LinearLayout.LayoutParams(ring.size, ring.size))
            addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(if (compact) 6 else 10)
            })
        }

        /** Without its text the gauge is a round button's size. */
        fun showText(shown: Boolean) {
            text.visibility = if (shown) View.VISIBLE else View.GONE
            val inset = (buttonSize - ring.size) / 2
            view.setPadding(inset, 0, if (shown) dp(if (compact) 10 else 14) else inset, 0)
        }

        fun show(text: String, fraction: Float) {
            value.text = text
            view.contentDescription = "${context.getString(name)} $text"
            ring.show(fraction.coerceIn(0f, 1f))
        }
    }

    /** Material 3's circular progress indicator, with a gap at each end of the active arc, around an icon. */
    @SuppressLint("ViewConstructor")
    private inner class Ring(icon: Int) : View(context) {
        val size = dp(if (compact) 36 else 44)
        private val stroke = dp(4f)
        private val iconSize = dp(if (compact) 18 else 20)
        private val drawable = context.getDrawable(icon)!!.mutate().apply { setTint(colors.primary) }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        private val oval = RectF()
        private var fraction = 0f
        private var animator: ValueAnimator? = null

        fun show(to: Float) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(fraction, to).apply {
                duration = MEDIUM_MS
                interpolator = EMPHASIZED
                addUpdateListener {
                    fraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            oval.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
            val radius = oval.width() / 2
            // The gap between the arcs, round caps included.
            val gap = Math.toDegrees(((dp(4f) + stroke) / radius).toDouble()).toFloat()
            val sweep = fraction * 360f
            paint.color = colors.selected
            if (sweep <= 0f) {
                canvas.drawArc(oval, 0f, 360f, false, paint)
            } else if (sweep < 360f - 2 * gap) {
                canvas.drawArc(oval, -90f + sweep + gap, 360f - sweep - 2 * gap, false, paint)
            }
            paint.color = colors.primary
            if (sweep > 0f) canvas.drawArc(oval, -90f, sweep.coerceAtMost(360f - gap), false, paint)
            val left = (width - iconSize) / 2
            val top = (height - iconSize) / 2
            drawable.setBounds(left, top, left + iconSize, top + iconSize)
            drawable.draw(canvas)
        }
    }

    /**
     * The app's slider, as Compose's Material 3 draws it in the settings: a
     * thick track, its active part in the primary color, a bar for the thumb
     * with a gap on each side, a dot at the end. A drag or a tap (or a click)
     * sets the value where it lands, the arrow keys move it a [step].
     */
    @SuppressLint("ViewConstructor")
    private inner class Slider(
        private val step: Float,
        private val min: Float = 0f,
        private val max: Float = 1f,
        /** How [valueLabel] shows the value. */
        private val format: (Float) -> String = { percent(it) },
        private val onChange: (Float) -> Unit,
    ) : View(context) {
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
            valueLabel?.text = format(value)
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
            setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), dp(SLIDER_DP))

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
            part(0f, x - thumb / 2 - gap, outer, inner, colors.primary)
            part(x + thumb / 2 + gap, width.toFloat(), inner, outer, colors.selected)
            paint.color = colors.primary
            if (x + thumb / 2 + gap < width - outer - inner) canvas.drawCircle(width - outer, height / 2f, inner, paint)
            rect.set(x - thumb / 2, (height - dp(THUMB_DP)) / 2, x + thumb / 2, (height + dp(THUMB_DP)) / 2)
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
                        parent.requestDisallowInterceptTouchEvent(true)
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

    /** A row as in the app's settings (SliderRow): [title] and the value over [slider]. */
    private fun sliderRow(title: Int, slider: Slider) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(colors.surfaceHigh, dp(12f))
        setPadding(dp(20), dp(12), dp(20), dp(12))
        val value = label("", colors.onSurfaceVariant, sp = 15f).apply { isSingleLine = true }
        slider.valueLabel = value
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                label(context.getString(title), sp = 16f, medium = true).apply { isSingleLine = true },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(value)
        })
        addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(SLIDER_DP)))
    }

    // -- Building blocks -----------------------------------------------------

    private fun addToBar(child: View, width: Int) = bar.addView(child, LinearLayout.LayoutParams(width, buttonSize).apply {
        if (bar.childCount > 0) marginStart = barGap
    })

    private fun separator() = bar.addView(View(context).apply { setBackgroundColor(colors.outlineVariant) }, LinearLayout.LayoutParams(dp(1), dp(32)).apply {
        marginStart = barGap + dp(if (compact) 2 else 6)
        marginEnd = dp(if (compact) 2 else 6)
    })

    /** Where [x] and [y] of the bar's layout land in the menu's layer, the bar shrunk (fitBar) but not sliding. */
    private fun barX(x: Float) = bar.left + bar.pivotX + (x - bar.pivotX) * bar.scaleX
    private fun barY(y: Float) = bar.top + bar.pivotY + (y - bar.pivotY) * bar.scaleY

    /** Where the bar slides in from and out to: off its screen edge. */
    private fun slide() = if (atBottom) dp(SLIDE_DP) else -dp(SLIDE_DP)

    /** A round button in the bar: [icon] in [tint] on [fill], pressed in with a spring as in the Home. */
    @SuppressLint("ClickableViewAccessibility")
    private fun button(icon: Int, description: Int, fill: Int = colors.surfaceHigh, tint: Int = colors.onSurface, onClick: () -> Unit): View {
        val button = FrameLayout(context).apply {
            contentDescription = context.getString(description)
            tooltipText = contentDescription
            background = ripple(fill, buttonSize / 2f)
            isFocusable = true
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
            setOnClickListener { onClick() }
            setOnTouchListener { button, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> button.animate().scaleX(PRESSED).scaleY(PRESSED).setDuration(PRESS_MS).setInterpolator(DECELERATE).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        button.animate().scaleX(1f).scaleY(1f).setDuration(RELEASE_MS).setInterpolator(SPRING).start()
                }
                false
            }
            val size = dp(if (compact) 22 else 24)
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(tint)
            }, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }
        addToBar(button, buttonSize)
        return button
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

    private fun label(text: String, color: Int = colors.onSurface, sp: Float = 15f, medium: Boolean = false) =
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

    /** A button's fill and icon fade to new colors. */
    private fun animateColors(button: View, fill: Int, tint: Int) {
        val background = ((button.background as RippleDrawable).getDrawable(0) as GradientDrawable)
        val icon = (button as ViewGroup).getChildAt(0) as ImageView
        val fromFill = background.color?.defaultColor ?: Color.TRANSPARENT
        val fromTint = icon.imageTintList?.defaultColor ?: tint
        if (fromFill == fill && fromTint == tint) return
        val evaluator = ArgbEvaluator()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SHORT_MS
            addUpdateListener {
                background.setColor(evaluator.evaluate(it.animatedFraction, fromFill, fill) as Int)
                icon.imageTintList = ColorStateList.valueOf(evaluator.evaluate(it.animatedFraction, fromTint, tint) as Int)
            }
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

    internal companion object {
        const val VEIL = 0x52000000                // the desktop stays in sight
        const val SCRIM = 0x52000000               // Material 3's, at 32%
        const val RIPPLE = 0x33FFFFFF
        const val SHORT_MS = 150L
        const val MEDIUM_MS = 250L
        // The popup gliding to another button, its pages fading through (as the Home's pages).
        const val GLIDE_MS = 320L
        const val FADE_OUT_MS = 90L
        const val FADE_IN_MS = 210L
        const val PRESS_MS = 100L
        const val RELEASE_MS = 350L
        const val PRESSED = 0.88f
        const val POPUP_FROM = 0.8f
        const val RESIZE_DELAY_MS = 300L
        const val STATS_MS = 1000L
        const val SLIDE_DP = 16f
        const val CONFIRM_DP = 264
        const val STEP = 0.05f
        // Material 3's slider, as Compose draws it in the app's settings.
        const val TRACK_DP = 16f
        const val THUMB_DP = 44f
        const val SLIDER_DP = 44
        // Plasma's display settings' range.
        const val MIN_KWIN_SCALE = 0.5f
        const val MAX_KWIN_SCALE = 3f
        const val GB = 1e9f
        val DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        // Compose's Emphasized, as the Home uses it.
        val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
        // Near the Home's press spring (damping 0.4): a small overshoot on release.
        val SPRING = OvershootInterpolator(2.5f)
    }
}
