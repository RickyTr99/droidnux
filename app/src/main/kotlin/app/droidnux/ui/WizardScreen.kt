package app.droidnux.ui

import android.content.Intent
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.droidnux.R
import app.droidnux.container.Component
import app.droidnux.container.InstallProgress
import app.droidnux.desktop.DesktopActivity
import app.droidnux.desktop.DesktopSettings

/** First run: what to install, the Android settings to check, then the installation. */
@Composable
fun WizardScreen(state: AppState, viewModel: AppViewModel) {
    var step by rememberSaveable { mutableStateOf(0) }
    // An installation already under way (the activity was recreated) shows its progress.
    val current = if (state.setup != null) 2 else step

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AnimatedContent(current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "step") { page ->
            when (page) {
                0 -> WizardPage(
                    title = stringResource(R.string.wizard_welcome_title),
                    text = stringResource(R.string.wizard_welcome_text),
                    aside = { SpaceSummary() },
                    footer = {
                        StepDots(0, 3)
                        Button(onClick = { step = 1 }, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.action_next)) }
                    },
                ) { ChoicePage() }
                1 -> WizardPage(
                    title = stringResource(R.string.wizard_checks_title),
                    text = stringResource(R.string.wizard_checks_text),
                    footer = {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = { step = 0 }, modifier = Modifier.height(48.dp)) {
                                Text(stringResource(R.string.action_back))
                            }
                            StepDots(1, 3)
                        }
                        Button(onClick = { viewModel.runSetup() }, modifier = Modifier.height(48.dp)) {
                            Text(stringResource(R.string.action_install))
                        }
                    },
                ) { SystemChecks(state.gpu) }
                else -> InstallPage(state, viewModel)
            }
        }
    }
}

