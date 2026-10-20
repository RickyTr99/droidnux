package app.droidnux.desktop

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import app.droidnux.display.X11Server
import kotlin.math.abs

/**
 * The touchscreen as a direct pointer: a tap clicks where it lands, a long
 * press is a right click, a drag holds the left button (moves windows, selects
 * text), two fingers scroll and a two-finger tap is a right click.
 */
class TouchGestures(private val handler: Handler = Handler(Looper.getMainLooper())) {

    private enum class State { IDLE, PENDING, DRAGGING, SCROLLING, DONE }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var fingers = 0
    private var scrollX = 0f
    private var scrollY = 0f
    private var scrolled = false

    private val longPress = Runnable {
        if (state == State.PENDING) {
            click(3)
            state = State.DONE
        }
    }

    /** [scale]: desktop pixels per surface pixel; [slop]: surface pixels a tap may travel. */
    fun onEvent(event: MotionEvent, scale: Float, slop: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                state = State.PENDING
                fingers = 1
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                X11Server.pointer(event.x * scale, event.y * scale)
                handler.postDelayed(longPress, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                fingers = maxOf(fingers, event.pointerCount)
                handler.removeCallbacks(longPress)
                when (state) {
                    State.PENDING -> {
                        state = State.SCROLLING
                        lastX = centerX(event)
                        lastY = centerY(event)
                        scrollX = 0f
                        scrollY = 0f
                        scrolled = false
                    }
                    State.DRAGGING -> {
                        X11Server.button(1, false)
                        state = State.DONE
                    }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (state == State.SCROLLING) {
                // Fingers left: keep scrolling from where the others are.
                lastX = centerX(event, excluding = event.actionIndex)
                lastY = centerY(event, excluding = event.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> when (state) {
                State.PENDING -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    handler.removeCallbacks(longPress)
                    // The press happens where the finger landed, then the drag follows it.
                    X11Server.button(1, true)
                    X11Server.pointer(event.x * scale, event.y * scale)
                    state = State.DRAGGING
                }
                State.DRAGGING -> X11Server.pointer(event.x * scale, event.y * scale)
                State.SCROLLING -> scroll(event, scale)
                else -> Unit
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                val tap = event.actionMasked == MotionEvent.ACTION_UP && event.eventTime - downTime < TAP_MS
                when (state) {
                    State.PENDING -> if (event.actionMasked == MotionEvent.ACTION_UP) click(1)
                    State.DRAGGING -> X11Server.button(1, false)
                    State.SCROLLING -> if (tap && !scrolled && fingers == 2) click(3)
                    else -> Unit
                }
                state = State.IDLE
            }
        }
    }

    private fun scroll(event: MotionEvent, scale: Float) {
        val x = centerX(event)
        val y = centerY(event)
        // Natural scrolling: content follows the fingers.
        scrollX -= (x - lastX) * scale / SCROLL_STEP
        scrollY -= (y - lastY) * scale / SCROLL_STEP
        lastX = x
        lastY = y
        val sx = scrollX.toInt()
        val sy = scrollY.toInt()
        if (sx != 0 || sy != 0) {
            X11Server.scroll(sx.toFloat(), sy.toFloat())
            scrollX -= sx
            scrollY -= sy
            scrolled = true
        }
    }

    private fun click(button: Int) {
        X11Server.button(button, true)
        X11Server.button(button, false)
    }

    private fun centerX(event: MotionEvent, excluding: Int = -1) = center(event, excluding) { event.getX(it) }
    private fun centerY(event: MotionEvent, excluding: Int = -1) = center(event, excluding) { event.getY(it) }

    private inline fun center(event: MotionEvent, excluding: Int, coordinate: (Int) -> Float): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == excluding) continue
            sum += coordinate(i)
            count++
        }
        return if (count > 0) sum / count else 0f
    }

    private companion object {
        const val LONG_PRESS_MS = 500L
        const val TAP_MS = 250L
        const val SCROLL_STEP = 24f // desktop pixels per scroll notch
    }
}
