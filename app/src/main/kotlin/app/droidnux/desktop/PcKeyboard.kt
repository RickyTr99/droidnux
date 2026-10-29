package app.droidnux.desktop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import app.droidnux.R
import app.droidnux.desktop.SessionMenu.Companion.ACCELERATE
import app.droidnux.desktop.SessionMenu.Companion.DECELERATE
import app.droidnux.desktop.SessionMenu.Companion.MEDIUM_MS
import app.droidnux.desktop.SessionMenu.Companion.ON_SURFACE
import app.droidnux.desktop.SessionMenu.Companion.ON_SURFACE_VARIANT
import app.droidnux.desktop.SessionMenu.Companion.PRIMARY
import app.droidnux.desktop.SessionMenu.Companion.SELECTED
import app.droidnux.desktop.SessionMenu.Companion.SHORT_MS
import app.droidnux.desktop.SessionMenu.Companion.SURFACE
import app.droidnux.desktop.SessionMenu.Companion.SURFACE_HIGH
import app.droidnux.desktop.SessionMenu.Companion.SURFACE_HIGHEST
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The full PC keyboard over the bottom of the desktop: six rows on a tablet,
 * five with Fn on a phone, under a bar with paste, Android's keyboard, shrink
 * and hide. Shrunk, it waits as a button that can be dragged to either side
 * or onto a close target. The keys send evdev codes, so the session's XKB
 * layout types the characters; the labels follow that layout. A modifier
 * tapped holds for the next key, tapped again until a third tap; held while
 * other keys are touched, it comes up with the finger.
 */
