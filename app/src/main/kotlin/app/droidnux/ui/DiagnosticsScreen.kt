package app.droidnux.ui

import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.droidnux.R
import app.droidnux.container.CommandResult
import app.droidnux.container.InstallProgress
import app.droidnux.desktop.DesktopActivity
import app.droidnux.display.X11Server
import app.droidnux.spike.BufferSpikeActivity

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel = viewModel()) {
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
                Text(stringResource(R.string.phase0_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.phase0_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section(stringResource(R.string.section_proot)) {
                ResultText(state.proot)
            }

            Section(stringResource(R.string.section_rootfs)) {
                RootfsSection(state, onInstall = viewModel::install, onCancel = viewModel::cancelInstall)
            }

            Section(stringResource(R.string.section_container)) {
                if (state.rootfs == RootfsState.Installed) {
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

            if (state.rootfs == RootfsState.Installed) {
                Section(stringResource(R.string.section_desktop)) {
                    DesktopSection(state, onInstall = viewModel::installDesktop)
                }
            }

            Section(stringResource(R.string.section_display)) {
                Mono(state.display ?: stringResource(R.string.status_checking))
                if (state.rootfs == RootfsState.Installed) {
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
private fun RootfsSection(state: DiagnosticsState, onInstall: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    Text(state.distro.displayName, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.padding(top = 4.dp))

    when (val rootfs = state.rootfs) {
        RootfsState.Checking -> Status(stringResource(R.string.status_checking))
        RootfsState.NotInstalled -> {
            Status(stringResource(R.string.status_not_installed, size(state.distro.sizeBytes)))
            ActionRow { Button(onClick = onInstall) { Text(stringResource(R.string.action_install)) } }
        }
        is RootfsState.Installing -> {
            val progress = rootfs.progress
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
        RootfsState.Installed -> {
            Status(stringResource(R.string.status_installed))
            ActionRow { FilledTonalButton(onClick = onInstall) { Text(stringResource(R.string.action_reinstall)) } }
        }
        is RootfsState.Failed -> {
            Status(stringResource(R.string.status_failed, rootfs.message), error = true)
            ActionRow { Button(onClick = onInstall) { Text(stringResource(R.string.action_install)) } }
        }
    }
}

@Composable
private fun DesktopSection(state: DiagnosticsState, onInstall: () -> Unit) {
    val context = LocalContext.current
    when {
        !X11Server.available -> Status(stringResource(R.string.desktop_no_x11), error = true)
        state.desktopInstalling -> {
            Status(stringResource(R.string.desktop_installing))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        state.desktopInstalled -> {
            Status(stringResource(R.string.desktop_installed))
            ActionRow {
                Button(onClick = {
                    context.startActivity(Intent(context, DesktopActivity::class.java))
                }) { Text(stringResource(R.string.action_open_desktop)) }
            }
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
