package app.droidnux.ui

import android.content.Intent
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.BiasAlignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.droidnux.R
import app.droidnux.container.Component
import app.droidnux.container.InstallProgress
import app.droidnux.desktop.Account
import app.droidnux.desktop.DesktopSettings
import app.droidnux.desktop.SessionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/** Pages after the welcome, one dot each: preferences, account, apps, Android, installation. */
private const val DOTS = 5
private const val INSTALL = DOTS

/** First run: a welcome, the app's preferences, the account, the apps, the Android settings, then the installation. */
@Composable
fun WizardScreen(state: AppState, viewModel: AppViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var apps by rememberSaveable { mutableStateOf(StoreCatalog.WIZARD_DEFAULT) }
    // An installation already under way (the activity was recreated) shows its progress.
    val current = if (state.setup != null) INSTALL else step
    // Free space, rechecked when the user comes back (from freeing some).
    val context = LocalContext.current
    var free by remember { mutableLongStateOf(Long.MAX_VALUE) }
    LifecycleResumeEffect(Unit) {
        free = StatFs(context.filesDir.path).availableBytes
        onPauseOrDispose {}
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            current,
            transitionSpec = {
                val forward = if (targetState > initialState) 1 else -1
                (slideInHorizontally { forward * it / 8 } + fadeIn()) togetherWith (slideOutHorizontally { -forward * it / 8 } + fadeOut())
            },
            label = "step",
        ) { page ->
            // The welcome's wallpaper reaches under the system bars; the pages keep clear of them.
            val pageModifier = if (page == 0) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            Box(pageModifier) {
                when (page) {
                    0 -> WelcomePage(DESKTOP_BYTES - free) { step = 1 }
                    1 -> PrefsPage(back = { step = 0 }) { step = 2 }
                    2 -> AccountPage(back = { step = 1 }, dot = 1, pages = DOTS) { step = 3 }
                    3 -> AppsPage(state, viewModel, apps, { apps = it }, back = { step = 2 }) { step = 4 }
                    4 -> ChecksPage(
                        state,
                        short = DESKTOP_BYTES + apps.mapNotNull(StoreCatalog::get).sumOf { it.sizeMb } * 1_000_000L - free,
                        back = { step = 3 },
                    ) { viewModel.runSetup(apps) }
                    else -> InstallPage(state, viewModel)
                }
            }
        }
    }
}

/** The desktop's size on disk. */
private const val DESKTOP_BYTES = 2_100_000_000L

/** The app's icon and name over a blurred wallpaper, coming in one after the other; [short] bytes missing keep it from starting. */
@Composable
private fun WelcomePage(short: Long, onStart: () -> Unit) {
    val context = LocalContext.current
    val compact = isCompact()
    val colors = MaterialTheme.colorScheme
    val pop = remember { Animatable(0f) }
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) }
        time.animateTo(1f, tween(1200, easing = LinearEasing))
    }
    // Each line rises into place after the one above.
    fun Modifier.rise(delay: Float) = graphicsLayer {
        val p = FastOutSlowInEasing.transform(((time.value - delay) / 0.4f).coerceIn(0f, 1f))
        alpha = p
        translationY = (1f - p) * 16.dp.toPx()
    }
    Box(
        Modifier.fillMaxSize().wallpaper().drawBehind {
            drawRect(
                Brush.verticalGradient(
                    0f to colors.background.copy(alpha = 0.15f),
                    0.45f to colors.background.copy(alpha = 0.55f),
                    0.85f to colors.background,
                ),
            )
        },
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val icon = if (compact) 96.dp else 120.dp
            Box(
                Modifier.size(icon)
                    .graphicsLayer {
                        alpha = pop.value.coerceIn(0f, 1f)
                        scaleX = 0.6f + 0.4f * pop.value
                        scaleY = scaleX
                    }
                    .clip(RoundedCornerShape(icon / 4))
                    .background(colorResource(R.color.icon_background)),
            ) {
                // The adaptive icon's foreground keeps a margin for the launcher's masks: this tile is the mask.
                Image(
                    painterResource(R.drawable.ic_launcher_foreground), null,
                    Modifier.fillMaxSize().graphicsLayer { scaleX = 1.3f; scaleY = 1.3f },
                )
            }
            Text(
                buildAnnotatedString {
                    append("Droid")
                    withStyle(SpanStyle(color = colors.primary)) { append("Nux") }
                },
                Modifier.padding(top = if (compact) 20.dp else 24.dp).rise(0.3f),
                fontSize = if (compact) 40.sp else 48.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = colors.onSurface,
            )
            Text(
                "${stringResource(R.string.distro_debian)} · ${stringResource(R.string.desktop_name)}",
                Modifier.padding(top = 6.dp).rise(0.42f),
                fontSize = if (compact) 16.sp else 18.sp,
                color = colors.onSurfaceVariant,
            )
            Column(Modifier.rise(0.54f), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onStart,
                    enabled = short <= 0,
                    contentPadding = PaddingValues(horizontal = 40.dp),
                    modifier = Modifier.padding(top = if (compact) 36.dp else 40.dp).height(48.dp),
                ) { Text(stringResource(R.string.wizard_start), fontSize = 16.sp) }
                if (short <= 0) {
                    Text(
                        stringResource(
                            R.string.wizard_space_line,
                            Formatter.formatShortFileSize(context, DESKTOP_BYTES),
                            Formatter.formatShortFileSize(context, DESKTOP_BYTES - short),
                        ),
                        Modifier.padding(top = 18.dp),
                        fontSize = 14.sp,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.wizard_space_short_text, Formatter.formatShortFileSize(context, short)),
                        Modifier.padding(top = 18.dp),
                        fontSize = 14.sp,
                        color = colors.error,
                    )
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }) {
                        Text(stringResource(R.string.wizard_space_open))
                    }
                }
            }
        }
    }
}

