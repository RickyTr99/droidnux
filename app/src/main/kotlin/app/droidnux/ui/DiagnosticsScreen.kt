package app.droidnux.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.droidnux.R
import app.droidnux.desktop.SessionLog
import app.droidnux.spike.BufferSpikeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings › Diagnostics: the log to send, a command in the container, the display core, the desktop's install log. */
@Composable
fun DiagnosticsPage(state: AppState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val installed = state.rootfs == InstallState.Installed
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    InfoRow(stringResource(R.string.diagnostics_log), text = stringResource(R.string.diagnostics_log_text)) {
        FilledTonalButton(
            onClick = {
                sharing = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { SessionLog.share(context, state.display) } }
                        .onSuccess { context.startActivity(it) }
                    sharing = false
                }
            },
            enabled = !sharing,
        ) { Text(stringResource(R.string.action_share)) }
    }
    InfoRow(
        stringResource(R.string.section_container),
        text = if (installed) null else stringResource(R.string.container_hint),
        action = if (installed) {
            { FilledTonalButton(onClick = viewModel::runUname, enabled = !state.running) { Text(stringResource(R.string.action_run)) } }
        } else {
            null
        },
    )
    AnimatedVisibility(visible = state.running) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    state.guest?.let {
        Mono(it.output.ifEmpty { "—" })
        Text(
            stringResource(R.string.exit_code, it.exitCode),
            fontSize = 13.sp,
            color = if (it.exitCode != 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }

    SectionHeader(stringResource(R.string.section_display), Modifier.padding(top = 20.dp, bottom = 4.dp))
    Mono(state.display ?: stringResource(R.string.status_checking))
    if (installed) {
        InfoRow(stringResource(R.string.action_buffer_spike), text = stringResource(R.string.diagnostics_spike_text)) {
            FilledTonalButton(onClick = { context.startActivity(Intent(context, BufferSpikeActivity::class.java)) }) {
                Text(stringResource(R.string.store_open))
            }
        }
    }

    if (state.desktopLog.isNotEmpty()) {
        SectionHeader(stringResource(R.string.diagnostics_desktop_log), Modifier.padding(top = 20.dp, bottom = 4.dp))
        Mono(state.desktopLog)
    }
}

/** Command output and system strings: monospaced, scrolling sideways instead of wrapping. */
@Composable
private fun Mono(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RowShape)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
