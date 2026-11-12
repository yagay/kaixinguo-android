@file:OptIn(ExperimentalMaterial3Api::class)

package com.kadaliao.kaixinguo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import com.kadaliao.kaixinguo.KaixinguoApplication
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kadaliao.kaixinguo.model.Card
import com.kadaliao.kaixinguo.model.Watch
import com.kadaliao.kaixinguo.model.dayLabel
import com.kadaliao.kaixinguo.model.friendly
import com.kadaliao.kaixinguo.model.todayKey
import kotlinx.coroutines.delay

/** DiscoverScreen is the recommended series. */
@Composable
fun DiscoverScreen() {
    val model = LocalModel.current
    Page("推荐") {
        FeedGrid(model.home, "暂时没有推荐", refresh = { model.home.refresh(merging = true) })
    }
}

/** RankScreen is the charts: a kind of series, and its boards. */
@Composable
fun RankScreen() {
    val model = LocalModel.current
    Page("排行榜") {
        val feed = model.rankFeed
        FeedKey(feed) {
            FeedGrid(feed, "这个榜单暂时是空的", refresh = { feed.refresh(merging = false) }) { RankControls() }
        }
    }
}

@Composable
private fun RankControls() {
    val model = LocalModel.current
    val contents = model.chartContents
    if (contents.isEmpty()) return
    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (contents.size > 1) {
            val current = model.currentRankContent
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                contents.forEachIndexed { i, c ->
                    SegmentedButton(
                        selected = c.id == current,
                        onClick = {
                            model.rankContent = c.id
                            model.rankBoard = ""
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, contents.size),
                        icon = {},
                    ) { Text(c.name, maxLines = 1) }
                }
            }
        }
        val boards = model.rankBoards
        ChipRow(selected = boards.indexOfFirst(model::boardIsOn)) {
            items(boards, key = { it.id }) { b ->
                Chip(b.name, model.boardIsOn(b)) { model.rankBoard = b.id }
            }
        }
    }
}

/** NewReleasesScreen is the calendar of new series, a week either side. */
@Composable
fun NewReleasesScreen() {
    val model = LocalModel.current
    Page("新剧上线") {
        val feed = model.newFeed
        FeedKey(feed) {
            FeedGrid(feed, "这一天没有新剧", refresh = { feed.refresh(merging = false) }) {
                val dates = model.newDates
                if (dates.isNotEmpty()) {
                    val today = todayKey()
                    ChipRow(selected = dates.indexOfFirst(model::dayIsOn), modifier = Modifier.padding(bottom = 4.dp)) {
                        items(dates, key = { it }) { d ->
                            Chip(dayLabel(d, today), model.dayIsOn(d)) { model.chooseDay(d) }
                        }
                    }
                }
            }
        }
    }
}

/** SearchScreen searches the series; before a search, it shows what people search for. */
@Composable
fun SearchScreen() {
    val model = LocalModel.current
    val router = LocalRouter.current
    val focusManager = LocalFocusManager.current
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var focused by remember { mutableStateOf(false) }
    val query = router.searchQuery
    LaunchedEffect(router.searchText) {
        val text = router.searchText
        if (text.isEmpty()) router.searchQuery = ""
        if (text.isEmpty() || text == router.searchQuery) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        suggestions = model.suggestions(text)
    }
    fun submit(s: String) {
        router.search(s)
        suggestions = emptyList()
        focusManager.clearFocus()
    }
    Scaffold(
        topBar = {
            Row(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SearchField(
                    router.searchText,
                    { router.searchText = it },
                    { submit(router.searchText) },
                    Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                )
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                suggestions.isNotEmpty() && router.searchText != query && focused -> SuggestionList(suggestions, ::submit)
                query.isEmpty() -> HotView()
                else -> {
                    val feed = model.searchFeed(query)
                    FeedKey(feed) { FeedGrid(feed, "没有找到“$query”相关的短剧，换个词试试") }
                }
            }
        }
    }
}