/** A Plasma-like wallpaper: soft blue, purple and teal glows on dark blue. */
private fun Modifier.wallpaper() = drawBehind {
    drawRect(Color(0xFF14202E))
    fun glow(color: Long, x: Float, y: Float, radius: Float) = drawRect(
        Brush.radialGradient(listOf(Color(color), Color.Transparent), Offset(size.width * x, size.height * y), size.maxDimension * radius),
    )
    glow(0xFF2C5C8A, 0.25f, 0.3f, 0.45f)
    glow(0xFF6A3F7A, 0.75f, 0.25f, 0.38f)
    glow(0xFF1E6E6A, 0.6f, 0.8f, 0.45f)
}

/** The app's settings worth choosing up front, as in Settings › App; the session menu's place shown on a small screen. */
@Composable
private fun PrefsPage(back: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(DesktopSettings.load(context)) }
    val save: ((DesktopSettings) -> DesktopSettings) -> Unit = { change -> settings = DesktopSettings.update(context, change) }
    var choosingLayout by remember { mutableStateOf(false) }
    WizardPage(
        title = stringResource(R.string.wizard_prefs_title),
        dot = 0,
        back = back,
        actions = { NextButton(R.string.action_next, onClick = onDone) },
    ) {
        ThemeColorRow(settings, save)
        PageSection(R.string.settings_session_menu)
        val menuRows = @Composable {
            SwitchRow(R.string.settings_menu_back, settings.menuBack) { on -> save { it.copy(menuBack = on) } }
            SwitchRow(R.string.settings_menu_shortcut, settings.menuShortcut) { on -> save { it.copy(menuShortcut = on) } }
            ChoiceRow(R.string.settings_menu_position, settings.menuBottom, R.string.settings_menu_top, R.string.settings_menu_bottom) { bottom ->
                save { it.copy(menuBottom = bottom) }
            }
        }
        if (isCompact()) {
            menuRows()
        } else {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { menuRows() }
                MenuPreview(settings.menuBottom, Modifier.width(176.dp).fillMaxHeight())
            }
        }
        PageSection(R.string.settings_keyboard)
        ChoiceRow(R.string.settings_keyboard_kind, settings.androidKeyboard, R.string.settings_keyboard_full, R.string.settings_keyboard_android) { android ->
            save { it.copy(androidKeyboard = android) }
        }
        InfoRow(stringResource(R.string.settings_layout), onClick = { choosingLayout = true }) {
            Text(layoutName(settings.layout), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        }
        PageSection(R.string.settings_startup)
        SwitchRow(R.string.settings_open_at_start, settings.openAtStart) { on -> save { it.copy(openAtStart = on) } }
    }
    if (choosingLayout) {
        LayoutDialog(settings.layout, onDismiss = { choosingLayout = false }) { layout ->
            save { it.copy(layout = layout) }
            choosingLayout = false
        }
    }
}

