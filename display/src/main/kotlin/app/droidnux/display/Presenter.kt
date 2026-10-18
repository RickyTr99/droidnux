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

    /** Call when the surface is created or its size changes (from SurfaceHolder.Callback). */
    fun attach(surface: Surface) = nativeAttach(surface)

    /** Call before the surface is destroyed. */
    fun detach() = nativeDetach()

    /** Frames presented since the process started (debug overlay). */
    val frameCount: Long get() = nativeFrameCount()

    @JvmStatic private external fun nativeAttach(surface: Surface)
    @JvmStatic private external fun nativeDetach()
    @JvmStatic private external fun nativeFrameCount(): Long
}
