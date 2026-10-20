package app.droidnux.desktop

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.FrameLayout
import android.widget.TextView
import app.droidnux.container.Container
import app.droidnux.container.Distro
import app.droidnux.display.Presenter
import app.droidnux.display.X11Server
import java.util.Locale

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
    /** Requested desktop size as a fraction of the screen, from [EXTRA_SCALE]. */
    private var desktopScale = DEFAULT_SCALE
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val touch = TouchGestures(handler)
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    /** Last text exchanged with X, so neither side gets its own copy back. */
    private var clipboardText: String? = null
    private var clipboardStamp = 0L
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { clipboardToX() }
    private val touchSlop by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private val touchpad = CapturedTouchpad()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        X11Server.onClipboard = { text ->
            handler.post {
                clipboardText = text
                clipboard.setPrimaryClip(ClipData.newPlainText("DroidNux", text))
            }
        }
        desktopScale = scaleOf(intent)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.setDecorFitsSystemWindows(false)

        surfaceView = DesktopSurfaceView().apply {
            holder.addCallback(this@DesktopActivity)
            isFocusable = true
            isFocusableInTouchMode = true
            // The X cursor is drawn by the presenter; hide Android's.
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
            setOnGenericMotionListener { _, event -> onPointerEvent(event) }
            setOnCapturedPointerListener { _, event -> onCapturedPointer(event) }
            setOnTouchListener { _, event -> onTouch(event) }
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
        setContentView(root)
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
    }

    /** The scale [intent] asks for; intents without one (the notification) keep the current size. */
    private fun scaleOf(intent: Intent?) =
        (intent?.getFloatExtra(EXTRA_SCALE, desktopScale) ?: desktopScale).coerceIn(MIN_SCALE, MAX_SCALE)

    override fun onResume() {
        super.onResume()
        surfaceView.requestFocus()
        clipboard.addPrimaryClipChangedListener(clipboardListener)
        overlay?.let { handler.post(overlayUpdater) }
    }

    override fun onPause() {
        handler.removeCallbacks(overlayUpdater)
        clipboard.removePrimaryClipChangedListener(clipboardListener)
        super.onPause()
    }

    override fun onDestroy() {
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
        if (hasFocus) {
            capturePointer()
            clipboardToX()
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
        if (!surfaceView.hasPointerCapture()) {
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
            DesktopSessionService.start(this, keyboardLayout())
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
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        sendPhysicalKey(event) || super.dispatchKeyEvent(event)

    /**
     * Physical keys go to X by scan code: the X keymap (setxkbmap) applies the
     * layout, as on a PC. Volume and power keys stay with Android.
     */
    private fun sendPhysicalKey(event: KeyEvent): Boolean {
        val scanCode = event.scanCode
        if (scanCode <= 0 || !X11Server.running || isSystemKey(event.keyCode)) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) X11Server.key(scanCode, true)
            KeyEvent.ACTION_UP -> X11Server.key(scanCode, false)
        }
        return true
    }

    private fun isSystemKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ||
        keyCode == KeyEvent.KEYCODE_POWER

    // -- Mouse and touchpad ------------------------------------------------

    private fun onPointerEvent(event: MotionEvent): Boolean {
        if (!X11Server.running) return false
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
        touch.onEvent(event, scale, touchSlop)
        return true
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

    private fun keyboardLayout(): String = when (Locale.getDefault().language) {
        "it", "de", "fr", "es", "pt" -> Locale.getDefault().language
        "en" -> if (Locale.getDefault().country == "GB") "gb" else "us"
        else -> "us"
    }

    companion object {
        private const val TAG = "DroidNux.Desktop"

        /** The desktop on screen, if any (main thread only). */
        private var current: DesktopActivity? = null

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
    }
}
