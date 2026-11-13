package app.droidnux.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display

/**
 * The device's cameras for Linux apps (core/camera_input.cpp): open only while
 * an app films, turned upright for the screen's rotation. Android's permission
 * is asked for when an app first opens one.
 */
object CameraInput {

    init {
        DisplayCore // loads the native library
    }

    private val handler = Handler(Looper.getMainLooper())
    private var displays: DisplayManager? = null

    /** Asks the user for the camera permission, the answer to [setPermission]; null: none can be asked. */
    @Volatile var permissionAsker: (() -> Unit)? = null

    /** Call once in the process that runs the display server, before Linux apps film. */
    fun init(context: Context) {
        if (displays != null) return
        val manager = context.applicationContext.getSystemService(DisplayManager::class.java)
        displays = manager
        nativeInit()
        val update = { nativeSetRotation((manager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0) * 90) }
        update()
        manager.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) update()
                }
            },
            handler,
        )
    }

    /** The user's answer: the cameras waiting for it open, or tell the app no. */
    fun setPermission(granted: Boolean) = nativeSetPermission(granted)

    /** The session is paused: the cameras close until it continues. */
    fun setPaused(paused: Boolean) = nativeSetPaused(paused)

    /** From a camera that Android refused to open. */
    @JvmStatic private fun onPermissionNeeded() {
        handler.post { permissionAsker?.invoke() ?: nativeSetPermission(false) }
    }

    @JvmStatic private external fun nativeInit()
    @JvmStatic private external fun nativeSetRotation(degrees: Int)
    @JvmStatic private external fun nativeSetPermission(granted: Boolean)
    @JvmStatic private external fun nativeSetPaused(paused: Boolean)
}
