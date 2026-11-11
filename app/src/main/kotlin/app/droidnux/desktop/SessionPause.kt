package app.droidnux.desktop

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import app.droidnux.display.AudioFocus
import java.io.File

/**
 * The session's pause: every guest process stopped (SIGSTOP), so the desktop
 * costs no CPU until it continues. Guest processes are the ones running
 * Burrow (every execve in the guest runs it again).
 */
object SessionPause {
    private const val TAG = "DroidNux.Session"

    /** Exists while the session is paused; the Home (another process) reads it. */
    fun file(context: Context) = File(context.filesDir, "session-paused")

    fun isPaused(context: Context) = file(context).exists()

    fun pause(context: Context) {
        file(context).createNewFile()
        AudioFocus.setPaused(true)
        Log.i(TAG, "paused ${signal(context, OsConstants.SIGSTOP)} guest processes")
    }

    /** Continues a paused session; nothing if it is not paused. */
    fun resume(context: Context) {
        if (!file(context).delete()) return
        Log.i(TAG, "resumed ${signal(context, OsConstants.SIGCONT)} guest processes")
        AudioFocus.setPaused(false)
    }

    /** Sends [signal] to every guest process; returns how many got it. */
    private fun signal(context: Context, signal: Int): Int {
        val done = HashSet<Int>()
        // A process may fork while the others stop: again until a pass finds no new one.
        while (true) {
            val pids = guestPids(context).filter { it !in done }
            if (pids.isEmpty()) return done.size
            for (pid in pids) {
                runCatching { Os.kill(pid, signal) }
                done += pid
            }
        }
    }

    /**
     * The session's processes now: the guest's that come from this process (the desktop's). The
     * store's apt and the Home's queries run under the app's other process and are left alone.
     */
    fun guestPids(context: Context): List<Int> {
        val burrow = File(context.applicationInfo.nativeLibraryDir, "libburrow.so").path
        val guest = File("/proc").list().orEmpty().mapNotNull { it.toIntOrNull() }
            .filter { runCatching { Os.readlink("/proc/$it/exe") }.getOrNull() == burrow }.toHashSet()
        val self = android.os.Process.myPid()
        return guest.filter { pid ->
            // Up through the guest's own processes to the first one outside: ours, or init for an orphan.
            var ancestor: Int? = pid
            while (ancestor != null && ancestor in guest) ancestor = parent(ancestor)
            ancestor == null || ancestor == self || ancestor == 1
        }
    }

    // After the name (which may hold spaces): the state, then the parent.
    private fun parent(pid: Int): Int? = runCatching {
        File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ')[1].toInt()
    }.getOrNull()
}