/** A small desktop with the session menu's handle at the top or at the [bottom]. */
@Composable
private fun MenuPreview(bottom: Boolean, modifier: Modifier) {
    val bias by animateFloatAsState(if (bottom) 1f else -1f, spring(stiffness = 400f), label = "menu")
    Box(modifier.clip(RowShape).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(6.dp)).wallpaper()) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.09f).background(Color(0xD914141A)))
            Box(
                Modifier.align(BiasAlignment(0f, bias)).padding(vertical = 2.dp).size(56.dp, 12.dp)
                    .clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary),
            )
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
        dot = dot.takeIf { back != null },
        pages = pages,
        back = back,
        actions = {
            NextButton(R.string.action_next, enabled = done) {
                Account.savePassword(context, password)
                DesktopSettings.update(context) { it.copy(user = name) }
                onDone()
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val compact = isCompact()
    val columns = if (compact) 1 else 2
    LaunchedEffect(Unit) { viewModel.loadStoreIcons() }
    val megabytes = chosen.mapNotNull(StoreCatalog::get).sumOf { it.sizeMb }
    val total = stringResource(R.string.wizard_apps_total, chosen.size, Formatter.formatShortFileSize(context, megabytes * 1_000_000L))
    WizardPage(
        title = stringResource(R.string.wizard_apps_title),
        dot = 2,
        back = back,
        // On a phone the footer has no room left: the total goes by the title.
        header = { if (compact) HeaderValue(total) },
        actions = {
            if (!compact) Text(total, Modifier.padding(end = 12.dp), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NextButton(R.string.action_next, onClick = onDone)
        },
    ) {
        for ((header, apps) in listOf(R.string.wizard_apps_popular to StoreCatalog.WIZARD_POPULAR, R.string.wizard_apps_more to StoreCatalog.WIZARD_MORE)) {
            PageSection(header, first = header == R.string.wizard_apps_popular)
            apps.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { app ->
                        val selected = app.id in chosen
                        AppChoice(app, selected, state, viewModel, Modifier.weight(1f)) {
                            onChange(if (selected) chosen - app.id else chosen + app.id)
                        }
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
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
        modifier.heightIn(min = 64.dp).clip(RowShape).background(background)
            .toggleable(selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The store's icon (as in the Store), its initial on its color until downloaded.
        val icon by produceState(viewModel.cachedIcon("store:${app.icon}"), state.storeIcons) {
            value = withContext(Dispatchers.IO) { viewModel.storeIcon(app.icon) } ?: value
        }
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.size(40.dp))
        } else {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Color(app.color)), contentAlignment = Alignment.Center) {
                Text(app.name.take(1), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(app.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                Formatter.formatShortFileSize(LocalContext.current, app.sizeMb * 1_000_000L),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = selected, onCheckedChange = null)
    }
}

/** The Android settings the desktop depends on; installing waits for the [short] bytes missing to be freed. */
@Composable
private fun ChecksPage(state: AppState, short: Long, back: () -> Unit, onInstall: () -> Unit) {
    WizardPage(
        title = stringResource(R.string.wizard_checks_title),
        dot = 3,
        back = back,
        actions = { NextButton(R.string.action_install, enabled = short <= 0, onClick = onInstall) },
    ) {
        if (short > 0) SpaceShort(short)
        SystemChecks(state.gpu)
    }
}

/**
 * A step: [title] (changing with a slide) and [header] beside it, [content] in a centered column up to [width],
 * then the footer: Indietro (off without [back]) and the step's [dot] of [pages], [actions] on the right. Without a dot
 * the left side stays empty.
 */
@Composable
private fun WizardPage(
    title: String,
    dot: Int?,
    back: (() -> Unit)?,
    pages: Int = DOTS,
    width: Dp = 640.dp,
    header: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val compact = isCompact()
    val side = if (compact) 24.dp else 48.dp
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().fadingEdges(scroll).verticalScroll(scroll)
                .padding(horizontal = side, vertical = if (compact) 24.dp else 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = width).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(
                        title,
                        Modifier.weight(1f),
                        transitionSpec = {
                            (fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 2 }) togetherWith
                                (fadeOut(tween(250)) + slideOutVertically(tween(250)) { -it / 2 })
                        },
                        label = "title",
                    ) {
                        Text(it, fontSize = if (compact) 26.sp else 28.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    header()
                }
                content()
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = side, end = side, top = 12.dp, bottom = if (compact) 24.dp else 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) {
                    FilledTonalButton(onClick = { back?.invoke() }, enabled = back != null, modifier = Modifier.height(48.dp)) {
                        Text(stringResource(R.string.action_back))
                    }
                    StepDots(dot, pages)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable
private fun NextButton(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 28.dp), modifier = Modifier.height(48.dp)) {
        Text(stringResource(label))
    }
}

/** A value at the end of the title's line. */
@Composable
private fun HeaderValue(text: String) {
    Text(text, fontSize = if (isCompact()) 13.sp else 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The installation would not fit: [short] bytes more are needed. */
@Composable
private fun SpaceShort(short: Long) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RowShape).background(colors.errorContainer).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Warning, null, tint = colors.onErrorContainer)
        Text(
            stringResource(R.string.wizard_space_short_text, Formatter.formatShortFileSize(context, short)),
            Modifier.weight(1f), fontWeight = FontWeight.Medium, color = colors.onErrorContainer,
        )
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }) {
            Text(stringResource(R.string.wizard_space_open), color = colors.onErrorContainer)
        }
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
            val scroll = rememberScrollState()
            Column(Modifier.fadingEdges(scroll).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            val scroll = rememberScrollState()
            Column(Modifier.fadingEdges(scroll).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

/**
 * The installation: a small desktop putting itself together beside the steps. When it is over the title turns
 * into "Ready" and the buttons to leave come in; the desktop opens out of the small one.
 */
@Composable
private fun InstallPage(state: AppState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val view = LocalView.current
    val compact = isCompact()
    val setup = state.setup
    val done = setup?.done == true
    var preview by remember { mutableStateOf<Rect?>(null) }
    val openDesktop = {
        viewModel.finishSetup()
        openDesktop(context, view, preview)
    }
    // The buttons to leave come in once the installation is over.
    val leaving = remember { MutableTransitionState(false) }.apply { targetState = done }

    WizardPage(
        title = stringResource(if (done) R.string.wizard_done_title else R.string.wizard_install_title),
        dot = DOTS - 1,
        back = null,
        width = 960.dp,
        header = {
            AnimatedVisibility(!done, enter = fadeIn(), exit = fadeOut()) {
                HeaderValue(installProgressText(overallProgress(state), running = setup?.error == null))
            }
        },
        actions = {
            if (setup?.error != null) NextButton(R.string.action_retry) { viewModel.runSetup() }
            AnimatedVisibility(leaving, enter = fadeIn() + slideInVertically { it / 2 }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!compact) TextButton(onClick = viewModel::finishSetup, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.action_home)) }
                    NextButton(R.string.action_open_desktop, onClick = openDesktop)
                }
            }
        },
    ) {
        val desktop = @Composable { modifier: Modifier ->
            DesktopPreview(done, modifier.onGloballyPositioned { preview = it.boundsInRoot() })
        }
        if (compact) {
            desktop(Modifier.fillMaxWidth().height(214.dp))
            Spacer(Modifier.height(4.dp))
            InstallSteps(state)
            AnimatedVisibility(leaving, Modifier.align(Alignment.CenterHorizontally), enter = fadeIn() + slideInVertically { it / 2 }) {
                TextButton(onClick = viewModel::finishSetup) { Text(stringResource(R.string.action_home)) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                desktop(Modifier.weight(1.6f).aspectRatio(1.6f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { InstallSteps(state) }
            }
        }
        InstallLog(state)
    }
}

/** How far the installation is, and, once it has gone on a while, about how long is left at the pace so far. */
@Composable
private fun installProgressText(total: Float, running: Boolean): String {
    val percent = (total * 100).toInt()
    val start = remember { System.currentTimeMillis() to total }
    val elapsed = System.currentTimeMillis() - start.first
    val gained = total - start.second
    if (!running || elapsed < 30_000 || gained < 0.02f) return stringResource(R.string.install_percent, percent)
    val minutes = ceil(elapsed * (1f - total) / gained / 60_000).toInt()
    return if (minutes <= 1) stringResource(R.string.install_eta_soon, percent) else stringResource(R.string.install_eta, percent, minutes)
}

/** A desktop putting itself together in a loop (panel, wallpaper, icons, a window) while the real one installs; whole when [done]. */
@Composable
private fun DesktopPreview(done: Boolean, modifier: Modifier) {
    val loop = rememberInfiniteTransition(label = "desktop")
    val time = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "time")
    // Read in the layers only: the loop redraws without recomposing.
    val t = { if (done) 0.9f else time.value }
    fun phase(from: Float, to: Float) = FastOutSlowInEasing.transform(((t() - from) / (to - from)).coerceIn(0f, 1f))
    fun shown() = 1f - phase(0.94f, 1f)
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF0B0B0E))) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = phase(0.36f, 0.46f) * shown() }.wallpaper())
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth(0.5f).fillMaxHeight(0.48f)
                .graphicsLayer {
                    val p = phase(0.8f, 0.86f) * shown()
                    alpha = p
                    scaleX = 0.9f + 0.1f * p
                    scaleY = scaleX
                }
                .clip(RoundedCornerShape(6.dp)).background(Color(0xFF232329)),
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.13f).background(Color(0xFF2C2C34)))
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.09f)
                .graphicsLayer {
                    translationY = size.height * (1f - phase(0.14f, 0.22f))
                    alpha = shown()
                }
                .background(Color(0xEB16161C)).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = Modifier.fillMaxHeight(0.65f).aspectRatio(1f)
            Box(icon.clip(RoundedCornerShape(20)).background(accent))
            Box(Modifier.width(1.dp).fillMaxHeight(0.55f).background(Color(0xFF44444C)))
            Box(icon.clip(RoundedCornerShape(20)).background(Color(0xFF3B7DD8)))
            // The apps arrive last, dropping into the panel one after the other.
            for ((index, color) in listOf(0xFF1E9E5A, 0xFF6E5A4A, 0xFF2F80D1).withIndex()) {
                Box(
                    icon.graphicsLayer {
                        val p = phase(0.58f + index * 0.05f, 0.64f + index * 0.05f) * shown()
                        alpha = p
                        translationY = -(1f - p) * 80.dp.toPx()
                    }.clip(RoundedCornerShape(20)).background(Color(color)),
                )
            }
        }
    }
}

