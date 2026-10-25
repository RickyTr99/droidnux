package app.droidnux.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.droidnux.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the store lists: everything, one category, or what is installed. */
private const val FILTER_ALL = -1
private const val FILTER_INSTALLED = -2

/** An entry of the store: a catalog app, or a package found by the search ([app] null). */
private data class Entry(val key: String, val name: String, val packages: List<String>, val app: StoreApp?, val summary: String?)

private fun StoreApp.entry() = Entry(id, name, packages, this, null)

/**
 * The store: Debian's apps (and VS Code) installed into the desktop with apt.
 * A catalog by category, or a search over all of Debian when there is text
 * in the field.
 */
@Composable
fun StoreTab(state: AppState, viewModel: AppViewModel, padding: PaddingValues) {
    var query by rememberSaveable { mutableStateOf(state.storeSearch?.query.orEmpty()) }
    var filter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var removing by remember { mutableStateOf<Entry?>(null) }
    val search = state.storeSearch?.takeIf { query.isNotBlank() }
    val row = @Composable { entry: Entry -> StoreRow(entry, state, viewModel) { removing = entry } }
    LaunchedEffect(Unit) { viewModel.loadStoreIcons() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(300.dp),
        contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SearchField(query) {
                query = it
                viewModel.storeSearch(it)
            }
        }
        if (search != null) {
            val results = search.results
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(
                    when {
                        results == null -> stringResource(R.string.store_searching)
                        results.isEmpty() -> stringResource(R.string.store_no_results, search.query)
                        else -> stringResource(R.string.store_results)
                    },
                    Modifier.padding(top = 8.dp),
                )
            }
            items(results.orEmpty(), key = { it.name }) { found ->
                // A catalog app found by its package shows as that app.
                val app = StoreCatalog.APPS.firstOrNull { it.packages.first() == found.name }
                row(app?.entry() ?: Entry(found.name, found.name, listOf(found.name), null, found.summary))
            }
            return@LazyVerticalGrid
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val chips = listOf(FILTER_ALL to R.string.store_all) +
                    StoreCategory.entries.map { it.ordinal to it.label } + (FILTER_INSTALLED to R.string.store_installed)
                items(chips, key = { it.first }) { (value, label) ->
                    FilterChip(stringResource(label), filter == value) { filter = value }
                }
            }
        }
        when (filter) {
            FILTER_INSTALLED -> {
                val installed = StoreCatalog.APPS.filter { it.packages.first() in state.packages }.map { it.entry() } +
                    state.storePackages.filter { it in state.packages }.map { Entry(it, it, listOf(it), null, null) }
                if (installed.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.store_installed_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    }
                }
                items(installed, key = { it.key }) { row(it) }
            }
            FILTER_ALL -> {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.store_featured), Modifier.padding(top = 8.dp)) }
                items(StoreCatalog.FEATURED, key = { "featured-${it.id}" }) { app ->
                    FeaturedCard(app.entry(), state, viewModel) { removing = app.entry() }
                }
                for (category in StoreCategory.entries) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(category.label), Modifier.padding(top = 12.dp)) }
                    items(StoreCatalog.APPS.filter { it.category == category && it !in StoreCatalog.FEATURED }, key = { it.id }) { row(it.entry()) }
                }
            }
            else -> {
                val category = StoreCategory.entries[filter]
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(category.label), Modifier.padding(top = 8.dp)) }
                items(StoreCatalog.APPS.filter { it.category == category }, key = { it.id }) { row(it.entry()) }
            }
        }
    }
    removing?.let { entry ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.store_remove_title, entry.name)) },
            text = { Text(stringResource(R.string.store_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    viewModel.storeRemove(entry.key, entry.packages)
                }) { Text(stringResource(R.string.store_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(colors.surfaceContainerHigh)
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Search, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(stringResource(R.string.store_search), color = colors.onSurfaceVariant, fontSize = 16.sp)
                    field()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onChange("") }) {
                        Icon(Icons.Close, stringResource(R.string.action_cancel), tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
            }
        },
    )
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(if (selected) colors.secondaryContainer else Color.Transparent, label = "chip")
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(background)
            .then(if (selected) Modifier else Modifier.border(1.dp, colors.outlineVariant, RoundedCornerShape(18.dp)))
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) colors.onSurface else colors.onSurfaceVariant)
    }
}

/** The launcher of an installed entry, looked up off the main thread (null while not installed). */
@Composable
private fun launcherOf(entry: Entry, installed: Boolean, viewModel: AppViewModel): String? {
    val launcher by produceState<String?>(null, entry.key, installed) {
        value = if (installed) withContext(Dispatchers.IO) { viewModel.storeLauncher(entry.packages, entry.app?.launcher) } else null
    }
    return launcher
}

