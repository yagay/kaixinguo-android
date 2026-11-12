@file:OptIn(ExperimentalMaterial3Api::class)

package com.kadaliao.kaixinguo.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kadaliao.kaixinguo.model.Card
import com.kadaliao.kaixinguo.model.Fav
import com.kadaliao.kaixinguo.model.Feed
import com.kadaliao.kaixinguo.model.favLabel
import com.kadaliao.kaixinguo.model.friendly
import kotlinx.coroutines.launch

/** Whether the window is wide enough for a tablet's layout. */
@Composable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/** The narrowest a poster in a grid gets: about three across a phone. */
@Composable
fun cellWidth(): Dp = if (isWide()) 150.dp else 104.dp

/** Page is a tab's own page: its title in a bar, over what it shows. */
@Composable
fun Page(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val bar = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text(title, fontWeight = FontWeight.Bold) }, actions = actions, scrollBehavior = bar) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), content = content)
    }
}

/** BackBar is a pushed page's bar: the way back, and maybe a title. */
@Composable
fun BackBar(title: String = "") {
    val router = LocalRouter.current
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = router::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        },
    )
}

/** Lets content run wider than its parent's padding, as a row that scrolls to the screen's edges. */
fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val wider = constraints.copy(
        minWidth = constraints.minWidth + extra,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
    )
    val p = measurable.measure(wider)
    layout((p.width - extra).coerceAtLeast(0), p.height) { p.place(-extra / 2, 0) }
}

