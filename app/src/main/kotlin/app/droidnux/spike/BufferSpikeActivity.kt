package app.droidnux.spike

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import app.droidnux.container.Container
import app.droidnux.container.Distro
import app.droidnux.display.BufferSpike
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 0 buffer-sharing spike: Turnip in the container renders straight into
 * AHardwareBuffers that SurfaceFlinger composites. Full screen, throwaway.
 */
class BufferSpikeActivity : Activity(), SurfaceHolder.Callback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private var spike: BufferSpike? = null
    private var guest: Process? = null
    private var jobs = mutableListOf<Job>()
    @Volatile private var guestLine = "starting guest…"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val surfaceView = SurfaceView(this).apply { holder.addCallback(this@BufferSpikeActivity) }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.MONOSPACE
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView)
            addView(status, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
            ))
        })
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (spike != null) return
        try {
            spike = BufferSpike(holder.surface, width, height)
        } catch (e: IllegalStateException) {
            status.text = "spike failed: ${e.message}"
            return
        }
        startGuest()
        jobs += scope.launch {
            while (isActive) {
                status.text = "${spike?.stats()}\nguest: $guestLine"
                delay(500)
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = stop()

    override fun onDestroy() {
        stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startGuest() {
        val process = try {
            Container(applicationContext).spawnShell(Distro.DEBIAN_TRIXIE, GUEST_SCRIPT)
        } catch (e: Exception) {
            guestLine = "cannot start: ${e.message}"
            return
        }
        guest = process
        jobs += scope.launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().forEachLine { line ->
                Log.i(TAG, line)
                guestLine = line
            }
            val code = process.waitFor()
            Log.i(TAG, "guest exited with $code")
            withContext(Dispatchers.Main) { guestLine = "exited with $code (last: $guestLine)" }
        }
    }

    private fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        guest?.destroy()
        guest = null
        spike?.close()
        spike = null
    }

    private companion object {
        const val TAG = "DroidNux.Guest"
        const val GUEST_SCRIPT =
            "export VK_ICD_FILENAMES=/opt/droidnux/mesa/share/vulkan/icd.d/freedreno_icd.aarch64.json; " +
                "exec /root/spike/buffer-spike"
    }
}
