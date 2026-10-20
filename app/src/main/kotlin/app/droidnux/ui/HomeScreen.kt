package app.droidnux.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.droidnux.MainActivity
import app.droidnux.R
import app.droidnux.container.Component
import app.droidnux.container.DesktopEnvironment.LinuxApp
import app.droidnux.desktop.DesktopActivity
import app.droidnux.desktop.DesktopSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private enum class Tab(val label: Int, val icon: ImageVector) {
    DESKTOP(R.string.tab_desktop, Icons.Desktop),
    APPS(R.string.tab_apps, Icons.Apps),
    SETTINGS(R.string.tab_settings, Icons.Settings),
    SYSTEM(R.string.tab_system, Icons.System),
}

/** Apps opened from the desktop tab when installed, in this order. */
private val FAVORITES = listOf("firefox-esr", "thunar", "mpv", "xfce4-terminal")
private const val TERMINAL = "xfce4-terminal"

@Composable
fun HomeScreen(state: AppState, viewModel: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.DESKTOP) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (state.apps == null) viewModel.loadApps() }

    if (diagnostics) {
        BackHandler { diagnostics = false }
        DiagnosticsScreen(viewModel)
        return
    }
    BackHandler(enabled = tab != Tab.DESKTOP) { tab = Tab.DESKTOP }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { page ->
            // Room for the bar floating over the bottom of every page.
            val padding = PaddingValues(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 120.dp)
            when (page) {
                Tab.DESKTOP -> DesktopTab(state, viewModel, padding, onAllApps = { tab = Tab.APPS }, onSystem = { tab = Tab.SYSTEM })
                Tab.APPS -> AppsTab(state, viewModel, padding)
                Tab.SETTINGS -> SettingsTab(padding)
                Tab.SYSTEM -> SystemTab(state, viewModel, padding, onDiagnostics = { diagnostics = true })
            }
        }
        BottomBar(tab, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) { tab = it }
    }
}

