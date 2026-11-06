package app.droidnux.ui

import app.droidnux.ui.theme.darkColors
import app.droidnux.ui.theme.ThemeColor
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.FileObserver
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import app.droidnux.MainActivity
import app.droidnux.R
import app.droidnux.container.Component
import app.droidnux.container.DesktopEnvironment.LinuxApp
import app.droidnux.desktop.DesktopActivity
import app.droidnux.desktop.DesktopKeysService
import app.droidnux.desktop.DesktopSessionService
import app.droidnux.desktop.DesktopSettings
import app.droidnux.desktop.StatsItem
import app.droidnux.desktop.StatsOverlay
import app.droidnux.desktop.StatsPosition
import app.droidnux.desktop.StatsSample
import app.droidnux.desktop.StatsStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class Tab(val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME(R.string.tab_home, Icons.Desktop, Icons.DesktopFilled),
    STORE(R.string.tab_store, Icons.Store, Icons.StoreFilled),
    SETTINGS(R.string.tab_settings, Icons.Settings, Icons.SettingsFilled),
}

/** The Settings tab's pages, in their groups. */
private enum class Category(val title: Int, val icon: ImageVector, val group: Int) {
    SCREEN(R.string.settings_screen, Icons.Desktop, R.string.settings_group_desktop),
    INPUT(R.string.settings_input, Icons.Keyboard, R.string.settings_group_desktop),
    STATS(R.string.settings_stats, Icons.Stats, R.string.settings_group_desktop),
    ACCOUNT(R.string.settings_account, Icons.User, R.string.settings_group_desktop),
    COMPONENTS(R.string.settings_components, Icons.Package, R.string.settings_group_system),
    ANDROID(R.string.settings_android, Icons.Android, R.string.settings_group_system),
    DIAGNOSTICS(R.string.system_diagnostics, Icons.Pulse, R.string.settings_group_system),
}

@Composable
fun HomeScreen(state: AppState, viewModel: AppViewModel) {
    // The pages side by side: a swipe moves between them, a tab slides to its page.
    val pager = rememberPagerState { Tab.entries.size }
    val tab = Tab.entries[pager.targetPage]
    val scope = rememberCoroutineScope()
    fun select(next: Tab) {
        scope.launch { pager.animateScrollToPage(next.ordinal, animationSpec = tween(320, easing = Emphasized)) }
    }
    var category by rememberSaveable { mutableStateOf<Category?>(null) }
    LaunchedEffect(Unit) { if (state.apps == null) viewModel.loadApps() }

    BackHandler(enabled = tab != Tab.HOME) { select(Tab.HOME) }
    val updates = !state.desktopUpToDate || Component.ALL.any { it.sha256.isNotEmpty() && state.components[it.id] == InstallState.NotInstalled }
    // Every return to the app (from the desktop too) brings the Home's sections in one after another.
    // Hidden from the first frame and again once out of sight, so no frame shows the sections before they come in.
    val entrance = remember { Animatable(0f) }
    LifecycleResumeEffect(Unit) {
        val play = scope.launch { entrance.animateTo(1f, tween(ENTRANCE_MS, easing = LinearEasing)) }
        onPauseOrDispose { play.cancel() }
    }
    LifecycleStartEffect(Unit) {
        onStopOrDispose { scope.launch { entrance.snapTo(0f) } }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            // Room for the bar floating over the bottom of every page, and for the system's bar (or the keyboard) under it:
            // inside the pages' scrolling, so they run on to the screen's edge.
            val system = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
            val padding = if (isCompact()) {
                PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 100.dp + system)
            } else {
                PaddingValues(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 120.dp + system)
            }
            val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            when (Tab.entries[page]) {
                // The Home runs under the status bar: its picture reaches the top edge.
                Tab.HOME -> HomeTab(state, viewModel, updates, entrance, onUpdates = { category = Category.COMPONENTS; select(Tab.SETTINGS) })
                Tab.STORE -> Box(Modifier.windowInsetsPadding(insets)) { StoreTab(state, viewModel, padding) }
                Tab.SETTINGS -> Box(Modifier.windowInsetsPadding(insets)) {
                    SettingsTab(
                        state, viewModel, padding, category, updates,
                        onCategory = { category = it },
                    )
                }
            }
        }
        BottomBar(
            tab,
            // The pill follows the pages, under the finger too.
            position = { pager.currentPage + pager.currentPageOffsetFraction },
            badge = if (updates) Tab.SETTINGS else null,
            // Not safeDrawing: with the IME in it the bar would ride up on the keyboard.
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .padding(bottom = if (isCompact()) 12.dp else 24.dp),
        ) { if (it != tab) select(it) }
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
private val UpdateColor = Color(0xFFF2C46D)
private val RunningColor = Color(0xFF3DDC84)

/** Material's emphasized easing: quick to leave, slow to settle. */
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

// The Home's entrance: each section takes ENTRANCE_ITEM_MS, ENTRANCE_STAGGER_MS after the one above.
private const val ENTRANCE_ITEM_MS = 300
private const val ENTRANCE_STAGGER_MS = 50
private const val ENTRANCE_MS = ENTRANCE_ITEM_MS + 3 * ENTRANCE_STAGGER_MS

/** Rises and fades in when [progress] reaches the [index]th section's turn. */
private fun Modifier.entrance(progress: Animatable<Float, AnimationVector1D>, index: Int) = graphicsLayer {
    val t = ((progress.value * ENTRANCE_MS - index * ENTRANCE_STAGGER_MS) / ENTRANCE_ITEM_MS).coerceIn(0f, 1f)
    val eased = Emphasized.transform(t)
    alpha = eased
    translationY = (1 - eased) * 16.dp.toPx()
}

