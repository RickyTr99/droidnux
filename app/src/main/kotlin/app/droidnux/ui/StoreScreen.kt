package app.droidnux.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.RadioButton
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.droidnux.R
import app.droidnux.container.AppStore
import app.droidnux.container.BackportsCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A page of the store over its home: a shelf's apps, a category, the installed apps, or one app ([key] of its item). */
private sealed interface StorePage {
    data class Shelf(val source: StoreSource) : StorePage
    data class Category(val category: StoreCategory) : StorePage
    data object Installed : StorePage
    data object Updates : StorePage
    data class App(val key: String) : StorePage
}

private fun StorePage.route(): String = when (this) {
    is StorePage.Shelf -> "src:${source.name}"
    is StorePage.Category -> "cat:${category.name}"
    StorePage.Installed -> "installed"
    StorePage.Updates -> "updates"
    is StorePage.App -> "app:$key"
}

private fun storePage(route: String): StorePage? = when {
    route.startsWith("src:") -> StoreSource.entries.firstOrNull { it.name == route.substring(4) }?.let(StorePage::Shelf)
    route.startsWith("cat:") -> StoreCategory.entries.firstOrNull { it.name == route.substring(4) }?.let(StorePage::Category)
    route == "installed" -> StorePage.Installed
    route == "updates" -> StorePage.Updates
    route.startsWith("app:") -> StorePage.App(route.substring(4))
    else -> null
}

/** What a page needs to open another one and to ask before removing an app. */
private class StoreNav(val open: (StorePage) -> Unit, val back: () -> Unit, val remove: (StoreItem) -> Unit)

/**
 * The store: Debian's apps (and VS Code) installed into the desktop with apt. Its home has the
 * search, the featured apps and the categories; each opens its page, and an app its own.
 */