/** One UI style: a floating rounded bar, the current tab in a pill with its name in bold. */
@Composable
private fun BottomBar(current: Tab, modifier: Modifier, onSelect: (Tab) -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(36.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab.entries.forEach { tab ->
            val selected = tab == current
            val background by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                label = "tab",
            )
            Row(
                Modifier.height(60.dp).clip(RoundedCornerShape(30.dp)).background(background)
                    .combinedClickable(onClick = { onSelect(tab) }).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(tab.icon, null, tint = color, modifier = Modifier.size(22.dp))
                Text(
                    stringResource(tab.label),
                    color = color,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

// -- Desktop -----------------------------------------------------------------

@Composable
private fun DesktopTab(
    state: AppState,
    viewModel: AppViewModel,
    padding: PaddingValues,
    onAllApps: () -> Unit,
    onSystem: () -> Unit,
) {
    val context = LocalContext.current
    val missing = Component.ALL.filter { it.sha256.isNotEmpty() && state.components[it.id] == InstallState.NotInstalled }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        BoxWithConstraints {
            val wide = maxWidth > 760.dp
            val hero = @Composable { modifier: Modifier ->
                Box(
                    modifier.height(300.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Debian, null, tint = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(140.dp))
                }
            }
            val actions = @Composable { modifier: Modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.home_title), fontSize = 28.sp, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(if (state.sessionRunning) R.string.home_running else R.string.home_ready),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { openDesktop(context) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Play, null, modifier = Modifier.size(22.dp))
                        Text(
                            stringResource(if (state.sessionRunning) R.string.action_resume_desktop else R.string.action_open_desktop),
                            fontSize = 18.sp,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                    FilledTonalButton(onClick = { launchApp(context, TERMINAL, null) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(stringResource(R.string.action_terminal), fontSize = 16.sp)
                    }
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    hero(Modifier.weight(1f))
                    actions(Modifier.width(300.dp))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    hero(Modifier.fillMaxWidth())
                    actions(Modifier.fillMaxWidth())
                }
            }
        }
        if (!state.desktopUpToDate || missing.isNotEmpty()) {
            InfoRow(
                title = stringResource(R.string.home_update_title),
                text = stringResource(R.string.home_update_text),
                icon = Icons.Info,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = onSystem,
            ) { Icon(Icons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(stringResource(R.string.home_quick))
            val apps = state.apps.orEmpty().associateBy { it.id }
            val favorites = FAVORITES.mapNotNull { apps[it] }.take(3)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                favorites.forEach { app -> AppTile(app, viewModel, Modifier.weight(1f)) }
                Column(
                    Modifier.weight(1f).height(120.dp).clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .combinedClickable(onClick = onAllApps),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Plus, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        stringResource(R.string.home_all_apps),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

private fun openDesktop(context: Context) = context.startActivity(Intent(context, DesktopActivity::class.java))

/** Starts a Linux app in the desktop (starting the desktop too when it is not running). */
fun launchApp(context: Context, id: String, app: LinuxApp?) {
    context.startActivity(Intent(context, DesktopActivity::class.java).putExtra(DesktopActivity.EXTRA_LAUNCH, id))
    // Recently used apps in the launcher's long-press menu of DroidNux's icon.
    if (app != null) ShortcutManagerCompat.pushDynamicShortcut(context, shortcut(context, app, null))
}

private fun shortcut(context: Context, app: LinuxApp, icon: Bitmap?): ShortcutInfoCompat =
    ShortcutInfoCompat.Builder(context, "app-${app.id}")
        .setShortLabel(app.name)
        .setIcon(icon?.let { IconCompat.createWithBitmap(it) } ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_LAUNCH).putExtra(MainActivity.EXTRA_APP, app.id),
        )
        .build()

// -- Apps --------------------------------------------------------------------

@Composable
private fun AppsTab(state: AppState, viewModel: AppViewModel, padding: PaddingValues) {
    val apps = state.apps
    if (apps == null) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            LinearProgressIndicator(Modifier.width(200.dp))
        }
        return
    }
    // XFCE's "preferred application" launchers repeat apps already listed.
    val visible = apps.filter { "X-Xfce-Toplevel" !in it.categories }
    val (settings, programs) = visible.partition { "Settings" in it.categories }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(132.dp),
        contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.apps_programs)) }
        items(programs, key = { it.id }) { AppTile(it, viewModel) }
        if (settings.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(stringResource(R.string.apps_settings), Modifier.padding(top = 16.dp))
            }
            items(settings, key = { it.id }) { AppTile(it, viewModel) }
        }
    }
}

/** An app: tap starts it in the desktop, long press offers a shortcut on Android's home screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(app: LinuxApp, viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState<Bitmap?>(null, app.id) {
        value = withContext(Dispatchers.IO) { viewModel.iconFile(app.id)?.let { BitmapFactory.decodeFile(it.path) } }
    }
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().height(120.dp).clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
                .combinedClickable(onClick = { launchApp(context, app.id, app) }, onLongClick = { menu = true })
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val bitmap = icon
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), null, Modifier.size(48.dp))
            } else {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
            }
            Text(
                app.name,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.apps_pin)) },
                enabled = ShortcutManagerCompat.isRequestPinShortcutSupported(context),
                onClick = {
                    menu = false
                    ShortcutManagerCompat.requestPinShortcut(context, shortcut(context, app, icon), null)
                },
            )
        }
    }
}

// -- Settings ----------------------------------------------------------------

@Composable
private fun SettingsTab(padding: PaddingValues) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(DesktopSettings.load(context)) }
    fun save(change: (DesktopSettings) -> DesktopSettings) {
        settings = DesktopSettings.update(context, change)
    }
    var choosingLayout by remember { mutableStateOf(false) }
    val screen = remember { context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(stringResource(R.string.settings_screen), Modifier.padding(bottom = 4.dp))
            var scale by remember { mutableFloatStateOf(settings.scale) }
            SliderRow(
                stringResource(R.string.menu_resolution),
                "${(screen.width() * scale).toInt() and 1.inv()} × ${(screen.height() * scale).toInt() and 1.inv()}",
                scale, DesktopActivity.MIN_SCALE..DesktopActivity.MAX_SCALE,
                onChange = { scale = it }, onDone = { save { it.copy(scale = scale) } },
            )
            var uiScale by remember { mutableFloatStateOf(settings.uiScale) }
            SliderRow(
                stringResource(R.string.menu_ui_scale),
                "${(uiScale * 100).roundToInt()}%",
                uiScale, DesktopSettings.MIN_UI_SCALE..DesktopSettings.MAX_UI_SCALE,
                onChange = { uiScale = it }, onDone = { save { it.copy(uiScale = uiScale) } },
            )
            SectionHeader(stringResource(R.string.settings_input), Modifier.padding(top = 20.dp, bottom = 4.dp))
            InfoRow(stringResource(R.string.settings_touch)) {
                SingleChoiceSegmentedButtonRow {
                    listOf(false to R.string.menu_mode_touch, true to R.string.menu_mode_trackpad).forEachIndexed { index, (trackpad, label) ->
                        SegmentedButton(
                            selected = settings.trackpad == trackpad,
                            onClick = { save { it.copy(trackpad = trackpad) } },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(stringResource(label)) }
                    }
                }
            }
            InfoRow(stringResource(R.string.settings_layout), onClick = { choosingLayout = true }) {
                Text(layoutName(settings.layout), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            }
            Text(
                stringResource(R.string.settings_next_session),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
    if (choosingLayout) {
        LayoutDialog(settings.layout, onDismiss = { choosingLayout = false }) { layout ->
            save { it.copy(layout = layout) }
            choosingLayout = false
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RowShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(value, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = current,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            // 5% steps.
            steps = ((range.endInclusive - range.start) * 20).roundToInt() - 1,
        )
    }
}

// -- System ------------------------------------------------------------------

@Composable
private fun SystemTab(state: AppState, viewModel: AppViewModel, padding: PaddingValues, onDiagnostics: () -> Unit) {
    var confirmReinstall by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(stringResource(R.string.system_installed), Modifier.padding(bottom = 4.dp))
            InfoRow(
                stringResource(R.string.distro_debian),
                text = stringResource(R.string.install_rootfs_text),
                icon = Icons.Check,
                iconTint = MaterialTheme.colorScheme.primary,
            ) {
                TextButton(onClick = { confirmReinstall = true }) { Text(stringResource(R.string.action_reinstall)) }
            }
            InfoRow(
                stringResource(R.string.desktop_xfce),
                text = stringResource(
                    when {
                        state.desktopInstalling -> R.string.desktop_installing
                        state.desktopUpToDate -> R.string.system_desktop_ok
                        else -> R.string.system_desktop_update
                    },
                ),
                icon = if (state.desktopUpToDate) Icons.Check else Icons.Info,
                iconTint = MaterialTheme.colorScheme.primary,
            ) {
                if (!state.desktopUpToDate && !state.desktopInstalling) {
                    FilledTonalButton(onClick = viewModel::installDesktop) { Text(stringResource(R.string.action_update_desktop)) }
                }
            }
            if (state.desktopInstalling) {
                val (done, all) = state.desktopPackages
                if (all > 0) {
                    LinearProgressIndicator(progress = { done.toFloat() / all }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            ComponentRow(Component.MESA, stringResource(R.string.install_gpu), state, viewModel)
            ComponentRow(Component.THEME, stringResource(R.string.section_theme), state, viewModel)

            SectionHeader(stringResource(R.string.system_checks), Modifier.padding(top = 20.dp, bottom = 4.dp))
            SystemChecks(state.gpu)

            SectionHeader(stringResource(R.string.system_advanced), Modifier.padding(top = 20.dp, bottom = 4.dp))
            InfoRow(stringResource(R.string.system_diagnostics), onClick = onDiagnostics) {
                Icon(Icons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (confirmReinstall) {
        AlertDialog(
            onDismissRequest = { confirmReinstall = false },
            title = { Text(stringResource(R.string.rootfs_reinstall_title, stringResource(R.string.distro_debian))) },
            text = { Text(stringResource(R.string.rootfs_reinstall_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReinstall = false
                    viewModel.install()
                }) { Text(stringResource(R.string.action_reinstall), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReinstall = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ComponentRow(component: Component, name: String, state: AppState, viewModel: AppViewModel) {
    val install = state.components[component.id] ?: InstallState.Checking
    val installed = install == InstallState.Installed
    InfoRow(
        name,
        text = when (install) {
            is InstallState.Installing -> stringResource(R.string.status_installing)
            is InstallState.Failed -> install.message
            InstallState.Installed -> component.version
            else -> state.componentVersions[component.id]?.let { stringResource(R.string.system_component_update, it, component.version) }
                ?: stringResource(R.string.system_component_missing)
        },
        icon = if (installed) Icons.Check else Icons.Info,
        iconTint = MaterialTheme.colorScheme.primary,
    ) {
        when {
            component.sha256.isEmpty() -> Unit
            install is InstallState.Installing -> TextButton(onClick = { viewModel.cancelComponent(component) }) {
                Text(stringResource(R.string.action_cancel))
            }
            !installed -> FilledTonalButton(onClick = { viewModel.installComponent(component) }) { Text(stringResource(R.string.action_install)) }
        }
    }
}
