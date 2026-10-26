package app.droidnux.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.droidnux.MainActivity
import app.droidnux.R
import app.droidnux.container.Component
import app.droidnux.container.DesktopEnvironment.LinuxApp
import app.droidnux.desktop.DesktopActivity
import app.droidnux.desktop.DesktopKeysService
import app.droidnux.desktop.DesktopSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class Tab(val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    DESKTOP(R.string.tab_desktop, Icons.Desktop, Icons.DesktopFilled),
    APPS(R.string.tab_apps, Icons.Apps, Icons.AppsFilled),
    STORE(R.string.tab_store, Icons.Store, Icons.StoreFilled),
    SETTINGS(R.string.tab_settings, Icons.Settings, Icons.SettingsFilled),
    SYSTEM(R.string.tab_system, Icons.System, Icons.SystemFilled),
}

private const val TERMINAL = "org.kde.konsole"

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
        // Short fades: the page follows the finger at once.
        AnimatedContent(tab, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(90)) }, label = "tab") { page ->
            // Room for the bar floating over the bottom of every page.
            val padding = PaddingValues(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 120.dp)
            when (page) {
                Tab.DESKTOP -> DesktopTab(state, viewModel, padding, onAllApps = { tab = Tab.APPS }, onSystem = { tab = Tab.SYSTEM })
                Tab.APPS -> AppsTab(state, viewModel, padding)
                Tab.STORE -> StoreTab(state, viewModel, padding)
                Tab.SETTINGS -> SettingsTab(padding)
                Tab.SYSTEM -> SystemTab(state, viewModel, padding, onDiagnostics = { diagnostics = true })
            }
        }
        BottomBar(tab, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) { tab = it }
    }
}

/** The selected tab's pill: lit from above, like a raised key. */
private val PillBrush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val center = Offset(size.width / 2, size.height * 0.35f)
        val radius = hypot(size.width / 2, size.height * 0.65f)
        return RadialGradientShader(center, radius, listOf(Color(0xFF4A4C53), Color(0xFF33353A)), listOf(0f, 0.75f))
    }
}
private val TabColor = Color(0xFFA9ABB3)

/**
 * A floating rounded bar: each tab is its icon over its name, the current one on a pill that slides
 * to it. The tab changes on touch down, without waiting for the finger to lift.
 */