/** HotView is the hot-search boards. */
@Composable
fun HotView() {
    val model = LocalModel.current
    LaunchedEffect(Unit) { model.loadHot() }
    val hot = model.hot
    val error = model.hotError
    when {
        hot != null -> Refreshable({ model.reloadHot() }) {
            CardGrid {
                hot.boards.filter { it.items.isNotEmpty() }.forEachIndexed { bi, b ->
                    line("board$bi") {
                        Text(b.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    }
                    itemsIndexed(b.items, key = { i, _ -> "$bi/$i" }) { _, it ->
                        CardView(
                            Card(
                                it.seriesId, it.title, it.coverUrl,
                                sub = it.searchScore.ifEmpty { it.heat },
                                rank = it.position, episodes = it.episodeCount,
                            ),
                        )
                    }
                }
            }
        }
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ErrorState(friendly(error)) { model.loadHot(force = true) }
        }
        else -> Spinner()
    }
}

/** MineScreen is what the viewer watched and saved. */
@Composable
fun MineScreen() {
    val model = LocalModel.current
    val router = LocalRouter.current
    val app = LocalContext.current.applicationContext as KaixinguoApplication
    val follows = app.follows
    val accountState by app.account.state.collectAsState()
    val accountRecommendation by app.account.recommendations.collectAsState()
    val watched = model.history.list.take(30)
    val saved = model.favorites.cards
    Page("我的", actions = {
        IconButton(onClick = { router.open(Route.Settings) }) {
            Icon(Icons.Outlined.Settings, contentDescription = "设置")
        }
    }) {
        CardGrid {
            line("account-card") {
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { router.open(Route.Account) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.ManageAccounts, contentDescription = null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            accountState.profile?.nickname?.takeIf(String::isNotBlank)
                                ?: "登录红果账号",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when {
                                !accountState.websiteSignedIn -> "查看登录方式、账号与同步状态"
                                accountRecommendation.ready -> "官网已登录 · App 账号推荐已验证"
                                else -> "官网已登录 · App 个性化推荐待验证"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "进入账号与同步")
                }
            }
            line("local-statistics") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "本机收藏 ${saved.size}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "最近观看 ${model.history.list.size}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (watched.isNotEmpty()) {
                line("watched-title") { SectionTitle("最近观看") }
                line("watched") {
                    LazyRow(
                        Modifier.bleed(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(watched, key = { it.seriesID }) { WatchCard(it) }
                    }
                }
            }
            line("local-follows-title") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SectionTitle("本机追更")
                    Text(
                        if ((follows?.unreadCount ?: 0) > 0)
                            "有 " + (follows?.unreadCount ?: 0) + " 部更新  ›"
                        else "管理追更  ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { router.open(Route.LocalFollows) }
                            .padding(8.dp),
                    )
                }
            }
            if (follows?.entries?.isEmpty() != false) {
                line("local-follows-empty") {
                    Text(
                        "在剧集详情点击「本机追更」，以后可以手动检查新集数",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            line("saved-title") { SectionTitle("收藏") }
            if (saved.isEmpty()) {
                line("saved-empty") {
                    EmptyState(
                        Icons.Outlined.StarOutline,
                        "还没有收藏",
                        "在剧集页点「收藏」，或者长按海报，想看的就存下来。",
                        Modifier.padding(top = 24.dp),
                    )
                }
            } else {
                items(saved, key = { it.id }) { CardView(it) }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

/** WatchCard is a series in the history, how far the viewer got, and a way to go on. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatchCard(watch: Watch) {
    val model = LocalModel.current
    val router = LocalRouter.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .width(96.dp)
                .clip(RoundedCornerShape(9.dp))
                .combinedClickable(
                    onClick = { router.open(Route.Series(watch.seriesID)) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                ),
        ) {
            Poster(watch.cover, radius = 9.dp) {
                if (!watch.finished && watch.duration > 0) {
                    val f = (watch.position / watch.duration).coerceIn(0.0, 1.0).toFloat()
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 7.dp)
                            .fillMaxWidth(f.coerceAtLeast(0.05f)).height(3.dp)
                            .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(watch.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (watch.finished) "看完第 ${watch.episode} 集" else "看到第 ${watch.episode} 集",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("继续播放") },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = {
                    menu = false
                    model.playSeason(watch.seriesID)
                },
            )
            DropdownMenuItem(
                text = { Text("从最近观看中移除", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    menu = false
                    model.history.remove(watch.seriesID)
                },
            )
        }
    }
}
