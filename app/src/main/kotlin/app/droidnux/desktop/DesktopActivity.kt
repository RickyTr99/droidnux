package app.droidnux.desktop

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
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
    private var touchDown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.setDecorFitsSystemWindows(false)

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@DesktopActivity)
            isFocusable = true
            isFocusableInTouchMode = true
            // The X cursor is drawn by the presenter; hide Android's.
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
            setOnGenericMotionListener { _, event -> onPointerEvent(event) }
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

    override fun onResume() {
        super.onResume()
        surfaceView.requestFocus()
        overlay?.let { handler.post(overlayUpdater) }
    }

    override fun onPause() {
        handler.removeCallbacks(overlayUpdater)
        super.onPause()
    }

    // -- Surface -----------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!X11Server.available) {
            overlay?.text = "This build has no X server"
            return
        }
        // Even sizes keep scaling artefacts away from odd pixel rows.
        val desktopWidth = (width * DESKTOP_SCALE).toInt() and 1.inv()
        val desktopHeight = (height * DESKTOP_SCALE).toInt() and 1.inv()
        scale = desktopWidth.toFloat() / width

        Presenter.attach(holder.surface)
        if (!X11Server.running) {
            val rootfs = Container(this).installer.rootfsDir(Distro.DEBIAN_TRIXIE)
            val started = X11Server.start(rootfs, desktopWidth, desktopHeight, DPI)
            Log.i(TAG, "X server ${if (started) "started" else "failed to start"} at ${desktopWidth}x$desktopHeight")
            DesktopSessionService.start(this, keyboardLayout())
        } else {
            X11Server.resize(desktopWidth, desktopHeight)
        }
        X11Server.redraw()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Presenter.detach()
    }

    // -- Keyboard ----------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Physical keys go to X by scan code: the X keymap (setxkbmap) applies
        // the layout, as on a PC. Volume keys and the virtual Back stay with Android.
        val scanCode = event.scanCode
        if (scanCode > 0 && X11Server.running && !isSystemKey(event.keyCode)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) X11Server.key(scanCode, true)
                KeyEvent.ACTION_UP -> X11Server.key(scanCode, false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isSystemKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ||
        keyCode == KeyEvent.KEYCODE_POWER

    // -- Mouse and touchpad ------------------------------------------------

    private fun onPointerEvent(event: MotionEvent): Boolean {
        if (!X11Server.running) return false
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
        // Touchscreen, first version: one finger acts as the left button.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                X11Server.pointer(event.x * scale, event.y * scale)
                X11Server.button(1, true)
                touchDown = true
            }
            MotionEvent.ACTION_MOVE -> if (touchDown) X11Server.pointer(event.x * scale, event.y * scale)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (touchDown) {
                X11Server.button(1, false)
                touchDown = false
            }
        }
        return true
    }

    // -- Debug overlay -----------------------------------------------------

    private var lastFrames = 0L
    private val overlayUpdater = object : Runnable {
        override fun run() {
            val frames = Presenter.frameCount
            overlay?.text = "${frames - lastFrames} fps · desktop ${(scale * 100).toInt()}%"
            lastFrames = frames
            handler.postDelayed(this, 1000)
        }
    }

    private fun keyboardLayout(): String = when (Locale.getDefault().language) {
        "it", "de", "fr", "es", "pt" -> Locale.getDefault().language
        "en" -> if (Locale.getDefault().country == "GB") "gb" else "us"
        else -> "us"
    }

    private companion object {
        const val TAG = "DroidNux.Desktop"
        const val DESKTOP_SCALE = 0.6f
        const val DPI = 96
    }
}
