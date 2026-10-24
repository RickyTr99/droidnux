package app.droidnux.display

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Android's audio focus for the desktop's sound: asked for while Linux apps
 * play (opening the desktop does not stop other apps' music), given back when
 * they stop. While another app has it (a call, a music player), the desktop
 * is silent; ducking is left to Android.
 */
object AudioFocus {

    init {
        DisplayCore // loads the native library
    }

    private val handler = Handler(Looper.getMainLooper())
    private var manager: AudioManager? = null
    private var playing = false
    private var held = false

    private val request by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, handler)
            .build()
    }

    /** Call once in the process that runs the display server, before Linux apps play. */
    fun init(context: Context) {
        if (manager != null) return
        manager = context.applicationContext.getSystemService(AudioManager::class.java)
        nativeInit()
    }

    /** The desktop is back on screen: take the focus back if the desktop is still playing. */
    fun reclaim() {
        if (playing && !held) requestFocus()
    }

    private fun requestFocus() {
        held = manager?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        nativeSetMuted(!held)
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                held = true
                nativeSetMuted(false)
            }
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                held = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT // a transient loss comes back by itself
                nativeSetMuted(true)
            }
        }
    }

    /** From the audio output, when the first sink starts and the last one stops. */
    @JvmStatic private fun onPlaying(playing: Boolean) {
        handler.post {
            this.playing = playing
            if (playing) {
                requestFocus()
            } else if (held) {
                manager?.abandonAudioFocusRequest(request)
                held = false
                nativeSetMuted(false)
            }
        }
    }

    @JvmStatic private external fun nativeInit()
    @JvmStatic private external fun nativeSetMuted(muted: Boolean)
}