@Composable
private fun BottomBar(current: Tab, modifier: Modifier, onSelect: (Tab) -> Unit) {
    BoxWithConstraints(modifier) {
        // 124 dp tabs, narrower on a phone.
        val item = minOf(124.dp, (maxWidth - 44.dp) / Tab.entries.size)
        val pillX by animateDpAsState(item * current.ordinal, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "pill")
        Box(Modifier.clip(RoundedCornerShape(40.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(6.dp)) {
            Box(Modifier.offset(x = pillX).size(item, 68.dp).clip(RoundedCornerShape(34.dp)).background(PillBrush))
            Row {
                Tab.entries.forEach { tab ->
                    val selected = tab == current
                    val color by animateColorAsState(if (selected) Color.White else TabColor, tween(150), label = "tab")
                    val interaction = remember { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    Column(
                        Modifier.size(item, 68.dp).clip(RoundedCornerShape(34.dp))
                            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(34.dp)) else Modifier)
                            .pointerInput(tab) { detectTapGestures(onPress = { onSelect(tab) }) }
                            // Keyboard, controller and accessibility (selecting again is harmless).
                            .selectable(selected, interactionSource = interaction, indication = null, onClick = { onSelect(tab) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Icon(if (selected) tab.selectedIcon else tab.icon, null, tint = color, modifier = Modifier.size(26.dp))
                        Text(
                            stringResource(tab.label),
                            color = color,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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
            // The last picture of the desktop, reloaded whenever the screen comes back.
            var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
            LifecycleResumeEffect(Unit) {
                val file = DesktopActivity.thumbnailFile(context)
                thumbnail = if (file.isFile) BitmapFactory.decodeFile(file.path) else null
                onPauseOrDispose { }
            }
            val hero = @Composable { modifier: Modifier ->
                Box(
                    // The desktop's proportions, so that the whole picture shows.
                    modifier.aspectRatio(thumbnail?.let { it.width.toFloat() / it.height } ?: 1.6f).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .combinedClickable(onClick = { openDesktop(context) }),
                    contentAlignment = Alignment.Center,
                ) {
                    val picture = thumbnail
                    if (picture != null) {
                        Image(
                            picture.asImageBitmap(), stringResource(R.string.home_last_session),
                            Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                        )
                    } else {
                        Icon(Icons.Debian, null, tint = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(140.dp))
                    }
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
                // The picture at most 420 dp tall, so the quick launch row stays above the bar.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    hero(Modifier.weight(1f, fill = false).heightIn(max = 420.dp))
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
        QuickLaunch(state, viewModel, onAllApps)
    }
}

/**
 * The Home's apps, in the user's order, then "All apps". In edit mode each
 * app has a remove badge and moves where it is dragged; "Add" picks more.
 */
@Composable
private fun QuickLaunch(state: AppState, viewModel: AppViewModel, onAllApps: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val apps = state.apps.orEmpty().associateBy { it.id }
    // The order while a drag moves things, saved when it ends.
    var order by remember(state.homeApps, apps.keys) { mutableStateOf(state.homeApps.filter { it in apps }) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    BackHandler(enabled = editing) { editing = false }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(
                stringResource(R.string.home_quick) + if (editing) " · " + stringResource(R.string.home_edit_hint) else "",
                Modifier.weight(1f),
            )
            if (editing) {
                Button(onClick = { editing = false }, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
                    Text(stringResource(R.string.home_done))
                }
            } else {
                TextButton(onClick = { editing = true }) { Text(stringResource(R.string.home_edit)) }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 12.dp
            val columns = ((maxWidth + gap) / (220.dp + gap)).toInt().coerceIn(2, 6)
            val cell = (maxWidth - gap * (columns - 1)) / columns
            val height = 120.dp
            val density = LocalDensity.current
            val stepX = with(density) { (cell + gap).toPx() }
            val stepY = with(density) { (height + gap).toPx() }
            fun origin(index: Int) = DpOffset((cell + gap) * (index % columns), (height + gap) * (index / columns))
            val count = order.size + 1
            Box(Modifier.fillMaxWidth().height(height * ((count + columns - 1) / columns) + gap * ((count - 1) / columns))) {
                order.forEach { id ->
                    val app = apps[id] ?: return@forEach
                    key(id) {
                        val index = order.indexOf(id)
                        val lifted = dragged == id
                        val target by animateDpOffsetAsState(origin(index), label = "tile")
                        val position = if (lifted) origin(index) else target
                        val scale by animateFloatAsState(if (lifted) 1.04f else 1f, label = "lift")
                        Box(
                            Modifier.offset(position.x, position.y).size(cell, height).zIndex(if (lifted) 1f else 0f)
                                .graphicsLayer {
                                    if (lifted) {
                                        translationX = drag.x
                                        translationY = drag.y - 6.dp.toPx()
                                    }
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .then(
                                    if (editing) {
                                        Modifier.pointerInput(id, columns) {
                                            detectDragGestures(
                                                onDragStart = { dragged = id; drag = Offset.Zero },
                                                onDragEnd = { dragged = null; drag = Offset.Zero; viewModel.setHomeApps(order) },
                                                onDragCancel = { dragged = null; drag = Offset.Zero },
                                            ) { change, amount ->
                                                change.consume()
                                                drag += amount
                                                // The cell under the tile's center, as the list is now.
                                                val from = order.indexOf(id)
                                                val col = (from % columns + (drag.x / stepX).roundToInt()).coerceIn(0, columns - 1)
                                                val row = (from / columns + (drag.y / stepY).roundToInt()).coerceAtLeast(0)
                                                val to = (row * columns + col).coerceIn(0, order.size - 1)
                                                if (to != from) {
                                                    order = order.toMutableList().apply { add(to, removeAt(from)) }
                                                    drag -= Offset(stepX * (to % columns - from % columns), stepY * (to / columns - from / columns))
                                                }
                                            }
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            AppTile(app, viewModel, editing = editing, lifted = lifted)
                            if (editing) {
                                IconButton(
                                    onClick = { viewModel.setHomeApps(order - id) },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp),
                                ) {
                                    Box(
                                        Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.Close, stringResource(R.string.home_remove, app.name), Modifier.size(14.dp)) }
                                }
                            }
                        }
                    }
                }
                val last by animateDpOffsetAsState(origin(order.size), label = "last")
                Column(
                    Modifier.offset(last.x, last.y).size(cell, height).clip(CardShape)
                        .then(
                            if (editing) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
                            } else {
                                Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                            },
                        )
                        .combinedClickable(onClick = { if (editing) adding = true else onAllApps() }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (editing) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (editing) Icons.Plus else Icons.Apps, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        stringResource(if (editing) R.string.home_add else R.string.home_all_apps),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
    if (adding) {
        val others = state.apps.orEmpty().filter { it.id !in order }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.home_add_title)) },
            text = {
                if (others.isEmpty()) {
                    Text(stringResource(R.string.home_add_empty))
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        others.forEach { app ->
                            Row(
                                Modifier.fillMaxWidth().clip(RowShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .clickable { viewModel.setHomeApps(order + app.id); adding = false }.padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(app.id, viewModel, 36.dp)
                                Text(app.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

/** Animates a [DpOffset] (Compose has no shortcut for it). */
@Composable
private fun animateDpOffsetAsState(target: DpOffset, label: String): State<DpOffset> =
    animateValueAsState(target, DpOffset.VectorConverter, spring(stiffness = Spring.StiffnessMediumLow), label = label)

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
    val (settings, programs) = apps.partition { "Settings" in it.categories }
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

/**
 * An app: tap starts it in the desktop, long press offers the quick launch and a shortcut on Android's
 * home screen. While the quick launch is [editing] it only shows, outlined ([lifted] while dragged).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: LinuxApp,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    lifted: Boolean = false,
) {
    val context = LocalContext.current
    val icon by produceState(viewModel.cachedIcon(app.id), app.id) {
        if (value == null) value = withContext(Dispatchers.IO) { viewModel.icon(app.id) }
    }
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().height(120.dp).clip(CardShape)
                .background(if (lifted) colors.surfaceContainerHigh else if (editing) colors.surfaceContainer else colors.surfaceContainerLow)
                .then(
                    when {
                        lifted -> Modifier.border(2.dp, colors.primary, CardShape)
                        editing -> Modifier.border(1.dp, colors.outlineVariant, CardShape)
                        else -> Modifier.combinedClickable(onClick = { launchApp(context, app.id, app) }, onLongClick = { menu = true })
                    },
                )
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
            val inHome = app.id in viewModel.state.value.homeApps
            DropdownMenuItem(
                text = { Text(stringResource(if (inHome) R.string.apps_home_remove else R.string.apps_home_add)) },
                onClick = {
                    menu = false
                    val ids = viewModel.state.value.homeApps
                    viewModel.setHomeApps(if (inHome) ids - app.id else ids + app.id)
                },
            )
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
    var changingPassword by remember { mutableStateOf(false) }
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
                            icon = {},
                        ) { Text(stringResource(label)) }
                    }
                }
            }
            InfoRow(stringResource(R.string.settings_layout), onClick = { choosingLayout = true }) {
                Text(layoutName(settings.layout), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            }
            SystemKeysRow()
            Text(
                stringResource(R.string.settings_next_session),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            SectionHeader(stringResource(R.string.settings_account), Modifier.padding(top = 20.dp, bottom = 4.dp))
            InfoRow(
                stringResource(R.string.wizard_account_password),
                text = stringResource(R.string.settings_password_summary, settings.user),
            ) {
                FilledTonalButton(onClick = { changingPassword = true }) { Text(stringResource(R.string.action_change)) }
            }
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                SectionHeader(stringResource(R.string.settings_debug), Modifier.padding(top = 20.dp, bottom = 4.dp))
                InfoRow(
                    stringResource(R.string.settings_stats),
                    text = stringResource(R.string.settings_stats_text),
                    onClick = { save { it.copy(stats = !it.stats) } },
                ) {
                    Switch(checked = settings.stats, onCheckedChange = { checked -> save { it.copy(stats = checked) } })
                }
            }
        }
    }
    if (choosingLayout) {
        LayoutDialog(settings.layout, onDismiss = { choosingLayout = false }) { layout ->
            save { it.copy(layout = layout) }
            choosingLayout = false
        }
    }
    if (changingPassword) PasswordDialog(onDismiss = { changingPassword = false })
}

/** The accessibility service that hands the desktop Meta, Alt+Tab and the other keys Android keeps. */
@Composable
private fun SystemKeysRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(DesktopKeysService.isEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        enabled = DesktopKeysService.isEnabled(context)
        onPauseOrDispose { }
    }
    InfoRow(
        stringResource(R.string.settings_system_keys),
        text = stringResource(if (enabled) R.string.settings_system_keys_on else R.string.settings_system_keys_off),
    ) {
        if (!enabled) {
            FilledTonalButton(onClick = {
                val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, DesktopKeysService::class.java).flattenToString())
                runCatching { context.startActivity(details) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }) { Text(stringResource(R.string.action_turn_on)) }
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
            // 5% steps, without a tick mark for each.
            steps = ((range.endInclusive - range.start) * 20).roundToInt() - 1,
            colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
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
                stringResource(R.string.desktop_name),
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
                val fraction = state.desktopProgress.fraction()
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            ComponentRow(Component.MESA, stringResource(R.string.install_gpu), state, viewModel)
            ComponentRow(Component.PLASMA, stringResource(R.string.plasma_component), state, viewModel)

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

/** An app's icon, decoded off the main thread; an empty tile until then. */
@Composable
private fun AppIcon(id: String, viewModel: AppViewModel, size: androidx.compose.ui.unit.Dp) {
    val icon by produceState(viewModel.cachedIcon(id), id) {
        if (value == null) value = withContext(Dispatchers.IO) { viewModel.icon(id) }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), null, Modifier.size(size))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
    }
}