@Composable
private fun InstallSteps(state: AppState) {
    val context = LocalContext.current
    val setup = state.setup
    val step = setup?.step ?: SetupStep.ROOTFS
    StepRow(stringResource(R.string.distro_debian), state.rootfs)
    val apt = state.desktopProgress
    StepRow(
        stringResource(R.string.desktop_name),
        when {
            state.desktopInstalling -> StepStatus.RUNNING
            state.desktopInstalled && state.desktopUpToDate && step > SetupStep.DESKTOP -> StepStatus.DONE
            step == SetupStep.DESKTOP && setup?.error != null -> StepStatus.FAILED
            else -> StepStatus.WAITING
        },
        when {
            !state.desktopInstalling -> ""
            apt.packages == 0 -> stringResource(R.string.status_package_list)
            apt.downloading && apt.totalBytes > 0 -> stringResource(
                R.string.status_bytes,
                Formatter.formatShortFileSize(context, apt.fetchedBytes),
                Formatter.formatShortFileSize(context, apt.totalBytes),
            )
            apt.downloading -> stringResource(R.string.status_download)
            else -> stringResource(R.string.install_packages, apt.setUp, apt.packages)
        },
        if (state.desktopInstalling) apt.fraction() else null,
    )
    StepRow(stringResource(R.string.install_gpu), state.components[Component.MESA.id])
    StepRow(stringResource(R.string.plasma_component), state.components[Component.PLASMA.id])
    val apps = setup?.apps.orEmpty().mapNotNull(StoreCatalog::get)
    if (apps.isNotEmpty()) {
        val current = apps.indexOfFirst { state.storeJobs[it.id] is StoreJob.Running }
        val failed = apps.filter { state.storeJobs[it.id] is StoreJob.Failed }
        val done = setup?.done == true
        StepRow(
            stringResource(R.string.install_apps),
            when {
                current >= 0 -> StepStatus.RUNNING
                done -> if (failed.isEmpty()) StepStatus.DONE else StepStatus.FAILED
                else -> StepStatus.WAITING
            },
            when {
                current >= 0 -> stringResource(R.string.install_apps_now, apps[current].name, current + 1, apps.size)
                done && failed.isNotEmpty() -> pluralStringResource(R.plurals.install_apps_failed, failed.size, failed.joinToString { it.name })
                done -> ""
                else -> apps.size.toString()
            },
            appsProgress(state, apps).takeIf { current >= 0 },
        )
    }
    setup?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
}