/** The app's own icon once installed, else Debian's (or Flathub's), else a tile in its color with its initial. */
@Composable
private fun StoreIcon(entry: Entry, launcher: String?, state: AppState, viewModel: AppViewModel, size: Dp) {
    val name = entry.app?.icon ?: entry.packages.first()
    val icon by produceState(launcher?.let(viewModel::cachedIcon) ?: viewModel.cachedIcon("store:$name"), launcher, state.storeIcons) {
        value = withContext(Dispatchers.IO) { launcher?.let(viewModel::icon) ?: viewModel.storeIcon(name) } ?: value
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), null, Modifier.size(size))
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
                .background(entry.app?.let { Color(it.color) } ?: MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(entry.name.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun FeaturedCard(entry: Entry, state: AppState, viewModel: AppViewModel, onRemove: () -> Unit) {
    val job = state.storeJobs[entry.key]
    val installed = entry.packages.first() in state.packages
    val launcher = launcherOf(entry, installed, viewModel)
    val background by animateColorAsState(
        if (job is StoreJob.Running || job == StoreJob.Queued) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "card",
    )
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(background).padding(20.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            StoreIcon(entry, launcher, state, viewModel, 56.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.name, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(entry.app?.vendor ?: R.string.store_vendor_debian), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (job != null) {
            JobStatus(job)
        } else if (entry.app != null) {
            Text(stringResource(entry.app.summary), fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            StoreActions(entry, job, installed, launcher, state, viewModel, onRemove)
        }
    }
}

@Composable
private fun StoreRow(entry: Entry, state: AppState, viewModel: AppViewModel, onRemove: () -> Unit) {
    val job = state.storeJobs[entry.key]
    val installed = entry.packages.first() in state.packages
    val launcher = launcherOf(entry, installed, viewModel)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RowShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            StoreIcon(entry, launcher, state, viewModel, 36.dp)
            Column(Modifier.weight(1f)) {
                Text(entry.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val summary = entry.summary ?: entry.app?.let { stringResource(it.summary) }
                if (summary != null) {
                    Text(summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            StoreActions(entry, job, installed, launcher, state, viewModel, onRemove)
        }
        if (job != null) JobStatus(job)
    }
}

/** What a job is doing, with its progress. */
@Composable
private fun JobStatus(job: StoreJob) {
    val text = when (job) {
        StoreJob.Queued -> stringResource(R.string.store_queued)
        is StoreJob.Running -> if (job.removing) stringResource(R.string.store_removing)
            else if (job.total > 0) stringResource(R.string.store_installing, job.done, job.total)
            else stringResource(R.string.status_preparing)
        is StoreJob.Failed -> job.message
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text,
            fontSize = 14.sp,
            color = if (job is StoreJob.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val bar = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        when {
            job is StoreJob.Running && job.total > 0 -> LinearProgressIndicator(progress = { job.done.toFloat() / job.total }, modifier = bar)
            job is StoreJob.Running -> LinearProgressIndicator(bar)
            else -> Unit
        }
    }
}

@Composable
private fun StoreActions(
    entry: Entry,
    job: StoreJob?,
    installed: Boolean,
    launcher: String?,
    state: AppState,
    viewModel: AppViewModel,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val padding = PaddingValues(horizontal = 18.dp)
    when {
        job == StoreJob.Queued || (job is StoreJob.Running && !job.removing) ->
            TextButton(onClick = { viewModel.storeCancel(entry.key) }) { Text(stringResource(R.string.action_cancel)) }
        job is StoreJob.Running -> Unit
        installed -> {
            // The desktop's own packages stay (the search finds them too).
            if (entry.packages.none { it in app.droidnux.container.DesktopEnvironment.desktopPackages }) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.store_remove)) }
            }
            if (launcher != null) {
                FilledTonalButton(onClick = { launchApp(context, launcher, state.apps?.firstOrNull { it.id == launcher }) }, contentPadding = padding) {
                    Text(stringResource(R.string.store_open))
                }
            }
        }
        job is StoreJob.Failed -> Button(onClick = { viewModel.storeInstall(entry.key, entry.packages) }, contentPadding = padding) {
            Text(stringResource(R.string.action_retry))
        }
        else -> Button(onClick = { viewModel.storeInstall(entry.key, entry.packages) }, contentPadding = padding) {
            Text(stringResource(R.string.action_install))
        }
    }
}
