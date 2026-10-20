package app.droidnux.display

import java.io.File

/**
 * The X11 front-end: an X server running on a thread of this process, drawing
 * through [Presenter]. One per process, started once. Input methods may be
 * called from any thread.
 */
object X11Server {

    init {
        DisplayCore // loads the native library
    }

    /** False in builds without the X server (Windows builds without the CI prebuilt). */
    val available: Boolean get() = nativeAvailable()

    val running: Boolean get() = available && nativeRunning()

    /**
     * Starts the server for the container whose root filesystem is [rootfs]:
     * the socket goes in its /tmp/.X11-unix, keymaps and fonts come from it.
     */
    fun start(rootfs: File, width: Int, height: Int, dpi: Int, display: Int = 0): Boolean {
        check(available) { "this build has no X server" }
        val tmp = File(rootfs, "tmp").apply { mkdirs() }
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

    /** Text typed without a physical key (soft keyboard, IME). */
    fun text(text: CharSequence) {
        text.codePoints().forEach { nativeText(it) }
    }

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

    /** Called on the X server thread with the text an X client copied. */
    @Volatile var onClipboard: ((String) -> Unit)? = null

    /** Android's clipboard text: X clients paste it (CLIPBOARD selection). */
    fun setClipboard(text: String) {
        if (running) nativeSetClipboard(text.toByteArray())
    }

    @JvmStatic private fun clipboardFromX(utf8: ByteArray) {
        onClipboard?.invoke(String(utf8, Charsets.UTF_8))
    }

    @JvmStatic private external fun nativeSnapshot(width: Int, height: Int): IntArray?
    @JvmStatic private external fun nativeAvailable(): Boolean
    @JvmStatic private external fun nativeStart(
        tmpDir: String, xkbDir: String, fontDir: String, display: Int, width: Int, height: Int, dpi: Int,
    ): Boolean
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
}
