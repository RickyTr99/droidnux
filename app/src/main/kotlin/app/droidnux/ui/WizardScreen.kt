package app.droidnux.ui

import android.content.Intent
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.droidnux.desktop.Account
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    var apps by rememberSaveable { mutableStateOf(StoreCatalog.WIZARD_DEFAULT) }
    // Choice, account (Plasma runs as a Linux user), apps, checks, install.
    val pages = 5
    // An installation already under way (the activity was recreated) shows its progress.
    val current = if (state.setup != null) pages - 1 else step

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
                        StepDots(0, pages)
                        Button(onClick = { step = 1 }, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.action_next)) }
                    },
                ) { ChoicePage() }
                pages - 2 -> WizardPage(
                    title = stringResource(R.string.wizard_checks_title),
                    text = stringResource(R.string.wizard_checks_text),
                    footer = {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = { step = pages - 3 }, modifier = Modifier.height(48.dp)) {
                                Text(stringResource(R.string.action_back))
                            }
                            StepDots(pages - 2, pages)
                        }
                        Button(onClick = { viewModel.runSetup(apps) }, modifier = Modifier.height(48.dp)) {
                            Text(stringResource(R.string.action_install))
                        }
                    },
                ) { SystemChecks(state.gpu) }
                pages - 1 -> InstallPage(state, viewModel, pages)
                2 -> AppsPage(state, viewModel, apps, { apps = it }, back = { step = 1 }, pages = pages) { step = 3 }
                else -> AccountPage(back = { step = 0 }, dot = 1, pages = pages) { step = 2 }
            }
        }
    }
}

/**
 * The desktop's Linux account: a name and a password, saved
 * with [Account] before [onDone]. A wizard step with [back] and its [dot] of
 * [pages], or on its own (installed before accounts existed) without them.
 */
@Composable
fun AccountPage(back: (() -> Unit)?, dot: Int, pages: Int, onDone: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(DesktopSettings.load(context).user) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val nameOk = Account.isValidName(name)
    val passwordOk = Account.isValidPassword(password)
    val done = nameOk && passwordOk && password == repeat

    WizardPage(
        title = stringResource(R.string.wizard_account_title),
        text = stringResource(R.string.wizard_account_text),
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (back != null) {
                    FilledTonalButton(onClick = back, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.action_back)) }
                    StepDots(dot, pages)
                }
            }
            Button(
                onClick = {
                    Account.savePassword(context, password)
                    DesktopSettings.update(context) { it.copy(user = name) }
                    onDone()
                },
                enabled = done,
                modifier = Modifier.height(48.dp),
            ) { Text(stringResource(R.string.action_next)) }
        },
    ) {
        Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.trim().lowercase() },
                label = { Text(stringResource(R.string.wizard_account_name)) },
                supportingText = {
                    Text(stringResource(if (name.isEmpty() || nameOk) R.string.wizard_account_name_hint else R.string.wizard_account_name_bad))
                },
                isError = name.isNotEmpty() && !nameOk,
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.wizard_account_password)) },
                singleLine = true,
                visualTransformation = transformation,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.VisibilityOff else Icons.Visibility,
                            stringResource(if (visible) R.string.wizard_account_hide else R.string.wizard_account_show),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = repeat,
                onValueChange = { repeat = it },
                label = { Text(stringResource(R.string.wizard_account_repeat)) },
                supportingText = {
                    if (repeat.isNotEmpty() && repeat != password) Text(stringResource(R.string.wizard_account_mismatch))
                },
                isError = repeat.isNotEmpty() && repeat != password,
                singleLine = true,
                visualTransformation = transformation,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The store's apps to install with the desktop: [chosen] ids, changed with [onChange]. */