/** The installation's log on request, and sharing it after a failure. */
@Composable
private fun InstallLog(state: AppState) {
    val context = LocalContext.current
    var showLog by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row {
        TextButton(onClick = { showLog = !showLog }) {
            Text(stringResource(if (showLog) R.string.install_hide_log else R.string.install_show_log))
        }
        if (state.setup?.error != null) {
            TextButton(
                onClick = {
                    sharing = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { SessionLog.share(context, state.display, state.desktopLog) } }
                            .onSuccess { context.startActivity(it) }
                        sharing = false
                    }
                },
                enabled = !sharing,
            ) { Text(stringResource(R.string.install_share_log)) }
        }
    }
    AnimatedVisibility(showLog && state.desktopLog.isNotEmpty()) {
        Text(
            state.desktopLog.lines().takeLast(LOG_LINES_SHOWN).joinToString("\n"),
            modifier = Modifier.fillMaxWidth().clip(RowShape).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(12.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val LOG_LINES_SHOWN = 8

private enum class StepStatus { WAITING, RUNNING, DONE, FAILED }

/** A step installed as a component: its state, what it is doing and how far. */
@Composable
private fun StepRow(title: String, install: InstallState?) {
    val status = when (install) {
        InstallState.Installed -> StepStatus.DONE
        is InstallState.Installing -> StepStatus.RUNNING
        is InstallState.Failed -> StepStatus.FAILED
        else -> StepStatus.WAITING
    }
    val progress = (install as? InstallState.Installing)?.progress
    val value = when (progress) {
        is InstallProgress.Downloading -> stringResource(
            R.string.status_bytes,
            Formatter.formatShortFileSize(LocalContext.current, progress.bytes),
            Formatter.formatShortFileSize(LocalContext.current, progress.total),
        )
        is InstallProgress.Extracting -> stringResource(R.string.status_extract)
        else -> (install as? InstallState.Failed)?.message.orEmpty()
    }
    StepRow(title, status, value, progress?.fraction())
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

/** A step of the installation: its icon, title and [value]; while it runs, highlighted with a bar under it. */
@Composable
private fun StepRow(title: String, status: StepStatus, value: String, fraction: Float?) {
    val colors = MaterialTheme.colorScheme
    val running = status == StepStatus.RUNNING
    val background by animateColorAsState(if (running) colors.secondaryContainer else colors.surfaceContainerLow, label = "step")
    val compact = isCompact()
    Column(
        Modifier.fillMaxWidth().heightIn(min = if (compact) 46.dp else 52.dp).clip(RowShape).background(background)
            .padding(horizontal = 16.dp, vertical = if (running) 12.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(status, Modifier.size(22.dp), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") {
                when (it) {
                    StepStatus.RUNNING -> CircularProgressIndicator(Modifier.padding(2.dp), color = colors.onSecondaryContainer, strokeWidth = 2.dp)
                    StepStatus.DONE -> Icon(Icons.Selected, null, tint = colors.primary)
                    StepStatus.FAILED -> Icon(Icons.Warning, null, tint = colors.error)
                    StepStatus.WAITING -> Icon(Icons.Pending, null, tint = colors.outline)
                }
            }
            Text(title, Modifier.weight(1f), fontSize = if (compact) 14.sp else 15.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
            AnimatedContent(value, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "value") {
                Text(
                    it, fontSize = 13.sp, maxLines = 2,
                    color = when (status) {
                        StepStatus.FAILED -> colors.error
                        StepStatus.RUNNING -> colors.onSecondaryContainer
                        else -> colors.onSurfaceVariant
                    },
                )
            }
        }
        if (running) {
            val bar = Modifier.fillMaxWidth().padding(start = 36.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = bar) else LinearProgressIndicator(bar)
        }
    }
}