/** A grid item that takes the whole line. */
fun LazyGridScope.line(key: Any, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/**
 * FeedGrid is a feed's posters, loading the next page as the viewer nears
 * the end, with what goes above them. Pulling it down calls `refresh`.
 */
@Composable
fun FeedGrid(
    feed: Feed,
    empty: String,
    refresh: (suspend () -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    LaunchedEffect(feed) {
        if (!feed.started) feed.loadMore()
    }
    val state = rememberLazyGridState()
    LaunchedEffect(state, feed) {
        snapshotFlow {
            val info = state.layoutInfo
            Triple(info.visibleItemsInfo.lastOrNull()?.index ?: -1, info.totalItemsCount, feed.loading)
        }.collect { (last, total, loading) ->
            if (!loading && feed.cards.isNotEmpty() && last >= total - 10 && feed.wantsMore) feed.loadMore()
        }
    }
    Refreshable(refresh) {
        CardGrid(state) {
            if (header != null) line("header") { Box(Modifier.bleed(16.dp)) { header() } }
            if (feed.cards.isEmpty()) {
                line("state") { FeedState(feed, empty, Modifier.fillMaxWidth().heightIn(min = 420.dp)) }
            } else {
                items(feed.cards, key = { it.id }) { CardView(it) }
                if (feed.loading && feed.started) {
                    line("more") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Refreshable lets the viewer pull its content down to call `refresh`, when there is one. */
@Composable
fun Refreshable(refresh: (suspend () -> Unit)?, content: @Composable BoxScope.() -> Unit) {
    if (refresh == null) {
        Box(Modifier.fillMaxSize(), content = content)
        return
    }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try {
                    refresh()
                } finally {
                    refreshing = false
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

/** CardGrid lays posters out in as many columns as fit. */
@Composable
fun CardGrid(state: LazyGridState = rememberLazyGridState(), content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cellWidth()),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

/** FeedState is what a feed with no series yet shows. */
@Composable
fun FeedState(feed: Feed, empty: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val error = feed.error
        when {
            feed.retrying -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("网络不稳定，正在重试…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error != null -> ErrorState(friendly(error)) { feed.retry() }
            feed.loading || !feed.started -> CircularProgressIndicator()
            else -> EmptyState(Icons.Outlined.Theaters, empty)
        }
    }
}

/** ErrorState says what failed, with a way to try again. */
@Composable
fun ErrorState(message: String, retry: () -> Unit) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text("加载失败", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        OutlinedButton(onClick = retry) { Text("重试") }
    }
}

/** EmptyState says there is nothing here, and maybe how to change that. */
@Composable
fun EmptyState(icon: ImageVector, title: String, description: String = "", modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (description.isNotEmpty()) {
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** Spinner fills its space with a spinner, while what goes there loads. */
@Composable
fun Spinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/**
 * CardView is a series in a grid: its poster, title and a line about it.
 * It opens the series, unless [LocalPlayCard] says to play it instead. A
 * long press saves it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardView(card: Card, modifier: Modifier = Modifier) {
    val model = LocalModel.current
    val router = LocalRouter.current
    val playCard = LocalPlayCard.current
    val haptic = LocalHapticFeedback.current
    val saved = model.favorites.has(card.id)
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(
                    onClick = { playCard?.invoke(card) ?: router.open(Route.Series(card.id)) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                ),
        ) {
            Poster(card.cover) {
                if (card.rank > 0) RankBadge(card.rank, Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (saved) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Star, contentDescription = "已收藏", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(11.dp))
                }
            }
            Text(
                card.sub.ifEmpty { " " },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(favLabel(saved)) },
                leadingIcon = { Icon(if (saved) Icons.Outlined.StarOutline else Icons.Filled.Star, contentDescription = null) },
                onClick = {
                    model.favorites.toggle(Fav.of(card))
                    menu = false
                },
            )
        }
    }
}

/** RankBadge is a series' place on a chart, the first three in medal colors. */
@Composable
fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    val medals = listOf(
        androidx.compose.ui.graphics.Color(0xFFF2AD21),
        androidx.compose.ui.graphics.Color(0xFF9E9E9E),
        androidx.compose.ui.graphics.Color(0xFFC27845),
    )
    val bg = if (rank <= 3) medals[rank - 1] else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(bg).widthIn(min = 22.dp).height(20.dp).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$rank", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
    }
}

/** Poster is a cover in a rounded 3:4 frame, gray until it loads. */
@Composable
fun Poster(url: String, modifier: Modifier = Modifier, radius: Dp = 10.dp, overlay: @Composable BoxScope.() -> Unit = {}) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(shape)
            .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), shape),
    ) {
        CoverImage(url, Modifier.fillMaxSize())
        overlay()
    }
}

/** The cover at `url` as it arrives: at once when memory has it. */
@Composable
fun rememberCover(url: String): Bitmap? {
    val covers = LocalCovers.current
    var bmp by remember(url) { mutableStateOf(covers.cached(url)) }
    LaunchedEffect(url) {
        if (bmp == null && url.isNotEmpty()) covers.image(url)?.let { bmp = it }
    }
    return bmp
}

/** CoverImage is a cover filling its frame, fading in as it arrives. */
@Composable
fun CoverImage(url: String, modifier: Modifier = Modifier) {
    val covers = LocalCovers.current
    // Memory has most covers already: those show at once.
    val initial = remember(url) { covers.cached(url) }
    var bmp by remember(url) { mutableStateOf(initial) }
    val alpha = remember(url) { Animatable(if (initial != null) 1f else 0f) }
    LaunchedEffect(url) {
        if (bmp != null || url.isEmpty()) return@LaunchedEffect
        val b = covers.image(url) ?: return@LaunchedEffect
        bmp = b
        alpha.animateTo(1f, tween(200))
    }
    Box(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))) {
        bmp?.let { b ->
            val img = remember(b) { b.asImageBitmap() }
            Image(
                img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value },
            )
        }
    }
}

/** Chip is a capsule that chooses one of a few, as a chart or a day. */
@Composable
fun Chip(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        color = if (on) c.surface else c.onSurface,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(if (on) c.onSurface else c.onSurface.copy(alpha = 0.07f))
            .clickable(enabled = enabled && !on, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
    )
}

/** ChipRow is a row of chips that scrolls sideways, starting near the chosen one. */
@Composable
fun ChipRow(selected: Int, modifier: Modifier = Modifier, padding: Dp = 16.dp, content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (selected > 1) state.scrollToItem(selected - 1)
    }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        state = state,
        contentPadding = PaddingValues(horizontal = padding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** SearchField is a field to type a search into, in a capsule. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester = remember { FocusRequester() },
) {
    val c = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
        cursorBrush = SolidColor(c.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = modifier.focusRequester(focus),
        decorationBox = { field ->
            Row(
                Modifier.height(42.dp).clip(CircleShape).background(c.onSurface.copy(alpha = 0.07f)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text("搜索短剧", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                    field()
                }
                if (value.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Cancel,
                        contentDescription = "清除",
                        tint = c.onSurfaceVariant,
                        modifier = Modifier.size(20.dp).clip(CircleShape).clickable {
                            onValueChange("")
                            focus.requestFocus()
                        },
                    )
                }
            }
        },
    )
}

/** SuggestionList is the words a search may be completed to. */
@Composable
fun SuggestionList(suggestions: List<String>, pick: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
        items(suggestions.size, key = { suggestions[it] }) { i ->
            val s = suggestions[i]
            Row(
                Modifier.fillMaxWidth().clickable { pick(s) }.height(48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text(s, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Keeps a feed's grid apart from the one before it, so a new chart starts at its top. */
@Composable
fun FeedKey(feed: Feed, content: @Composable () -> Unit) {
    key(feed) { content() }
}
