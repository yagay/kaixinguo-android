package com.kadaliao.kaixinguo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kadaliao.kaixinguo.KaixinguoApplication
import com.kadaliao.kaixinguo.diagnostics.Diagnostics
import com.kadaliao.kaixinguo.model.AppModel
import com.kadaliao.kaixinguo.model.Card
import com.kadaliao.kaixinguo.model.Fav
import com.kadaliao.kaixinguo.model.Feed
import com.kadaliao.kaixinguo.model.FeedPage
import com.kadaliao.kaixinguo.model.PlayerState
import com.kadaliao.kaixinguo.model.friendly
import dev.openhongguo.DiscoverOptions
import kotlinx.coroutines.launch

/** Openhongguo's official bookmall video channels, not a ranking endpoint. */
private enum class Channel(val title: String, val sdkName: String) {
    RECOMMEND("推荐", "推荐"),
    REAL("真人", "真人剧"),
    COMIC("漫剧", "漫剧"),
}

@Composable
fun ReelsScreen(inPip: Boolean) {
    val model = LocalModel.current
    val player = model.player
    val router = LocalRouter.current
    val scope = rememberCoroutineScope()
    val account = (LocalContext.current.applicationContext as KaixinguoApplication).account
    val accountSession by account.state.collectAsState()
    val accountRecommendation by account.recommendations.collectAsState()
    // Separate source: credentialed results must never enter the anonymous
    // discovery SDK, its persistent cache or any other feed.
    val accountFeed = remember(model, account, accountSession.sessionRevision, accountRecommendation.attempt) {
        Feed(
            model.scope,
            { cursor ->
                val page = account.fetchAccountRecommendations(cursor)
                FeedPage(cards = page.cards, cursor = page.cursor, more = page.hasMore)
            },
            retryEmptyOnError = false,
        )
    }
    LaunchedEffect(accountFeed, accountSession.signedIn) {
        if (accountSession.signedIn && !accountFeed.started) accountFeed.loadMore()
    }
    val verifiedAccountSource = accountSession.signedIn &&
        accountRecommendation.ready && accountFeed.cards.isNotEmpty()
    // Do not briefly play anonymous recommendations while the authenticated
    // source is being checked; use ordinary recommendations only after failure.
    val tryingAccountSource = accountSession.signedIn && !accountRecommendation.blocked
    val realFeed = remember(model) { discoverFeed(model, Channel.REAL.sdkName) }
    val comicFeed = remember(model) { discoverFeed(model, Channel.COMIC.sdkName) }
    var channel by rememberSaveable { mutableStateOf(Channel.RECOMMEND) }
    var recommendationIndex by rememberSaveable { mutableIntStateOf(0) }
    var realIndex by rememberSaveable { mutableIntStateOf(0) }
    var comicIndex by rememberSaveable { mutableIntStateOf(0) }
    var refreshVersion by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(accountSession.sessionRevision, verifiedAccountSource) { recommendationIndex = 0 }

    val feed = when (channel) {
        Channel.RECOMMEND -> if (tryingAccountSource) accountFeed else model.home
        Channel.REAL -> realFeed
        Channel.COMIC -> comicFeed
    }
    val savedIndex = when (channel) {
        Channel.RECOMMEND -> recommendationIndex
        Channel.REAL -> realIndex
        Channel.COMIC -> comicIndex
    }
    fun saveIndex(page: Int) {
        when (channel) {
            Channel.RECOMMEND -> recommendationIndex = page
            Channel.REAL -> realIndex = page
            Channel.COMIC -> comicIndex = page
        }
    }

    LaunchedEffect(feed) {
        if (!feed.started) feed.loadMore()
    }
    DisposableEffect(player) {
        onDispose { if (!player.inPictureInPicture) player.leave() }
    }
    if (!inPip) {
        PlayerWindow(
            hideBars = false,
            keepOn = player.isPresented && !player.paused &&
                (player.state == PlayerState.Playing || player.state == PlayerState.Buffering),
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // One stable PlayerView behind the entire pager: pages only move overlays.
        // This avoids attaching the same ExoPlayer to multiple page surfaces.
        if (!inPip) VideoSurface(Modifier.fillMaxSize())

        key(channel, refreshVersion, accountSession.sessionRevision, verifiedAccountSource) {
            FeedPages(
                feed = feed,
                initialPage = savedIndex,
                inPip = inPip,
                onSettled = ::saveIndex,
                onSeries = { id ->
                    player.leave()
                    router.open(Route.Series(id))
                },
            )
        }

        if (!inPip) {
            // Reuse the existing accurate playback clock and seek bar.
            if (feed.cards.isNotEmpty() && player.isPresented) {
                ProgressBar(Modifier.align(Alignment.BottomCenter))
            }
            Box(
                Modifier.fillMaxWidth().height(100.dp).align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.46f), Color.Transparent),
                        ),
                    ),
            )
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .statusBarsPadding().padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Channel.entries.forEach { option ->
                        val active = option == channel
                        Column(
                            Modifier.clickable {
                                if (option != channel) {
                                    Diagnostics.record("reels", "channel_changed")
                                    player.leave()
                                    channel = option
                                }
                            }.padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                option.title,
                                color = if (active) Color.White else Color.White.copy(alpha = 0.72f),
                                fontSize = if (active) 18.sp else 16.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            )
                            Box(
                                Modifier.padding(top = 5.dp).width(18.dp).height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (active) Color.White else Color.Transparent),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        scope.launch {
                            Diagnostics.record("reels", "refresh_requested")
                            player.leave()
                            if (channel == Channel.RECOMMEND && accountSession.signedIn) {
                                // A fresh account attempt invalidates the previous cursor.
                                account.retryAccountRecommendations()
                                model.home.refresh(merging = false)
                            } else {
                                feed.refresh(merging = false)
                            }
                            saveIndex(0)
                            refreshVersion++
                        }
                    },
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新刷剧列表", tint = Color.White)
                }
            }
        }
        if (!inPip && channel == Channel.RECOMMEND && accountSession.signedIn) {
            // A small source indicator avoids silently presenting anonymous
            // results as the official account recommendation feed.
            val sourceLabel = when {
                verifiedAccountSource -> "账号推荐"
                accountRecommendation.blocked -> "普通推荐 · 账号推荐暂不可用"
                accountRecommendation.phase.name == "CHECKING" -> "正在验证账号推荐"
                else -> "正在加载账号推荐"
            }
            Text(
                text = sourceLabel,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()
                    .padding(start = 14.dp, top = 52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.46f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun FeedPages(
    feed: Feed,
    initialPage: Int,
    inPip: Boolean,
    onSettled: (Int) -> Unit,
    onSeries: (String) -> Unit,
) {
    val model = LocalModel.current
    val player = model.player
    val cards = feed.cards
    // Each channel has its own pager instance and restored position.
    val pager = rememberPagerState(
        initialPage = initialPage.coerceIn(0, (cards.size - 1).coerceAtLeast(0)),
        pageCount = { cards.size },
    )

    LaunchedEffect(cards.size) {
        if (cards.isNotEmpty() && pager.currentPage > cards.lastIndex) {
            pager.scrollToPage(cards.lastIndex)
        }
    }
    LaunchedEffect(pager.settledPage, cards.getOrNull(pager.settledPage)?.id) {
        val page = pager.settledPage
        onSettled(page)
        val card = cards.getOrNull(page)
        if (card != null && (!player.isPresented || player.seriesID != card.id)) {
            // A new show begins at episode one; the existing Player takes care
            // of episode progression, media resolution and playback history.
            player.play(card.id, 1)
        }
    }
    LaunchedEffect(pager.settledPage, cards.size, feed.loading) {
        if (cards.isNotEmpty() && cards.lastIndex - pager.settledPage <= 2 &&
            feed.wantsMore && !feed.loading) {
            feed.loadMore()
        }
    }

    if (cards.isEmpty()) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (feed.loading || !feed.started || feed.retrying) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
            }
            Text(
                when {
                    feed.error != null -> friendly(feed.error)
                    feed.loading || !feed.started -> "正在加载短剧…"
                    else -> "暂时没有可刷的短剧"
                },
                color = Color.White,
            )
            if (feed.error != null) TextButton(onClick = feed::retry) {
                Text("重试", color = Color.White)
            }
        }
        return
    }

    VerticalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
    ) { page ->
        val card = cards.getOrNull(page) ?: return@VerticalPager
        val selected = page == pager.settledPage
        val playingThis = selected && player.isPresented &&
            player.seriesID == card.id && player.state == PlayerState.Playing
        Box(Modifier.fillMaxSize()) {
            // Pages entering/leaving show their own cover; the selected page
            // becomes transparent only after its video is ready.
            if (!playingThis) CoverImage(card.cover, Modifier.fillMaxSize())
            if (!inPip) {
                Box(Modifier.fillMaxSize().clickable {
                    if (selected && player.seriesID == card.id) player.togglePause()
                })
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(240.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                            ),
                        ),
                )
                Column(
                    Modifier.align(Alignment.BottomStart)
                        .padding(start = 16.dp, end = 90.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        card.title,
                        color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onSeries(card.id) },
                    )
                    val currentEpisode = if (selected && player.seriesID == card.id && player.number > 0)
                        "第${player.number}集" else "第1集"
                    val total = if (card.episodes > 0) " · 全${card.episodes}集" else ""
                    Text(
                        currentEpisode + total,
                        color = Color.White.copy(alpha = 0.86f), fontSize = 13.sp,
                        modifier = Modifier.clickable { onSeries(card.id) },
                    )
                }
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    ReelButton(
                        "收藏",
                        onClick = { model.favorites.toggle(Fav.of(card)) },
                    ) {
                        val saved = model.favorites.has(card.id)
                        Icon(
                            if (saved) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = null, tint = Color.White,
                            modifier = Modifier.size(25.dp),
                        )
                    }
                    ReelButton("选集", onClick = { onSeries(card.id) }) {
                        Icon(
                            Icons.Filled.GridView, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(24.dp),
                        )
                    }
                    ReelButton("详情", onClick = { onSeries(card.id) }) {
                        Icon(
                            Icons.Outlined.Info, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(24.dp),
                        )
                    }
                }
                if (selected && (player.state == PlayerState.Resolving ||
                            player.state == PlayerState.Buffering)) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )
                }
                val failure = if (selected && player.seriesID == card.id)
                    player.state as? PlayerState.Failed else null
                if (failure != null) {
                    Column(
                        Modifier.align(Alignment.Center).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(failure.message, color = Color.White)
                        TextButton(onClick = player::retry) { Text("重新播放") }
                    }
                }
            }
        }
    }
}

private fun discoverFeed(model: AppModel, channel: String): Feed =
    Feed(model.scope) { cursor ->
        val page = model.catalog.discover(DiscoverOptions(channel = channel, cursor = cursor))
        FeedPage(
            cards = page.items.map(Card::of),
            cursor = page.cursor,
            more = page.hasMore,
        )
    }

@Composable
private fun ReelButton(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.34f)),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}
