package app.droidnux.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.math.cbrt
import kotlin.math.roundToInt

/**
 * The desktop's volume is Android's media volume: the PipeWire sink shows it as
 * its own (Plasma's slider and OSD follow the tablet's volume keys), and what
 * the desktop sets changes Android's. PipeWire's volume is linear, its sliders
 * show the cube root, which is what Android's steps are mapped to.
 */
object AudioVolume {

    init {
        DisplayCore // loads the native library
    }

    private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    private const val MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"

    private val handler = Handler(Looper.getMainLooper())
    private var manager: AudioManager? = null
    /** The step and mute the desktop asked for last: Android's echo of them is not sent back. */
    private var setIndex = -1
    private var setMute: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
            sendToDesktop()
        }
    }

    /** Call once in the process that runs the display server, with [AudioFocus.init]. */
    fun init(context: Context) {
        if (manager != null) return
        manager = context.applicationContext.getSystemService(AudioManager::class.java)
        nativeInit()
        val filter = IntentFilter(VOLUME_CHANGED).apply { addAction(MUTE_CHANGED) }
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        sendToDesktop()
    }

    private fun sendToDesktop() {
        val audio = manager ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val index = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mute = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        if (index == setIndex && mute == setMute) return
        setIndex = -1
        setMute = null
        val level = index.toFloat() / max
        nativeSetVolume(level * level * level, mute)
    }

    /** From a sink's thread: the desktop changed its volume. */
    @JvmStatic private fun onDesktopVolume(volume: Float, mute: Boolean) {
        handler.post {
            val audio = manager ?: return@post
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val index = (cbrt(volume) * max).roundToInt().coerceIn(0, max)
            setIndex = index
            setMute = mute
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != index) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            }
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC) != mute) {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0,
                )
            }
        }
    }

    @JvmStatic private external fun nativeInit()
    @JvmStatic private external fun nativeSetVolume(volume: Float, mute: Boolean)
}
