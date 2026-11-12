package app.droidnux.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.droidnux.R

/**
 * The Android settings the desktop depends on, each with what to do about
 * it. Checked again whenever the screen comes back (the user may have just
 * changed one).
 */
@Composable
fun SystemChecks(gpu: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    var phantomHelp by remember { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    val phantomOk = remember(refresh) { phantomProcessesUnlimited(context) }
    val batteryOk = remember(refresh) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val filesOk = remember(refresh) { Environment.isExternalStorageManager() }
    val microphoneOk = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val notificationsOk = remember(refresh) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CheckRow(R.string.check_gpu, gpu != null, gpu ?: stringResource(R.string.check_gpu_none))
        CheckRow(R.string.check_phantom, phantomOk, action = R.string.check_how) { phantomHelp = true }
        CheckRow(R.string.check_battery, batteryOk) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null)),
            )
        }
        CheckRow(R.string.check_files, filesOk) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null)),
            )
        }
        CheckRow(R.string.check_microphone, microphoneOk) { microphone.launch(Manifest.permission.RECORD_AUDIO) }
        CheckRow(R.string.check_notifications, notificationsOk) { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    if (phantomHelp) PhantomDialog(R.string.check_phantom) { phantomHelp = false }
}

/** Under [title], how to turn off Android's limit on child processes; the title alone when it is off already. */
@Composable
fun PhantomDialog(title: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val limited = remember { !phantomProcessesUnlimited(context) }
    val close = @Composable { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = if (limited) ({ Text(stringResource(R.string.check_phantom_steps)) }) else null,
        confirmButton = {
            if (limited) {
                TextButton(onClick = {
                    onDismiss()
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                }) { Text(stringResource(R.string.check_open_developer)) }
            } else {
                close()
            }
        },
        dismissButton = if (limited) close else null,
    )
}

/**
 * A setting: a check (after its [value]) when [ok], else the button that fixes it, or the [value] with a warning
 * when nothing can.
 */
@Composable
private fun CheckRow(title: Int, ok: Boolean, value: String? = null, action: Int = R.string.check_allow, onAction: (() -> Unit)? = null) {
    InfoRow(stringResource(title)) {
        if (ok || onAction == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (value != null) Text(value, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(
                    if (ok) Icons.Selected else Icons.Warning, null,
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            FilledTonalButton(onClick = onAction) { Text(stringResource(action)) }
        }
    }
}

/**
 * Android 12+ kills an app's child processes beyond 32 ("phantom
 * processes"); Firefox and Plasma together go past that. The developer option
 * "Disable child process restrictions" turns the limit off.
 */
private fun phantomProcessesUnlimited(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val flag = "settings_enable_monitor_phantom_procs"
    // Same lookup as FeatureFlagUtils: the global setting (adb) wins, then the
    // property the developer option writes.
    val value = Settings.Global.getString(context.contentResolver, flag)
        ?: runCatching {
            ProcessBuilder("getprop", "persist.sys.fflag.override.$flag").start()
                .inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrNull()
    return value == "false"
}