@Composable
private fun AppsPage(
    state: AppState,
    viewModel: AppViewModel,
    chosen: List<String>,
    onChange: (List<String>) -> Unit,
    back: () -> Unit,
    pages: Int,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val columns = if (isCompact()) 1 else 2
    LaunchedEffect(Unit) { viewModel.loadStoreIcons() }
    val megabytes = chosen.mapNotNull(StoreCatalog::get).sumOf { it.sizeMb }
    WizardPage(
        title = stringResource(R.string.wizard_apps_title),
        text = stringResource(R.string.wizard_apps_text),
        aside = {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryLine(stringResource(R.string.wizard_apps_selected), chosen.size.toString())
                SummaryLine(stringResource(R.string.wizard_apps_space), Formatter.formatShortFileSize(context, megabytes * 1_000_000L))
            }
        },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = back, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.action_back)) }
                StepDots(2, pages)
            }
            Button(onClick = onDone, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.action_next)) }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((header, apps) in listOf(R.string.wizard_apps_popular to StoreCatalog.WIZARD_POPULAR, R.string.wizard_apps_more to StoreCatalog.WIZARD_MORE)) {
                SectionHeader(stringResource(header), Modifier.padding(top = if (header == R.string.wizard_apps_more) 12.dp else 0.dp))
                apps.chunked(columns).forEach { pair ->
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { app ->
                            val selected = app.id in chosen
                            AppChoice(app, selected, state, viewModel, Modifier.weight(1f).fillMaxHeight()) {
                                onChange(if (selected) chosen - app.id else chosen + app.id)
                            }
                        }
                        if (pair.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppChoice(app: StoreApp, selected: Boolean, state: AppState, viewModel: AppViewModel, modifier: Modifier, onToggle: () -> Unit) {
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "app",
    )
    Row(
        modifier.clip(CardShape).background(background)
            .toggleable(selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The store's icon (as in the Store), its initial on its color until downloaded.
        val icon by produceState(viewModel.cachedIcon("store:${app.icon}"), state.storeIcons) {
            value = withContext(Dispatchers.IO) { viewModel.storeIcon(app.icon) } ?: value
        }
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.size(44.dp))
        } else {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(app.color)), contentAlignment = Alignment.Center) {
                Text(app.name.take(1), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(app.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(app.summary), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Checkbox(checked = selected, onCheckedChange = null)
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
    val compact = isCompact()
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = if (compact) 20.dp else 48.dp, vertical = if (compact) 24.dp else 32.dp)) {
        val wide = maxWidth > 840.dp
        Column(Modifier.fillMaxSize()) {
            val intro = @Composable {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        title,
                        fontSize = if (compact) 28.sp else 36.sp,
                        lineHeight = if (compact) 34.sp else 42.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text,
                        fontSize = if (compact) 15.sp else 17.sp,
                        lineHeight = if (compact) 22.sp else 25.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                Modifier.fillMaxWidth().padding(top = if (compact) 16.dp else 24.dp),
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
    // Side by side, or one above the other on a phone (the names would not fit).
    val pair = @Composable { first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit ->
        if (isCompact()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(stringResource(R.string.wizard_distro))
            pair(
                { ChoiceCard(stringResource(R.string.distro_debian), stringResource(R.string.distro_debian_text), Icons.Debian, it) },
                { ChoiceCard(stringResource(R.string.distro_ubuntu), stringResource(R.string.coming_soon), null, it) },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(stringResource(R.string.wizard_desktop))
            pair(
                { ChoiceCard(stringResource(R.string.desktop_name), stringResource(R.string.desktop_text), Icons.Desktop, it) },
                { ChoiceCard(stringResource(R.string.desktop_gnome), stringResource(R.string.coming_soon), null, it) },
            )
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

/** A new password for the account ([Account.changePassword]), after the current one. */
@Composable
fun PasswordDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current = remember { Account.password(context) }
    var old by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    // A keystore that lost its key (Account.password) has no current password to ask for.
    val done = (current == null || old.isNotEmpty()) && Account.isValidPassword(password) && password == repeat
    val field = @Composable { value: String, onChange: (String) -> Unit, label: Int, error: Int?, last: Boolean ->
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(stringResource(label)) },
            supportingText = error?.let { { Text(stringResource(it)) } },
            isError = error != null,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false, keyboardType = KeyboardType.Password,
                imeAction = if (last) ImeAction.Done else ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_password_change)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_password_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (current != null) {
                    field(old, { old = it; wrong = false }, R.string.settings_password_current,
                        R.string.settings_password_wrong.takeIf { wrong }, false)
                }
                field(password, { password = it }, R.string.settings_password_new, null, false)
                field(repeat, { repeat = it }, R.string.wizard_account_repeat,
                    R.string.wizard_account_mismatch.takeIf { repeat.isNotEmpty() && repeat != password }, true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (current != null && old != current) {
                    wrong = true
                } else {
                    Account.changePassword(context, password)
                    onDismiss()
                }
            }, enabled = done) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun InstallPage(state: AppState, viewModel: AppViewModel, pages: Int) {
    val context = LocalContext.current
    val setup = state.setup
    var showLog by remember { mutableStateOf(false) }
    val step = setup?.step ?: SetupStep.ROOTFS
    val mesa = state.components[Component.MESA.id]
    val plasma = state.components[Component.PLASMA.id]
    val total by animateFloatAsState(if (setup?.done == true) 1f else overallProgress(state), label = "total")

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
            StepDots(pages - 1, pages)
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
                stepDetail(state.rootfs),
            )
            val apt = state.desktopProgress
            StepRow(
                stringResource(R.string.desktop_name),
                when {
                    !state.desktopInstalling -> stringResource(R.string.install_desktop_text)
                    apt.packages == 0 -> stringResource(R.string.status_package_list)
                    apt.downloading -> stringResource(R.string.status_download)
                    else -> stringResource(R.string.status_installing)
                },
                when {
                    state.desktopInstalling -> StepStatus.RUNNING
                    state.desktopInstalled && state.desktopUpToDate && step > SetupStep.DESKTOP -> StepStatus.DONE
                    step == SetupStep.DESKTOP && setup?.error != null -> StepStatus.FAILED
                    else -> StepStatus.WAITING
                },
                if (state.desktopInstalling) apt.fraction() else null,
                when {
                    apt.packages == 0 -> null
                    apt.downloading -> stringResource(
                        R.string.status_bytes,
                        Formatter.formatShortFileSize(context, apt.fetchedBytes),
                        Formatter.formatShortFileSize(context, apt.totalBytes),
                    ).takeIf { apt.totalBytes > 0 }
                    else -> stringResource(R.string.install_packages, apt.setUp, apt.packages)
                },
            )
            StepRow(
                stringResource(R.string.install_gpu),
                stepText(mesa, stringResource(R.string.install_gpu_text)),
                stepStatus(mesa),
                (mesa as? InstallState.Installing)?.progress?.fraction(),
                stepDetail(mesa),
            )
            StepRow(
                stringResource(R.string.plasma_component),
                stepText(plasma, stringResource(R.string.install_plasma_text)),
                stepStatus(plasma),
                (plasma as? InstallState.Installing)?.progress?.fraction(),
                stepDetail(plasma),
            )
            val apps = setup?.apps.orEmpty().mapNotNull(StoreCatalog::get)
            if (apps.isNotEmpty()) {
                val current = apps.indexOfFirst { state.storeJobs[it.id] is StoreJob.Running }
                val failed = apps.filter { state.storeJobs[it.id] is StoreJob.Failed }
                StepRow(
                    stringResource(R.string.install_apps),
                    when {
                        current >= 0 -> stringResource(R.string.install_apps_now, apps[current].name, current + 1, apps.size)
                        failed.isNotEmpty() && setup?.done == true -> stringResource(R.string.install_apps_failed, failed.joinToString { it.name })
                        else -> stringResource(R.string.install_apps_text, apps.size)
                    },
                    when {
                        current >= 0 -> StepStatus.RUNNING
                        setup?.done == true -> if (failed.isEmpty()) StepStatus.DONE else StepStatus.FAILED
                        else -> StepStatus.WAITING
                    },
                    appsProgress(state, apps).takeIf { current >= 0 },
                )
            }
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
private fun stepText(state: InstallState?, idle: String): String = when ((state as? InstallState.Installing)?.progress) {
    is InstallProgress.Downloading -> stringResource(R.string.status_download)
    is InstallProgress.Extracting -> stringResource(R.string.status_extract)
    else -> (state as? InstallState.Failed)?.message ?: idle
}

/** The amount downloaded, under the bar. */
@Composable
private fun stepDetail(state: InstallState?): String? = when (val progress = (state as? InstallState.Installing)?.progress) {
    is InstallProgress.Downloading -> stringResource(
        R.string.status_bytes,
        Formatter.formatShortFileSize(LocalContext.current, progress.bytes),
        Formatter.formatShortFileSize(LocalContext.current, progress.total),
    )
    else -> null
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
    val desktop = when {
        state.desktopInstalled && state.desktopUpToDate && !state.desktopInstalling -> 1f
        else -> state.desktopProgress.fraction() ?: 0f
    }
    val system = 0.15f * part(state.rootfs) + 0.75f * desktop +
        0.08f * part(state.components[Component.MESA.id]) + 0.02f * part(state.components[Component.PLASMA.id])
    val apps = state.setup?.apps.orEmpty().mapNotNull(StoreCatalog::get)
    return if (apps.isEmpty()) system else 0.7f * system + 0.3f * appsProgress(state, apps)
}

/** Share of the chosen [apps] installed: the ones done, and the running one's steps. */
private fun appsProgress(state: AppState, apps: List<StoreApp>): Float = apps.sumOf { app ->
    val job = state.storeJobs[app.id]
    when {
        job is StoreJob.Running && job.total > 0 -> job.done.toDouble() / job.total
        job == null && app.packages.first() in state.packages -> 1.0
        else -> 0.0
    }
}.toFloat() / apps.size

/** A step of the installation; while it runs, a bar with [detail] and the percentage under it. */
@Composable
private fun StepRow(title: String, text: String, status: StepStatus, fraction: Float?, detail: String? = null) {
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
        }
        if (running) {
            Column(Modifier.padding(start = 42.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(detail.orEmpty(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${(fraction * 100).toInt()}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
