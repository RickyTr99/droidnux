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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
        InfoRow(
            title = stringResource(R.string.check_gpu),
            text = if (gpu != null) stringResource(R.string.check_gpu_ok, gpu) else stringResource(R.string.check_gpu_none),
            icon = if (gpu != null) Icons.Check else Icons.Warning,
            iconTint = if (gpu != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        InfoRow(
            title = stringResource(R.string.check_phantom),
            text = stringResource(if (phantomOk) R.string.check_phantom_ok else R.string.check_phantom_text),
            icon = if (phantomOk) Icons.Check else Icons.Warning,
            iconTint = if (phantomOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            action = if (phantomOk) null else {
                { FilledTonalButton(onClick = { phantomHelp = true }) { Text(stringResource(R.string.check_how)) } }
            },
        )
        InfoRow(
            title = stringResource(R.string.check_battery),
            text = stringResource(R.string.check_battery_text),
            icon = if (batteryOk) Icons.Check else Icons.Info,
            iconTint = if (batteryOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            action = if (batteryOk) null else {
                {
                    FilledTonalButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }) { Text(stringResource(R.string.check_allow)) }
                }
            },
        )
        InfoRow(
            title = stringResource(R.string.check_files),
            text = stringResource(R.string.check_files_text),
            icon = if (filesOk) Icons.Check else Icons.Info,
            iconTint = if (filesOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            action = if (filesOk) null else {
                {
                    FilledTonalButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null)),
                        )
                    }) { Text(stringResource(R.string.check_allow)) }
                }
            },
        )
        InfoRow(
            title = stringResource(R.string.check_microphone),
            text = stringResource(R.string.check_microphone_text),
            icon = if (microphoneOk) Icons.Check else Icons.Info,
            iconTint = if (microphoneOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            action = if (microphoneOk) null else {
                {
                    FilledTonalButton(onClick = { microphone.launch(Manifest.permission.RECORD_AUDIO) }) {
                        Text(stringResource(R.string.check_allow))
                    }
                }
            },
        )
        InfoRow(
            title = stringResource(R.string.check_notifications),
            text = stringResource(R.string.check_notifications_text),
            icon = if (notificationsOk) Icons.Check else Icons.Info,
            iconTint = if (notificationsOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            action = if (notificationsOk || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) null else {
                {
                    FilledTonalButton(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text(stringResource(R.string.check_allow))
                    }
                }
            },
        )
    }

    if (phantomHelp) {
        AlertDialog(
            onDismissRequest = { phantomHelp = false },
            title = { Text(stringResource(R.string.check_phantom)) },
            text = { Text(stringResource(R.string.check_phantom_steps)) },
            confirmButton = {
                TextButton(onClick = {
                    phantomHelp = false
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                }) { Text(stringResource(R.string.check_open_developer)) }
            },
            dismissButton = { TextButton(onClick = { phantomHelp = false }) { Text(stringResource(R.string.action_close)) } },
        )
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
