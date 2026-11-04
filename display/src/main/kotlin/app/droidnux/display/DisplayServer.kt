package app.droidnux.display

import java.io.File

/**
 * The display server the desktop connects to: a Wayland compositor hosting
 * the nested KWin, on a thread of this process and drawing through
 * [Presenter]. One per process, started once. Input methods may be called
 * from any thread.
 */
object DisplayServer {

    init {
        DisplayCore // loads the native library
    }

    val running: Boolean get() = nativeRunning()

    /**
     * Starts the server for the container whose root filesystem is [rootfs]:
     * the socket is its /tmp/wayland-[display] (/tmp is the session's
     * XDG_RUNTIME_DIR).
     */
    fun start(rootfs: File, width: Int, height: Int, display: Int = 0): Boolean =
        nativeStart(File(rootfs, "tmp").apply { mkdirs() }.absolutePath, display, width, height)

    fun resize(width: Int, height: Int) = nativeResize(width, height)

    /** Repaints the whole desktop, e.g. right after [Presenter.attach]. */
    fun redraw() = nativeRedraw()

    /** Physical key by evdev keycode (Android KeyEvent.scanCode). */
    fun key(evdevKeycode: Int, down: Boolean) = nativeKey(evdevKeycode, down)

    /**
     * Soft keyboard text for the focused text field, through the nested
     * KWin's input method. Dropped while no text field has the focus.
     */
    fun commitText(text: String) = nativeCommitText(text.toByteArray())

    /** The text the soft keyboard is still composing, shown in the field; "" clears it. */
    fun preeditText(text: String) = nativePreeditText(text.toByteArray())

    /** Called on the compositor thread when a text field gains (true) or loses the focus; true again when it changes (a tap on it does). */
    @Volatile var onTextInput: ((Boolean) -> Unit)? = null

    @JvmStatic private fun textInputFromWayland(active: Boolean) {
        onTextInput?.invoke(active)
    }

    /** The nested desktop's interface scale (1 = 100%). */
    fun setScale(scale: Float) = nativeSetScale(scale)

    /** Called on the compositor thread when KWin's scale changes by other means (Plasma's display settings). */
    @Volatile var onScale: ((Float) -> Unit)? = null

    @JvmStatic private fun scaleFromWayland(scale: Float) {
        onScale?.invoke(scale)
    }

    /** Touchscreen, in desktop pixels: [TOUCH_DOWN] and the others, then [TOUCH_FRAME]. */
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

    /** Called on the compositor thread with the text copied in the desktop. */
    @Volatile var onClipboard: ((String) -> Unit)? = null

    /** Android's clipboard text: the desktop's apps paste it. */
    fun setClipboard(text: String) {
        if (running) nativeSetClipboard(text.toByteArray())
    }

    @JvmStatic private fun clipboardFromDesktop(utf8: ByteArray) {
        onClipboard?.invoke(String(utf8, Charsets.UTF_8))
    }

    @JvmStatic private external fun nativeSnapshot(width: Int, height: Int): IntArray?
    @JvmStatic private external fun nativeStart(tmpDir: String, display: Int, width: Int, height: Int): Boolean
    @JvmStatic private external fun nativeRunning(): Boolean
    @JvmStatic private external fun nativeResize(width: Int, height: Int)
    @JvmStatic private external fun nativeRedraw()
    @JvmStatic private external fun nativeKey(keycode: Int, down: Boolean)
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
