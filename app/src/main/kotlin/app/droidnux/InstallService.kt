package app.droidnux

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

/**
 * Keeps the app's process in the foreground while it installs (AppViewModel's jobs): with the
 * app behind another one, or the screen off, Android would freeze or kill it and its apt.
 */
class InstallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.install_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.install_running))
            .setProgress(0, 0, true)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "DroidNux.Install"
        private const val CHANNEL = "install"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "app.droidnux.action.STOP_INSTALL"

        /**
         * On while something installs. Android refuses the start from the background: the job goes on
         * as before. Off by a command, after the start's: stopService() before the service is in the
         * foreground would crash the app.
         */
        fun set(context: Context, running: Boolean) {
            val intent = Intent(context, InstallService::class.java)
            runCatching { if (running) context.startForegroundService(intent) else context.startService(intent.setAction(ACTION_STOP)) }
                .onFailure { Log.w(TAG, "service: $it") }
        }
    }
}