/** Shrinks to [scale] under the finger and springs back on release. */
@Composable
private fun Modifier.pressScale(interaction: InteractionSource, scale: Float = 0.94f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val current by animateFloatAsState(
        if (pressed) scale else 1f,
        if (pressed) tween(100) else spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return graphicsLayer {
        scaleX = current
        scaleY = current
    }
}

/** A label changing: the old one leaves upwards, the new one rises in. */
private fun <S> AnimatedContentTransitionScope<S>.textSwap(): ContentTransform =
    (fadeIn(tween(210, 90)) + slideInVertically(tween(210, 90, Emphasized)) { it / 4 }) togetherWith
        (fadeOut(tween(90)) + slideOutVertically(tween(90)) { -it / 4 })

/** The yellow dot of an update waiting. */
@Composable
private fun Badge(modifier: Modifier = Modifier) = Box(modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(UpdateColor))

/**
 * A floating rounded bar: each tab is its icon over its name, the current one on a pill at [position]
 * (the page shown, fractional while the pages move). The tab changes on touch down, without waiting
 * for the finger to lift. [badge] marks a tab with something waiting.
 */
@Composable
private fun BottomBar(current: Tab, position: () -> Float, badge: Tab?, modifier: Modifier, onSelect: (Tab) -> Unit) {
    // Smaller on a phone, never closer than 12 dp to the screen's sides.
    val compact = isCompact()
    val height = if (compact) 60.dp else 68.dp
    BoxWithConstraints(modifier.padding(horizontal = if (compact) 12.dp else 0.dp)) {
        // Tabs just wide enough for "Impostazioni": the bar stays a pill in the middle, also on a phone.
        val item = minOf(if (compact) 88.dp else 108.dp, (maxWidth - if (compact) 12.dp else 44.dp) / Tab.entries.size)
        Box(Modifier.clip(RoundedCornerShape(40.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(6.dp)) {
            Box(
                Modifier.offset { IntOffset((item.toPx() * position()).roundToInt(), 0) }.size(item, height)
                    .clip(RoundedCornerShape(height / 2)).background(PillBrush),
            )
            Row {
                Tab.entries.forEach { tab ->
                    val selected = tab == current
                    val color by animateColorAsState(if (selected) Color.White else TabColor, tween(150), label = "tab")
                    // The icon hops when its tab is chosen.
                    val hop = remember { Animatable(1f) }
                    var wasSelected by remember { mutableStateOf(selected) }
                    LaunchedEffect(selected) {
                        if (selected && !wasSelected) {
                            hop.animateTo(1.18f, tween(110))
                            hop.animateTo(1f, spring(dampingRatio = 0.5f))
                        }
                        wasSelected = selected
                    }
                    val interaction = remember { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    Box(
                        Modifier.size(item, height).clip(RoundedCornerShape(height / 2))
                            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(height / 2)) else Modifier)
                            .pointerInput(tab) { detectTapGestures(onPress = { onSelect(tab) }) }
                            // Keyboard, controller and accessibility (selecting again is harmless).
                            .selectable(selected, interactionSource = interaction, indication = null, onClick = { onSelect(tab) }),
                    ) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp, Alignment.CenterVertically),
                        ) {
                            Icon(
                                if (selected) tab.selectedIcon else tab.icon, null, tint = color,
                                modifier = Modifier.size(if (compact) 24.dp else 26.dp).graphicsLayer {
                                    scaleX = hop.value
                                    scaleY = hop.value
                                },
                            )
                            Text(
                                stringResource(tab.label),
                                color = color,
                                fontSize = if (compact) 11.sp else 14.sp,
                                // Without the default tracking "Impostazioni" fits a phone's tab.
                                letterSpacing = if (compact) 0.sp else TextUnit.Unspecified,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // Not RowScope's: this one sits in the tab's Box.
                        androidx.compose.animation.AnimatedVisibility(
                            tab == badge && !selected,
                            Modifier.align(Alignment.TopCenter).padding(top = 10.dp).offset(x = 16.dp),
                            enter = scaleIn(spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow)),
                            exit = scaleOut(),
                        ) { Badge() }
                    }
                }
            }
        }
    }
}

// -- Home --------------------------------------------------------------------

