package app.droidnux.display

import android.view.Surface

/**
 * The presentation core's link to an Android surface. While attached, frames
 * from the display server appear on it as a hardware layer; while detached
 * the server keeps running and repaints everything on the next attach.
 */
object Presenter {

    init {
        DisplayCore // loads the native library
    }

    /**
     * Call when the surface is created or its size changes (from SurfaceHolder.Callback).
     * [maxRefreshRate]: the highest refresh rate of the surface's display, in Hz.
     */
    fun attach(surface: Surface, maxRefreshRate: Float) = nativeAttach(surface, maxRefreshRate)

    /** Call before the surface is destroyed. */
    fun detach() = nativeDetach()

    /** Shows or hides the X cursor layer (hidden while the touchscreen drives the pointer). */
    fun setCursorVisible(visible: Boolean) = nativeSetCursorVisible(visible)

    /** The X cursor's height on the desktop, in desktop pixels. */
    val cursorY: Int get() = nativeCursorY()

    /** Frames presented since the process started (debug overlay). */
    val frameCount: Long get() = nativeFrameCount()

    /** Time spent preparing frames since the process started, in nanoseconds (debug overlay). */
    val frameTimeTotalNanos: Long get() = nativeFrameTimeTotal()

    /** Longest frame preparation since the previous call, in nanoseconds (debug overlay). */
    fun takeFrameTimeMaxNanos(): Long = nativeTakeFrameTimeMax()

    @JvmStatic private external fun nativeAttach(surface: Surface, maxRefreshRate: Float)
    @JvmStatic private external fun nativeDetach()
    @JvmStatic private external fun nativeSetCursorVisible(visible: Boolean)
    @JvmStatic private external fun nativeCursorY(): Int
    @JvmStatic private external fun nativeFrameCount(): Long
    @JvmStatic private external fun nativeFrameTimeTotal(): Long
    @JvmStatic private external fun nativeTakeFrameTimeMax(): Long
}