@Composable
fun StoreTab(state: AppState, viewModel: AppViewModel, padding: PaddingValues) {
    // The pages opened over the home, one route per line (saved across recreations).
    var routes by rememberSaveable { mutableStateOf("") }
    val stack = routes.lines().mapNotNull(::storePage)
    var removing by remember { mutableStateOf<StoreItem?>(null) }
    val nav = StoreNav(
        open = { page -> routes = (stack + page).joinToString("\n") { it.route() } },
        back = { routes = stack.dropLast(1).joinToString("\n") { it.route() } },
        remove = { removing = it },
    )
    BackHandler(enabled = stack.isNotEmpty()) { nav.back() }
    LaunchedEffect(Unit) {
        viewModel.loadStoreIcons()
        viewModel.loadStoreCatalog()
        viewModel.checkStoreUpdates(ifOlderThan = UPDATES_CHECK_MS)
    }

    AnimatedContent(
        stack,
        transitionSpec = {
            val forward = targetState.size >= initialState.size
            (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { if (forward) it / 10 else -it / 10 }) togetherWith
                (fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { if (forward) -it / 20 else it / 20 })
        },
        contentKey = { it.lastOrNull()?.route() },
        label = "store",
    ) { pages ->
        when (val page = pages.lastOrNull()) {
            null -> StoreHome(state, viewModel, padding, nav)
            is StorePage.Shelf -> {
                val items = remember(state.storeItems, page.source) { sourceItems(state.storeItems, page.source) }
                ListPage(stringResource(shelfTitle(page.source)), items, emptyList(), state, viewModel, padding, nav)
            }
            is StorePage.Category -> {
                val items = remember(state.storeItems, page.category) { state.storeItems.filter { it.category == page.category } }
                ListPage(stringResource(page.category.label), items, page.category.filters, state, viewModel, padding, nav)
            }
            StorePage.Installed -> ListPage(stringResource(R.string.store_installed), installedItems(state), emptyList(), state, viewModel, padding, nav)
            StorePage.Updates -> UpdatesPage(state, viewModel, padding, nav)
            is StorePage.App -> {
                val item = findItem(state, page.key)
                if (item != null) AppPage(item, state, viewModel, padding, nav) else LaunchedEffect(Unit) { nav.back() }
            }
        }
    }

    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.store_remove_title, item.name)) },
            text = { Text(stringResource(R.string.store_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    viewModel.storeRemove(item.key, item.packages)
                }) { Text(stringResource(R.string.store_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** An item by its key: in the store, among apt's search results, or a package installed from them. */
private fun findItem(state: AppState, key: String): StoreItem? =
    state.storeItems.firstOrNull { it.key == key }
        ?: state.storeSearch?.more?.firstOrNull { it.name == key }?.let(::packageItem)
        ?: key.takeIf { it in state.storePackages }?.let { packageItem(app.droidnux.container.AppStore.Found(it, "")) }

private fun packageItem(found: app.droidnux.container.AppStore.Found) =
    StoreItem(found.name, found.name, found.summary.ifEmpty { null }, listOf(found.name), StoreCategory.TOOLS)

/** The store's apps that are installed, and the packages installed from its search. */
private fun installedItems(state: AppState): List<StoreItem> {
    fun installed(item: StoreItem) = item.isInstalled(state.packages) && (item.app != null || item.key in state.storePackages)
    // An app installed from another source (Flathub, backports) is listed as its main entry, whose page has them all.
    val items = state.storeItems.filter { item -> item.sources.any(::installed) }
    val shown = items.flatMapTo(HashSet()) { item -> item.sources.map { it.key } }
    return items + state.storePackages.filter { it in state.packages && it !in shown }.map { packageItem(app.droidnux.container.AppStore.Found(it, "")) }
}

// -- Home --------------------------------------------------------------------------------------

@Composable
private fun StoreHome(state: AppState, viewModel: AppViewModel, padding: PaddingValues, nav: StoreNav) {
    var query by rememberSaveable { mutableStateOf(state.storeSearch?.query.orEmpty()) }
    val setQuery = { text: String ->
        query = text
        viewModel.storeSearch(text)
    }
    BackHandler(enabled = query.isNotEmpty()) { setQuery("") }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(top = padding.calculateTopPadding()).horizontal(padding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { SearchField(query, stringResource(R.string.store_search_hint), setQuery) }
            IconButton(
                onClick = { nav.open(StorePage.Installed) },
                modifier = Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Icon(Icons.Check, stringResource(R.string.store_installed), modifier = Modifier.size(22.dp))
            }
            val updates = remember(state.storeUpdates, state.packages, state.storeItems) { updatesByApp(state) }
            val count = updates.first.size + if (updates.second.isEmpty()) 0 else 1
            BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
                IconButton(
                    onClick = { nav.open(StorePage.Updates) },
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Icon(Icons.Update, stringResource(R.string.store_updates), modifier = Modifier.size(22.dp))
                }
            }
        }
        // The field stays where it is: typing swaps what is under it, the keyboard keeps its focus.
        Crossfade(query.isBlank(), label = "search") { home ->
            if (home) HomeList(state, viewModel, padding, nav) else SearchResults(query, state, viewModel, padding, nav)
        }
    }
}

/** The shelves of the home, in order: each with its source, whose page lists them all (none for the suggested). */
private val SHELVES = listOf(
    R.string.store_shelf_vendors to StoreSource.VENDOR,
    R.string.store_shelf_github to StoreSource.GITHUB,
    R.string.store_shelf_suggested to null,
    R.string.store_shelf_flathub to StoreSource.FLATHUB,
)

private fun shelfTitle(source: StoreSource) = SHELVES.first { it.second == source }.first

/** The store's items that [source] has, as their main entry (whose page has every source). */
private fun sourceItems(items: List<StoreItem>, source: StoreSource) = items.filter { item -> item.sources.any { it.source == source } }

@Composable
private fun HomeList(state: AppState, viewModel: AppViewModel, padding: PaddingValues, nav: StoreNav) {
    val compact = isCompact()
    val byKey = remember(state.storeItems) { state.storeItems.associateBy { it.key } }
    val featured = remember(byKey) { StoreCatalog.FEATURED.mapNotNull { byKey[it.id] } }
    val shelves = remember(state.storeItems) {
        SHELVES.map { (title, source) ->
            Triple(title, source, source?.let { sourceItems(state.storeItems, it) } ?: StoreCatalog.SUGGESTED.mapNotNull { byKey[it.id] })
        }.filter { it.third.isNotEmpty() }
    }
    val list = rememberLazyListState()
    LazyColumn(
        state = list,
        contentPadding = padding.below(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize().fadingEdges(list),
    ) {
        item(key = "banner") { Banner(featured, state, viewModel, compact, nav) }
        if (state.storeCatalog == CatalogState.FAILED) {
            item(key = "failed") {
                Text(stringResource(R.string.store_catalog_failed), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "categories") {
            val row = rememberLazyListState()
            LazyRow(state = row, modifier = Modifier.fadingEdges(row, vertical = false), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(stringResource(R.string.store_for_you), true) {} }
                items(StoreCategory.entries, key = { it.name }) { category ->
                    FilterChip(stringResource(category.label), false) { nav.open(StorePage.Category(category)) }
                }
            }
        }
        for ((title, source, items) in shelves) {
            item(key = title) {
                Shelf(stringResource(title), items, source?.let { { nav.open(StorePage.Shelf(it)) } }, state, viewModel, compact, nav)
            }
        }
    }
}

/** A row of app cards under a title: six across a tablet, more scroll in; [all] opens the page with every one. */
@Composable
private fun Shelf(title: String, items: List<StoreItem>, all: (() -> Unit)?, state: AppState, viewModel: AppViewModel, compact: Boolean, nav: StoreNav) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(title, Modifier.weight(1f))
            if (all != null) TextButton(onClick = all) { Text(stringResource(R.string.store_show_all, items.size)) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = if (compact) 10.dp else 12.dp
            val width = if (compact) 108.dp else (maxWidth - gap * 5) / 6
            val row = rememberLazyListState()
            // A few cards each: the home decodes a few dozen icons, not a catalog's worth.
            LazyRow(state = row, modifier = Modifier.fadingEdges(row, vertical = false), horizontalArrangement = Arrangement.spacedBy(gap)) {
                items(items.take(SHELF_CARDS), key = { it.key }) { item -> AppCard(item, state, viewModel, width, compact, nav) }
            }
        }
    }
}

@Composable
private fun AppCard(item: StoreItem, state: AppState, viewModel: AppViewModel, width: Dp, compact: Boolean, nav: StoreNav) {
    val installed = item.sources.firstOrNull { it.isInstalled(state.packages) }
    val launcher = launcherOf(installed ?: item, installed != null, viewModel)
    Column(
        Modifier.width(width).clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { nav.open(StorePage.App(item.key)) }.padding(if (compact) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StoreIcon(item, launcher, state, viewModel, if (compact) 44.dp else 52.dp)
        Column {
            Text(item.name, fontSize = if (compact) 14.sp else 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(developerOf(item), fontSize = if (compact) 12.sp else 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The search, under the home's field: the store's apps and catalogs as you type (in memory), a few
 * at first, with filters from their categories; the rest of Debian's packages only when asked (apt).
 */
@Composable
private fun SearchResults(query: String, state: AppState, viewModel: AppViewModel, padding: PaddingValues, nav: StoreNav) {
    var filter by remember(query) { mutableStateOf<String?>(null) }
    var all by remember(query) { mutableStateOf(false) }
    val search = state.storeSearch?.takeIf { it.query == query }
    val results = search?.results.orEmpty()
    val filters = remember(results) {
        results.flatMap { item -> item.catalog?.categories.orEmpty().filter { it in StoreCategory.FILTER_LABELS }.distinct() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(MAX_SEARCH_FILTERS).map { it.key to it.value }
    }
    val shown = filter?.let { f -> results.filter { f in it.catalog?.categories.orEmpty() } } ?: results
    val more = search?.more
    val list = rememberLazyGridState()
    LazyVerticalGrid(
        state = list,
        columns = GridCells.Adaptive(360.dp),
        contentPadding = padding.below(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().fadingEdges(list),
    ) {
        if (search == null) return@LazyVerticalGrid
        if (filters.size > 1) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                val row = rememberLazyListState()
                LazyRow(state = row, modifier = Modifier.fadingEdges(row, vertical = false), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip("${stringResource(R.string.store_all)} · ${results.size}", filter == null) { filter = null } }
                    items(filters, key = { it.first }) { (name, count) ->
                        val label = StoreCategory.FILTER_LABELS[name]?.let { stringResource(it) } ?: name
                        FilterChip("$label · $count", filter == name) { filter = if (filter == name) null else name }
                    }
                }
            }
        }
        if (results.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "none") {
                Text(stringResource(R.string.store_no_results, search.query), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(if (all) shown else shown.take(SEARCH_FIRST_RESULTS), key = { it.key }) { item -> StoreRow(item, state, viewModel, nav, actions = false) }
        item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                if (!all && shown.size > SEARCH_FIRST_RESULTS) {
                    FilledTonalButton(onClick = { all = true }) { Text(stringResource(R.string.store_show_all, shown.size)) }
                }
                if (more == null) {
                    TextButton(onClick = viewModel::storeSearchPackages) {
                        Icon(Icons.Terminal, null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.store_search_packages), modifier = Modifier.padding(start = 8.dp))
                    }
                } else if (search.searchingMore) {
                    Text(stringResource(R.string.store_searching), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!more.isNullOrEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "packages") { SectionHeader(stringResource(R.string.store_more_packages), Modifier.padding(top = 12.dp)) }
            items(more.map(::packageItem), key = { "pkg:" + it.key }) { item -> StoreRow(item, state, viewModel, nav, actions = false) }
        } else if (more != null && !search.searchingMore) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "packages") {
                Text(stringResource(R.string.store_no_results, search.query), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun LazyGridScope.rows(items: List<StoreItem>, state: AppState, viewModel: AppViewModel, nav: StoreNav) {
    items(items, key = { it.key }) { item -> StoreRow(item, state, viewModel, nav) }
}

/** The featured apps, one card at a time: they turn by themselves until the finger takes over. */
@Composable
private fun Banner(items: List<StoreItem>, state: AppState, viewModel: AppViewModel, compact: Boolean, nav: StoreNav) {
    if (items.isEmpty()) return
    val pager = rememberPagerState { items.size }
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pager.settledPage, dragged) {
        if (dragged) return@LaunchedEffect
        delay(BANNER_TURN_MS)
        pager.animateScrollToPage((pager.settledPage + 1) % items.size, animationSpec = tween(600))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalPager(
            pager,
            pageSpacing = 12.dp,
            key = { items[it].key },
            modifier = Modifier.fillMaxWidth().height(if (compact) 280.dp else 260.dp),
        ) { index -> BannerCard(items[index], state, viewModel, compact, nav) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            repeat(items.size) { index ->
                val selected = pager.currentPage == index
                val width by animateDpAsState(if (selected) 20.dp else 6.dp, tween(250), label = "dot")
                val color by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, tween(250), label = "dot",
                )
                Box(Modifier.size(width, 6.dp).clip(CircleShape).background(color))
            }
        }
    }
}

@Composable
private fun BannerCard(page: StoreItem, state: AppState, viewModel: AppViewModel, compact: Boolean, nav: StoreNav) {
    val current = page.sources.firstOrNull { it.isInstalled(state.packages) }
    val item = current ?: page
    val installed = current != null
    val launcher = launcherOf(item, installed, viewModel)
    val job = page.sources.firstNotNullOfOrNull { state.storeJobs[it.key] }
    val shot = item.catalog?.screenshots?.firstOrNull()
    val shape = RoundedCornerShape(28.dp)
    val open = { nav.open(StorePage.App(page.key)) }
    if (compact) {
        Column(Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = open)) {
            Shot(shot, item, state, viewModel, Modifier.fillMaxWidth().weight(1f))
            Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    StoreIcon(item, launcher, state, viewModel, 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(summaryOf(item), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    StoreActions(item, job, installed, launcher, state, viewModel, nav)
                }
                if (job != null) JobStatus(job)
            }
        }
        return
    }
    Row(Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = open)) {
        Column(
            Modifier.weight(0.44f).fillMaxHeight().padding(horizontal = 36.dp, vertical = 28.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            SectionHeader(stringResource(R.string.store_featured))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                StoreIcon(item, launcher, state, viewModel, 64.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.name, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${developerOf(item)} · ${stringResource(item.category.label)}",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (job != null) {
                JobStatus(job)
            } else {
                Text(summaryOf(item), fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StoreActions(item, job, installed, launcher, state, viewModel, nav) }
        }
        Shot(shot, item, state, viewModel, Modifier.weight(0.56f).fillMaxHeight().padding(top = 20.dp, end = 20.dp, bottom = 20.dp).clip(RoundedCornerShape(16.dp)))
    }
}

// -- Lists -------------------------------------------------------------------------------------

/** A category (or the installed apps): its filters, a search inside it, its apps. */
@Composable
private fun ListPage(
    title: String,
    items: List<StoreItem>,
    filters: List<String>,
    state: AppState,
    viewModel: AppViewModel,
    padding: PaddingValues,
    nav: StoreNav,
) {
    var query by rememberSaveable(title) { mutableStateOf("") }
    var filter by rememberSaveable(title) { mutableStateOf<String?>(null) }
    val resources = LocalResources.current
    val counts = remember(items, filters) {
        filters.map { f -> f to items.count { f in it.catalog?.categories.orEmpty() } }.filter { it.second > 0 }
    }
    val shown = remember(items, filter, query) {
        val filtered = filter?.let { f -> items.filter { f in it.catalog?.categories.orEmpty() } } ?: items
        if (query.isBlank()) filtered else StoreItem.search(filtered, query) { resources.getString(it) }
    }
    BackHandler(enabled = query.isNotEmpty()) { query = "" }
    Column(Modifier.fillMaxSize()) {
        PageHeader(title, padding, nav) { SearchField(query, stringResource(R.string.store_search_in, title), { query = it }) }
        val list = rememberLazyGridState()
        LazyVerticalGrid(
            state = list,
            columns = GridCells.Adaptive(300.dp),
            contentPadding = padding.below(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().fadingEdges(list),
        ) {
            if (counts.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                    val row = rememberLazyListState()
                    LazyRow(state = row, modifier = Modifier.fadingEdges(row, vertical = false), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip("${stringResource(R.string.store_all)} · ${items.size}", filter == null) { filter = null } }
                        items(counts, key = { it.first }) { (name, count) ->
                            val label = StoreCategory.FILTER_LABELS[name]?.let { stringResource(it) } ?: name
                            FilterChip("$label · $count", filter == name) { filter = if (filter == name) null else name }
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (query.isNotBlank()) stringResource(R.string.store_no_results, query) else stringResource(R.string.store_installed_empty),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            rows(shown, state, viewModel, nav)
        }
    }
}

/** A page's top: back, its title, and [trailing] (its search) beside them, or under them on a phone. */
@Composable
private fun PageHeader(title: String, padding: PaddingValues, nav: StoreNav, trailing: @Composable () -> Unit) {
    val compact = isCompact()
    val back = @Composable {
        IconButton(onClick = nav.back, modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            Icon(Icons.Back, stringResource(R.string.store_back), modifier = Modifier.size(22.dp))
        }
    }
    val heading = @Composable { Text(title, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    Column(
        Modifier.padding(top = padding.calculateTopPadding()).horizontal(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                back()
                heading()
            }
            trailing()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                back()
                Box(Modifier.widthIn(max = 360.dp)) { heading() }
                Box(Modifier.weight(1f)) { trailing() }
            }
        }
    }
}

@Composable
private fun StoreRow(item: StoreItem, state: AppState, viewModel: AppViewModel, nav: StoreNav, actions: Boolean = true) {
    // The app as installed (from backports, Flathub…) when it is, else its main entry.
    val current = item.sources.firstOrNull { it.isInstalled(state.packages) }
    val installed = current != null
    val shown = current ?: item
    val job = item.sources.firstNotNullOfOrNull { state.storeJobs[it.key] }
    val launcher = launcherOf(shown, installed, viewModel)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RowShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { nav.open(StorePage.App(item.key)) }
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            StoreIcon(item, launcher, state, viewModel, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val summary = summaryOf(item)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // An app without the label comes from Debian.
                    if (item.source != StoreSource.DEBIAN) Tag(sourceName(item))
                    if (summary.isNotEmpty()) {
                        Text(summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (actions) {
                StoreActions(shown, job, installed, launcher, state, viewModel, nav, compact = true)
            } else if (installed) {
                Tag(stringResource(R.string.store_installed_tag))
            }
        }
        if (job != null) JobStatus(job)
    }
}

// -- Updates -----------------------------------------------------------------------------------

/** An installed app ([item], the source it is installed from) with its packages' updates. */
private class AppUpdate(val page: StoreItem, val item: StoreItem, val updates: List<AppStore.Update>) {
    val bytes = updates.sumOf { it.bytes }
}

/** The updates of the installed apps, then the rest: the system's packages. */
private fun updatesByApp(state: AppState): Pair<List<AppUpdate>, List<AppStore.Update>> {
    val byPackage = state.storeUpdates.updates.groupBy { it.pkg }
    val apps = installedItems(state).mapNotNull { page ->
        val item = page.sources.firstOrNull { it.isInstalled(state.packages) } ?: page
        val updates = item.packages.flatMap { byPackage[it.removePrefix(AppStore.BACKPORTS)].orEmpty() }
        if (updates.isEmpty()) null else AppUpdate(page, item, updates)
    }
    val taken = apps.flatMapTo(HashSet()) { it.updates }
    return apps to state.storeUpdates.updates.filter { it !in taken }
}

/** A version as people know it: Debian's without epoch and revision. */
private fun shortVersion(update: AppStore.Update, version: String) =
    if (update.pkg.startsWith(AppStore.FLATPAK)) version else BackportsCatalog.shortVersion(version)

/** The updates: the apps one by one, the system's packages together, all of them at once. */
@Composable
private fun UpdatesPage(state: AppState, viewModel: AppViewModel, padding: PaddingValues, nav: StoreNav) {
    val context = LocalContext.current
    val compact = isCompact()
    val updates = state.storeUpdates
    val (apps, system) = remember(updates, state.packages, state.storeItems) { updatesByApp(state) }
    val total = updates.updates.sumOf { it.bytes }
    val allJob = state.storeJobs[UPDATE_ALL]
    val busy = allJob == StoreJob.Queued || allJob is StoreJob.Running
    val now = System.currentTimeMillis()
    val checked = if (updates.checkedAt > 0 && now - updates.checkedAt < 60_000) {
        stringResource(R.string.store_updates_checked, stringResource(R.string.store_updates_now))
    } else if (updates.checkedAt > 0) {
        stringResource(
            R.string.store_updates_checked,
            android.text.format.DateUtils.getRelativeTimeSpanString(updates.checkedAt, now, android.text.format.DateUtils.MINUTE_IN_MILLIS),
        )
    } else ""
    val size = android.text.format.Formatter.formatShortFileSize(context, total)
    val note = when {
        updates.failed -> stringResource(R.string.store_updates_failed)
        updates.checkedAt == 0L -> if (updates.checking) stringResource(R.string.store_updates_checking) else null
        updates.updates.isEmpty() -> stringResource(R.string.store_updates_none)
        else -> null
    }
    val appsTitle = "${stringResource(R.string.store_updates_apps)} · ${apps.size}"
    val systemTitle = stringResource(R.string.store_updates_system)
    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.store_updates), padding, nav) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (compact && updates.updates.isNotEmpty()) "$size · $checked" else checked,
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (compact) Modifier.weight(1f) else Modifier,
                )
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    if (updates.checking) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.4.dp)
                    } else {
                        IconButton(onClick = { viewModel.checkStoreUpdates() }, enabled = !busy) {
                            Icon(Icons.Refresh, stringResource(R.string.store_updates_check), modifier = Modifier.size(22.dp))
                        }
                    }
                }
                when {
                    busy -> TextButton(onClick = { viewModel.storeCancel(UPDATE_ALL) }) { Text(stringResource(R.string.action_cancel)) }
                    updates.updates.isNotEmpty() -> Button(
                        onClick = { viewModel.storeUpgrade(UPDATE_ALL, emptyList(), total) },
                        contentPadding = PaddingValues(horizontal = 18.dp),
                    ) {
                        Text(if (compact) stringResource(R.string.store_update_all) else "${stringResource(R.string.store_update_all)} · $size")
                    }
                }
            }
        }
        val list = rememberLazyGridState()
        LazyVerticalGrid(
            state = list,
            columns = GridCells.Adaptive(360.dp),
            contentPadding = padding.below(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().fadingEdges(list),
        ) {
            if (allJob != null) item(span = { GridItemSpan(maxLineSpan) }, key = "all") { JobStatus(allJob) }
            if (note != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "note") {
                    Text(note, fontSize = 15.sp, color = if (updates.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (apps.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "apps") { SectionHeader(appsTitle) }
                items(apps, key = { it.item.key }) { update -> UpdateRow(update, busy, state, viewModel, nav) }
            }
            if (system.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "system") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                        SectionHeader(systemTitle)
                        SystemUpdates(system)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(update: AppUpdate, busy: Boolean, state: AppState, viewModel: AppViewModel, nav: StoreNav) {
    val context = LocalContext.current
    val item = update.item
    val job = state.storeJobs[item.key]
    val launcher = launcherOf(item, true, viewModel)
    // The app's own package first: its versions are the app's.
    val main = update.updates.firstOrNull { it.pkg == item.packages.first().removePrefix(AppStore.BACKPORTS) } ?: update.updates.first()
    val versions = listOf(shortVersion(main, main.from), shortVersion(main, main.to)).filter { it.isNotEmpty() }.joinToString(" → ")
    Column(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RowShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { nav.open(StorePage.App(update.page.key)) }
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            StoreIcon(item, launcher, state, viewModel, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Only Flathub's: its versions are not Debian's.
                    if (item.source == StoreSource.FLATHUB) Tag(sourceName(item))
                    Text(
                        "$versions · ${android.text.format.Formatter.formatShortFileSize(context, update.bytes)}",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                job == StoreJob.Queued || job is StoreJob.Running ->
                    TextButton(onClick = { viewModel.storeCancel(item.key) }) { Text(stringResource(R.string.action_cancel)) }
                !busy -> FilledTonalButton(
                    onClick = { viewModel.storeUpgrade(item.key, update.updates.map { it.pkg }, update.bytes) },
                    contentPadding = PaddingValues(horizontal = 18.dp),
                ) { Text(stringResource(R.string.store_update)) }
            }
        }
        if (job != null) JobStatus(job)
    }
}

/** The system's packages, one row that opens to list them. */
@Composable
private fun SystemUpdates(updates: List<AppStore.Update>) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) -90f else 90f, tween(220), label = "chevron")
    Column(Modifier.fillMaxWidth().clip(RowShape).background(colors.surfaceContainerLow).animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { open = !open }.padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(colors.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(Icons.Package, null, tint = colors.primary, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.store_updates_packages), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "${pluralStringResource(R.plurals.store_package_count, updates.size, updates.size)} · " +
                        android.text.format.Formatter.formatShortFileSize(context, updates.sumOf { it.bytes }),
                    fontSize = 13.sp, color = colors.onSurfaceVariant,
                )
            }
            Icon(Icons.Chevron, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp).rotate(turn))
        }
        if (open) {
            FlowRow(
                Modifier.padding(start = 70.dp, end = 20.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (update in updates) {
                    Text(
                        "${update.pkg.removePrefix(AppStore.FLATPAK)} ${shortVersion(update, update.to)}",
                        fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(240.dp),
                    )
                }
            }
        }
    }
}

// -- An app ------------------------------------------------------------------------------------

/**
 * An app's page: what it is, its screenshots, its description, where it comes from. An app on
 * Debian and Flathub has a choice of the two: Debian's first, unless Flathub's is the installed one.
 */
@Composable
private fun AppPage(page: StoreItem, state: AppState, viewModel: AppViewModel, padding: PaddingValues, nav: StoreNav) {
    val sources = page.sources
    // The installed source first, else the main one (Debian's stable, the vendor's).
    var chosen by rememberSaveable(page.key) { mutableIntStateOf(sources.indexOfFirst { it.isInstalled(state.packages) }.coerceAtLeast(0)) }
    val item = sources[chosen.coerceIn(sources.indices)]
    val job = state.storeJobs[item.key]
    val installed = item.isInstalled(state.packages)
    val launcher = launcherOf(item, installed, viewModel)
    val compact = isCompact()
    val catalog = item.catalog ?: page.catalog
    val colors = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    val versions = sources.map { versionOf(it, state, viewModel) }
    val actions = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 360.dp).animateContentSize()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StoreActions(item, job, installed, launcher, state, viewModel, nav) }
            if (job != null) JobStatus(job)
        }
    }
    val list = rememberLazyListState()
    LazyColumn(
        state = list,
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize().fadingEdges(list),
    ) {
        item(key = "back") {
            IconButton(onClick = nav.back, modifier = Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceContainerLow)) {
                Icon(Icons.Back, stringResource(R.string.store_back), modifier = Modifier.size(22.dp))
            }
        }
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    StoreIcon(item, launcher, state, viewModel, if (compact) 72.dp else 96.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, fontSize = if (compact) 24.sp else 30.sp, fontWeight = FontWeight.Medium)
                        val by = catalog?.developer?.takeIf { it.isNotEmpty() } ?: stringResource(item.app?.vendor ?: R.string.store_vendor_debian)
                        Text(by, fontSize = 15.sp, color = colors.onSurfaceVariant)
                        Text(stringResource(item.category.label), fontSize = 14.sp, color = colors.primary)
                    }
                    if (!compact) actions()
                }
                if (compact) actions()
            }
        }
        if (sources.size > 1) {
            item(key = "sources") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.store_source))
                    val rows = @Composable { index: Int, modifier: Modifier ->
                        SourceRow(sourceName(sources[index]), versions[index], index == chosen, modifier) { chosen = index }
                    }
                    if (compact) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (index in sources.indices) rows(index, Modifier.fillMaxWidth().clip(groupShape(index, sources.size)))
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (index in sources.indices) rows(index, Modifier.weight(1f).clip(CardShape))
                        }
                    }
                }
            }
        }
        item(key = "summary") {
            val summary = summaryOf(page)
            if (summary.isNotEmpty()) Text(summary, fontSize = 17.sp, lineHeight = 24.sp)
        }
        val shots = catalog?.screenshots.orEmpty()
        if (shots.isNotEmpty()) {
            item(key = "shots") {
                val row = rememberLazyListState()
                LazyRow(state = row, modifier = Modifier.fadingEdges(row, vertical = false), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shots) { url ->
                        Shot(url, item, state, viewModel, Modifier.height(if (compact) 200.dp else 280.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(16.dp)))
                    }
                }
            }
        }
        item(key = "description") {
            Column(Modifier.widthIn(max = 840.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(stringResource(R.string.store_description))
                val text = catalog?.description?.takeIf { it.isNotEmpty() }
                    ?: listOfNotNull(
                        item.app?.let { stringResource(it.summary) } ?: item.summary,
                        if (item.category == StoreCategory.TOOLS) stringResource(R.string.store_tool_hint) else null,
                    ).joinToString("\n")
                for (line in text.lines()) {
                    if (line.startsWith("• ")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 4.dp)) {
                            Text("•", fontSize = 15.sp, color = colors.primary)
                            Text(line.removePrefix("• "), fontSize = 15.sp, lineHeight = 22.sp, color = colors.onSurfaceVariant)
                        }
                    } else {
                        Text(line, fontSize = 15.sp, lineHeight = 22.sp, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        item(key = "info") {
            Column(Modifier.widthIn(max = 840.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(stringResource(R.string.store_info))
                versions[chosen.coerceIn(sources.indices)]?.let { InfoLine(stringResource(R.string.store_version), it) }
                InfoLine(stringResource(R.string.store_source), sourceName(item))
                InfoLine(
                    stringResource(R.string.store_package),
                    item.packages.joinToString(", ") { it.removePrefix(app.droidnux.container.AppStore.FLATPAK).removePrefix(app.droidnux.container.AppStore.BACKPORTS) },
                )
                catalog?.developer?.takeIf { it.isNotEmpty() }?.let { InfoLine(stringResource(R.string.store_developer), it) }
                catalog?.homepage?.let { url ->
                    InfoLine(stringResource(R.string.store_homepage), url.removePrefix("https://").removePrefix("http://").trimEnd('/')) { uri.openUri(url) }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RowShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.width(140.dp))
        Text(
            value,
            fontSize = 15.sp,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

// -- Pieces ------------------------------------------------------------------------------------

/** One of an app's sources: a radio button, its name and version. */
@Composable
private fun SourceRow(name: String, version: String?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(if (selected) colors.secondaryContainer else colors.surfaceContainerLow, label = "source")
    Row(
        modifier.background(background).clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (version != null) Text(version, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

/** A row of a group of joined rows: the group's outer corners round, the inner ones small. */
private fun groupShape(index: Int, size: Int) = RoundedCornerShape(
    topStart = if (index == 0) 20.dp else 6.dp, topEnd = if (index == 0) 20.dp else 6.dp,
    bottomStart = if (index == size - 1) 20.dp else 6.dp, bottomEnd = if (index == size - 1) 20.dp else 6.dp,
)

/** A small label beside a name. */
@Composable
private fun Tag(text: String) {
    Text(
        text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Where [item] comes from: its vendor by name, else the source's. */
@Composable
private fun sourceName(item: StoreItem): String =
    if (item.source == StoreSource.VENDOR) item.app?.vendor?.let { stringResource(it) } ?: stringResource(item.source.label)
    else stringResource(item.source.label)

/** Who makes [item]: the catalog's developer, else its vendor, else its source. */
@Composable
private fun developerOf(item: StoreItem): String =
    item.catalog?.developer?.takeIf { it.isNotEmpty() } ?: item.app?.vendor?.let { stringResource(it) } ?: stringResource(item.source.label)

/** The version of a source as far as known: the installed one, backports' list, else "stable" for Debian's. */
@Composable
private fun versionOf(item: StoreItem, state: AppState, viewModel: AppViewModel): String? {
    val installed = item.isInstalled(state.packages)
    val version by produceState(item.version, item.key, installed) {
        if (installed && !item.flathub) value = withContext(Dispatchers.IO) { viewModel.storeVersion(item.packages.first()) } ?: value
    }
    return version ?: if (item.source == StoreSource.DEBIAN) stringResource(R.string.store_stable) else null
}

/** The text under an item's name: the catalog's summary, or the store's own. */
@Composable
private fun summaryOf(item: StoreItem): String = item.summary ?: item.app?.let { stringResource(it.summary) }.orEmpty()

/** A screenshot, faded in once loaded; without one, the app's color with its icon. */
@Composable
private fun Shot(url: String?, item: StoreItem, state: AppState, viewModel: AppViewModel, modifier: Modifier) {
    val width = with(LocalDensity.current) { 640.dp.roundToPx() }
    val bitmap by produceState<Bitmap?>(null, url) {
        value = url?.let { withContext(Dispatchers.IO) { viewModel.storeShot(it, width) } }
    }
    val colors = MaterialTheme.colorScheme
    Crossfade(bitmap, modifier, tween(250), label = "shot") { image ->
        if (image != null) {
            Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        } else {
            Box(
                Modifier.fillMaxSize().background(if (url == null) item.app?.let { Color(it.color).copy(alpha = 0.35f) } ?: colors.surfaceContainerHigh else colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (url == null) StoreIcon(item, null, state, viewModel, 96.dp)
            }
        }
    }
}

@Composable
private fun SearchField(value: String, hint: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(colors.surfaceContainerHigh)
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Search, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(hint, color = colors.onSurfaceVariant, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** The launcher of an installed item, looked up off the main thread (null while not installed). */
@Composable
private fun launcherOf(item: StoreItem, installed: Boolean, viewModel: AppViewModel): String? {
    val launcher by produceState<String?>(null, item.key, installed) {
        value = if (installed) withContext(Dispatchers.IO) { viewModel.storeLauncher(item.packages, item.app?.launcher) } else null
    }
    return launcher
}

/** The app's own icon once installed, else Debian's (or Flathub's), else a tile in its color: a terminal for tools, its initial for apps. */
@Composable
private fun StoreIcon(item: StoreItem, launcher: String?, state: AppState, viewModel: AppViewModel, size: Dp) {
    val name = item.icon
    val icon by produceState(launcher?.let(viewModel::cachedIcon) ?: viewModel.cachedIcon("store:$name"), launcher, name, state.storeIcons) {
        value = withContext(Dispatchers.IO) { launcher?.let(viewModel::icon) ?: viewModel.storeIcon(name) } ?: value
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), null, Modifier.size(size))
        return
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(item.app?.let { Color(it.color) } ?: MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (item.category == StoreCategory.TOOLS) {
            Icon(Icons.Terminal, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        } else {
            Text(item.name.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium)
        }
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
            job is StoreJob.Running && job.total > 0 -> LinearProgressIndicator(progress = { (job.done + job.part).coerceAtMost(job.total.toFloat()) / job.total }, modifier = bar)
            job is StoreJob.Running -> LinearProgressIndicator(bar)
            else -> Unit
        }
    }
}

/** Install, open, remove, cancel or retry; [compact] in a row of a list, where only the main action fits. */
@Composable
private fun StoreActions(
    item: StoreItem,
    job: StoreJob?,
    installed: Boolean,
    launcher: String?,
    state: AppState,
    viewModel: AppViewModel,
    nav: StoreNav,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val padding = PaddingValues(horizontal = 18.dp)
    when {
        job == StoreJob.Queued || (job is StoreJob.Running && !job.removing) ->
            TextButton(onClick = { viewModel.storeCancel(item.key) }) { Text(stringResource(R.string.action_cancel)) }
        job is StoreJob.Running -> Unit
        installed -> {
            // The desktop's own packages stay (the search finds them too).
            val removable = item.packages.none { it in app.droidnux.container.DesktopEnvironment.desktopPackages }
            if (removable && (!compact || launcher == null)) {
                TextButton(onClick = { nav.remove(item) }) { Text(stringResource(R.string.store_remove)) }
            }
            if (launcher != null) {
                FilledTonalButton(onClick = { launchApp(context, launcher, state.apps?.firstOrNull { it.id == launcher }) }, contentPadding = padding) {
                    Text(stringResource(R.string.store_open))
                }
            }
        }
        job is StoreJob.Failed -> Button(onClick = { viewModel.storeInstall(item.key, item.packages) }, contentPadding = padding) {
            Text(stringResource(R.string.action_retry))
        }
        else -> Button(onClick = { viewModel.storeInstall(item.key, item.packages) }, contentPadding = padding) {
            Text(stringResource(R.string.action_install))
        }
    }
}

/** The side padding of [padding] (the page's margins). */
@Composable
private fun Modifier.horizontal(padding: PaddingValues, extra: Dp = 0.dp): Modifier {
    val direction = LocalLayoutDirection.current
    return padding(start = padding.calculateStartPadding(direction) + extra, end = padding.calculateEndPadding(direction) + extra)
}

/** [padding] for content under a header: [top] instead of the page's top margin. */
@Composable
private fun PaddingValues.below(top: Dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(start = calculateStartPadding(direction), top = top, end = calculateEndPadding(direction), bottom = calculateBottomPadding())
}

private const val BANNER_TURN_MS = 6_000L

/** How old the last look for updates may be when the store opens. */
private const val UPDATES_CHECK_MS = 6 * 60 * 60 * 1000L

/** The search's first results, before "Show all", and its category filters at most. */
private const val SEARCH_FIRST_RESULTS = 8
private const val MAX_SEARCH_FILTERS = 4

/** The cards of a shelf on the home; its page has the rest. */
private const val SHELF_CARDS = 12
