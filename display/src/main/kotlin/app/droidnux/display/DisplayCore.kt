package app.droidnux.display

import android.util.Log

/** Kotlin handle to the native presentation core. Never on a frame's path. */
object DisplayCore {

    init {
        System.loadLibrary("droidnux-display")
    }

    /** Report on the GPU buffer path (buffers, EGL and Vulkan extensions), also logged. */
    fun probe(): String = nativeProbe().trimEnd().also { report ->
        report.lineSequence().forEach { Log.i(TAG, it) }
    }

    private const val TAG = "DroidNux.Display"

    private external fun nativeProbe(): String
}
