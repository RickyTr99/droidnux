package app.droidnux.desktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.droidnux.BuildConfig
import app.droidnux.R
import app.droidnux.container.Container
import app.droidnux.container.DesktopEnvironment
import app.droidnux.container.Distro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps the desktop session alive: a foreground service in the :desktop
 * process (with the display server and DesktopActivity) that runs the guest session
 * and keeps the network on while the desktop is not on screen.
 */
class DesktopSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var session: Process? = null
    private var battery: BroadcastReceiver? = null
    private var network: NetworkReporter? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            endDesktop()
            return START_NOT_STICKY
        }
        // The microphone type lets Linux apps record while the desktop is in the background;
        // Android refuses it without the permission.
        val microphone = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        startForeground(
            NOTIFICATION_ID, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0),
        )
        if (session == null) {
            startSession(intent?.getStringExtra(EXTRA_LAYOUT) ?: "us")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        battery?.let { unregisterReceiver(it) }
        network?.stop()
        stopSession()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSession(layout: String) {
        val distro = Distro.DEBIAN_TRIXIE
        val container = Container(this)
        val rootfs = container.installer.rootfsDir(distro)
        DesktopEnvironment.prepare(rootfs, java.io.File(applicationInfo.nativeLibraryDir))
        // Android's battery in the desktop (sticky: the first call comes at once).
        if (battery == null) {
            battery = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    scope.launch { runCatching { writeBattery(rootfs, intent) }.onFailure { Log.w(TAG, "battery: $it") } }
                }
            }.also { registerReceiver(it, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
        }
        if (network == null) network = NetworkReporter(this, rootfs, scope).also { it.start() }
        val user = DesktopSettings.load(this).user
        val process = container.spawnShell(distro, DesktopEnvironment.sessionCommand(layout, user, verbose = BuildConfig.DIAGNOSTIC))
        val password = Account.password(this).orEmpty()
        session = process
        Log.i(TAG, "session started (layout $layout)")
        // The account's password, on the session's input: it never sits in a file or the environment.
        scope.launch {
            val before = Account.passwordBefore(this@DesktopSessionService).orEmpty()
            runCatching { process.outputStream.use { it.write("$password\n$before\n".toByteArray()) } }
        }
        scope.launch {
            val log = SessionLog.Writer(this@DesktopSessionService)
            try {
                process.inputStream.bufferedReader().forEachLine {
                    Log.i(TAG_GUEST, it)
                    log.line(it)
                    // The desktop logged out; its services (PipeWire, D-Bus) would keep the runtime alive.
                    if (it == DesktopEnvironment.SESSION_END_MARKER) process.destroy()
                    if (it == DesktopEnvironment.WALLET_MOVED_MARKER) Account.walletMoved(this@DesktopSessionService, password)
                }
            } catch (e: java.io.IOException) {
                // destroy() from another thread closes the stream under the reader.
                Log.i(TAG, "session output closed: $e")
            } finally {
                log.close()
            }
            val code = process.waitFor()
            Log.i(TAG, "session ended with $code")
            if (session === process) handler.post { endDesktop() }
        }
    }

    /**
     * Ends everything: the guest (Burrow kills all its processes when it
     * exits), the desktop activity, and this process with the display server.
     */
    private fun endDesktop() {
        stopSession()
        DesktopActivity.finishCurrent()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // Let the activity close first; a restart then starts a new display server.
        handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, EXIT_DELAY_MS)
    }

    // Volts to 0.1 and degrees to 1: the file (and Plasma) change only when something shows.
    private fun writeBattery(rootfs: java.io.File, intent: Intent) {
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val toFull = getSystemService(BatteryManager::class.java).computeChargeTimeRemaining()
        DesktopEnvironment.writeBattery(
            rootfs,
            percent = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1),
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING,
            full = status == BatteryManager.BATTERY_STATUS_FULL,
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            toFull = if (toFull > 0) toFull / 1000 else 0,
            celsius = (intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f).roundToInt().toFloat(),
            volts = (intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 100f).roundToInt() / 10f,
        )
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
        private const val EXIT_DELAY_MS = 500L

        /** Ends the session, as the notification's Stop action does. */
        fun stop(context: Context) {
            context.startService(Intent(context, DesktopSessionService::class.java).setAction(ACTION_STOP))
        }

        fun start(context: Context, layout: String) {
            context.startForegroundService(
                Intent(context, DesktopSessionService::class.java).putExtra(EXTRA_LAYOUT, layout),
            )
        }
    }
}
