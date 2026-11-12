package com.kadaliao.kaixinguo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kadaliao.kaixinguo.model.Card
import com.kadaliao.kaixinguo.model.Fav
import com.kadaliao.kaixinguo.model.Feed
import com.kadaliao.kaixinguo.model.FeedPage
import com.kadaliao.kaixinguo.model.PlayerState
import com.kadaliao.kaixinguo.model.friendly
import dev.openhongguo.DiscoverOptions
import kotlinx.coroutines.launch

/**
 * The anonymous Hongguo bookmall video feeds, not the short-drama rank lists.
 * Channels are mapped by openhongguo to video_feed(16), real_person_feed(39),
 * and motion_comic_feed(36); their continuation stays inside the SDK.
 */
internal enum class OfficialChannel(val title: String, val sdkChannel: String) {
    RECOMMEND("推荐", "推荐"),
    LIVE("真人", "真人剧"),
    COMIC("漫剧", "漫剧"),
}

@Composable
fun OfficialReelsScreen(inPip: Boolean) {
    val model = LocalModel.current
    val router = LocalRouter.current
    val player = model.player
    val scope = rememberCoroutineScope()
    val live = remember(model) { channelFeed(model, OfficialChannel.LIVE.sdkChannel) }
    val comic = remember(model) { channelFeed(model, OfficialChannel.COMIC.sdkChannel) }
    var channel by rememberSaveable { mutableStateOf(OfficialChannel.RECOMMEND) }
    var recommendIndex by rememberSaveable { mutableIntStateOf(0) }
    var liveIndex by rememberSaveable { mutableIntStateOf(0) }
    var comicIndex by rememberSaveable { mutableIntStateOf(0) }
    val feed = when (channel) {
        OfficialChannel.RECOMMEND -> model.home
        OfficialChannel.LIVE -> live
        OfficialChannel.COMIC -> comic
    }
    val index = when (channel) {
        OfficialChannel.RECOMMEND -> recommendIndex
        OfficialChannel.LIVE -> liveIndex
        OfficialChannel.COMIC -> comicIndex
    }
    fun selectIndex(value: Int) {
        when (channel) {
            OfficialChannel.RECOMMEND -> recommendIndex = value
            OfficialChannel.LIVE -> liveIndex = value
            OfficialChannel.COMIC -> comicIndex = value
        }
    }
    val cards = feed.cards
    val card = cards.getOrNull(index)
    var distance by remember { mutableFloatStateOf(0f) }
    var advanceWhenLoaded by remember { mutableStateOf<OfficialChannel?>(null) }
    val threshold = with(LocalDensity.current) { 68.dp.toPx() }

    LaunchedEffect(feed) {
        if (!feed.started && !feed.loading) feed.loadMore()
    }
    LaunchedEffect(feed, cards.size) {
        if (cards.isEmpty()) selectIndex(0)
        else if (index > cards.lastIndex) selectIndex(cards.lastIndex)
        if (advanceWhenLoaded == channel && index < cards.lastIndex) {
            selectIndex(index + 1)
            advanceWhenLoaded = null
        }
    }
    LaunchedEffect(feed, index, cards.size) {
        if (cards.isNotEmpty() && cards.lastIndex - index <= 2 && feed.wantsMore && !feed.loading) {
            feed.loadMore()
        }
    }
    LaunchedEffect(channel, card?.id) {
        if (card == null) {
            if (player.isPresented) player.leave()
        } else {
            // Use exactly the existing play / history / episode / CENC / MediaSession chain.
            model.playSeason(card.id)
        }
    }
    DisposableEffect(router) {
        router.reelsActive = true
        onDispose {
            if (!player.inPictureInPicture) player.leave()
            router.reelsActive = false
        }
    }
    if (!inPip) {
        PlayerWindow(
            hideBars = false,
            keepOn = player.isPresented && !player.paused &&
                (player.state == PlayerState.Playing || player.state == PlayerState.Buffering),
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!inPip && card != null) {
            VideoSurface(Modifier.fillMaxSize())
            val current = player.isPresented && player.seriesID == card.id
            if (!current || player.state != PlayerState.Playing) {
                CoverImage(card.cover, Modifier.fillMaxSize())
            }
            Box(
                Modifier.fillMaxSize().pointerInput(channel, index, cards.size) {
                    detectVerticalDragGestures(
                        onDragStart = { distance = 0f },
                        onVerticalDrag = { change, delta ->
                            change.consume()
                            distance += delta
                        },
                        onDragEnd = {
                            val delta = when {
                                distance < -threshold -> 1
                                distance > threshold -> -1
                                else -> 0
                            }
                            if (delta > 0 && index == cards.lastIndex && feed.wantsMore) {
                                advanceWhenLoaded = channel
                                feed.loadMore()
                            } else if (delta != 0) {
                                selectIndex((index + delta).coerceIn(0, cards.lastIndex))
                            }
                            distance = 0f
                        },
                        onDragCancel = { distance = 0f },
                    )
                }.clickable {
                    if (current) player.togglePause()
                },
            )

            // Let the video show through: only gradient at the bottom, not an opaque card.
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(220.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))),
            )

            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .padding(start = 16.dp, end = 82.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    card.title, color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 19.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val episode = if (current && player.number > 0) "第${player.number}集" else "正在准备"
                val total = card.episodes.takeIf { it > 0 }?.let { " · 全${it}集" } ?: ""
                Text(
                    "$episode$total", color = Color.White.copy(alpha = 0.88f), fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        player.leave()
                        router.reelsActive = false
                        router.open(Route.Series(card.id))
                    },
                )
                if (card.sub.isNotBlank()) {
                    Text(card.sub, color = Color.White.copy(alpha = 0.66f),
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("上滑切换下一部", color = Color.White.copy(alpha = 0.56f), fontSize = 11.sp)
            }
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 82.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ReelAction(
                    title = if (model.favorites.has(card.id)) "已收藏" else "收藏",
                    onClick = { model.favorites.toggle(Fav.of(card)) },
                ) {
                    Icon(
                        if (model.favorites.has(card.id)) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp),
                    )
                }
                ReelAction(title = "选集", onClick = {
                    player.leave()
                    router.reelsActive = false
                    router.open(Route.Series(card.id))
                }) { Icon(Icons.Filled.GridView, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                ReelAction(title = "详情", onClick = {
                    player.leave()
                    router.reelsActive = false
                    router.open(Route.Series(card.id))
                }) { Icon(Icons.Outlined.Info, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            }
            if (current && (player.state == PlayerState.Resolving || player.state == PlayerState.Buffering)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            val failure = if (current) player.state as? PlayerState.Failed else null
            if (failure != null) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(failure.message, color = Color.White)
                    TextButton(onClick = { player.retry() }) { Text("重新播放", color = Color.White) }
                }
            }
        } else if (card == null) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (feed.loading || feed.retrying || !feed.started) CircularProgressIndicator(color = Color.White)
                Text(
                    if (feed.error != null) friendly(feed.error)
                    else if (feed.loading || !feed.started) "正在获取${channel.title}短剧…"
                    else "暂无${channel.title}短剧",
                    color = Color.White,
                )
                if (feed.error != null) {
                    TextButton(onClick = feed::retry) { Text("重试", color = Color.White) }
                }
            }
        }

        if (!inPip) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(92.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.46f), Color.Transparent))),
            )
            Row(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (option in OfficialChannel.entries) {
                        val selected = option == channel
                        Column(
                            Modifier.clickable {
                                advanceWhenLoaded = null
                                channel = option
                            }.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(option.title, color = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
                                fontSize = if (selected) 18.sp else 16.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                            Box(
                                Modifier.padding(top = 5.dp).width(18.dp).height(3.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (selected) Color.White else Color.Transparent),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    advanceWhenLoaded = null
                    scope.launch {
                        feed.refresh(merging = false)
                        selectIndex(0)
                    }
                }, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新本频道", tint = Color.White)
                }
            }
        }
    }
}

/** A separate anonymous feed for a verified bookmall video channel. */
private fun channelFeed(model: com.kadaliao.kaixinguo.model.AppModel, channel: String) =
    Feed(model.scope) { cursor ->
        val p = model.catalog.discover(DiscoverOptions(channel = channel, cursor = cursor))
        FeedPage(p.items.map(Card::of), p.cursor, p.hasMore)
    }

@Composable
private fun ReelAction(title: String, onClick: () -> Unit, symbol: @Composable () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.32f)),
            contentAlignment = Alignment.Center,
        ) { symbol() }
        Text(title, fontSize = 11.sp, color = Color.White)
    }
}
