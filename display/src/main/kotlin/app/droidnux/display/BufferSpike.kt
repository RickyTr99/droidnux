package app.droidnux.display

import android.view.Surface

/**
 * Buffer-sharing spike: shares [bufferCount] AHardwareBuffers of
 * [width]x[height] with the guest client (guest/buffer-spike) and presents
 * what it renders on [surface]. Call [close] before the surface goes away.
 */
class BufferSpike(surface: Surface, val width: Int, val height: Int, bufferCount: Int = 3) : AutoCloseable {

    private var handle: Long

    init {
        DisplayCore // loads the native library
        handle = nativeStart(surface, width, height, bufferCount)
    }

    /** One-line status, refreshed at most once per second. */
    fun stats(): String = if (handle != 0L) nativeStats(handle) else "stopped"

    override fun close() {
        if (handle != 0L) {
            nativeStop(handle)
            handle = 0
        }
    }

    private companion object {
        @JvmStatic external fun nativeStart(surface: Surface, width: Int, height: Int, buffers: Int): Long
        @JvmStatic external fun nativeStats(handle: Long): String
        @JvmStatic external fun nativeStop(handle: Long)
    }
}
