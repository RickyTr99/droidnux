package app.droidnux.desktop

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.PointerIcon
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import app.droidnux.R
import kotlin.math.roundToInt

/**
 * The menu over the desktop: a pill at the top (keyboard, input mode, desktop
 * size, back to Android, exit) with a panel below it for the size slider or
 * the exit confirmation. Over the SurfaceView, so it only fades: no sliding.
 */
class SessionMenu(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onToggleKeyboard()
        fun onTrackpadChanged(trackpad: Boolean)
        /** While the slider moves, so the label can show the resolution. */
        fun resolutionFor(scale: Float): String
        fun onScaleChanged(scale: Float)
        fun onBackToAndroid()
        fun onExit()
        fun onClosed()
    }

    private enum class Panel { NONE, SIZE, EXIT }

    private val handler = Handler(Looper.getMainLooper())
    private var panel = Panel.NONE
    private var trackpad = false
    private var scale = 1f
    private var opened = false
    private val applyScale = Runnable { listener.onScaleChanged(scale) }

    /** Full-screen layer: a touch outside the pill closes the menu. */
    val view: FrameLayout = FrameLayout(context).apply {
        visibility = View.GONE
        isClickable = true
        setOnClickListener { close() }
        setOnGenericMotionListener { _, _ -> true } // hover stays here, not on the desktop under it
        // Android's own pointer, which the desktop under the menu hides (X draws its cursor there).
        pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_ARROW)
    }

    val isOpen: Boolean get() = opened

    private val modeIcon = icon(R.drawable.ic_menu_touch, ON_SURFACE)
    private val modeLabel = label("")
    private val sizeLabel = label("")
    private val sizeButton: LinearLayout
    private val panelView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, dp(16f))
        setPadding(dp(18), dp(16), dp(18), dp(16))
        visibility = View.GONE
    }

    init {
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(SURFACE, dp(28f))
            setPadding(dp(6), 0, dp(6), 0)
            isClickable = true // touches on the pill's background do not close the menu
        }
        pill.addView(iconButton(R.drawable.ic_menu_keyboard, R.string.menu_keyboard, ON_SURFACE) {
            close()
            listener.onToggleKeyboard()
        })
        pill.addView(textButton(modeIcon, modeLabel) {
            trackpad = !trackpad
            updateLabels()
            listener.onTrackpadChanged(trackpad)
        })
        sizeButton = textButton(icon(R.drawable.ic_menu_display, ON_SURFACE), sizeLabel) {
            show(if (panel == Panel.SIZE) Panel.NONE else Panel.SIZE)
        }
        pill.addView(sizeButton)
        pill.addView(View(context).apply { setBackgroundColor(OUTLINE_VARIANT) }, LinearLayout.LayoutParams(dp(1), dp(24)).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
        pill.addView(iconButton(R.drawable.ic_menu_android, R.string.menu_to_android, ON_SURFACE) {
            close()
            listener.onBackToAndroid()
        })
        pill.addView(iconButton(R.drawable.ic_menu_power, R.string.menu_exit, ERROR) {
            show(if (panel == Panel.EXIT) Panel.NONE else Panel.EXIT)
        })

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
            addView(panelView, LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }
        view.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = dp(24) })
    }

    fun open(trackpad: Boolean, scale: Float) {
        this.trackpad = trackpad
        this.scale = scale
        updateLabels()
        show(Panel.NONE, animate = false)
        opened = true
        fade(view, true)
        view.getChildAt(0).requestFocus()
    }

    fun close() {
        if (!opened) return
        opened = false
        // A size still waiting for the slider to rest applies now.
        if (handler.hasCallbacks(applyScale)) {
            handler.removeCallbacks(applyScale)
            applyScale.run()
        }
        fade(view, false)
        listener.onClosed()
    }

    private fun updateLabels() {
        modeIcon.setImageResource(if (trackpad) R.drawable.ic_menu_trackpad else R.drawable.ic_menu_touch)
        modeLabel.setText(if (trackpad) R.string.menu_mode_trackpad else R.string.menu_mode_touch)
        sizeLabel.text = percent(scale)
    }

    private fun show(next: Panel, animate: Boolean = true) {
        panel = next
        animateColor(sizeButton, if (next == Panel.SIZE) SELECTED else Color.TRANSPARENT)
        if (next == Panel.NONE) {
            if (animate) fade(panelView, false) else panelView.visibility = View.GONE
            return
        }
        panelView.removeAllViews()
        when (next) {
            Panel.SIZE -> buildSizePanel()
            Panel.EXIT -> buildExitPanel()
            Panel.NONE -> Unit
        }
        panelView.alpha = 0f
        fade(panelView, true)
    }

    private fun buildSizePanel() {
        val value = label(percent(scale), PRIMARY, medium = true)
        val resolution = label(listener.resolutionFor(scale), ON_SURFACE_VARIANT, sp = 13f)
        panelView.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label(context.getString(R.string.menu_desktop_size)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
        })
        val steps = ((DesktopActivity.MAX_SCALE - DesktopActivity.MIN_SCALE) * 20).roundToInt() // 5% steps
        panelView.addView(SeekBar(context).apply {
            max = steps
            progress = ((scale - DesktopActivity.MIN_SCALE) * 20).roundToInt()
            // A thin track and a round thumb, as Material 3 (the window's old theme draws thick bars).
            progressDrawable = LayerDrawable(arrayOf(
                rounded(SURFACE_HIGHEST, dp(2f)),
                ClipDrawable(rounded(PRIMARY, dp(2f)), Gravity.START, ClipDrawable.HORIZONTAL),
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            minHeight = dp(4)
            maxHeight = dp(4)
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(PRIMARY)
                setSize(dp(20), dp(20))
            }
            splitTrack = false
            background = null
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    scale = DesktopActivity.MIN_SCALE + progress / 20f
                    value.text = percent(scale)
                    sizeLabel.text = percent(scale)
                    resolution.text = listener.resolutionFor(scale)
                    // Resizing the desktop costs a full redraw: only once the slider rests.
                    handler.removeCallbacks(applyScale)
                    handler.postDelayed(applyScale, RESIZE_DELAY_MS)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        panelView.addView(resolution)
    }

    private fun buildExitPanel() {
        panelView.addView(label(context.getString(R.string.menu_exit_title), medium = true))
        panelView.addView(label(context.getString(R.string.menu_exit_text), ON_SURFACE_VARIANT, sp = 13f).apply {
            setPadding(0, dp(4), 0, dp(12))
        })
        panelView.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(pillButton(context.getString(R.string.action_cancel), ON_SURFACE, Color.TRANSPARENT) { show(Panel.NONE) })
            addView(pillButton(context.getString(R.string.menu_exit_confirm), ON_ERROR, ERROR) {
                close()
                listener.onExit()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(8) })
        })
    }

    // -- Building blocks -----------------------------------------------------

    private fun iconButton(icon: Int, description: Int, tint: Int, onClick: () -> Unit) =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER
            contentDescription = context.getString(description)
            background = ripple(Color.TRANSPARENT, dp(22f))
            isFocusable = true
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
            setOnClickListener { onClick() }
            addView(icon(icon, tint))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(44))
        }

    private fun textButton(icon: ImageView, text: TextView, onClick: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(Color.TRANSPARENT, dp(22f))
            setPadding(dp(14), 0, dp(14), 0)
            isFocusable = true
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
            setOnClickListener { onClick() }
            addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
            text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            addView(text)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
        }

    private fun pillButton(text: String, textColor: Int, fill: Int, onClick: () -> Unit) =
        TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = ripple(fill, dp(22f))
            setPadding(dp(20), 0, dp(20), 0)
            isFocusable = true
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
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

    /** Selected state of a pill button: its fill fades between colors. */
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

    private fun percent(scale: Float) = "${(scale * 100).roundToInt()}%"

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)
    private fun dp(value: Int) = dp(value.toFloat()).roundToInt()

    private object Color {
        const val TRANSPARENT = 0
        const val WHITE = -0x1
    }

    private companion object {
        // MoonVibe's dark palette (blue fallback), as in the app's theme.
        const val SURFACE = 0xF51A1B20.toInt()     // surfaceContainerLow, nearly opaque
        const val SURFACE_HIGHEST = 0xFF33353A.toInt()
        const val SELECTED = 0xFF3E4759.toInt()    // secondaryContainer
        const val PRIMARY = 0xFF82A6E8.toInt()
        const val ON_SURFACE = 0xFFE2E2E9.toInt()
        const val ON_SURFACE_VARIANT = 0xFFC4C6D0.toInt()
        const val OUTLINE_VARIANT = 0xFF44474E.toInt()
        const val ERROR = 0xFFFFB4AB.toInt()
        const val ON_ERROR = 0xFF690005.toInt()
        const val RIPPLE = 0x33FFFFFF
        const val SHORT_MS = 150L
        const val MEDIUM_MS = 250L
        const val RESIZE_DELAY_MS = 300L
        val DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    }
}
