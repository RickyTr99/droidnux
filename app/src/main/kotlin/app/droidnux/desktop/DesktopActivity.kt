package app.droidnux.desktop

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import android.widget.TextView
import app.droidnux.container.Container
import app.droidnux.container.DesktopEnvironment
import app.droidnux.container.Distro
import app.droidnux.display.AudioFocus
import app.droidnux.display.Presenter
import app.droidnux.display.X11Server
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Linux desktop, full screen. Runs in the :desktop process together with
 * the X server and DesktopSessionService.
 */
class DesktopActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private var overlay: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Desktop pixels per surface pixel (the desktop runs below native resolution). */
    private var scale = 1f
    /** Desktop size as a fraction of the screen: the session menu's (saved), or [EXTRA_SCALE]. */
    private var desktopScale = DEFAULT_SCALE
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val touch = TouchGestures(handler)
    private var cursorVisible = true
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    /** Last text exchanged with X, so neither side gets its own copy back. */
    private var clipboardText: String? = null
    private var clipboardStamp = 0L
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { clipboardToX() }
    private val touchSlop by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private val touchpad = CapturedTouchpad()
    /** The touchscreen in trackpad mode: the finger moves the pointer instead of clicking where it lands. */
    private val screenTouchpad = CapturedTouchpad()
    private var trackpadMode = false
    /** Interface size the user chose, 1 = the default; independent of the resolution ([desktopScale]). */
    private var uiScale = 1f
    private lateinit var menu: SessionMenu

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        AudioFocus.init(this)
        X11Server.onClipboard = { text ->
            handler.post {
                clipboardText = text
                clipboard.setPrimaryClip(ClipData.newPlainText("DroidNux", text))
            }
        }
        val settings = DesktopSettings.load(this)
        desktopScale = settings.scale
        desktopScale = scaleOf(intent)
        trackpadMode = settings.trackpad
        uiScale = settings.uiScale
        intent.getStringExtra(EXTRA_LAUNCH)?.let { launchApp(it) }
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.setDecorFitsSystemWindows(false)
        // The surface is a text editor for the soft keyboard: only the menu's button may show it.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        surfaceView = DesktopSurfaceView().apply {
            holder.addCallback(this@DesktopActivity)
            isFocusable = true
            isFocusableInTouchMode = true
            // It always has the focus: no keyboard focus frame around the whole desktop.
            defaultFocusHighlightEnabled = false
            // The X cursor is drawn by the presenter; hide Android's.
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
            setOnGenericMotionListener { _, event -> onPointerEvent(event) }
            setOnCapturedPointerListener { _, event -> onCapturedPointer(event) }
            setOnTouchListener { _, event -> onTouch(event) }
            // The panel's corners (menu, tray) sit in the edge-swipe Back zones: keep the bottom of
            // both edges for the desktop. Android honours at most 200 dp per edge.
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val height = (GESTURE_EXCLUSION_DP * resources.displayMetrics.density).toInt()
                view.systemGestureExclusionRects = listOf(Rect(0, view.height - height, view.width, view.height))
            }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView)
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            overlay = TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0x88000000.toInt())
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(12, 6, 12, 6)
            }
            root.addView(overlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
            ))
        }
        menu = SessionMenu(this, menuListener)
        root.addView(menu.view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        setContentView(root)
        // The soft keyboard covers the bottom of the desktop: slide it up to keep the pointer in sight.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            if (ime != keyboardHeight) {
                keyboardHeight = ime
                panForKeyboard()
            }
            insets
        }
        // Back opens the menu instead of leaving: it has "Back to Android".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { toggleMenu() }
        }
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requested = scaleOf(intent)
        if (requested != desktopScale) {
            desktopScale = requested
            if (surfaceWidth > 0) resizeDesktop()
        }
        intent.getStringExtra(EXTRA_LAUNCH)?.let { launchApp(it) }
    }

    /** Starts the Linux app [id] in the session, as soon as the session listens (it may be starting). */
    private fun launchApp(id: String) {
        val rootfs = Container(this).installer.rootfsDir(Distro.DEBIAN_TRIXIE)
        Thread {
            repeat(LAUNCH_ATTEMPTS) {
                if (DesktopEnvironment.requestLaunch(rootfs, id)) return@Thread
                Thread.sleep(LAUNCH_RETRY_MS)
            }
            Log.w(TAG, "the session never listened for $id")
        }.start()
    }

    private var keyboardHeight = 0

    /** Moves the desktop up just enough for the pointer to stay above the soft keyboard. */
    private fun panForKeyboard() {
        val pointer = Presenter.cursorY / scale // desktop pixels to surface pixels
        val margin = KEYBOARD_MARGIN_DP * resources.displayMetrics.density
        val visibleBottom = surfaceView.height - keyboardHeight
        val shift = if (keyboardHeight == 0) 0f else (pointer + margin - visibleBottom).coerceIn(0f, keyboardHeight.toFloat())
        surfaceView.animate().translationY(-shift).setDuration(KEYBOARD_PAN_MS).start()
    }

    /** Settings changed in the app while the desktop was in the background apply on return. */
    private fun reloadSettings() {
        val settings = DesktopSettings.load(this)
        trackpadMode = settings.trackpad
        if (settings.scale != desktopScale) {
            desktopScale = settings.scale.coerceIn(MIN_SCALE, MAX_SCALE)
            if (surfaceWidth > 0) resizeDesktop()
            uiScale = settings.uiScale
            applyUiScale()
        } else if (settings.uiScale != uiScale) {
            uiScale = settings.uiScale
            applyUiScale()
        }
    }

    /** The scale [intent] asks for; intents without one (the notification) keep the current size. */
    private fun scaleOf(intent: Intent?) =
        (intent?.getFloatExtra(EXTRA_SCALE, desktopScale) ?: desktopScale).coerceIn(MIN_SCALE, MAX_SCALE)

    override fun onResume() {
        super.onResume()
        reloadSettings()
        AudioFocus.reclaim()
        surfaceView.requestFocus()
        clipboard.addPrimaryClipChangedListener(clipboardListener)
        overlay?.let { handler.post(overlayUpdater) }
    }

    override fun onPause() {
        handler.removeCallbacks(overlayUpdater)
        clipboard.removePrimaryClipChangedListener(clipboardListener)
        super.onPause()
    }

    @Deprecated("Android 12 and older; newer versions use the OnBackInvokedCallback")
    override fun onBackPressed() = toggleMenu()

    override fun onDestroy() {
        if (focused === this) focused = null
        if (current === this) {
            current = null
            X11Server.onClipboard = null
        }
        super.onDestroy()
    }

    /**
     * Captures the mouse while the desktop has focus: Android then stops
     * tracking the pointer, so the screen edges no longer pull in its bars or
     * taskbar. Android releases it when the window loses focus.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused = if (hasFocus) this else focused.takeIf { it !== this }
        if (hasFocus) {
            capturePointer()
            clipboardToX()
        } else {
            releaseKeys()
        }
    }

    /**
     * Offers Android's clipboard to X. Android lets apps read it only while
     * focused, so this runs on focus and on changes made meanwhile.
     */
    private fun clipboardToX() {
        if (!X11Server.running) return
        // The description is cheap and shows no "pasted" notice: read the text only when it changed.
        val description = clipboard.primaryClipDescription ?: return
        if (description.timestamp == clipboardStamp) return
        clipboardStamp = description.timestamp
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty() || text == clipboardText) return
        clipboardText = text
        X11Server.setClipboard(text)
    }

    private fun capturePointer() {
        if (!surfaceView.hasPointerCapture() && !menu.isOpen) {
            surfaceView.requestFocus()
            surfaceView.requestPointerCapture()
        }
    }

    // -- Surface -----------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!X11Server.available) {
            overlay?.text = "This build has no X server"
            return
        }
        surfaceWidth = width
        surfaceHeight = height
        Presenter.attach(holder.surface, window.decorView.display?.supportedModes?.maxOf { it.refreshRate } ?: 0f)
        resizeDesktop()
    }

    /** Starts the X server, or resizes it, at [desktopScale] of the surface. */
    private fun resizeDesktop() {
        // Even sizes keep scaling artefacts away from odd pixel rows.
        val desktopWidth = (surfaceWidth * desktopScale).toInt() and 1.inv()
        val desktopHeight = (surfaceHeight * desktopScale).toInt() and 1.inv()
        scale = desktopWidth.toFloat() / surfaceWidth

        if (!X11Server.running) {
            val rootfs = Container(this).installer.rootfsDir(Distro.DEBIAN_TRIXIE)
            val started = X11Server.start(rootfs, desktopWidth, desktopHeight, DPI)
            Log.i(TAG, "X server ${if (started) "started" else "failed to start"} at ${desktopWidth}x$desktopHeight")
            DesktopSessionService.start(this, DesktopSettings.load(this).xkbLayout(), dpi())
            clipboardToX()
        } else {
            X11Server.resize(desktopWidth, desktopHeight)
        }
        X11Server.redraw()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Presenter.detach()
    }

    // -- Keyboard ----------------------------------------------------------

    /**
     * Takes physical keys before the IME sees them: some IMEs (Samsung Keyboard)
     * consume hardware key events and re-inject copies without a scan code.
     */
    private inner class DesktopSurfaceView : SurfaceView(this@DesktopActivity) {
        override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean =
            sendPhysicalKey(event) || super.dispatchKeyEventPreIme(event)

        override fun onCheckIsTextEditor() = true

        /** The soft keyboard: committed text goes to X as characters, its keys as key presses. */
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            // Visible-password text: keyboards commit each character instead of composing words.
            outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            return object : BaseInputConnection(this, false) {
                override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                    if (text.toString() == "\n") tapKey(KEY_ENTER) else X11Server.text(text)
                    return true
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength) { tapKey(KEY_BACKSPACE) }
                    repeat(afterLength) { tapKey(KEY_DELETE) }
                    return true
                }
            }
        }
    }

    private fun tapKey(evdevKeycode: Int) {
        X11Server.key(evdevKeycode, true)
        X11Server.key(evdevKeycode, false)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        sendPhysicalKey(event) || super.dispatchKeyEvent(event)

    /**
     * Physical keys go to X by scan code: the X keymap (setxkbmap) applies the
     * layout, as on a PC. Volume and power keys stay with Android.
     */
    private fun sendPhysicalKey(event: KeyEvent): Boolean {
        if (isMenuShortcut(event)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggleMenu()
            return true
        }
        if (menu.isOpen) {
            // The menu takes the keys (focus moves with arrows and Tab); Escape closes it.
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP) menu.close()
                return true
            }
            return false
        }
        // The soft keyboard's keys have no scan code: map the ones it sends as key events.
        val scanCode = event.scanCode.takeIf { it > 0 } ?: SOFT_KEYS[event.keyCode] ?: 0
        if (scanCode <= 0 || !X11Server.running || isSystemKey(event.keyCode)) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 && keysDown.add(scanCode)) X11Server.key(scanCode, true)
            KeyEvent.ACTION_UP -> if (keysDown.remove(scanCode)) X11Server.key(scanCode, false)
        }
        return true
    }

    /** Keys pressed in X: their release may never reach X (menu opened, focus lost). */
    private val keysDown = mutableSetOf<Int>()

    /** Releases in X the keys still held, e.g. Ctrl and Alt of the menu shortcut. */
    private fun releaseKeys() {
        if (X11Server.running) keysDown.forEach { X11Server.key(it, false) }
        keysDown.clear()
    }

    /** Ctrl+Alt+Space: free in XFCE and in common Linux apps. */
    private fun isMenuShortcut(event: KeyEvent) =
        event.keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed && event.isAltPressed

    private fun isSystemKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ||
        keyCode == KeyEvent.KEYCODE_POWER

    // -- Mouse and touchpad ------------------------------------------------

    private fun onPointerEvent(event: MotionEvent): Boolean {
        if (!X11Server.running) return false
        showCursor(true)
        // Capture lost without a focus change.
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) capturePointer()
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER ->
                X11Server.pointer(event.x * scale, event.y * scale)
            MotionEvent.ACTION_SCROLL -> X11Server.scroll(
                event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                -event.getAxisValue(MotionEvent.AXIS_VSCROLL), // Android: positive is up
            )
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val button = xButton(event.actionButton) ?: return true
                X11Server.pointer(event.x * scale, event.y * scale)
                X11Server.button(button, event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS)
            }
            else -> return false
        }
        return true // also stops the right button from acting as Back
    }

    /** Captured mouse: relative motion, accelerated by X. */
    private fun onCapturedPointer(event: MotionEvent): Boolean {
        if (!X11Server.running) return false
        showCursor(true)
        if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
            val range = event.device?.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD)?.range ?: 0f
            // The whole touchpad width crosses half the desktop before X's acceleration.
            val unit = if (range > 0f && surfaceWidth > 0) range / (surfaceWidth * scale * 0.5f) else 1f
            return touchpad.onEvent(event, unit)
        }
        when (event.actionMasked) {
            // Button events repeat the motion of the event they come with.
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {
                var dx = event.x
                var dy = event.y
                for (i in 0 until event.historySize) {
                    dx += event.getHistoricalX(i)
                    dy += event.getHistoricalY(i)
                }
                if (dx != 0f || dy != 0f) X11Server.pointerRelative(dx, dy)
            }
            MotionEvent.ACTION_SCROLL -> X11Server.scroll(
                event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                -event.getAxisValue(MotionEvent.AXIS_VSCROLL),
            )
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val button = xButton(event.actionButton) ?: return true
                X11Server.button(button, event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS)
            }
            else -> return false
        }
        return true
    }

    private fun showCursor(visible: Boolean) {
        if (visible == cursorVisible) return
        cursorVisible = visible
        Presenter.setCursorVisible(visible)
    }

    private fun xButton(androidButton: Int) = when (androidButton) {
        MotionEvent.BUTTON_PRIMARY -> 1
        MotionEvent.BUTTON_TERTIARY -> 2
        MotionEvent.BUTTON_SECONDARY -> 3
        MotionEvent.BUTTON_BACK -> 8
        MotionEvent.BUTTON_FORWARD -> 9
        else -> null
    }

    private fun onTouch(event: MotionEvent): Boolean {
        if (!X11Server.running) return false
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            // Mouse drags: buttons come through onPointerEvent, positions here.
            if (event.actionMasked == MotionEvent.ACTION_MOVE) X11Server.pointer(event.x * scale, event.y * scale)
            return true
        }
        if (trackpadMode) {
            showCursor(true)
            // A finger crossing the screen moves the pointer across the desktop, before X's acceleration.
            screenTouchpad.onEvent(event, 1f / scale)
        } else {
            showCursor(false) // a finger needs no pointer; the next mouse motion brings it back
            touch.onEvent(event, scale, touchSlop)
        }
        return true
    }

    // -- Session menu ------------------------------------------------------

    private fun toggleMenu() {
        if (menu.isOpen) {
            menu.close()
        } else {
            // The menu takes the keys from now on, their releases included.
            releaseKeys()
            // The pointer must reach the menu: Android draws it again until the menu closes.
            surfaceView.releasePointerCapture()
            menu.open(trackpadMode, desktopScale, uiScale)
        }
    }

    private val menuListener = object : SessionMenu.Listener {
        override fun onToggleKeyboard() {
            val imm = getSystemService(InputMethodManager::class.java)
            val shown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            if (shown) {
                imm.hideSoftInputFromWindow(surfaceView.windowToken, 0)
            } else {
                surfaceView.requestFocus()
                imm.showSoftInput(surfaceView, 0)
            }
        }

        override fun onTrackpadChanged(trackpad: Boolean) {
            trackpadMode = trackpad
            DesktopSettings.update(this@DesktopActivity) { it.copy(trackpad = trackpad) }
        }

        override fun resolutionFor(scale: Float) =
            "${(surfaceWidth * scale).toInt() and 1.inv()} × ${(surfaceHeight * scale).toInt() and 1.inv()}"

        override fun onScaleChanged(scale: Float) {
            desktopScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
            DesktopSettings.update(this@DesktopActivity) { it.copy(scale = desktopScale) }
            if (surfaceWidth > 0) resizeDesktop()
            applyUiScale() // same physical size at the new resolution
        }

        override fun onUiScaleChanged(scale: Float) {
            uiScale = scale
            DesktopSettings.update(this@DesktopActivity) { it.copy(uiScale = scale) }
            applyUiScale()
        }

        override fun onBackToAndroid() {
            moveTaskToBack(true)
        }

        override fun onExit() = DesktopSessionService.stop(this@DesktopActivity)

        override fun onClosed() {
            surfaceView.requestFocus()
            capturePointer()
        }
    }

    /**
     * DPI for the session: 96 at the default resolution, then scaled with it,
     * so a sharper desktop keeps its elements the same size on screen.
     */
    private fun dpi() = (BASE_DPI * uiScale * desktopScale / DEFAULT_SCALE).roundToInt().coerceIn(MIN_DPI, MAX_DPI)

    private fun applyUiScale() {
        if (!X11Server.running) return
        val command = DesktopEnvironment.uiScaleCommand(dpi())
        Thread {
            runCatching {
                val process = Container(this).spawnShell(Distro.DEBIAN_TRIXIE, command)
                process.inputStream.readBytes()
                process.waitFor()
            }.onFailure { Log.w(TAG, "cannot apply the interface size", it) }
        }.start()
    }

    // -- Debug overlay -----------------------------------------------------

    private var lastFrames = 0L
    private var lastFrameTime = 0L
    private val overlayUpdater = object : Runnable {
        override fun run() {
            val frames = Presenter.frameCount
            val frameTime = Presenter.frameTimeTotalNanos
            val maxMs = Presenter.takeFrameTimeMaxNanos() / 1e6
            val count = frames - lastFrames
            // Frame time: copying the damage and submitting it, not the whole pipeline.
            val time = if (count > 0) {
                String.format(Locale.ROOT, " · frame %.1f ms (max %.1f)", (frameTime - lastFrameTime) / 1e6 / count, maxMs)
            } else {
                ""
            }
            overlay?.text = "$count fps$time · desktop ${(scale * 100).toInt()}%"
            lastFrames = frames
            lastFrameTime = frameTime
            handler.postDelayed(this, 1000)
        }
    }

    companion object {
        private const val TAG = "DroidNux.Desktop"

        /** The desktop on screen, if any (main thread only). */
        private var current: DesktopActivity? = null

        /** The desktop while it has the window focus (main thread only). */
        private var focused: DesktopActivity? = null

        /**
         * A key from [DesktopKeysService], before Android's own shortcuts see
         * it: true when the focused desktop took it. Main thread.
         */
        fun onSystemKey(event: KeyEvent): Boolean = focused?.sendPhysicalKey(event) ?: false

        /** Closes the desktop activity, when the session ends. Main thread. */
        fun finishCurrent() {
            current?.finishAndRemoveTask()
        }
        private const val DPI = 96

        /** Float extra: desktop size as a fraction of the screen, [MIN_SCALE] to [MAX_SCALE]. */
        const val EXTRA_SCALE = "app.droidnux.desktop.SCALE"
        const val DEFAULT_SCALE = 0.6f
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 1f

        private const val GESTURE_EXCLUSION_DP = 200
        /** String extra: desktop id of a Linux app to start in the session. */
        const val EXTRA_LAUNCH = "app.droidnux.desktop.LAUNCH"
        private const val KEYBOARD_MARGIN_DP = 48
        private const val KEYBOARD_PAN_MS = 250L
        private const val LAUNCH_ATTEMPTS = 120
        private const val LAUNCH_RETRY_MS = 500L
        private const val BASE_DPI = 96
        private const val MIN_DPI = 48
        private const val MAX_DPI = 384

        private const val KEY_BACKSPACE = 14
        private const val KEY_ENTER = 28
        private const val KEY_DELETE = 111

        /** Android keycodes the soft keyboard sends without a scan code, as evdev keycodes. */
        private val SOFT_KEYS = mapOf(
            KeyEvent.KEYCODE_DEL to KEY_BACKSPACE,
            KeyEvent.KEYCODE_FORWARD_DEL to KEY_DELETE,
            KeyEvent.KEYCODE_ENTER to KEY_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER to 96,
            KeyEvent.KEYCODE_TAB to 15,
            KeyEvent.KEYCODE_ESCAPE to 1,
            KeyEvent.KEYCODE_SPACE to 57,
            KeyEvent.KEYCODE_DPAD_UP to 103,
            KeyEvent.KEYCODE_DPAD_DOWN to 108,
            KeyEvent.KEYCODE_DPAD_LEFT to 105,
            KeyEvent.KEYCODE_DPAD_RIGHT to 106,
            KeyEvent.KEYCODE_MOVE_HOME to 102,
            KeyEvent.KEYCODE_MOVE_END to 107,
            KeyEvent.KEYCODE_PAGE_UP to 104,
            KeyEvent.KEYCODE_PAGE_DOWN to 109,
        )
    }
}
