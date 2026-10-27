package app.droidnux.ui

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
import app.droidnux.desktop.DesktopSessionService
import app.droidnux.desktop.DesktopSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class Tab(val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME(R.string.tab_home, Icons.Desktop, Icons.DesktopFilled),
    STORE(R.string.tab_store, Icons.Store, Icons.StoreFilled),
    SETTINGS(R.string.tab_settings, Icons.Settings, Icons.SettingsFilled),
}

/** The Settings tab's pages. */
private enum class Category(val title: Int, val icon: ImageVector) {
    SCREEN(R.string.settings_screen, Icons.Desktop),
    INPUT(R.string.settings_input, Icons.Keyboard),
    ACCOUNT(R.string.settings_account, Icons.User),
    COMPONENTS(R.string.settings_components, Icons.Package),
    ANDROID(R.string.settings_android, Icons.Android),
    DIAGNOSTICS(R.string.system_diagnostics, Icons.Pulse),
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
    val entrance = remember { Animatable(1f) }
    LifecycleResumeEffect(Unit) {
        val play = scope.launch {
            entrance.snapTo(0f)
            entrance.animateTo(1f, tween(ENTRANCE_MS, easing = LinearEasing))
        }
        onPauseOrDispose { play.cancel() }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            // Room for the bar floating over the bottom of every page.
            val padding = if (isCompact()) {
                PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 100.dp)
            } else {
                PaddingValues(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 120.dp)
            }
            when (Tab.entries[page]) {
                // The Home runs under the status bar: its picture reaches the top edge.
                Tab.HOME -> HomeTab(state, viewModel, updates, entrance, onUpdates = { category = Category.COMPONENTS; select(Tab.SETTINGS) })
                Tab.STORE -> Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) { StoreTab(state, viewModel, padding) }
                Tab.SETTINGS -> Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
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
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing)
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
    val compact = isCompact()
    val side = if (compact) 16.dp else 48.dp
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero(state, viewModel, side, Modifier.entrance(entrance, 0))
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

/**
 * The last picture of the desktop from edge to edge, fading into the page, with the session's state and
 * buttons over its bottom. A dark band under the status bar keeps its icons readable on a light wallpaper.
 */
