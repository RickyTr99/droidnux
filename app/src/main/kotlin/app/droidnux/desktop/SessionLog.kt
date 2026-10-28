package app.droidnux.desktop

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import app.droidnux.BuildConfig
import java.io.Closeable
import java.io.File

/**
 * The last session's output in a file, and the log a tester sends: the
 * device, the GPU probe, that output and the app's own logcat.
 */
object SessionLog {

    private const val MAX_BYTES = 16L shl 20

    private fun file(context: Context) = File(context.filesDir, "session.log")

    /** Starts the file over; lines past [MAX_BYTES] are dropped (verbose KWin fills it fast). */
    class Writer(context: Context) : Closeable {
        private val out = file(context).bufferedWriter()
        private var bytes = 0L

        fun line(text: String) {
            if (bytes > MAX_BYTES) return
            bytes += text.length + 1
            out.appendLine(if (bytes > MAX_BYTES) "[log truncated]" else text)
            // The session may die with the process: every line on disk.
            out.flush()
        }

        override fun close() = out.close()
    }

    /** Writes the log to send and returns the chooser that shares it; blocking. */
    fun share(context: Context, probe: String?): Intent {
        val log = File(context.cacheDir, "logs/droidnux-log.txt").apply { parentFile?.mkdirs() }
        log.bufferedWriter().use { out ->
            out.appendLine("DroidNux ${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID}${if (BuildConfig.DIAGNOSTIC) ", diagnostic" else ""})")
            out.appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) out.appendLine("SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            out.appendLine("Kernel ${System.getProperty("os.version")}, board ${Build.BOARD}, hardware ${Build.HARDWARE}")
            out.appendLine("\n== GPU probe ==\n${probe ?: "—"}")
            out.appendLine("\n== Last session ==")
            file(context).takeIf { it.exists() }?.reader()?.use { it.copyTo(out) } ?: out.appendLine("—")
            // An app reads only its own processes' lines: the display server's and the session's.
            out.appendLine("\n== logcat ==")
            val logcat = ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start()
            logcat.inputStream.reader().use { it.copyTo(out) }
            logcat.waitFor()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", log)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }
}
