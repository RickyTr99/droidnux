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
import android.provider.Settings
import android.util.Log
import app.droidnux.BuildConfig
import app.droidnux.MainActivity
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
    private var drives: RemovableDrives? = null
    /** Plasma's Restart: the desktop opens again once this process is gone. */
    @Volatile private var restart = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            endDesktop()
            return START_NOT_STICKY
        }
        // The microphone and camera types let Linux apps record and film while the desktop is in the
        // background; Android refuses each without its permission.
        val microphone = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val camera = checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        startForeground(
            NOTIFICATION_ID, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
                (if (camera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0),
        )
        if (session == null) {
            startSession(intent?.getStringExtra(EXTRA_LAYOUT) ?: "us")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        battery?.let { unregisterReceiver(it) }
        network?.stop()
        drives?.stop()
        stopSession()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSession(layout: String) {
        val distro = Distro.DEBIAN_TRIXIE
        val container = Container(this)
        val rootfs = container.installer.rootfsDir(distro)
        DesktopEnvironment.prepare(this, rootfs)
        // Android's battery in the desktop (sticky: the first call comes at once).
        if (battery == null) {
            battery = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    scope.launch { runCatching { writeBattery(rootfs, intent) }.onFailure { Log.w(TAG, "battery: $it") } }
                }
            }.also { registerReceiver(it, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
        }
        if (network == null) network = NetworkReporter(this, rootfs, scope).also { it.start() }
        SessionPause.file(this).delete() // left by a process that died paused
        val settings = DesktopSettings.load(this)
        if (drives == null) drives = RemovableDrives(this, rootfs, settings.user, scope).also { it.start() }
        val verbose = BuildConfig.DIAGNOSTIC || settings.logs
        val brightness = Brightness.toSlider(Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128))
        val process = container.spawnShell(distro, DesktopEnvironment.sessionCommand(layout, settings.user, verbose, brightness))
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
            var loggedOut = false
            try {
                process.inputStream.bufferedReader().forEachLine {
                    if (verbose) Log.i(TAG_GUEST, it)
                    log.line(it)
                    // The desktop logged out; its services (PipeWire, D-Bus) would keep the runtime alive.
                    if (it == DesktopEnvironment.SESSION_END_MARKER || it == DesktopEnvironment.SESSION_RESTART_MARKER) {
                        loggedOut = true
                        restart = it == DesktopEnvironment.SESSION_RESTART_MARKER
                        process.destroy()
                    }
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
            // Not the logout's or stopSession's destroy(), which ends it with SIGTERM.
            if (session === process && !loggedOut && code == SessionKill.EXIT_STATUS) {
                Log.w(TAG, "session killed by Android")
                runCatching { SessionKill.record(this@DesktopSessionService) }
                getSystemService(NotificationManager::class.java).notify(KILLED_NOTIFICATION_ID, killedNotification())
            }
            if (session === process) handler.post { endDesktop() }
        }
    }

    /**
     * Ends everything: the guest (Burrow kills all its processes when it
     * exits), the desktop activity, and this process with the display server.
     */
    private fun endDesktop() {
        stopSession()
        // While the desktop is still on screen: Android lets a visible app start an activity.
        if (restart) {
            startActivity(Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_RESTART_DESKTOP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
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
        // Stopped processes would not see the end.
        SessionPause.resume(this)
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

    /** Stays after this process: the Home, which it opens, says what to do. */
    private fun killedNotification(): Notification {
        val home = PendingIntent.getActivity(
            this, 2, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.session_killed))
            .setContentIntent(home)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        /** The notification of a session Android killed; the Home removes it with its own notice. */
        const val KILLED_NOTIFICATION_ID = 2
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