class PcKeyboard(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onKey(evdevKeycode: Int, down: Boolean)
        fun onPaste()
        fun onAndroidKeyboard()
        /** The height the keyboard covers, 0 once hidden. */
        fun onHeight(height: Int)
    }

    private val compact = context.resources.configuration.smallestScreenWidthDp < 600
    private var layout = Layouts.get("us")
    private var shown = false
    /** Shrunk to [bubble]: the layer stays visible for it. */
    private var minimized = false

    val isShown: Boolean get() = shown

    /** Full-screen layer, transparent to touches outside the panel. */
    val view = FrameLayout(context).apply {
        visibility = View.GONE
        clipChildren = false
    }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(SURFACE) }
        // Rounded on top only: the outline reaches past the bottom edge.
        val radius = dp(if (compact) 18f else 24f)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height + radius.roundToInt(), radius)
        }
        clipToOutline = true
        isClickable = true // a touch between two keys stays off the desktop
        isFocusable = false
        if (!compact) setPadding(dp(24), dp(10), dp(24), dp(18)) // a phone's: in place()
    }
    private val keyRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val hint = label("", ON_SURFACE_VARIANT, 12f)
    private val preview = label("", ON_SURFACE, if (compact) 22f else 26f, medium = true).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = rounded(SURFACE_HIGHEST, dp(12f))
        alpha = 0f
    }
    private val hidePreview = Runnable { preview.animate().alpha(0f).setDuration(SHORT_MS).start() }

    private val keys = mutableListOf<Key>()
    /** Codes down in the desktop, so nothing is sent twice and all come up on hide. */
    private val down = mutableSetOf<Int>()
    private val modifiers = mutableMapOf<Int, Int>()
    /** Modifiers under a finger, and whether another key was touched meanwhile. */
    private val held = mutableMapOf<Int, Boolean>()
    private var capsLock = false

    private val bubbleSize = dp(if (compact) 48 else 56)
    private val bubble = ImageView(context).apply {
        setImageResource(R.drawable.ic_menu_keyboard)
        scaleType = ImageView.ScaleType.CENTER
        background = rounded(SELECTED, dp(if (compact) 14f else 16f))
        contentDescription = context.getString(R.string.kbd_show)
        visibility = View.GONE
    }
    /** Where a dragged [bubble] is let go to close it, as Android's bubbles. */
    private val closeTarget = ImageView(context).apply {
        setImageResource(R.drawable.ic_kbd_close)
        scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(SCRIM)
            setStroke(dp(2), ON_SURFACE)
        }
        visibility = View.GONE
    }

    init {
        panel.addView(strip(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 32 else 40)).apply {
            bottomMargin = dp(if (compact) 4 else 8)
        })
        panel.addView(keyRows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))
        view.addView(preview, FrameLayout.LayoutParams(0, 0))
        view.addView(closeTarget, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(BUBBLE_BOTTOM_DP - 8)
        })
        view.addView(bubble, FrameLayout.LayoutParams(bubbleSize, bubbleSize, Gravity.TOP or Gravity.START))
        bubble.setOnTouchListener(BubbleDrag())
        // Turned: the panel takes its new size, the button its place on the new edges.
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) view.post {
                if (shown) listener.onHeight(place())
                if (minimized) placeBubble(animate = false)
            }
        }
    }

    /** Paste, the modifiers' hint, Android's keyboard, shrink and hide, over the keys. */
    private fun strip() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(chip(context.getString(R.string.kbd_paste)) { listener.onPaste() })
        addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
        })
        hint.gravity = Gravity.END
        hint.maxLines = 1
        hint.ellipsize = TextUtils.TruncateAt.END
        addView(iconChip(R.drawable.ic_kbd_phone, R.string.kbd_android) { listener.onAndroidKeyboard() }, chipParams(0))
        addView(iconChip(R.drawable.ic_kbd_minimize, R.string.kbd_minimize) { minimize() }, chipParams(8))
        addView(iconChip(R.drawable.ic_kbd_hide, R.string.kbd_hide) { hide() }, chipParams(8))
    }

    private fun chipParams(start: Int) = LinearLayout.LayoutParams(dp(40), dp(if (compact) 30 else 36)).apply { marginStart = dp(start) }

    fun show(xkbLayout: String) {
        if (layout.id != xkbLayout || keys.isEmpty()) {
            layout = Layouts.get(xkbLayout)
            buildKeys()
        }
        if (view.parent == null) return
        if (minimized) {
            minimized = false
            bubble.animate().cancel()
            bubble.animate().alpha(0f).scaleX(BUBBLE_HIDDEN_SCALE).scaleY(BUBBLE_HIDDEN_SCALE).setStartDelay(0).setDuration(SHORT_MS)
                .withEndAction { if (!minimized) bubble.visibility = View.GONE }.start()
        }
        val height = place()
        if (!shown) {
            shown = true
            view.visibility = View.VISIBLE
            // Laid out in place: the window's hole for the desktop's SurfaceView is measured
            // at layout and would leave out a panel still below the edge. It slides in after.
            panel.animate().cancel()
            panel.translationY = 0f
            panel.doOnPreDraw {
                panel.translationY = height.toFloat()
                panel.animate().translationY(0f).setDuration(MEDIUM_MS).setInterpolator(DECELERATE).start()
            }
        }
        listener.onHeight(height)
    }

    /**
     * Sizes the panel for the screen now and returns its height: a phone held
     * upright gets the whole width and more height, or its keys would be too narrow.
     */
    private fun place(): Int {
        val parent = view.parent as View
        val portrait = parent.height > parent.width
        val height = when {
            compact && portrait -> parent.width * 0.73f
            compact -> min(parent.width, parent.height) * 0.56f
            else -> min(parent.width, parent.height) * 0.51f
        }.roundToInt()
        if (compact) {
            val side = dp(if (portrait) 8 else 44)
            panel.setPadding(side, dp(8), side, dp(8))
        }
        panel.layoutParams.height = height
        panel.requestLayout()
        return height
    }

    fun hide() {
        if (!shown) return
        shown = false
        releaseAll()
        panel.animate().translationY(panel.height.toFloat()).setDuration(HIDE_MS).setInterpolator(ACCELERATE)
            .withEndAction { if (!shown && !minimized) view.visibility = View.GONE }.start()
        listener.onHeight(0)
    }

    /** The keyboard down to a button that brings it back. */
    private fun minimize() {
        minimized = true
        hide()
        placeBubble(animate = false)
        bubble.visibility = View.VISIBLE
        bubble.alpha = 0f
        bubble.scaleX = BUBBLE_HIDDEN_SCALE
        bubble.scaleY = BUBBLE_HIDDEN_SCALE
        bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(SHORT_MS).setDuration(MEDIUM_MS)
            .setInterpolator(DECELERATE).start()
    }

    /** The button gone: the keyboard comes back from the session menu or a text field. */
    private fun closeBubble() {
        minimized = false
        bubble.animate().alpha(0f).scaleX(BUBBLE_HIDDEN_SCALE).scaleY(BUBBLE_HIDDEN_SCALE).setStartDelay(0).setDuration(SHORT_MS)
            .withEndAction { if (!minimized && !shown) view.visibility = View.GONE }.start()
    }

    // -- Button ----------------------------------------------------------------

    /** The button on its saved edge and height, between the top and the desktop's panel. */
    private fun placeBubble(animate: Boolean) {
        val settings = DesktopSettings.load(context)
        val margin = dp(BUBBLE_MARGIN_DP).toFloat()
        val x = if (settings.keyboardButtonLeft) margin else view.width - bubbleSize - margin
        val y = margin + bubbleSpan() * settings.keyboardButtonY
        if (animate) {
            bubble.animate().translationX(x).translationY(y).setStartDelay(0).setDuration(MEDIUM_MS).setInterpolator(DECELERATE).start()
        } else {
            bubble.translationX = x
            bubble.translationY = y
        }
    }

    /** How far the button's top can go down from its highest place. */
    private fun bubbleSpan() = (view.height - bubbleSize - dp(BUBBLE_BOTTOM_DP) - dp(BUBBLE_MARGIN_DP)).coerceAtLeast(1).toFloat()

    /** Tap: the keyboard back. Drag: anywhere, then to the nearer side, or onto the close target. */
    private inner class BubbleDrag : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        private var overTarget = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = bubble.translationX
                    startY = bubble.translationY
                    dragging = false
                    overTarget = false
                    bubble.animate().cancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        closeTarget.visibility = View.VISIBLE
                        closeTarget.alpha = 0f
                        closeTarget.scaleX = 1f
                        closeTarget.scaleY = 1f
                        closeTarget.animate().alpha(1f).setDuration(SHORT_MS).start()
                    }
                    if (dragging) {
                        bubble.translationX = (startX + dx).coerceIn(0f, (view.width - bubbleSize).toFloat())
                        bubble.translationY = (startY + dy).coerceIn(0f, (view.height - bubbleSize).toFloat())
                        val over = onTarget()
                        if (over != overTarget) {
                            overTarget = over
                            if (over) bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            val scale = if (over) 1.2f else 1f
                            closeTarget.animate().scaleX(scale).scaleY(scale).setDuration(SHORT_MS).start()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> when {
                    dragging -> {
                        closeTarget.animate().alpha(0f).setDuration(SHORT_MS)
                            .withEndAction { closeTarget.visibility = View.GONE }.start()
                        if (overTarget) {
                            closeBubble()
                        } else {
                            val left = bubble.translationX + bubbleSize / 2f < view.width / 2f
                            val y = ((bubble.translationY - dp(BUBBLE_MARGIN_DP)) / bubbleSpan()).coerceIn(0f, 1f)
                            DesktopSettings.update(context) { it.copy(keyboardButtonLeft = left, keyboardButtonY = y) }
                            placeBubble(animate = true)
                        }
                    }
                    event.actionMasked == MotionEvent.ACTION_UP -> show(layout.id)
                }
            }
            return true
        }

        private fun onTarget(): Boolean {
            val x = bubble.translationX + bubbleSize / 2f - (closeTarget.left + closeTarget.width / 2f)
            val y = bubble.translationY + bubbleSize / 2f - (closeTarget.top + closeTarget.height / 2f)
            return hypot(x, y) < dp(64f)
        }
    }

    /** Everything comes up in the desktop: on hide, and when the desktop loses the keys. */
    fun releaseAll() {
        modifiers.clear()
        held.clear()
        for (code in down.toList()) send(code, false)
        keys.forEach { it.pressed = false }
        refresh()
    }

    // -- Keys ------------------------------------------------------------------

    private enum class Kind { CHAR, SPECIAL, MODIFIER, CAPS, FN }

    private inner class Key(
        val code: Int,
        val weight: Float,
        val kind: Kind,
        /** Fixed label; character keys take theirs from the layout. */
        val text: String? = null,
        /** What the key sends while Fn is on (phone), with its label. */
        val fnCode: Int = 0,
        val fnText: String? = null,
    ) {
        lateinit var view: FrameLayout
        lateinit var background: GradientDrawable
        var label: TextView? = null
        var sub: TextView? = null
        var pressed = false
        /** The code sent by the touch now down, Fn's when it was on. */
        var sentCode = code
    }

    private fun char(code: Int) = Key(code, 1f, Kind.CHAR)
    private fun special(code: Int, weight: Float, text: String) = Key(code, weight, Kind.SPECIAL, text)
    private fun modifier(code: Int, weight: Float, text: String) = Key(code, weight, Kind.MODIFIER, text)
    private fun string(id: Int) = context.getString(id)

    private fun rows(): List<List<Key>> {
        val iso = layout.iso
        val altGr = if (layout.altGr) "AltGr" else "Alt"
        val numbers = listOf(KEY_GRAVE) + (2..11) + listOf(KEY_MINUS, KEY_EQUAL)
        val top = (16..25) + listOf(KEY_LEFTBRACE, KEY_RIGHTBRACE)
        val middle = (30..38) + listOf(KEY_SEMICOLON, KEY_APOSTROPHE)
        val bottom = (44..50) + listOf(KEY_COMMA, KEY_DOT, KEY_SLASH)
        return if (compact) {
            // F1 to F12 on the numbers and the page keys on the arrows while Fn is on.
            val fn = (numbers.drop(1)).mapIndexed { i, code ->
                Key(code, 1f, Kind.CHAR, fnCode = if (i < 10) KEY_F1 + i else KEY_F11 + i - 10, fnText = "F${i + 1}")
            }
            listOf(
                listOf(special(KEY_ESC, 1f, "Esc")) + fn + special(KEY_BACKSPACE, 1.5f, "⌫"),
                listOf(special(KEY_TAB, 1.5f, "Tab")) + top.map(::char) + char(KEY_BACKSLASH),
                listOf(modifier(KEY_LEFTSHIFT, 1.75f, string(R.string.kbd_shift))) + middle.map(::char) +
                    special(KEY_ENTER, 1.75f, string(R.string.kbd_enter)),
                (if (iso) listOf(char(KEY_102ND)) else emptyList()) + bottom.map(::char) +
                    Key(KEY_UP, 1f, Kind.SPECIAL, "↑", KEY_PAGEUP, "⇞") +
                    special(KEY_DELETE, 1.25f, string(R.string.kbd_delete)),
                listOf(
                    Key(0, 1f, Kind.FN, "Fn"),
                    modifier(KEY_LEFTCTRL, 1f, "Ctrl"),
                    modifier(KEY_LEFTMETA, 1.25f, "Super"), // the longer label: whole on a phone held upright
                    modifier(KEY_LEFTALT, 1f, "Alt"),
                    special(KEY_SPACE, 5.5f, string(R.string.kbd_space)),
                    modifier(KEY_RIGHTALT, 1f, altGr),
                    Key(KEY_LEFT, 1f, Kind.SPECIAL, "←", KEY_HOME, "⇤"),
                    Key(KEY_DOWN, 1f, Kind.SPECIAL, "↓", KEY_PAGEDOWN, "⇟"),
                    Key(KEY_RIGHT, 1f, Kind.SPECIAL, "→", KEY_END, "⇥"),
                ),
            )
        } else {
            listOf(
                listOf(special(KEY_ESC, 1f, "Esc")) + (0 until 12).map {
                    special(if (it < 10) KEY_F1 + it else KEY_F11 + it - 10, 1f, "F${it + 1}")
                } + special(KEY_INSERT, 1f, string(R.string.kbd_insert)) + special(KEY_DELETE, 1f, string(R.string.kbd_delete)),
                numbers.map(::char) + special(KEY_BACKSPACE, 2f, "⌫"),
                listOf(special(KEY_TAB, 1.5f, "Tab")) + top.map(::char) + Key(KEY_BACKSLASH, 1.5f, Kind.CHAR),
                listOf(Key(KEY_CAPSLOCK, 1.75f, Kind.CAPS, string(R.string.kbd_caps))) + middle.map(::char) +
                    special(KEY_ENTER, 2.25f, string(R.string.kbd_enter)),
                // A European keyboard has one more key, between the left Shift and Z.
                listOf(modifier(KEY_LEFTSHIFT, if (iso) 1.25f else 2.25f, string(R.string.kbd_shift))) +
                    (if (iso) listOf(char(KEY_102ND)) else emptyList()) + bottom.map(::char) +
                    special(KEY_UP, 1f, "↑") + modifier(KEY_RIGHTSHIFT, 1.75f, string(R.string.kbd_shift)),
                listOf(
                    modifier(KEY_LEFTCTRL, 1.25f, "Ctrl"),
                    modifier(KEY_LEFTMETA, 1.25f, "Super"),
                    modifier(KEY_LEFTALT, 1.25f, "Alt"),
                    special(KEY_SPACE, 5.75f, string(R.string.kbd_space)),
                    modifier(KEY_RIGHTALT, 1.25f, altGr),
                    special(KEY_LEFT, 1f, "←"),
                    special(KEY_DOWN, 1f, "↓"),
                    special(KEY_RIGHT, 1f, "→"),
                    special(KEY_COMPOSE, 1.25f, "Menu"),
                ),
            )
        }
    }

    private fun buildKeys() {
        releaseAll()
        keyRows.removeAllViews()
        keys.clear()
        for ((r, row) in rows().withIndex()) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            keyRows.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                if (r > 0) topMargin = dp(if (compact) 4 else 8)
            })
            for ((i, key) in row.withIndex()) {
                createKeyView(key, i == 0, i == row.size - 1)
                line.addView(key.view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, key.weight).apply {
                    if (i > 0) marginStart = dp(3)
                })
                keys += key
            }
        }
        refresh()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createKeyView(key: Key, first: Boolean, last: Boolean) {
        val outer = dp(if (compact) 12f else 14f)
        val inner = dp(if (compact) 5f else 6f)
        val left = if (first) outer else inner
        val right = if (last) outer else inner
        key.background = GradientDrawable().apply { cornerRadii = floatArrayOf(left, left, right, right, right, right, left, left) }
        key.view = FrameLayout(context).apply {
            background = key.background
            setOnTouchListener { _, event -> onTouch(key, event) }
        }
        val character = key.kind == Kind.CHAR
        val label = label(key.text ?: "", ON_SURFACE, if (character) (if (compact) 16f else 20f) else (if (compact) 12f else 14f),
            medium = !character).apply {
            includeFontPadding = false
            maxLines = 1
        }
        key.label = label
        key.view.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        if (character && !compact) {
            key.sub = label("", ON_SURFACE_VARIANT, 11f).also {
                key.view.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(4)
                    marginEnd = dp(8)
                })
            }
        }
        key.view.contentDescription = when (key.code) {
            KEY_BACKSPACE -> "Backspace"
            else -> null
        }
    }

    // -- State -----------------------------------------------------------------

    private fun state(code: Int) = modifiers[code] ?: OFF
    private val fn get() = state(FN) != OFF

    /** Labels, colors and the hint for the keys and modifiers down now. */
    private fun refresh() {
        val shift = KEY_LEFTSHIFT in down || KEY_RIGHTSHIFT in down
        val altGr = layout.altGr && KEY_RIGHTALT in down
        val plain = !shift && !altGr
        for (key in keys) {
            if (key.kind == Kind.CHAR) {
                key.label?.text = if (fn && key.fnText != null) key.fnText else layout.label(key.code, shift, capsLock, altGr) ?: ""
                key.sub?.text = if (plain) layout.sub(key.code) ?: "" else ""
            } else if (key.fnText != null) {
                key.label?.text = if (fn) key.fnText else key.text
            }
            val on = when (key.kind) {
                Kind.MODIFIER -> key.code in down
                Kind.FN -> fn
                Kind.CAPS -> capsLock
                else -> false
            }
            val locked = (key.kind == Kind.MODIFIER || key.kind == Kind.FN) && state(key.code.takeIf { it != 0 } ?: FN) == LOCKED
            key.background.setColor(when {
                key.pressed -> PRESSED
                on -> SELECTED
                key.kind == Kind.CHAR -> SURFACE_HIGH
                else -> SURFACE_HIGHEST
            })
            key.label?.setTextColor(if (locked || key.kind == Kind.CAPS && capsLock) PRIMARY else ON_SURFACE)
        }
        val active = modifiers.filterValues { it != OFF }.keys.mapNotNull { code -> keys.firstOrNull { (it.code.takeIf { c -> c != 0 } ?: FN) == code }?.text }
        hint.text = when {
            active.isEmpty() -> ""
            modifiers.values.any { it == LOCKED } -> context.getString(R.string.kbd_hint_locked, active.distinct().joinToString(" + "))
            else -> context.getString(R.string.kbd_hint_next, active.distinct().joinToString(" + "))
        }
    }

    private fun send(code: Int, press: Boolean) {
        if (if (press) down.add(code) else down.remove(code)) listener.onKey(code, press)
    }

    /** A modifier is down while kept or under a finger. */
    private fun sync(code: Int) {
        if (code != FN) send(code, state(code) != OFF || code in held)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onTouch(key: Key, event: MotionEvent): Boolean {
        val press = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
            else -> return true
        }
        if (press) key.view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        key.pressed = press
        when (key.kind) {
            Kind.MODIFIER, Kind.FN -> onModifier(key.code.takeIf { it != 0 } ?: FN, press)
            else -> if (press) {
                if (key.kind == Kind.CAPS) capsLock = !capsLock
                held.keys.forEach { held[it] = true } // the modifiers under a finger were used
                key.sentCode = if (fn && key.fnCode != 0) key.fnCode else key.code
                send(key.sentCode, true)
                showPreview(key)
            } else {
                val was = key.sentCode in down
                send(key.sentCode, false)
                view.removeCallbacks(hidePreview)
                view.postDelayed(hidePreview, PREVIEW_MS)
                // The modifiers kept for this key come up.
                if (was) for ((code, state) in modifiers.toList()) if (state == NEXT) {
                    modifiers[code] = OFF
                    sync(code)
                }
            }
        }
        refresh()
        return true
    }

    /** Tap: for the next key; second tap: locked; third: off. Held: down with the finger. */
    private fun onModifier(code: Int, press: Boolean) {
        if (press) {
            when (state(code)) {
                NEXT -> modifiers[code] = LOCKED
                LOCKED -> modifiers[code] = OFF
                else -> held[code] = false
            }
        } else if (code in held) {
            val used = held.remove(code) == true
            modifiers[code] = if (used) OFF else NEXT
        }
        sync(code)
    }

    /** The character of the touched key, above the finger. */
    private fun showPreview(key: Key) {
        val text = key.label?.text
        if (key.kind != Kind.CHAR || text.isNullOrEmpty() || text.length > 1 && text[0] == 'F') return
        val keyAt = IntArray(2).also { key.view.getLocationInWindow(it) }
        val ownAt = IntArray(2).also { view.getLocationInWindow(it) }
        val width = (key.view.width * 1.3f).roundToInt()
        val height = (key.view.height * 1.4f).roundToInt()
        preview.layoutParams = (preview.layoutParams as FrameLayout.LayoutParams).apply {
            this.width = width
            this.height = height
            leftMargin = keyAt[0] - ownAt[0] + (key.view.width - width) / 2
            topMargin = keyAt[1] - ownAt[1] - height - dp(6)
        }
        preview.text = text
        view.removeCallbacks(hidePreview)
        preview.animate().cancel()
        preview.alpha = 1f
    }

    // -- Building blocks -------------------------------------------------------

    private fun chip(text: String, onClick: () -> Unit) = label(text, ON_SURFACE, 13f).apply {
        gravity = Gravity.CENTER
        minHeight = dp(if (compact) 30 else 36)
        setPadding(dp(14), 0, dp(14), 0)
        background = rounded(SURFACE_HIGH, dp(18f))
        setOnClickListener { onClick() }
    }

    private fun iconChip(icon: Int, description: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = rounded(SURFACE_HIGH, dp(18f))
        contentDescription = context.getString(description)
        setOnClickListener { onClick() }
    }

    private fun label(text: String, color: Int, sp: Float, medium: Boolean = false) = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)
    private fun dp(value: Int) = dp(value.toFloat()).roundToInt()

    /**
     * Key labels of the XKB layouts offered in the settings, by evdev code:
     * without and with Shift, and with AltGr where it types something.
     */
    class Layouts private constructor(val id: String, val iso: Boolean, val altGr: Boolean) {
        private val keys = mutableMapOf<Int, Array<String?>>()

        private fun labels(vararg labels: Array<String?>?) = apply {
            POSITIONS.forEachIndexed { i, code -> labels[i]?.let { keys[code] = it } }
        }

        /** A letter in another place than on a US keyboard, or with an AltGr character. */
        private fun letter(code: Int, letter: String, altGr: String?) = apply {
            keys[code] = arrayOf(letter, letter.uppercase(Locale.ROOT), altGr)
        }

        private fun entry(code: Int): Array<String?>? = keys[code] ?: LETTERS[code]?.let {
            arrayOf(it.toString(), it.uppercaseChar().toString(), null)
        }

        /** The label of a character key, or null when it types nothing in this layout. */
        fun label(code: Int, shift: Boolean, capsLock: Boolean, altGrDown: Boolean): String? {
            val entry = entry(code) ?: return null
            if (altGrDown && entry.size > 2 && entry[2] != null) return entry[2]
            val normal = entry[0] ?: return null
            val shifted = entry.getOrNull(1) ?: normal
            // Caps Lock acts on the letters only.
            val isLetter = normal.length == 1 && normal[0].isLetter() && shifted == normal.uppercase(Locale.ROOT)
            return if (if (isLetter) shift != capsLock else shift) shifted else normal
        }

        /** The small label in the corner: Shift's character, or AltGr's on a letter. */
        fun sub(code: Int): String? {
            val entry = entry(code) ?: return null
            val normal = entry[0] ?: return null
            val shifted = entry.getOrNull(1)
            return if (normal[0].isLetter() && shifted == normal.uppercase(Locale.ROOT)) entry.getOrNull(2) else shifted
        }

        companion object {
            // Every key with a character besides the letters, in the order of the tables below.
            private val POSITIONS = intArrayOf(
                KEY_GRAVE, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, KEY_MINUS, KEY_EQUAL,
                KEY_LEFTBRACE, KEY_RIGHTBRACE, KEY_BACKSLASH, KEY_SEMICOLON, KEY_APOSTROPHE, KEY_102ND,
                KEY_COMMA, KEY_DOT, KEY_SLASH,
            )
            private val LETTERS = buildMap {
                for ((start, row) in listOf(16 to "qwertyuiop", 30 to "asdfghjkl", 44 to "zxcvbnm")) {
                    row.forEachIndexed { i, c -> put(start + i, c) }
                }
            }

            private fun k(vararg labels: String?): Array<String?> = arrayOf(*labels)

            fun get(id: String): Layouts = when (id) {
                "gb" -> Layouts(id, true, true).labels(
                    k("`", "¬", "¦"), k("1", "!"), k("2", "\""), k("3", "£"), k("4", "$", "€"), k("5", "%"),
                    k("6", "^"), k("7", "&"), k("8", "*"), k("9", "("), k("0", ")"), k("-", "_"), k("=", "+"),
                    k("[", "{"), k("]", "}"), k("#", "~"), k(";", ":"), k("'", "@"), k("\\", "|"),
                    k(",", "<"), k(".", ">"), k("/", "?"),
                )
                "it" -> Layouts(id, true, true).labels(
                    k("\\", "|"), k("1", "!"), k("2", "\""), k("3", "£"), k("4", "$"), k("5", "%"), k("6", "&"),
                    k("7", "/"), k("8", "("), k("9", ")"), k("0", "="), k("'", "?"), k("ì", "^"),
                    k("è", "é", "["), k("+", "*", "]"), k("ù", "§"), k("ò", "ç", "@"), k("à", "°", "#"),
                    k("<", ">"), k(",", ";"), k(".", ":"), k("-", "_"),
                ).letter(18, "e", "€")
                "de" -> Layouts(id, true, true).labels(
                    k("^", "°"), k("1", "!"), k("2", "\"", "²"), k("3", "§", "³"), k("4", "$"), k("5", "%"),
                    k("6", "&"), k("7", "/", "{"), k("8", "(", "["), k("9", ")", "]"), k("0", "=", "}"),
                    k("ß", "?", "\\"), k("´", "`"), k("ü", "Ü"), k("+", "*", "~"), k("#", "'"),
                    k("ö", "Ö"), k("ä", "Ä"), k("<", ">", "|"), k(",", ";"), k(".", ":"), k("-", "_"),
                ).letter(21, "z", null).letter(44, "y", null).letter(16, "q", "@").letter(18, "e", "€").letter(50, "m", "µ")
                // AZERTY: A and Q, Z and W change places, M goes next to L and its place takes the comma.
                "fr" -> Layouts(id, true, true).labels(
                    k("²", "²"), k("&", "1"), k("é", "2", "~"), k("\"", "3", "#"), k("'", "4", "{"),
                    k("(", "5", "["), k("-", "6", "|"), k("è", "7", "`"), k("_", "8", "\\"), k("ç", "9", "^"),
                    k("à", "0", "@"), k(")", "°", "]"), k("=", "+", "}"), k("^", "¨"), k("$", "£", "¤"),
                    k("*", "µ"), k("m", "M"), k("ù", "%"), k("<", ">"), k(";", "."), k(":", "/"), k("!", "§"),
                ).letter(16, "a", null).letter(30, "q", null).letter(17, "z", null).letter(44, "w", null)
                    .letter(18, "e", "€").apply { keys[50] = k(",", "?") }
                "es" -> Layouts(id, true, true).labels(
                    k("º", "ª", "\\"), k("1", "!", "|"), k("2", "\"", "@"), k("3", "·", "#"), k("4", "$", "~"),
                    k("5", "%", "€"), k("6", "&", "¬"), k("7", "/"), k("8", "("), k("9", ")"), k("0", "="),
                    k("'", "?"), k("¡", "¿"), k("`", "^", "["), k("+", "*", "]"), k("ç", "Ç", "}"),
                    k("ñ", "Ñ"), k("´", "¨", "{"), k("<", ">"), k(",", ";"), k(".", ":"), k("-", "_"),
                ).letter(18, "e", "€")
                "pt" -> Layouts(id, true, true).labels(
                    k("\\", "|"), k("1", "!"), k("2", "\"", "@"), k("3", "#", "£"), k("4", "$", "§"), k("5", "%"),
                    k("6", "&"), k("7", "/", "{"), k("8", "(", "["), k("9", ")", "]"), k("0", "=", "}"),
                    k("'", "?"), k("«", "»"), k("+", "*", "¨"), k("´", "`"), k("~", "^"),
                    k("ç", "Ç"), k("º", "ª"), k("<", ">"), k(",", ";"), k(".", ":"), k("-", "_"),
                ).letter(18, "e", "€")
                else -> Layouts("us", false, false).labels(
                    k("`", "~"), k("1", "!"), k("2", "@"), k("3", "#"), k("4", "$"), k("5", "%"), k("6", "^"),
                    k("7", "&"), k("8", "*"), k("9", "("), k("0", ")"), k("-", "_"), k("=", "+"),
                    k("[", "{"), k("]", "}"), k("\\", "|"), k(";", ":"), k("'", "\""), null,
                    k(",", "<"), k(".", ">"), k("/", "?"),
                )
            }
        }
    }

    private companion object {
        const val OFF = 0
        const val NEXT = 1
        const val LOCKED = 2
        /** Fn's place in the modifier states: it sends nothing. */
        const val FN = -1
        const val PRESSED = 0xFF44474E.toInt()
        const val PREVIEW_MS = 75L
        const val HIDE_MS = 300L
        const val SCRIM = 0x99000000.toInt()
        const val BUBBLE_HIDDEN_SCALE = 0.6f
        const val BUBBLE_MARGIN_DP = 16
        /** Room under the button at its lowest: the desktop's panel. */
        const val BUBBLE_BOTTOM_DP = 72

        // evdev codes (linux/input-event-codes.h).
        const val KEY_ESC = 1
        const val KEY_MINUS = 12
        const val KEY_EQUAL = 13
        const val KEY_BACKSPACE = 14
        const val KEY_TAB = 15
        const val KEY_LEFTBRACE = 26
        const val KEY_RIGHTBRACE = 27
        const val KEY_ENTER = 28
        const val KEY_LEFTCTRL = 29
        const val KEY_SEMICOLON = 39
        const val KEY_APOSTROPHE = 40
        const val KEY_GRAVE = 41
        const val KEY_LEFTSHIFT = 42
        const val KEY_BACKSLASH = 43
        const val KEY_COMMA = 51
        const val KEY_DOT = 52
        const val KEY_SLASH = 53
        const val KEY_RIGHTSHIFT = 54
        const val KEY_LEFTALT = 56
        const val KEY_SPACE = 57
        const val KEY_CAPSLOCK = 58
        const val KEY_F1 = 59
        const val KEY_102ND = 86
        const val KEY_F11 = 87
        const val KEY_RIGHTALT = 100
        const val KEY_HOME = 102
        const val KEY_UP = 103
        const val KEY_PAGEUP = 104
        const val KEY_LEFT = 105
        const val KEY_RIGHT = 106
        const val KEY_END = 107
        const val KEY_DOWN = 108
        const val KEY_PAGEDOWN = 109
        const val KEY_INSERT = 110
        const val KEY_DELETE = 111
        const val KEY_LEFTMETA = 125
        const val KEY_COMPOSE = 127
    }
}
