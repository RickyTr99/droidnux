package app.droidnux.desktop

import android.view.MotionEvent
import app.droidnux.display.X11Server
import kotlin.math.abs

/**
 * A touchpad while the pointer is captured: Android then sends raw finger
 * positions (SOURCE_TOUCHPAD), so the gestures are ours. One finger moves the
 * pointer, two scroll, a tap clicks (two fingers: right button, three: middle).
 */
class CapturedTouchpad {

    private val lastX = FloatArray(MAX_POINTERS)
    private val lastY = FloatArray(MAX_POINTERS)
    private var downTime = 0L
    private var fingers = 0
    private var travel = 0f
    private var scrollX = 0f
    private var scrollY = 0f
    private var pressedButton = 0

    /** [unit]: touchpad units per desktop pixel. */
    fun onEvent(event: MotionEvent, unit: Float): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                fingers = 1
                travel = 0f
                scrollX = 0f
                scrollY = 0f
                remember(event)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                fingers = maxOf(fingers, event.pointerCount)
                remember(event)
            }
            MotionEvent.ACTION_MOVE -> move(event, unit)
            MotionEvent.ACTION_UP -> {
                if (travel < TAP_SLOP * unit && event.eventTime - downTime < TAP_TIMEOUT_MS && pressedButton == 0) {
                    val button = when (fingers) { 1 -> 1; 2 -> 3; else -> 2 }
                    X11Server.button(button, true)
                    X11Server.button(button, false)
                }
                fingers = 0
            }
            MotionEvent.ACTION_BUTTON_PRESS -> if (pressedButton == 0) {
                // A clickpad has one button: two fingers on it mean the right one.
                pressedButton = if (event.pointerCount >= 2) 3 else 1
                X11Server.button(pressedButton, true)
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> if (pressedButton != 0) {
                X11Server.button(pressedButton, false)
                pressedButton = 0
                travel = Float.MAX_VALUE // the release is not a tap
            }
            else -> return false
        }
        return true
    }

    private fun move(event: MotionEvent, unit: Float) {
        var dx = 0f
        var dy = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            if (id >= MAX_POINTERS) continue
            dx += event.getX(i) - lastX[id]
            dy += event.getY(i) - lastY[id]
            count++
        }
        remember(event)
        if (count == 0) return
        dx /= count
        dy /= count
        travel += abs(dx) + abs(dy)
        if (event.pointerCount == 1) {
            X11Server.pointerRelative(dx / unit, dy / unit)
        } else {
            // Natural scrolling, as on Android: content follows the fingers.
            scrollX -= dx / unit / SCROLL_STEP
            scrollY -= dy / unit / SCROLL_STEP
            val sx = scrollX.toInt()
            val sy = scrollY.toInt()
            if (sx != 0 || sy != 0) {
                X11Server.scroll(sx.toFloat(), sy.toFloat())
                scrollX -= sx
                scrollY -= sy
            }
        }
    }

    private fun remember(event: MotionEvent) {
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            if (id >= MAX_POINTERS) continue
            lastX[id] = event.getX(i)
            lastY[id] = event.getY(i)
        }
    }

    private companion object {
        const val MAX_POINTERS = 10
        const val TAP_TIMEOUT_MS = 200
        const val TAP_SLOP = 12f   // desktop pixels a tap may travel
        const val SCROLL_STEP = 24f // desktop pixels per scroll notch
    }
}