/** A step: title and text on the left (on top on narrow screens), [content] beside them, [footer] below. */
@Composable
private fun WizardPage(
    title: String,
    text: String,
    aside: @Composable () -> Unit = {},
    footer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        val wide = maxWidth > 840.dp
        Column(Modifier.fillMaxSize()) {
            val intro = @Composable {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(title, fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text(text, fontSize = 17.sp, lineHeight = 25.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    aside()
                }
            }
            if (wide) {
                Row(Modifier.weight(1f).padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                    Box(Modifier.width(360.dp)) { intro() }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    intro()
                    content()
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) { footer() }
        }
    }
}

@Composable
private fun SpaceSummary() {
    val context = LocalContext.current
    val free = remember { StatFs(context.filesDir.path).availableBytes }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryLine(stringResource(R.string.wizard_space_needed), stringResource(R.string.wizard_space_needed_value))
        SummaryLine(stringResource(R.string.wizard_space_free), Formatter.formatShortFileSize(context, free))
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ChoicePage() {
    val context = LocalContext.current
    var layout by remember { mutableStateOf(DesktopSettings.load(context).layout) }
    var choosingLayout by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(stringResource(R.string.wizard_distro))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceCard(stringResource(R.string.distro_debian), stringResource(R.string.distro_debian_text), Icons.Debian, Modifier.weight(1f))
                ChoiceCard(stringResource(R.string.distro_ubuntu), stringResource(R.string.coming_soon), null, Modifier.weight(1f))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(stringResource(R.string.wizard_desktop))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceCard(stringResource(R.string.desktop_xfce), stringResource(R.string.desktop_xfce_text), Icons.Desktop, Modifier.weight(1f))
                ChoiceCard(stringResource(R.string.desktop_plasma), stringResource(R.string.coming_soon), null, Modifier.weight(1f))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(stringResource(R.string.settings_keyboard))
            InfoRow(stringResource(R.string.settings_layout), onClick = { choosingLayout = true }) {
                Text(layoutName(layout), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            }
        }
    }
    if (choosingLayout) {
        LayoutDialog(layout, onDismiss = { choosingLayout = false }) { chosen ->
            layout = chosen
            DesktopSettings.update(context) { it.copy(layout = chosen) }
            choosingLayout = false
        }
    }
}

/** A distro or desktop: the available one is chosen, the others are coming ([icon] null). */
@Composable
private fun ChoiceCard(title: String, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, modifier: Modifier) {
    val available = icon != null
    Row(
        modifier
            .clip(CardShape)
            .background(if (available) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                .background(if (available) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = color)
            Text(text, fontSize = 14.sp, color = if (available) MaterialTheme.colorScheme.onSurfaceVariant else color)
        }
        if (available) Icon(Icons.Selected, stringResource(R.string.chosen), tint = MaterialTheme.colorScheme.primary)
    }
}

/** Name of an XKB layout for the user; "" is the one of Android's language. */
@Composable
fun layoutName(layout: String): String {
    val names = mapOf(
        "us" to java.util.Locale.US, "gb" to java.util.Locale.UK, "it" to java.util.Locale.ITALY,
        "de" to java.util.Locale.GERMANY, "fr" to java.util.Locale.FRANCE,
        "es" to java.util.Locale.Builder().setLanguage("es").setRegion("ES").build(),
        "pt" to java.util.Locale.Builder().setLanguage("pt").setRegion("PT").build(),
    )
    return if (layout.isEmpty()) {
        stringResource(R.string.layout_system, names[DesktopSettings().xkbLayout()]?.displayName.orEmpty())
    } else {
        names[layout]?.displayName ?: layout
    }
}

@Composable
fun LayoutDialog(current: String, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_layout)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (listOf("") + DesktopSettings.LAYOUTS).forEach { layout ->
                    InfoRow(layoutName(layout), selected = layout == current, onClick = { onChoose(layout) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun InstallPage(state: AppState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val setup = state.setup
    var showLog by remember { mutableStateOf(false) }
    val step = setup?.step ?: SetupStep.ROOTFS
    val mesa = state.components[Component.MESA.id]
    val theme = state.components[Component.THEME.id]
    val total by animateFloatAsState(overallProgress(state), label = "total")

    WizardPage(
        title = stringResource(if (setup?.done == true) R.string.wizard_done_title else R.string.wizard_install_title),
        text = stringResource(if (setup?.done == true) R.string.wizard_done_text else R.string.wizard_install_text),
        aside = {
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryLine(stringResource(R.string.wizard_total), "${(total * 100).toInt()}%")
                LinearProgressIndicator(progress = { total }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
            }
        },
        footer = {
            StepDots(2, 3)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    setup?.done == true -> Button(onClick = {
                        viewModel.finishSetup()
                        context.startActivity(Intent(context, DesktopActivity::class.java))
                    }, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.action_open_desktop)) }
                    setup?.error != null -> Button(onClick = viewModel::runSetup, modifier = Modifier.height(48.dp)) {
                        Text(stringResource(R.string.action_retry))
                    }
                    else -> FilledTonalButton(onClick = viewModel::cancelSetup, modifier = Modifier.height(48.dp)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StepRow(
                stringResource(R.string.distro_debian),
                stepText(state.rootfs, stringResource(R.string.install_rootfs_text)),
                stepStatus(state.rootfs),
                (state.rootfs as? InstallState.Installing)?.progress?.fraction(),
            )
            val (done, all) = state.desktopPackages
            StepRow(
                stringResource(R.string.desktop_xfce),
                when {
                    state.desktopInstalling && all > 0 -> stringResource(R.string.install_packages, done, all)
                    state.desktopInstalling -> stringResource(R.string.status_preparing)
                    else -> stringResource(R.string.install_desktop_text)
                },
                when {
                    state.desktopInstalling -> StepStatus.RUNNING
                    state.desktopInstalled && state.desktopUpToDate && step > SetupStep.DESKTOP -> StepStatus.DONE
                    step == SetupStep.DESKTOP && setup?.error != null -> StepStatus.FAILED
                    else -> StepStatus.WAITING
                },
                if (state.desktopInstalling && all > 0) done.toFloat() / all else null,
            )
            StepRow(
                stringResource(R.string.install_gpu),
                stepText(mesa, stringResource(R.string.install_gpu_text)),
                stepStatus(mesa),
                (mesa as? InstallState.Installing)?.progress?.fraction(),
            )
            StepRow(
                stringResource(R.string.section_theme),
                stepText(theme, stringResource(R.string.install_theme_text)),
                stepStatus(theme),
                (theme as? InstallState.Installing)?.progress?.fraction(),
            )
            setup?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
            TextButton(onClick = { showLog = !showLog }) {
                Text(stringResource(if (showLog) R.string.install_hide_log else R.string.install_show_log))
            }
            AnimatedVisibility(showLog && state.desktopLog.isNotEmpty()) {
                Text(
                    state.desktopLog,
                    modifier = Modifier.fillMaxWidth().clip(RowShape).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class StepStatus { WAITING, RUNNING, DONE, FAILED }

private fun stepStatus(state: InstallState?) = when (state) {
    InstallState.Installed -> StepStatus.DONE
    is InstallState.Installing -> StepStatus.RUNNING
    is InstallState.Failed -> StepStatus.FAILED
    else -> StepStatus.WAITING
}

@Composable
private fun stepText(state: InstallState?, idle: String): String = when (val progress = (state as? InstallState.Installing)?.progress) {
    is InstallProgress.Downloading -> stringResource(
        R.string.status_downloading,
        Formatter.formatShortFileSize(LocalContext.current, progress.bytes),
        Formatter.formatShortFileSize(LocalContext.current, progress.total),
    )
    is InstallProgress.Extracting -> stringResource(R.string.status_extracting, ((progress.fraction() ?: 0f) * 100).toInt())
    else -> (state as? InstallState.Failed)?.message ?: idle
}

private fun InstallProgress.fraction(): Float? = when (this) {
    is InstallProgress.Downloading -> bytes.toFloat() / total.coerceAtLeast(1)
    is InstallProgress.Extracting -> bytes.toFloat() / total.coerceAtLeast(1)
    InstallProgress.Verifying -> null
}

/** Rough share of each step in the whole installation (the desktop packages take most of it). */
private fun overallProgress(state: AppState): Float {
    fun part(install: InstallState?) = when (install) {
        InstallState.Installed -> 1f
        is InstallState.Installing -> install.progress?.fraction() ?: 0f
        else -> 0f
    }
    val (done, all) = state.desktopPackages
    val desktop = when {
        state.desktopInstalled && state.desktopUpToDate && !state.desktopInstalling -> 1f
        all > 0 -> done.toFloat() / all
        else -> 0f
    }
    return 0.15f * part(state.rootfs) + 0.65f * desktop +
        0.05f * part(state.components[Component.MESA.id]) + 0.15f * part(state.components[Component.THEME.id])
}

@Composable
private fun StepRow(title: String, text: String, status: StepStatus, fraction: Float?) {
    val running = status == StepStatus.RUNNING
    Column(
        Modifier.fillMaxWidth().clip(RowShape)
            .background(if (running) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint) = when (status) {
                StepStatus.DONE -> Icons.Check to MaterialTheme.colorScheme.primary
                StepStatus.FAILED -> Icons.Warning to MaterialTheme.colorScheme.error
                StepStatus.RUNNING -> Icons.Pending to MaterialTheme.colorScheme.onSurface
                StepStatus.WAITING -> Icons.Pending to MaterialTheme.colorScheme.outline
            }
            Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (fraction != null) Text("${(fraction * 100).toInt()}%", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        if (running) {
            Spacer(Modifier.size(0.dp))
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(start = 42.dp))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 42.dp))
            }
        }
    }
}
