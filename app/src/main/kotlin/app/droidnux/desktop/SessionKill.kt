package app.droidnux.desktop

import android.content.Context
import java.io.File
import java.time.Instant

/**
 * Sessions Android killed. Past 32 child processes ("phantom processes") it kills an app's
 * oldest ones first, which is the session's first process: the whole desktop goes with it.
 */
object SessionKill {

    /** Exit status of a process ended by SIGKILL, which only Android sends to the session. */
    const val EXIT_STATUS = 128 + 9

    /** Exists until the Home (another process) has told the user. */
    private fun notice(context: Context) = File(context.filesDir, "session-killed")

    /** One line a kill, in the log a tester sends. */
    fun history(context: Context) = File(context.filesDir, "session-kills.log")

    fun record(context: Context) {
        history(context).appendText("${Instant.now()} session killed by Android\n")
        notice(context).createNewFile()
    }

    fun pending(context: Context) = notice(context).exists()

    fun dismiss(context: Context) {
        notice(context).delete()
    }
}
