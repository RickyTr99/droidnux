package app.droidnux.desktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.droidnux.R
import app.droidnux.container.Container
import app.droidnux.container.DesktopEnvironment
import app.droidnux.container.Distro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the desktop session alive: a foreground service in the :desktop
 * process (with the X server and DesktopActivity) that runs the guest session
 * and keeps the network on while the desktop is not on screen.
 */
class DesktopSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var session: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            endDesktop()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (session == null) {
            startSession(intent?.getStringExtra(EXTRA_LAYOUT) ?: "us", intent?.getIntExtra(EXTRA_DPI, 96) ?: 96)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSession()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSession(layout: String, dpi: Int) {
        val distro = Distro.DEBIAN_TRIXIE
        val container = Container(this)
        val rootfs = container.installer.rootfsDir(distro)
        DesktopEnvironment.prepare(rootfs, java.io.File(applicationInfo.nativeLibraryDir))
        val process = container.spawnShell(distro, DesktopEnvironment.sessionCommand(layout, dpi))
        session = process
        Log.i(TAG, "session started (layout $layout)")
        scope.launch {
            try {
                process.inputStream.bufferedReader().forEachLine {
                    Log.i(TAG_GUEST, it)
                    // The desktop logged out; its services (PipeWire, D-Bus) would keep proot alive.
                    if (it == DesktopEnvironment.SESSION_END_MARKER) process.destroy()
                }
            } catch (e: java.io.IOException) {
                // destroy() from another thread closes the stream under the reader.
                Log.i(TAG, "session output closed: $e")
            }
            val code = process.waitFor()
            Log.i(TAG, "session ended with $code")
            if (session === process) handler.post { endDesktop() }
        }
    }

    /**
     * Ends everything: the guest (proot kills all its processes when it
     * exits), the desktop activity, and this process with the X server.
     */
    private fun endDesktop() {
        stopSession()
        DesktopActivity.finishCurrent()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // Let the activity close first; a restart then starts a new X server.
        handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, EXIT_DELAY_MS)
    }

    private fun stopSession() {
        session?.destroy()
        session = null
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.session_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, DesktopActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DesktopSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.session_running))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.session_stop), stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "DroidNux.Session"
        private const val TAG_GUEST = "DroidNux.Guest"
        private const val CHANNEL = "session"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.droidnux.action.STOP_SESSION"
        private const val EXTRA_LAYOUT = "layout"
        private const val EXTRA_DPI = "dpi"
        private const val EXIT_DELAY_MS = 500L

        /** Ends the session, as the notification's Stop action does. */
        fun stop(context: Context) {
            context.startService(Intent(context, DesktopSessionService::class.java).setAction(ACTION_STOP))
        }

        fun start(context: Context, layout: String, dpi: Int) {
            context.startForegroundService(
                Intent(context, DesktopSessionService::class.java).putExtra(EXTRA_LAYOUT, layout).putExtra(EXTRA_DPI, dpi),
            )
        }
    }
}
