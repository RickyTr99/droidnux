package app.droidnux.display

import java.io.File

/**
 * The display server the desktop's clients connect to, on a thread of this
 * process and drawing through [Presenter]: the X11 front-end (an X server) or
 * the Wayland one (a compositor hosting a nested one). One per process,
 * started once. Input methods may be called from any thread.
 */
object DisplayServer {

    init {
        DisplayCore // loads the native library
    }

    /** False in builds without the X server (Windows builds without the CI prebuilt), for X11 only. */
    val available: Boolean get() = nativeAvailable()

    val running: Boolean get() = nativeRunning()

    /**
     * Starts the server for the container whose root filesystem is [rootfs].
     * X11: the socket goes in its /tmp/.X11-unix, keymaps and fonts come from
     * it. Wayland: the socket is its /tmp/wayland-[display] (/tmp is the
     * session's XDG_RUNTIME_DIR).
     */
    fun start(rootfs: File, width: Int, height: Int, dpi: Int, display: Int = 0, wayland: Boolean = false): Boolean {
        val tmp = File(rootfs, "tmp").apply { mkdirs() }
        if (wayland) return nativeStartWayland(tmp.absolutePath, display, width, height)
        check(available) { "this build has no X server" }
        return nativeStart(
            tmp.absolutePath,
            File(rootfs, "usr/share/X11/xkb").absolutePath,
            File(rootfs, "usr/share/fonts/X11").absolutePath,
            display, width, height, dpi,
        )
    }

    fun resize(width: Int, height: Int) = nativeResize(width, height)

    /** Repaints the whole desktop, e.g. right after [Presenter.attach]. */
    fun redraw() = nativeRedraw()

    /** Physical key by evdev keycode (Android KeyEvent.scanCode). */
    fun key(evdevKeycode: Int, down: Boolean) = nativeKey(evdevKeycode, down)

    /** Text typed without a physical key (soft keyboard, IME), X11 only. */
    fun text(text: CharSequence) {
        text.codePoints().forEach { nativeText(it) }
    }

    /**
     * Wayland: soft keyboard text for the focused text field, through the
     * nested KWin's input method. Dropped while no text field has the focus.
     */
    fun commitText(text: String) = nativeCommitText(text.toByteArray())

    /** Wayland: the text the soft keyboard is still composing, shown in the field; "" clears it. */
    fun preeditText(text: String) = nativePreeditText(text.toByteArray())

    /** Wayland: called on the compositor thread when a text field gains (true) or loses the focus. */
    @Volatile var onTextInput: ((Boolean) -> Unit)? = null

    @JvmStatic private fun textInputFromWayland(active: Boolean) {
        onTextInput?.invoke(active)
    }

    /** Wayland: the nested desktop's interface scale (1 = 100%). */
    fun setScale(scale: Float) = nativeSetScale(scale)

    /** Wayland touchscreen, in desktop pixels: [TOUCH_DOWN] and the others, then [TOUCH_FRAME]. */
    fun touch(type: Int, id: Int = 0, x: Float = 0f, y: Float = 0f) = nativeTouch(type, id, x, y)

    const val TOUCH_DOWN = 0
    const val TOUCH_MOTION = 1
    const val TOUCH_UP = 2
    const val TOUCH_FRAME = 3
    const val TOUCH_CANCEL = 4

    fun pointer(x: Float, y: Float) = nativePointer(x, y)
    fun pointerRelative(dx: Float, dy: Float) = nativePointerRelative(dx, dy)

    /** X button numbers: 1 left, 2 middle, 3 right, 8 back, 9 forward. */
    fun button(button: Int, down: Boolean) = nativeButton(button, down)

    /** Scroll in wheel notches; positive [dy] scrolls down, positive [dx] right. */
    fun scroll(dx: Float, dy: Float) = nativeScroll(dx, dy)

    /**
     * The desktop scaled to [width] x [height] (ARGB), or null if the server
     * is not running or did not answer in time. Blocks up to half a second.
     */
    fun snapshot(width: Int, height: Int): IntArray? = if (running) nativeSnapshot(width, height) else null

    /** Called on the server's thread with the text copied in the desktop. */
    @Volatile var onClipboard: ((String) -> Unit)? = null

    /** Android's clipboard text: the desktop's apps paste it. */
    fun setClipboard(text: String) {
        if (running) nativeSetClipboard(text.toByteArray())
    }

    @JvmStatic private fun clipboardFromDesktop(utf8: ByteArray) {
        onClipboard?.invoke(String(utf8, Charsets.UTF_8))
    }

    @JvmStatic private external fun nativeSnapshot(width: Int, height: Int): IntArray?
    @JvmStatic private external fun nativeAvailable(): Boolean
    @JvmStatic private external fun nativeStart(
        tmpDir: String, xkbDir: String, fontDir: String, display: Int, width: Int, height: Int, dpi: Int,
    ): Boolean
    @JvmStatic private external fun nativeStartWayland(tmpDir: String, display: Int, width: Int, height: Int): Boolean
    @JvmStatic private external fun nativeRunning(): Boolean
    @JvmStatic private external fun nativeResize(width: Int, height: Int)
    @JvmStatic private external fun nativeRedraw()
    @JvmStatic private external fun nativeKey(keycode: Int, down: Boolean)
    @JvmStatic private external fun nativeText(codepoint: Int)
    @JvmStatic private external fun nativePointer(x: Float, y: Float)
    @JvmStatic private external fun nativePointerRelative(dx: Float, dy: Float)
    @JvmStatic private external fun nativeButton(button: Int, down: Boolean)
    @JvmStatic private external fun nativeScroll(dx: Float, dy: Float)
    @JvmStatic private external fun nativeSetClipboard(utf8: ByteArray)
    @JvmStatic private external fun nativeCommitText(utf8: ByteArray)
    @JvmStatic private external fun nativePreeditText(utf8: ByteArray)
    @JvmStatic private external fun nativeSetScale(scale: Float)
    @JvmStatic private external fun nativeTouch(type: Int, id: Int, x: Float, y: Float)
}