@Composable
private fun Hero(state: AppState, viewModel: AppViewModel, side: Dp, modifier: Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val compact = isCompact()
    // Where the picture is: the desktop opens out of it.
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val open = { openDesktop(context, view, bounds) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    // Reloaded whenever the screen comes back; decoding a screen-sized picture takes a few frames.
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        val load = scope.launch {
            val file = DesktopActivity.thumbnailFile(context)
            thumbnail = withContext(Dispatchers.IO) { if (file.isFile) BitmapFactory.decodeFile(file.path) else null }
        }
        onPauseOrDispose { load.cancel() }
    }
    var confirmStop by remember { mutableStateOf(false) }
    // From "Exit" until the session is gone.
    var stopping by remember { mutableStateOf(false) }
    val page = MaterialTheme.colorScheme.background
    Box(
        modifier.fillMaxWidth().onGloballyPositioned { bounds = it.boundsInRoot() }
            .clickable(onClickLabel = stringResource(R.string.action_open_desktop), onClick = open),
    ) {
        val picture = thumbnail
        if (picture != null) {
            Image(picture.asImageBitmap(), stringResource(R.string.home_last_session), Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Debian, null, tint = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(140.dp))
            }
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f), 0.15f to Color.Transparent,
                    0.4f to Color.Transparent, 0.65f to page.copy(alpha = 0.6f), 0.85f to page.copy(alpha = 0.92f), 1f to page,
                ),
            ),
        )
        val live = state.sessionRunning || stopping
        val status = @Composable { modifier: Modifier ->
            Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val dot by animateColorAsState(
                    when {
                        stopping -> UpdateColor
                        live -> RunningColor
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    label = "dot",
                )
                Box(Modifier.size(10.dp)) {
                    if (live) Halo(dot, period = if (stopping) 900 else 2400)
                    Box(Modifier.matchParentSize().clip(CircleShape).background(dot))
                }
                Column {
                    AnimatedContent(
                        when {
                            stopping -> R.string.home_stopping
                            live -> R.string.home_running
                            else -> R.string.home_ready
                        },
                        transitionSpec = { textSwap() },
                        label = "status",
                    ) { Text(stringResource(it), fontSize = if (compact) 18.sp else 22.sp, fontWeight = FontWeight.Medium) }
                    Text(stringResource(R.string.home_title), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val openButton = @Composable { modifier: Modifier ->
            val press = remember { MutableInteractionSource() }
            Button(onClick = open, modifier = modifier.height(56.dp).pressScale(press), interactionSource = press) {
                Icon(Icons.Play, null, modifier = Modifier.size(22.dp))
                AnimatedContent(live, transitionSpec = { textSwap() }, label = "open") { resume ->
                    Text(
                        stringResource(if (resume) R.string.action_resume_desktop else R.string.action_open_desktop),
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
                // Almost half the screen, never less than its old fixed size (a phone held sideways).
                .height((LocalConfiguration.current.screenHeightDp * 0.45f).dp.coerceAtLeast(if (compact) 300.dp else 260.dp))
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
 * where it is dragged; "Add" picks more.
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
                            AppTile(app, viewModel, height, iconSize = if (compact) 52.dp else 56.dp, editing = editing, lifted = lifted)
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
 * The categories and the chosen one's page: on a phone a grid of categories, each opening its page
 * (Back returns); on a tablet the list beside the page.
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
                Category.ACCOUNT -> AccountPage(settings)
                Category.COMPONENTS -> ComponentsPage(state, viewModel)
                Category.ANDROID -> SystemChecks(state.gpu)
                Category.DIAGNOSTICS -> DiagnosticsPage(state, viewModel)
            }
        }
    }

    if (isCompact()) {
        BackHandler(enabled = category != null) { onCategory(null) }
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
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (page == null) {
                    Text(stringResource(R.string.tab_settings), fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
                    Category.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { CategoryCard(it, summary(it), badge = it == Category.COMPONENTS && updates, Modifier.weight(1f)) { onCategory(it) } }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(onClick = { onCategory(null) }) { Icon(Icons.Back, stringResource(R.string.action_back)) }
                        Text(stringResource(page.title), fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    }
                    pageContent(page)
                }
            }
        }
    } else {
        val current = category ?: Category.SCREEN
        Row(Modifier.fillMaxSize().padding(padding), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.tab_settings), fontSize = 28.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 16.dp, bottom = 20.dp),
                )
                Box {
                    // The chosen row's background slides to it, as the bar's pill does.
                    val y by animateDpAsState(
                        (CategoryRowHeight + 4.dp) * current.ordinal,
                        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                        label = "category",
                    )
                    Box(
                        Modifier.offset(y = y).fillMaxWidth().height(CategoryRowHeight).clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Category.entries.forEach {
                            CategoryRow(it, selected = it == current, badge = it == Category.COMPONENTS && updates) { onCategory(it) }
                        }
                    }
                }
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
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(page.title), fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                    Box(Modifier.widthIn(max = 760.dp)) { pageContent(page) }
                }
            }
        }
    }
}

/** A category on a phone: its icon, its name and a line of its state. */
@Composable
private fun CategoryCard(category: Category, summary: String, badge: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier.pressScale(press).heightIn(min = 120.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(press, LocalIndication.current, onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) { Icon(category.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(category.title), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Two lines on every card, so a longer summary doesn't make its card taller than the others.
                Text(
                    summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp,
                )
            }
        }
        if (badge) Badge(Modifier.align(Alignment.TopEnd).padding(18.dp))
    }
}

private val CategoryRowHeight = 52.dp

/** A category in the tablet's list (the chosen one's background is drawn under the list). */
@Composable
private fun CategoryRow(category: Category, selected: Boolean, badge: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(CategoryRowHeight).clip(RoundedCornerShape(18.dp))
            .selectable(selected, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(category.icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(
            stringResource(category.title),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (badge) Badge()
    }
}

@Composable
private fun ScreenPage(settings: DesktopSettings, screen: android.graphics.Rect, save: ((DesktopSettings) -> DesktopSettings) -> Unit) {
    val context = LocalContext.current
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
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        InfoRow(
            stringResource(R.string.settings_stats),
            text = stringResource(R.string.settings_stats_text),
            onClick = { save { it.copy(stats = !it.stats) } },
        ) {
            Switch(checked = settings.stats, onCheckedChange = { checked -> save { it.copy(stats = checked) } })
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
