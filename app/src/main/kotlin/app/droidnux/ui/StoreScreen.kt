package app.droidnux.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A page of the store over its home: a category, the installed apps, or one app ([key] of its item). */
private sealed interface StorePage {
    data class Category(val category: StoreCategory) : StorePage
    data object Installed : StorePage
    data class App(val key: String) : StorePage
}

private fun StorePage.route(): String = when (this) {
    is StorePage.Category -> "cat:${category.name}"
    StorePage.Installed -> "installed"
    is StorePage.App -> "app:$key"
}

private fun storePage(route: String): StorePage? = when {
    route.startsWith("cat:") -> StoreCategory.entries.firstOrNull { it.name == route.substring(4) }?.let(StorePage::Category)
    route == "installed" -> StorePage.Installed
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
            is StorePage.Category -> {
                val items = remember(state.storeItems, page.category) { state.storeItems.filter { it.category == page.category } }
                ListPage(stringResource(page.category.label), items, page.category.filters, state, viewModel, padding, nav)
            }
            StorePage.Installed -> ListPage(stringResource(R.string.store_installed), installedItems(state), emptyList(), state, viewModel, padding, nav)
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
    val items = state.storeItems.filter { item ->
        item.packages.first() in state.packages && (item.app != null || item.key in state.storePackages)
    }
    val shown = items.mapTo(HashSet()) { it.key }
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
    val search = state.storeSearch?.takeIf { query.isNotBlank() }
    val compact = isCompact()

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(top = padding.calculateTopPadding()).horizontal(padding)) {
            SearchField(query, stringResource(R.string.store_search), setQuery)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = when {
                maxWidth >= 1000.dp -> 5
                maxWidth >= 600.dp -> 3
                else -> 2
            }
            if (search != null) {
                SearchResults(search, state, viewModel, padding, nav)
                return@BoxWithConstraints
            }
            val counts = remember(state.storeItems) { state.storeItems.groupingBy { it.category }.eachCount() }
            val installed = installedItems(state).size
            val featured = remember(state.storeItems) { StoreCatalog.FEATURED.mapNotNull { app -> state.storeItems.firstOrNull { it.key == app.id } } }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = padding.below(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "banner") { Banner(featured, state, viewModel, compact, nav) }
                if (state.storeCatalog == CatalogState.FAILED) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "failed") {
                        Text(stringResource(R.string.store_catalog_failed), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "categories") {
                    SectionHeader(stringResource(R.string.store_categories), Modifier.padding(top = 12.dp))
                }
                items(StoreCategory.entries, key = { it.name }) { category ->
                    val count = counts[category] ?: 0
                    CategoryTile(
                        stringResource(category.label), category.icon,
                        if (category == StoreCategory.TOOLS) pluralStringResource(R.plurals.store_package_count, count, count)
                        else pluralStringResource(R.plurals.store_app_count, count, count),
                        compact,
                    ) { nav.open(StorePage.Category(category)) }
                }
                item(key = "installed") {
                    CategoryTile(stringResource(R.string.store_installed), Icons.Check, pluralStringResource(R.plurals.store_app_count, installed, installed), compact) {
                        nav.open(StorePage.Installed)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(search: StoreSearch, state: AppState, viewModel: AppViewModel, padding: PaddingValues, nav: StoreNav) {
    val more = search.more
    LazyVerticalGrid(
        columns = GridCells.Adaptive(300.dp),
        contentPadding = padding.below(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (search.results.isEmpty() && more.isNullOrEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (more == null) stringResource(R.string.store_searching) else stringResource(R.string.store_no_results, search.query),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        rows(search.results, state, viewModel, nav)
        if (!more.isNullOrEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.store_more_packages), Modifier.padding(top = 12.dp)) }
            rows(more.map(::packageItem), state, viewModel, nav)
        }
    }
}

private fun LazyGridScope.rows(items: List<StoreItem>, state: AppState, viewModel: AppViewModel, nav: StoreNav) {
    items(items, key = { it.key }) { item -> StoreRow(item, state, viewModel, nav) }
}

@Composable
private fun CategoryTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, count: String, compact: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(if (compact) 64.dp else 76.dp).clip(CardShape).background(colors.surfaceContainerLow)
            .clickable(onClick = onClick).padding(horizontal = if (compact) 12.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(if (compact) 38.dp else 44.dp).clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.primary, modifier = Modifier.size(22.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = if (compact) 14.sp else 15.sp, fontWeight = FontWeight.Medium, maxLines = if (compact) 2 else 1, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            if (!compact) Text(count, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
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
            modifier = Modifier.fillMaxWidth().height(if (compact) 280.dp else 296.dp),
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
private fun BannerCard(item: StoreItem, state: AppState, viewModel: AppViewModel, compact: Boolean, nav: StoreNav) {
    val installed = item.packages.first() in state.packages
    val launcher = launcherOf(item, installed, viewModel)
    val job = state.storeJobs[item.key]
    val shot = item.catalog?.screenshots?.firstOrNull()
    val shape = RoundedCornerShape(28.dp)
    val open = { nav.open(StorePage.App(item.key)) }
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
                        "${stringResource(item.app?.vendor ?: R.string.store_vendor_debian)} · ${stringResource(item.category.label)}",
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
    val context = LocalContext.current
    val counts = remember(items, filters) {
        filters.map { f -> f to items.count { f in it.catalog?.categories.orEmpty() } }.filter { it.second > 0 }
    }
    val shown = remember(items, filter, query) {
        val filtered = filter?.let { f -> items.filter { f in it.catalog?.categories.orEmpty() } } ?: items
        if (query.isBlank()) filtered else StoreItem.search(filtered, query) { context.getString(it) }
    }
    BackHandler(enabled = query.isNotEmpty()) { query = "" }
    Column(Modifier.fillMaxSize()) {
        PageHeader(title, padding, nav) { SearchField(query, stringResource(R.string.store_search_in, title)) { query = it } }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(300.dp),
            contentPadding = padding.below(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (counts.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun StoreRow(item: StoreItem, state: AppState, viewModel: AppViewModel, nav: StoreNav) {
    val job = state.storeJobs[item.key]
    val installed = item.packages.first() in state.packages
    val launcher = launcherOf(item, installed, viewModel)
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
                if (summary.isNotEmpty()) {
                    Text(summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            StoreActions(item, job, installed, launcher, state, viewModel, nav, compact = true)
        }
        if (job != null) JobStatus(job)
    }
}

// -- An app ------------------------------------------------------------------------------------

/** An app's page: what it is, its screenshots, its description, where it comes from. */
@Composable
private fun AppPage(item: StoreItem, state: AppState, viewModel: AppViewModel, padding: PaddingValues, nav: StoreNav) {
    val job = state.storeJobs[item.key]
    val installed = item.packages.first() in state.packages
    val launcher = launcherOf(item, installed, viewModel)
    val compact = isCompact()
    val catalog = item.catalog
    val colors = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    val actions = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 360.dp).animateContentSize()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StoreActions(item, job, installed, launcher, state, viewModel, nav) }
            if (job != null) JobStatus(job)
        }
    }
    LazyColumn(
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize(),
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
                val summary = summaryOf(item)
                if (summary.isNotEmpty()) Text(summary, fontSize = 17.sp, lineHeight = 24.sp)
            }
        }
        val shots = catalog?.screenshots.orEmpty()
        if (shots.isNotEmpty()) {
            item(key = "shots") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                InfoLine(stringResource(R.string.store_package), item.packages.joinToString(", "))
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
private fun SearchField(value: String, hint: String, onChange: (String) -> Unit) {
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
            job is StoreJob.Running && job.total > 0 -> LinearProgressIndicator(progress = { job.done.toFloat() / job.total }, modifier = bar)
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
