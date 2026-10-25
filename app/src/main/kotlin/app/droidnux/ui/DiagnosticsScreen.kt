package app.droidnux.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.droidnux.R
import app.droidnux.container.CommandResult
import app.droidnux.container.Component
import app.droidnux.container.InstallProgress
import app.droidnux.desktop.DesktopActivity
import app.droidnux.spike.BufferSpikeActivity

@Composable
fun DiagnosticsScreen(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(
                    stringResource(R.string.phase0_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.phase0_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section(stringResource(R.string.section_rootfs)) {
                InstallSection(
                    name = state.distro.displayName,
                    sizeBytes = state.distro.sizeBytes,
                    state = state.rootfs,
                    onInstall = viewModel::install,
                    onCancel = viewModel::cancelInstall,
                    reinstallWarning = stringResource(R.string.rootfs_reinstall_text),
                )
            }

            Section(stringResource(R.string.section_container)) {
                if (state.rootfs == InstallState.Installed) {
                    Button(onClick = viewModel::runUname, enabled = !state.running) {
                        Text(stringResource(R.string.action_run))
                    }
                    AnimatedVisibility(visible = state.running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                    state.guest?.let {
                        Spacer(Modifier.padding(top = 12.dp))
                        ResultText(it, showExitCode = true)
                    }
                } else {
                    Text(
                        stringResource(R.string.container_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.rootfs == InstallState.Installed) {
                Section(stringResource(R.string.section_desktop)) {
                    DesktopSection(state, onInstall = viewModel::installDesktop)
                }
                Section(stringResource(R.string.section_gpu)) {
                    ComponentSection(
                        Component.MESA, stringResource(R.string.gpu_mesa, Component.MESA.version), state, viewModel,
                    )
                }
                Section(stringResource(R.string.plasma_component)) {
                    ComponentSection(
                        Component.PLASMA, stringResource(R.string.plasma_component_version, Component.PLASMA.version), state, viewModel,
                    )
                }
            }

            Section(stringResource(R.string.section_display)) {
                Mono(state.display ?: stringResource(R.string.status_checking))
                if (state.rootfs == InstallState.Installed) {
                    val context = LocalContext.current
                    ActionRow {
                        FilledTonalButton(onClick = {
                            context.startActivity(Intent(context, BufferSpikeActivity::class.java))
                        }) { Text(stringResource(R.string.action_buffer_spike)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComponentSection(component: Component, name: String, state: AppState, viewModel: AppViewModel) {
    val install = state.components[component.id] ?: InstallState.Checking
    if (component.sha256.isEmpty()) {
        Status(stringResource(R.string.component_unreleased, name))
    } else {
        InstallSection(
            name = name,
            sizeBytes = component.sizeBytes,
            state = install,
            onInstall = { viewModel.installComponent(component) },
            onCancel = { viewModel.cancelComponent(component) },
        )
    }
    state.componentVersions[component.id]?.takeIf { install != InstallState.Installed }?.let {
        Spacer(Modifier.padding(top = 8.dp))
        Status(stringResource(R.string.component_installed_version, it))
    }
}

@Composable
private fun InstallSection(
    name: String,
    sizeBytes: Long,
    state: InstallState,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    reinstallWarning: String? = null, // asks before reinstalling, which deletes what is installed
) {
    val context = LocalContext.current
    var confirming by remember { mutableStateOf(false) }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.padding(top = 4.dp))

    when (state) {
        InstallState.Checking -> Status(stringResource(R.string.status_checking))
        InstallState.NotInstalled -> {
            Status(stringResource(R.string.status_not_installed, size(sizeBytes)))
            ActionRow { Button(onClick = onInstall) { Text(stringResource(R.string.action_install)) } }
        }
        is InstallState.Installing -> {
            val progress = state.progress
            val (label, fraction) = when (progress) {
                is InstallProgress.Downloading -> stringResource(
                    R.string.status_downloading, size(progress.bytes), size(progress.total),
                ) to progress.bytes.toFloat() / progress.total
                is InstallProgress.Extracting -> {
                    val percent = (progress.bytes * 100 / progress.total.coerceAtLeast(1)).toInt()
                    stringResource(R.string.status_extracting, percent) to percent / 100f
                }
                is InstallProgress.Verifying, null -> stringResource(R.string.status_verifying) to null
            }
            Status(label)
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            ActionRow { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } }
        }
        InstallState.Installed -> {
            Status(stringResource(R.string.status_installed))
            ActionRow {
                FilledTonalButton(onClick = { if (reinstallWarning != null) confirming = true else onInstall() }) {
                    Text(stringResource(R.string.action_reinstall))
                }
            }
            if (confirming && reinstallWarning != null) {
                AlertDialog(
                    onDismissRequest = { confirming = false },
                    title = { Text(stringResource(R.string.rootfs_reinstall_title, name)) },
                    text = { Text(reinstallWarning) },
                    confirmButton = {
                        TextButton(onClick = { confirming = false; onInstall() }) {
                            Text(stringResource(R.string.action_reinstall), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    // The safe choice is the one under a stray tap's thumb.
                    dismissButton = { Button(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) } },
                )
            }
        }
        is InstallState.Failed -> {
            Status(stringResource(R.string.status_failed, state.message), error = true)
            ActionRow { Button(onClick = onInstall) { Text(stringResource(R.string.action_install)) } }
        }
    }
}

/** Shared storage for the guest: needs "all files access", granted in Android's settings. */
@Composable
private fun StorageAccess() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleResumeEffect(Unit) {
        granted = Environment.isExternalStorageManager()
        onPauseOrDispose {}
    }
    Spacer(Modifier.padding(top = 12.dp))
    Status(stringResource(if (granted) R.string.storage_granted else R.string.storage_denied))
    AnimatedVisibility(visible = !granted) {
        ActionRow {
            FilledTonalButton(onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            }) { Text(stringResource(R.string.action_allow_storage)) }
        }
    }
}

@Composable
private fun DesktopSection(state: AppState, onInstall: () -> Unit) {
    val context = LocalContext.current
    when {
        state.desktopInstalling -> {
            Status(stringResource(R.string.desktop_installing))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        state.desktopInstalled -> {
            Status(
                stringResource(if (state.desktopUpToDate) R.string.desktop_installed else R.string.desktop_update),
            )
            ActionRow {
                Button(onClick = { context.startActivity(Intent(context, DesktopActivity::class.java)) }) { Text(stringResource(R.string.action_open_desktop)) }
                if (!state.desktopUpToDate) {
                    FilledTonalButton(onClick = onInstall) { Text(stringResource(R.string.action_update_desktop)) }
                }
            }
            StorageAccess()
        }
        else -> {
            Status(stringResource(R.string.desktop_not_installed))
            ActionRow { Button(onClick = onInstall) { Text(stringResource(R.string.action_install_desktop)) } }
        }
    }
    if (state.desktopLog.isNotEmpty()) {
        Spacer(Modifier.padding(top = 12.dp))
        Mono(state.desktopLog)
    }
}

/**
 * Desktop size as a fraction of the screen, remembered across launches.
 * Applied when the desktop opens, also to a running one.
 */

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .animateContentSize()
            .padding(20.dp),
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.padding(top = 10.dp))
        content()
    }
}

@Composable
private fun Status(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun ResultText(result: CommandResult?, showExitCode: Boolean = false) {
    if (result == null) {
        Status(stringResource(R.string.status_checking))
        return
    }
    Mono(result.output.ifEmpty { "—" })
    if (showExitCode || result.exitCode != 0) {
        Spacer(Modifier.padding(top = 6.dp))
        Status(stringResource(R.string.exit_code, result.exitCode), error = result.exitCode != 0)
    }
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
