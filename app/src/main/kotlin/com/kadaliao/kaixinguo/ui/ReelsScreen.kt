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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kadaliao.kaixinguo.model.PlayerState
import com.kadaliao.kaixinguo.model.Card
import com.kadaliao.kaixinguo.model.Feed
import com.kadaliao.kaixinguo.model.FeedPage
import dev.openhongguo.ShortRankQuery
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * First-stage reels experience: recommendations from the existing feed, a single ExoPlayer,
 * vertical gestures switch series, and the Player keeps auto-next-episode inside a series.
 * The feed is not a claim of access to the official account-personalized reel endpoint.
 */
@Composable
fun ReelsScreen() {
    val model = LocalModel.current
    val router = LocalRouter.current
    val player = model.player
    val scope = rememberCoroutineScope()
    // Display-only categories: reuse existing Catalog + Feed and keep all player internals intact.
    val humanFeed = remember(model) {
        Feed(model.scope) { cursor ->
            val result = model.catalog.shortRank(ShortRankQuery(content = "human", cursor = cursor))
            FeedPage(cursor = result.cursor, more = result.hasMore).apply {
                cards = result.items.filter { it.seriesId.isNotEmpty() }.map { item ->
                    Card(
                        id = item.seriesId,
                        title = item.title,
                        cover = item.coverUrl,
                        sub = item.score.ifEmpty {
                            if (item.episodeCount > 0) "全${item.episodeCount}集" else ""
                        },
                        episodes = item.episodeCount,
                    )
                }
            }
        }
    }
    var channel by rememberSaveable { mutableStateOf("推荐") }
    var recommendIndex by rememberSaveable { mutableIntStateOf(0) }
    var humanIndex by rememberSaveable { mutableIntStateOf(0) }
    val human = channel == "真人"
    val feed = if (human) humanFeed else model.home
    val index = if (human) humanIndex else recommendIndex
    fun selectIndex(value: Int) {
        if (human) humanIndex = value else recommendIndex = value
    }
    var drag by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val cards = feed.cards
    val card = cards.getOrNull(index)

    LaunchedEffect(feed) {
        if (feed.wantsMore && !feed.loading) feed.loadMore()
    }
    LaunchedEffect(feed, cards.size) {
        if (cards.isEmpty()) selectIndex(0)
        else if (index > cards.lastIndex) selectIndex(cards.lastIndex)
    }
    LaunchedEffect(feed, index, cards.size) {
        if (cards.isNotEmpty() && index >= cards.size - 3 && feed.wantsMore && !feed.loading) {
            feed.loadMore()
        }
    }
    LaunchedEffect(channel, card?.id) {
        // Switching categories must not leave the previous category playing behind an empty feed.
        if (card == null) player.leave() else model.playSeason(card.id)
    }
    DisposableEffect(router) {
        router.reelPlaybackActive = true
        onDispose {
            // Keep the upstream Player behavior: leaving its screen pauses playback.
            player.leave()
            router.reelPlaybackActive = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (card == null) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (feed.loading || feed.retrying || !feed.started) {
                    CircularProgressIndicator(color = Color.White)
                }
                Text(
                    if (feed.error != null) "短剧加载失败" else if (feed.loading || !feed.started) "正在加载短剧…"
                    else "暂无短剧",
                    color = Color.White,
                    modifier = Modifier.padding(16.dp),
                )
                if (feed.error != null) Button(onClick = feed::retry) { Text("重试") }
            }
        } else {

        // Reuse Kaixinguo's single ExoPlayer. No changes to Player, Video or MediaSession.
        VideoSurface(Modifier.fillMaxSize())
        val current = router.reelPlaybackActive && player.isPresented
        if (!current || player.state != PlayerState.Playing) {
            CoverImage(card.cover, Modifier.fillMaxSize())
        }

        // The transparent layer owns the reel gestures; action buttons above it stay clickable.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(index, cards.size) {
                    detectVerticalDragGestures(
                        onDragStart = { drag = 0f },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            drag += amount
                        },
                        onDragEnd = {
                            val direction = when {
                                drag < -threshold -> 1
                                drag > threshold -> -1
                                else -> 0
                            }
                            if (direction > 0 && index >= cards.lastIndex) {
                                if (feed.wantsMore) feed.loadMore()
                            } else if (direction != 0) {
                                selectIndex((index + direction).coerceIn(0, cards.lastIndex))
                            }
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f },
                    )
                }
                .clickable {
                    if (router.reelPlaybackActive && player.isPresented) player.togglePause()
                },
        )

        if (current && (player.state == PlayerState.Resolving || player.state == PlayerState.Buffering)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        val failed = if (current) player.state as? PlayerState.Failed else null
        if (failed != null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(failed.message, color = Color.White)
                Button(onClick = { player.retry() }, modifier = Modifier.padding(top = 12.dp)) { Text("重新播放") }
            }
        }

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(card.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (current) "第 " + player.number + " 集" else "正在打开…",
                color = Color.White.copy(alpha = 0.85f),
            )
            if (card.sub.isNotEmpty()) {
                Text(card.sub, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    player.leave()
                    router.reelPlaybackActive = false
                    router.open(Route.Series(card.id))
                }) { Text("剧集详情 / 选集", color = Color.White) }
                Spacer(Modifier.weight(1f))
                Text(
                    (index + 1).toString() + " / " + cards.size + " · 上下滑切换",
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
        }
        } // end current video card

        // Centered category tabs remain available even when one feed is empty or loading.
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .statusBarsPadding()
                .background(Color.Black.copy(alpha = 0.22f))
                .padding(horizontal = 12.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("刷短剧", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("推荐", "真人").forEach { label ->
                    val selected = channel == label
                    Column(
                        Modifier.clickable { channel = label }.padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            label,
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.66f),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = if (selected) 17.sp else 15.sp,
                        )
                        Box(
                            Modifier.padding(top = 5.dp).width(20.dp).height(3.dp)
                                .background(
                                    if (selected) Color.White else Color.Transparent,
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch {
                    feed.refresh(merging = false)
                    selectIndex(0)
                }
            }) { Text("刷新", color = Color.White, fontSize = 13.sp) }
        }

    }
}