/** The desktop's picture with its state and buttons, then the favorites and every app. */
@Composable
private fun HomeTab(state: AppState, viewModel: AppViewModel, updates: Boolean, entrance: Animatable<Float, AnimationVector1D>, onUpdates: () -> Unit) {
    val context = LocalContext.current
    val compact = isCompact()
    val side = if (compact) 16.dp else 48.dp
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    // The desktop's wallpaper; its last picture, windows and all, only while it is paused
    // (or before a session copied the wallpaper). Reloaded whenever the screen comes back,
    // and when the file is written anew (the picture on leaving the desktop, often after
    // this screen is back). Decoding takes a few frames.
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(state.sessionPaused) {
        val wallpaper = viewModel.wallpaperFile
        val file = if (!state.sessionPaused && wallpaper.isFile) wallpaper else DesktopActivity.thumbnailFile(context)
        fun load() = scope.launch {
            thumbnail = withContext(Dispatchers.IO) { if (file.isFile) blurred(file.path) else null }
        }
        var load = load()
        val observer = object : FileObserver(file.parentFile!!, MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == file.name) scope.launch { load.cancel(); load = load() }
            }
        }
        observer.startWatching()
        onPauseOrDispose {
            observer.stopWatching()
            load.cancel()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Hero(state, viewModel, side, thumbnail, Modifier.entrance(entrance, 0))
            Column(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(start = side, end = side, top = 8.dp, bottom = if (compact) 100.dp else 120.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 24.dp),
            ) {
                if (updates) {
                    Box(Modifier.entrance(entrance, 1)) {
                        InfoRow(
                            title = stringResource(R.string.home_update_title),
                            text = stringResource(R.string.home_update_text),
                            icon = Icons.Info,
                            iconTint = UpdateColor,
                            onClick = onUpdates,
                        ) { Icon(Icons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                val next = if (updates) 2 else 1
                QuickLaunch(state, viewModel, Modifier.entrance(entrance, next))
                AllApps(state, viewModel, Modifier.entrance(entrance, next + 1))
            }
        }
    }
}

/**
 * The last picture of the desktop from edge to edge, fading into the page, with the session's state and
 * buttons over its bottom. A dark band under the status bar keeps its icons readable on a light wallpaper.
 */
@Composable
private fun Hero(state: AppState, viewModel: AppViewModel, side: Dp, picture: Bitmap?, modifier: Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val compact = isCompact()
    // Where the picture is: the desktop opens out of it.
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val open = { openDesktop(context, view, bounds) }
    val scope = rememberCoroutineScope()
    var confirmStop by remember { mutableStateOf(false) }
    // From "Exit" until the session is gone.
    var stopping by remember { mutableStateOf(false) }
    val live = state.sessionRunning || stopping
    val paused = state.sessionPaused && !stopping
    // Paused: the picture greys out, frozen as the desktop is.
    val frozen by animateFloatAsState(if (paused) 1f else 0f, tween(400), label = "frozen")
    Box(
        modifier.fillMaxWidth().onGloballyPositioned { bounds = it.boundsInRoot() }
            .clickable(onClickLabel = stringResource(R.string.action_open_desktop), onClick = open),
    ) {
        if (picture != null) {
            Image(
                picture.asImageBitmap(), stringResource(R.string.home_last_session), Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                alpha = 1f - 0.3f * frozen,
                colorFilter = if (frozen > 0f) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1f - 0.7f * frozen) }) else null,
            )
        } else {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Debian, null, tint = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(140.dp))
            }
        }
        val page = MaterialTheme.colorScheme.background
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f), 0.15f to Color.Transparent,
                    0.6f to Color.Transparent, 0.8f to page.copy(alpha = 0.6f), 0.93f to page.copy(alpha = 0.92f), 1f to page,
                ),
            ),
        )
        AnimatedVisibility(
            paused, Modifier.align(Alignment.Center),
            enter = fadeIn() + scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f),
            exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.6f),
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(page.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Pause, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
            }
        }
        val status = @Composable { modifier: Modifier ->
            Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val dot by animateColorAsState(
                    when {
                        stopping || paused -> UpdateColor
                        live -> RunningColor
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    label = "dot",
                )
                Box(Modifier.size(10.dp)) {
                    if (live && !paused) Halo(dot, period = if (stopping) 900 else 2400)
                    Box(Modifier.matchParentSize().clip(CircleShape).background(dot))
                }
                Column {
                    AnimatedContent(
                        when {
                            stopping -> R.string.home_stopping
                            paused -> R.string.home_paused
                            live -> R.string.home_running
                            else -> R.string.home_ready
                        },
                        transitionSpec = { textSwap() },
                        label = "status",
                    ) { Text(stringResource(it), fontSize = if (compact) 18.sp else 22.sp, fontWeight = FontWeight.Medium) }
                    Text(
                        if (paused) stringResource(R.string.home_paused_text) else stringResource(R.string.home_title),
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val openButton = @Composable { modifier: Modifier ->
            val press = remember { MutableInteractionSource() }
            Button(onClick = open, modifier = modifier.height(56.dp).pressScale(press), interactionSource = press) {
                Icon(Icons.Play, null, modifier = Modifier.size(22.dp))
                val action = when {
                    paused -> R.string.action_resume_paused
                    live -> R.string.action_resume_desktop
                    else -> R.string.action_open_desktop
                }
                AnimatedContent(action, transitionSpec = { textSwap() }, label = "open") { text ->
                    Text(
                        stringResource(text),
                        fontSize = 18.sp,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
        // Grows in beside the button while the session runs; a wheel while it stops.
        val stop = @Composable {
            AnimatedVisibility(
                live,
                enter = expandHorizontally(tween(350, easing = Emphasized)) + fadeIn() +
                    scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f),
                exit = shrinkHorizontally(tween(350, easing = Emphasized)) + fadeOut(tween(200)) + scaleOut(tween(350), targetScale = 0.6f),
            ) {
                val press = remember { MutableInteractionSource() }
                FilledTonalIconButton(
                    onClick = { if (!stopping) confirmStop = true },
                    modifier = Modifier.size(56.dp).pressScale(press),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White),
                    interactionSource = press,
                ) {
                    if (stopping) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.4.dp)
                    } else {
                        Icon(Icons.Stop, stringResource(R.string.home_stop))
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                // Over half the screen (the bottom fades into the page), never less than its old fixed size (a phone held sideways).
                .height((LocalConfiguration.current.screenHeightDp * 0.55f).dp.coerceAtLeast(if (compact) 300.dp else 260.dp))
                .padding(start = side, end = side, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
        ) {
            if (compact) {
                status(Modifier)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    openButton(Modifier.weight(1f))
                    stop()
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    status(Modifier.weight(1f))
                    stop()
                    openButton(Modifier.width(280.dp))
                }
            }
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.menu_exit_title)) },
            text = { Text(stringResource(R.string.menu_exit_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    stopping = true
                    DesktopSessionService.stop(context)
                    // The desktop's process takes a moment to go.
                    scope.launch {
                        for (attempt in 1..10) {
                            delay(500)
                            viewModel.refresh()
                            if (!viewModel.state.value.sessionRunning) break
                        }
                        stopping = false
                    }
                }) { Text(stringResource(R.string.menu_exit_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A ring of [color] that swells and fades every [period] ms: the session is alive. */
@Composable
private fun Halo(color: Color, period: Int) {
    val pulse by rememberInfiniteTransition(label = "halo").animateFloat(
        0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing)), label = "pulse",
    )
    Box(
        Modifier.size(10.dp).graphicsLayer {
            // Swells over the first 70%, then rests.
            val t = FastOutSlowInEasing.transform((pulse / 0.7f).coerceAtMost(1f))
            scaleX = 1 + 1.8f * t
            scaleY = scaleX
            alpha = 0.55f * (1 - t)
        }.clip(CircleShape).background(color),
    )
}

/**
 * The favorites, in the user's order, in a dock. In edit mode each app has a remove badge and moves
 * where it is dragged, or with a keyboard or controller: Enter (A) lifts the focused one, the arrows
 * move it, Enter or Escape (B) puts it down; "Add" picks more.
 */
@Composable
private fun QuickLaunch(state: AppState, viewModel: AppViewModel, modifier: Modifier = Modifier) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val apps = state.apps.orEmpty().associateBy { it.id }
    // The order while a drag moves things, saved when it ends.
    var order by remember(state.homeApps, apps.keys) { mutableStateOf(state.homeApps.filter { it in apps }) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    // The app lifted from the keyboard or a controller.
    var moving by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = editing) { editing = false }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
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
        val compact = isCompact()
        BoxWithConstraints(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 6.dp, vertical = 12.dp),
        ) {
            // As many as the apps' grid below, so the two line up.
            val columns = appColumns(compact, maxWidth)
            val gap = 4.dp
            val cell = (maxWidth - gap * (columns - 1)) / columns
            val height = if (compact) 92.dp else 100.dp
            val density = LocalDensity.current
            val stepX = with(density) { (cell + gap).toPx() }
            val stepY = with(density) { (height + gap).toPx() }
            fun origin(index: Int) = DpOffset((cell + gap) * (index % columns), (height + gap) * (index / columns))
            // "Add" while editing, or in place of an empty dock.
            val add = editing || order.isEmpty()
            val count = order.size + if (add) 1 else 0
            Box(Modifier.fillMaxWidth().height(height * ((count + columns - 1) / columns) + gap * ((count - 1) / columns))) {
                order.forEach { id ->
                    val app = apps[id] ?: return@forEach
                    key(id) {
                        val index = order.indexOf(id)
                        val lifted = dragged == id || moving == id
                        var focused by remember { mutableStateOf(false) }
                        fun putDown() {
                            moving = null
                            viewModel.setHomeApps(order)
                        }
                        val target by animateDpOffsetAsState(origin(index), label = "tile")
                        val position = if (dragged == id) origin(index) else target
                        val scale by animateFloatAsState(if (lifted) 1.04f else 1f, label = "lift")
                        Box(
                            Modifier.offset(position.x, position.y).size(cell, height).zIndex(if (lifted) 1f else 0f)
                                .graphicsLayer {
                                    if (dragged == id) {
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
                                            .onFocusChanged {
                                                focused = it.isFocused
                                                if (!it.isFocused && moving == id) putDown()
                                            }
                                            .onKeyEvent { event ->
                                                val down = event.type == KeyEventType.KeyDown
                                                when (event.key) {
                                                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.ButtonA, Key.Spacebar -> {
                                                        if (down) if (moving == id) putDown() else moving = id
                                                        true
                                                    }
                                                    // On release, as Back would leave edit mode on its own release.
                                                    Key.Escape, Key.ButtonB, Key.Back -> (moving == id).also { if (it && !down) putDown() }
                                                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                                                        if (moving != id) return@onKeyEvent false
                                                        if (down) {
                                                            val step = when (event.key) {
                                                                Key.DirectionLeft -> -1
                                                                Key.DirectionRight -> 1
                                                                Key.DirectionUp -> -columns
                                                                else -> columns
                                                            }
                                                            val from = order.indexOf(id)
                                                            val to = (from + step).coerceIn(0, order.size - 1)
                                                            if (to != from) order = order.toMutableList().apply { add(to, removeAt(from)) }
                                                        }
                                                        true
                                                    }
                                                    else -> false
                                                }
                                            }
                                            .focusable()
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            AppTile(app, viewModel, height, iconSize = if (compact) 52.dp else 56.dp, editing = editing, lifted = lifted || focused)
                            if (editing) {
                                IconButton(
                                    onClick = { viewModel.setHomeApps(order - id) },
                                    modifier = Modifier.align(Alignment.TopEnd).size(36.dp),
                                ) {
                                    Box(
                                        Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.Close, stringResource(R.string.home_remove, app.name), Modifier.size(12.dp)) }
                                }
                            }
                        }
                    }
                }
                if (add) {
                    val last by animateDpOffsetAsState(origin(order.size), label = "last")
                    Column(
                        Modifier.offset(last.x, last.y).size(cell, height).clip(CardShape).clickable { adding = true },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier.size(if (compact) 52.dp else 56.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Plus, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(
                            stringResource(R.string.home_add),
                            fontSize = if (compact) 12.sp else 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
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

/** Every app, programs before the desktop's settings; the magnifier opens a filter. */
@Composable
private fun AllApps(state: AppState, viewModel: AppViewModel, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = query != null) { query = null }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(stringResource(R.string.home_all_apps), Modifier.weight(1f))
            IconButton(onClick = { query = if (query == null) "" else null }) {
                Icon(if (query == null) Icons.Search else Icons.Close, stringResource(R.string.home_search), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val text = query
        if (text != null) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                value = text,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.home_search)) },
                leadingIcon = { Icon(Icons.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        val apps = state.apps
        if (apps == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        val (settings, programs) = apps.partition { "Settings" in it.categories }
        val shown = (programs + settings).filter { text.isNullOrBlank() || it.name.contains(text.trim(), ignoreCase = true) }
        if (shown.isEmpty()) {
            Text(
                stringResource(R.string.store_no_results, text.orEmpty().trim()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            return@Column
        }
        val compact = isCompact()
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) { // The dock's inset: columns line up.
            val columns = appColumns(compact, maxWidth)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { app ->
                            key(app.id) {
                                AppTile(app, viewModel, if (compact) 92.dp else 104.dp, iconSize = if (compact) 48.dp else 52.dp, modifier = Modifier.weight(1f))
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Apps a row: four on a phone, about 110 dp each on a tablet. */
private fun appColumns(compact: Boolean, width: Dp): Int = if (compact) 4 else (width / 110.dp).toInt().coerceIn(4, 10)

/**
 * The desktop's last picture, lightly blurred, at a quarter of its size. Done once per load, so it costs
 * nothing per frame and works on every Android version.
 */
private fun blurred(path: String): Bitmap? {
    // A light blur: a few pixels at about a quarter of the screen's size (a photo may be far larger), the screen stretches it smoothly.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(path, it) }
    var sample = 4
    while (bounds.outWidth / (sample * 2) >= 700) sample *= 2
    val quarter = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return boxBlur(quarter, radius = 3, passes = 2)
}

/** [passes] box blurs of [radius] pixels, across then down (three passes come close to a Gaussian). */
private fun boxBlur(source: Bitmap, radius: Int, passes: Int): Bitmap {
    val w = source.width
    val h = source.height
    var pixels = IntArray(w * h).also { source.getPixels(it, 0, w, 0, 0, w, h) }
    var out = IntArray(w * h)
    // One line of [count] pixels from [start], [step] apart, averaged over the window around each (edges repeat).
    fun line(start: Int, step: Int, count: Int) {
        for (i in 0 until count) {
            var r = 0
            var g = 0
            var b = 0
            for (k in -radius..radius) {
                val p = pixels[start + (i + k).coerceIn(0, count - 1) * step]
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
            }
            val n = 2 * radius + 1
            out[start + i * step] = (0xFF shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
        }
    }
    repeat(passes) {
        for (y in 0 until h) line(y * w, 1, w)
        pixels = out.also { out = pixels }
        for (x in 0 until w) line(x, w, h)
        pixels = out.also { out = pixels }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

/** Animates a [DpOffset] (Compose has no shortcut for it). */
@Composable
private fun animateDpOffsetAsState(target: DpOffset, label: String): State<DpOffset> =
    animateValueAsState(target, DpOffset.VectorConverter, spring(stiffness = Spring.StiffnessMediumLow), label = label)

/** Opens the desktop growing out of [from] in [view] (the Home's picture), as a launcher opens an app. */
private fun openDesktop(context: Context, view: View, from: Rect?) {
    val options = from?.let {
        ActivityOptions.makeClipRevealAnimation(view, it.left.roundToInt(), it.top.roundToInt(), it.width.roundToInt(), it.height.roundToInt()).toBundle()
    }
    context.startActivity(Intent(context, DesktopActivity::class.java), options)
}

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

/**
 * An app, [height] tall: tap starts it in the desktop, long press offers the favorites and a shortcut on
 * Android's home screen. While the favorites are [editing] it only shows, outlined ([lifted] while dragged).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: LinuxApp,
    viewModel: AppViewModel,
    height: Dp,
    iconSize: Dp,
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
    val compact = isCompact()
    val press = remember { MutableInteractionSource() }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().height(height).clip(CardShape)
                .then(
                    when {
                        lifted -> Modifier.background(colors.surfaceContainerHigh).border(2.dp, colors.primary, CardShape)
                        editing -> Modifier
                        else -> Modifier.combinedClickable(
                            interactionSource = press,
                            indication = LocalIndication.current,
                            onClick = { launchApp(context, app.id, app) },
                            onLongClick = { menu = true },
                        )
                    },
                )
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val bitmap = icon
            val iconModifier = Modifier.size(iconSize).pressScale(press, 0.88f)
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), null, iconModifier)
            } else {
                Box(iconModifier.clip(RoundedCornerShape(iconSize * 0.3f)).background(colors.surfaceContainerHighest))
            }
            Text(
                app.name,
                fontSize = if (compact) 12.sp else 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
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

/**
 * The categories and the chosen one's page: on a phone the grouped list, each row opening its page
 * (Back returns); on a tablet the same list beside the page.
 */
@Composable
private fun SettingsTab(
    state: AppState,
    viewModel: AppViewModel,
    padding: PaddingValues,
    category: Category?,
    updates: Boolean,
    onCategory: (Category?) -> Unit,
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(DesktopSettings.load(context)) }
    fun save(change: (DesktopSettings) -> DesktopSettings) {
        settings = DesktopSettings.update(context, change)
    }
    val screen = remember { context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds }
    val summary = @Composable { it: Category ->
        when (it) {
            Category.SCREEN -> "${(screen.width() * settings.scale).toInt() and 1.inv()} × ${(screen.height() * settings.scale).toInt() and 1.inv()} · " +
                "${(settings.uiScale * 100).roundToInt()}%"
            Category.INPUT -> layoutName(settings.layout) + " · " +
                stringResource(if (settings.trackpad) R.string.menu_mode_trackpad else R.string.menu_mode_touch)
            Category.STATS -> stringResource(if (settings.stats) settings.statsStyle.label else R.string.stats_off)
            Category.ACCOUNT -> settings.user
            Category.COMPONENTS -> stringResource(if (updates) R.string.settings_components_update else R.string.settings_components_ok)
            Category.ANDROID -> stringResource(R.string.settings_android_text)
            Category.DIAGNOSTICS -> stringResource(R.string.settings_diagnostics_text)
        }
    }
    val pageContent = @Composable { it: Category ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (it) {
                Category.SCREEN -> ScreenPage(settings, screen, ::save)
                Category.INPUT -> InputPage(settings, ::save)
                Category.STATS -> StatsPage(settings, screen, viewModel.wallpaperFile, ::save)
                Category.ACCOUNT -> AccountPage(settings)
                Category.COMPONENTS -> ComponentsPage(state, viewModel)
                Category.ANDROID -> SystemChecks(state.gpu)
                Category.DIAGNOSTICS -> DiagnosticsPage(state, viewModel, settings, ::save)
            }
        }
    }

    if (isCompact()) {
        BackHandler(enabled = category != null) { onCategory(null) }
        // How far the pages are pulled down, the same for every page.
        val reach = remember { mutableFloatStateOf(0f) }
        AnimatedContent(
            category,
            transitionSpec = {
                // Into a page from the right, back to the grid towards the right.
                val towards = if (targetState != null) 1 else -1
                (fadeIn(tween(210, 90)) + slideInHorizontally(tween(300, easing = Emphasized)) { towards * it / 10 }) togetherWith
                    (fadeOut(tween(90)) + slideOutHorizontally(tween(300, easing = Emphasized)) { -towards * it / 10 })
            },
            label = "category",
        ) { page ->
            ReachPage(
                reach,
                padding = padding,
            ) { open ->
                if (page == null) {
                    Text(
                        stringResource(R.string.tab_settings), fontSize = 24.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp).reachTitle(open),
                    )
                    CategoryGroups(selected = null, updates, summary) { onCategory(it) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(onClick = { onCategory(null) }) { Icon(Icons.Back, stringResource(R.string.action_back)) }
                        Text(stringResource(page.title), fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.reachTitle(open))
                    }
                    pageContent(page)
                }
            }
        }
    } else {
        val current = category ?: Category.SCREEN
        // The bottom padding inside the scrolling columns: they run on under the floating bar.
        val direction = LocalLayoutDirection.current
        val bottom = padding.calculateBottomPadding()
        Row(
            Modifier.fillMaxSize().padding(
                start = padding.calculateStartPadding(direction), top = padding.calculateTopPadding(), end = padding.calculateEndPadding(direction),
            ),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(bottom = bottom), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.tab_settings), fontSize = 28.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                CategoryGroups(selected = current, updates, summary) { onCategory(it) }
            }
            AnimatedContent(
                current,
                transitionSpec = {
                    (fadeIn(tween(240, 90)) + slideInVertically(tween(300, 90, Emphasized)) { it / 30 }) togetherWith
                        (fadeOut(tween(90)) + slideOutVertically(tween(90)) { -it / 60 })
                },
                label = "page",
                modifier = Modifier.weight(1f),
            ) { page ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottom), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(page.title), fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                    Box(Modifier.widthIn(max = 760.dp)) { pageContent(page) }
                }
            }
        }
    }
}

/**
 * A Settings page on a phone, usable with one hand: pulled down at its top, it slides down to where the
 * thumb reaches; scrolling up brings it back before the page scrolls. [reach] is
 * how far it is pulled, in pixels. [content] gets how far down it is (0 to 1), for [reachTitle].
 */
@Composable
private fun ReachPage(
    reach: MutableFloatState,
    padding: PaddingValues,
    content: @Composable ColumnScope.(open: () -> Float) -> Unit,
) {
    val max = with(LocalDensity.current) { (LocalConfiguration.current.screenHeightDp * 0.35f).dp.toPx() }
    fun open() = (reach.floatValue / max).coerceIn(0f, 1f)
    val connection = remember(max) {
        object : NestedScrollConnection {
            // Up: the header closes first.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0 || reach.floatValue <= 0f) return Offset.Zero
                val take = minOf(-available.y, reach.floatValue)
                reach.floatValue -= take
                return Offset(0f, -take)
            }

            // Down past the top, by the finger only (not a fling): the header opens.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0 || source != NestedScrollSource.UserInput) return Offset.Zero
                val take = minOf(available.y, max - reach.floatValue)
                reach.floatValue += take
                return Offset(0f, take)
            }

            // Let go half way: open or closed, whichever the flick or the nearer end says.
            override suspend fun onPreFling(available: Velocity): Velocity {
                val now = reach.floatValue
                if (now <= 0f || now >= max) return Velocity.Zero
                val target = when {
                    available.y > REACH_FLICK -> max
                    available.y < -REACH_FLICK -> 0f
                    now > max / 2 -> max
                    else -> 0f
                }
                animate(now, target, animationSpec = tween(280, easing = Emphasized)) { value, _ -> reach.floatValue = value }
                return if (target == max) available else Velocity.Zero
            }
        }
    }
    // The page moves down by the pull: placed again each frame, never measured again.
    Column(
        Modifier.fillMaxSize().nestedScroll(connection).offset { IntOffset(0, reach.floatValue.roundToInt()) }
            .verticalScroll(rememberScrollState()).padding(padding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content(::open) }
}

/** A page's title growing to big type, upwards from its bottom left, as the page is pulled down. */
private fun Modifier.reachTitle(open: () -> Float) = graphicsLayer {
    val scale = 1 + 0.45f * open()
    scaleX = scale
    scaleY = scale
    transformOrigin = TransformOrigin(0f, 1f)
}

/** Pixels a second: a flick at least this fast opens or closes the reach header. */
private const val REACH_FLICK = 300f

/**
 * The categories in their groups, each under its header: rows joined into one block, its outer corners round.
 * On a tablet [selected] is the page beside the list; on a phone there is none and each row has a chevron.
 */
@Composable
private fun CategoryGroups(selected: Category?, updates: Boolean, summary: @Composable (Category) -> String, onClick: (Category) -> Unit) {
    Category.entries.groupBy { it.group }.forEach { (group, categories) ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionHeader(stringResource(group), Modifier.padding(start = 20.dp, bottom = 10.dp))
            categories.forEachIndexed { index, category ->
                val top = if (index == 0) 24.dp else 6.dp
                val bottom = if (index == categories.lastIndex) 24.dp else 6.dp
                CategoryRow(
                    category, summary(category), RoundedCornerShape(top, top, bottom, bottom),
                    selected = category == selected, chevron = selected == null,
                    badge = category == Category.COMPONENTS && updates,
                ) { onClick(category) }
            }
        }
    }
}

/** A category: its icon in a circle, its name and a line of its state; the desktop's in the primary tones, the system's in the secondary. */
@Composable
private fun CategoryRow(
    category: Category,
    summary: String,
    shape: Shape,
    selected: Boolean,
    chevron: Boolean,
    badge: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val desktop = category.group == R.string.settings_group_desktop
    val background by animateColorAsState(if (selected) colors.secondaryContainer else colors.surfaceContainerLow, label = "row")
    val circle by animateColorAsState(
        when {
            selected -> colors.primary
            desktop -> colors.primaryContainer
            else -> colors.secondaryContainer
        },
        label = "circle",
    )
    val icon by animateColorAsState(
        when {
            selected -> colors.onPrimary
            desktop -> colors.onPrimaryContainer
            else -> colors.onSecondaryContainer
        },
        label = "icon",
    )
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.pressScale(press, 0.97f).fillMaxWidth().heightIn(min = 72.dp).clip(shape).background(background)
            .selectable(selected, press, LocalIndication.current, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(circle), contentAlignment = Alignment.Center) {
            Icon(category.icon, null, tint = icon, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(category.title), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            )
            Text(
                summary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = when {
                    badge -> colors.primary
                    selected -> colors.onSecondaryContainer
                    else -> colors.onSurfaceVariant
                },
            )
        }
        if (badge) Badge()
        if (chevron) Icon(Icons.Chevron, null, tint = colors.outline, modifier = Modifier.size(20.dp))
    }
}

/** The theme color, as in MoonVibe: under its name a dot for each color, the chosen one bigger and with a check. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeColorRow(settings: DesktopSettings, save: ((DesktopSettings) -> DesktopSettings) -> Unit) {
    val context = LocalContext.current
    val chosen = ThemeColor.picked ?: ThemeColor.resolve(settings.themeColor)
    Column(
        Modifier.fillMaxWidth().clip(RowShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.theme_color), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(stringResource(themeColorName(chosen)), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(Modifier.padding(top = 4.dp).offset(x = (-8).dp)) {
            for (color in ThemeColor.choices) {
                val selected = color == chosen
                val scale by animateFloatAsState(if (selected) 1.15f else 1f, tween(150), label = "swatch")
                val check by animateFloatAsState(if (selected) 1f else 0f, tween(150), label = "check")
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .selectable(selected, role = Role.RadioButton) {
                            ThemeColor.picked = color
                            save { it.copy(themeColor = color) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val swatch = remember(color) { themeSwatch(context, color) }
                    Box(
                        Modifier.size(28.dp).graphicsLayer { scaleX = scale; scaleY = scale }.background(swatch, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Check, stringResource(themeColorName(color)),
                            tint = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.size(18.dp).graphicsLayer { alpha = check },
                        )
                    }
                }
            }
        }
    }
}

/** A color's dot: its primary color, or the wallpaper's colors around the circle. */
private fun themeSwatch(context: android.content.Context, color: String): Brush =
    if (color == ThemeColor.DYNAMIC && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        val first = Color(context.getColor(android.R.color.system_accent1_300))
        Brush.sweepGradient(listOf(first, Color(context.getColor(android.R.color.system_accent2_300)), Color(context.getColor(android.R.color.system_accent3_300)), first))
    } else {
        SolidColor(darkColors(context, color).primary)
    }

private fun themeColorName(color: String) = when (color) {
    ThemeColor.DYNAMIC -> R.string.theme_color_dynamic
    "purple" -> R.string.theme_color_purple
    "pink" -> R.string.theme_color_pink
    "red" -> R.string.theme_color_red
    "orange" -> R.string.theme_color_orange
    "yellow" -> R.string.theme_color_yellow
    "green" -> R.string.theme_color_green
    "teal" -> R.string.theme_color_teal
    "gray" -> R.string.theme_color_gray
    else -> R.string.theme_color_blue
}

@Composable
private fun ScreenPage(settings: DesktopSettings, screen: android.graphics.Rect, save: ((DesktopSettings) -> DesktopSettings) -> Unit) {
    val context = LocalContext.current
    ThemeColorRow(settings, save)
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
}

/** The statistics over the desktop, as MoonVibe's: the style over a preview of the screen, then where, how opaque and what it shows. */
@Composable
private fun StatsPage(settings: DesktopSettings, screen: android.graphics.Rect, wallpaper: File, save: ((DesktopSettings) -> DesktopSettings) -> Unit) {
    val style = settings.statsStyle
    var opacity by remember { mutableFloatStateOf(settings.statsOpacity) }
    InfoRow(stringResource(R.string.stats_show), onClick = { save { it.copy(stats = !it.stats) } }) {
        Switch(checked = settings.stats, onCheckedChange = { checked -> save { it.copy(stats = checked) } })
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        StatsStyle.entries.forEachIndexed { index, each ->
            SegmentedButton(
                selected = style == each,
                onClick = { save { it.copy(statsStyle = each) } },
                shape = SegmentedButtonDefaults.itemShape(index, StatsStyle.entries.size),
                icon = {},
            ) { Text(stringResource(each.label)) }
        }
    }
    StatsPreview(settings.copy(statsOpacity = opacity), screen, wallpaper)
    var choosingPosition by remember { mutableStateOf(false) }
    Box {
        InfoRow(stringResource(R.string.stats_position), onClick = { choosingPosition = true }) {
            Text(stringResource(settings.statsPosition(style).label), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        }
        DropdownMenu(expanded = choosingPosition, onDismissRequest = { choosingPosition = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            StatsPosition.entries.forEach { position ->
                DropdownMenuItem(
                    text = { Text(stringResource(position.label)) },
                    onClick = {
                        choosingPosition = false
                        save { it.copy(statsPositions = it.statsPositions + (style to position)) }
                    },
                )
            }
        }
    }
    SliderRow(
        stringResource(R.string.stats_opacity), "${(opacity * 100).roundToInt()}%",
        opacity, DesktopSettings.MIN_STATS_OPACITY..1f,
        onChange = { opacity = it }, onDone = { save { it.copy(statsOpacity = opacity) } },
    )
    InfoRow(stringResource(R.string.stats_warnings), onClick = { save { it.copy(statsWarnings = !it.statsWarnings) } }) {
        Switch(checked = settings.statsWarnings, onCheckedChange = { checked -> save { it.copy(statsWarnings = checked) } })
    }
    SectionHeader(
        stringResource(
            when (style) {
                StatsStyle.BAR -> R.string.stats_items_bar
                StatsStyle.PANEL -> R.string.stats_items_panel
                StatsStyle.MINI -> R.string.stats_items_mini
            },
        ),
        Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
    // The shown ones in their order, dragged by their handle; the hidden ones after, a switch away from the end of it.
    val saved = settings.statsItems(style)
    var order by remember(style, saved) { mutableStateOf(saved) }
    var dragged by remember { mutableStateOf<StatsItem?>(null) }
    var drag by remember { mutableFloatStateOf(0f) }
    var step by remember { mutableFloatStateOf(1f) }
    val gap = with(LocalDensity.current) { 8.dp.toPx() }
    fun store(items: List<StatsItem>) = save { it.copy(statsItemsByStyle = it.statsItemsByStyle + (style to items)) }
    for (item in order + StatsItem.entries.filter { it !in order }) key(item) {
        val shown = item in order
        val lifted = dragged == item
        val scale by animateFloatAsState(if (lifted) 1.02f else 1f, label = "lift")
        fun toggle() = store(if (shown) order - item else order + item)
        // shortcut: the other rows jump to their new place instead of sliding; animate them if it looks rough.
        InfoRow(
            stringResource(item.label),
            Modifier.onSizeChanged { step = it.height + gap }.zIndex(if (lifted) 1f else 0f).graphicsLayer {
                translationY = if (lifted) drag else 0f
                scaleX = scale
                scaleY = scale
            },
            icon = ImageVector.vectorResource(item.icon),
            iconTint = MaterialTheme.colorScheme.primary,
            onClick = ::toggle,
        ) {
            Switch(checked = shown, onCheckedChange = { toggle() })
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (shown) {
                    Icon(
                        Icons.Grip, stringResource(R.string.stats_reorder), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxSize().padding(8.dp).pointerInput(item) {
                            detectDragGestures(
                                onDragStart = { dragged = item; drag = 0f },
                                onDragEnd = { dragged = null; drag = 0f; store(order) },
                                onDragCancel = { dragged = null; drag = 0f; order = saved },
                            ) { change, amount ->
                                change.consume()
                                drag += amount.y
                                // The place under the row's middle, as the list is now.
                                val from = order.indexOf(item)
                                val to = (from + (drag / step).roundToInt()).coerceIn(0, order.lastIndex)
                                if (to != from) {
                                    order = order.toMutableList().apply { add(to, removeAt(from)) }
                                    drag -= (to - from) * step
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The statistics over a screen of the device's shape, drawn at their real size and shrunk to fit. */
@Composable
private fun StatsPreview(settings: DesktopSettings, screen: android.graphics.Rect, wallpaper: File) {
    val density = LocalDensity.current
    // Plasma's wallpaper, at about the preview's size; plain before a session copied it.
    val picture by produceState<Bitmap?>(null, wallpaper) {
        value = withContext(Dispatchers.IO) {
            if (!wallpaper.isFile) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(wallpaper.path, it) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1000) sample *= 2
            BitmapFactory.decodeFile(wallpaper.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    val width = with(density) { screen.width().toDp() }
    val height = with(density) { screen.height().toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // A tall screen (a phone held upright) as wide as fits under 360 dp of height.
        val shown = minOf(maxWidth, 360.dp * (width / height))
        Box(
            Modifier.width(shown).aspectRatio(width / height).clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            picture?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            AndroidView(
                factory = { StatsOverlay(it) },
                update = {
                    it.apply(settings, animate = true)
                    it.show(StatsSample.SAMPLE)
                },
                modifier = Modifier.requiredSize(width, height).graphicsLayer {
                    scaleX = shown / width
                    scaleY = shown / width
                },
            )
        }
    }
}

@Composable
private fun InputPage(settings: DesktopSettings, save: ((DesktopSettings) -> DesktopSettings) -> Unit) {
    var choosingLayout by remember { mutableStateOf(false) }
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
    if (choosingLayout) {
        LayoutDialog(settings.layout, onDismiss = { choosingLayout = false }) { layout ->
            save { it.copy(layout = layout) }
            choosingLayout = false
        }
    }
}

@Composable
private fun AccountPage(settings: DesktopSettings) {
    var changingPassword by remember { mutableStateOf(false) }
    InfoRow(
        stringResource(R.string.wizard_account_password),
        text = stringResource(R.string.settings_password_summary, settings.user),
    ) {
        FilledTonalButton(onClick = { changingPassword = true }) { Text(stringResource(R.string.action_change)) }
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

/** The distribution, the desktop's packages and the components. */
@Composable
private fun ComponentsPage(state: AppState, viewModel: AppViewModel) {
    var confirmReinstall by remember { mutableStateOf(false) }
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
        iconTint = if (state.desktopUpToDate) MaterialTheme.colorScheme.primary else UpdateColor,
        action = if (state.desktopUpToDate || state.desktopInstalling) null else {
            { FilledTonalButton(onClick = viewModel::installDesktop) { Text(stringResource(R.string.action_update_desktop)) } }
        },
    )
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
        iconTint = if (installed) MaterialTheme.colorScheme.primary else UpdateColor,
        action = when {
            component.sha256.isEmpty() -> null
            install is InstallState.Installing -> {
                { TextButton(onClick = { viewModel.cancelComponent(component) }) { Text(stringResource(R.string.action_cancel)) } }
            }
            !installed -> {
                { FilledTonalButton(onClick = { viewModel.installComponent(component) }) { Text(stringResource(R.string.action_install)) } }
            }
            else -> null
        },
    )
}

/** An app's icon, decoded off the main thread; an empty tile until then. */
@Composable
private fun AppIcon(id: String, viewModel: AppViewModel, size: Dp) {
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
