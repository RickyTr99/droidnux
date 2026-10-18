package app.droidnux.desktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
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
    private var session: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (session == null) startSession(intent?.getStringExtra(EXTRA_LAYOUT) ?: "us")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSession()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSession(layout: String) {
        val distro = Distro.DEBIAN_TRIXIE
        val container = Container(this)
        val rootfs = container.installer.rootfsDir(distro)
        DesktopEnvironment.prepare(rootfs, java.io.File(applicationInfo.nativeLibraryDir))
        val process = container.spawnShell(distro, DesktopEnvironment.sessionCommand(layout))
        session = process
        Log.i(TAG, "session started (layout $layout)")
        scope.launch {
            process.inputStream.bufferedReader().forEachLine { Log.i(TAG_GUEST, it) }
            val code = process.waitFor()
            Log.i(TAG, "session ended with $code")
            if (session === process) {
                session = null
                stopSelf()
            }
        }
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

        fun start(context: Context, layout: String) {
            context.startForegroundService(
                Intent(context, DesktopSessionService::class.java).putExtra(EXTRA_LAYOUT, layout),
            )
        }
    }
}
