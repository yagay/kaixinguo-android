@file:OptIn(ExperimentalMaterial3Api::class)

package com.kadaliao.kaixinguo.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.kadaliao.kaixinguo.KaixinguoApplication
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openhongguo.Episode
import dev.openhongguo.Season
import dev.openhongguo.Series
import com.kadaliao.kaixinguo.model.Card
import com.kadaliao.kaixinguo.model.Fav
import com.kadaliao.kaixinguo.model.SeriesPage
import com.kadaliao.kaixinguo.model.favLabel
import com.kadaliao.kaixinguo.model.friendly
import com.kadaliao.kaixinguo.model.metaLine
import com.kadaliao.kaixinguo.model.seasonLabel

/** SeriesScreen is a series: its poster over its own colors, what it is about, a way to play it, and its seasons and episodes. */
@Composable
fun SeriesScreen(id: String) {
    val model = LocalModel.current
    val sp = remember(id) { model.seriesEntry(id) }
    LaunchedEffect(id) { model.seriesPage(id) } // loads it, unless it is here or on its way
    val series = sp.series
    val error = sp.error
    if (series != null) {
        SeriesBody(sp, series)
        return
    }
    Scaffold(topBar = { BackBar() }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error != null) ErrorState(friendly(error)) { model.loadSeries(sp) } else Spinner()
        }
    }
}

@Composable
private fun SeriesBody(sp: SeriesPage, series: Series) {
    val router = LocalRouter.current
    val wide = isWide()
    val scroll = rememberScrollState()
    val threshold = with(LocalDensity.current) { (if (wide) 260 else 170).dp.toPx() }
    val titleInBar by remember { derivedStateOf { scroll.value > threshold } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Hero(sp, series, wide)
            if (!wide) {
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    About(series)
                    Actions(sp, series, wide)
                }
            }
            Episodes(sp, wide)
        }
        TopAppBar(
            title = {
                AnimatedVisibility(titleInBar, enter = fadeIn(), exit = fadeOut()) {
                    Text(series.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = {
                IconButton(onClick = router::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (titleInBar) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
            ),
        )
    }
}

/** The poster over its own colors, blurred wide; up under the bar too. */
@Composable
private fun Hero(sp: SeriesPage, series: Series, wide: Boolean) {
    Box(Modifier.fillMaxWidth()) {
        Backdrop(series.coverUrl, Modifier.matchParentSize())
        Column {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(64.dp)) // the bar
            Row(
                Modifier.padding(horizontal = if (wide) 44.dp else 16.dp).padding(top = if (wide) 12.dp else 4.dp, bottom = if (wide) 30.dp else 18.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(if (wide) 32.dp else 16.dp),
            ) {
                Poster(
                    series.coverUrl,
                    Modifier.width(if (wide) 200.dp else 118.dp).shadow(18.dp, RoundedCornerShape(12.dp), clip = false),
                    radius = 12.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (wide) 10.dp else 6.dp)) {
                    Text(
                        series.title,
                        style = if (wide) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(metaLine(series), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    if (wide) {
                        Box(Modifier.widthIn(max = 640.dp)) { About(series) }
                        Spacer(Modifier.height(8.dp))
                        Actions(sp, series, wide)
                    }
                }
            }
        }
    }
}

/**
 * Backdrop is a cover shrunk to a few pixels and stretched back out, which
 * blurs it on every version of Android; from 12 on, a real blur smooths it.
 */
@Composable
private fun Backdrop(url: String, modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val cover = rememberCover(url)
    val tiny = remember(cover) { cover?.let { Bitmap.createScaledBitmap(it, 12, 16, true).asImageBitmap() } }
    Box(modifier.background(bg)) {
        if (tiny != null) {
            Image(
                tiny,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize().alpha(0.75f)
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(24.dp) else Modifier),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.1f to bg.copy(alpha = 0.1f), 0.97f to bg)))
    }
}

@Composable
private fun About(series: Series) {
    if (series.description.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text(
        series.description,
        style = MaterialTheme.typography.bodyMedium,
        lineHeight = 22.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .animateContentSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = !expanded },
    )
}

@Composable
private fun Actions(sp: SeriesPage, series: Series, wide: Boolean) {
    val model = LocalModel.current
    val follows = (LocalContext.current.applicationContext as KaixinguoApplication).follows
    val saved = model.favorites.has(sp.id)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (sp.episodes.isNotEmpty() && !sp.isBook) {
            val r = model.resumePoint(sp)
            Button(
                onClick = { model.player.play(r.seriesID, r.number, r.start) },
                modifier = (if (wide) Modifier else Modifier.weight(1f)).height(48.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(r.label, fontWeight = FontWeight.SemiBold)
            }
        }
        OutlinedButton(
            onClick = { model.favorites.toggle(Fav.of(Card.of(series))) },
            modifier = Modifier.height(48.dp),
        ) {
            Icon(if (saved) Icons.Filled.Star else Icons.Outlined.StarOutline, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (saved) "已收藏" else "收藏", fontWeight = FontWeight.SemiBold)
        }
    }
    if (!sp.isBook && follows != null) {
        TextButton(onClick = {
            follows.toggle(sp.id, series.title, series.coverUrl, series.episodeCount)
        }) {
            Text(
                if (follows.contains(sp.id)) "✓ 已加入本机追更 · 点击取消"
                else "＋ 加入本机追更",
            )
        }
    }
    }
}

@Composable
private fun Episodes(sp: SeriesPage, wide: Boolean) {
    val model = LocalModel.current
    val router = LocalRouter.current
    val w = model.history.get(sp.id)
    val side = if (wide) 44.dp else 16.dp
    Column(
        Modifier.padding(horizontal = side).padding(top = 10.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SeasonChips(sp, side, Modifier.bleed(side)) { se -> router.replaceTop(Route.Series(se.seriesId)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("选集", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("共 ${sp.episodes.size} 集", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            PartPicker(sp, wide)
        }
        when {
            sp.isBook -> Text("这是小说或有声书，不能播放", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            sp.episodes.isEmpty() -> Text("暂时没有可看的剧集", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> EpisodeGrid(
                sp.partEpisodes,
                current = w?.episode ?: 0,
                watched = w?.watched?.toSet() ?: emptySet(),
                minWidth = if (wide) 56.dp else 50.dp,
            ) { e -> model.player.play(sp.id, e.number) }
        }
    }
}

/** SeasonChips shows the seasons of a series in several, this one chosen; seasons still to come show, but can't be chosen yet. */
@Composable
fun SeasonChips(sp: SeriesPage, padding: Dp, modifier: Modifier = Modifier, choose: (Season) -> Unit) {
    val seasons = sp.seasons ?: return
    if (seasons.size <= 1) return
    ChipRow(selected = seasons.indexOfFirst { it.seriesId == sp.id }, modifier = modifier, padding = padding) {
        items(seasons, key = { it.seriesId }) { se ->
            Chip(seasonLabel(se), se.seriesId == sp.id, enabled = se.episodeCount > 0) { choose(se) }
        }
    }
}

/** PartPicker chooses which hundred episodes show, for long series. */
@Composable
fun PartPicker(sp: SeriesPage, wide: Boolean) {
    val parts = sp.parts
    if (parts.size <= 1) return
    val part = sp.part.coerceIn(0, parts.lastIndex)
    if (parts.size <= (if (wide) 6 else 3)) {
        SingleChoiceSegmentedButtonRow {
            parts.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = part == i,
                    onClick = { sp.part = i },
                    shape = SegmentedButtonDefaults.itemShape(i, parts.size),
                    icon = {},
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text(p, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            }
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(parts[part])
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            parts.forEachIndexed { i, p ->
                DropdownMenuItem(
                    text = { Text(p) },
                    trailingIcon = { if (i == part) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = {
                        sp.part = i
                        open = false
                    },
                )
            }
        }
    }
}

/** EpisodeGrid is the episodes of the part showing, to tap, laid out in as many columns as fit. */
@Composable
fun EpisodeGrid(episodes: List<Episode>, current: Int, watched: Set<Int>, minWidth: Dp, play: (Episode) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val spacing = 8.dp
        val columns = ((maxWidth + spacing) / (minWidth + spacing)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            for (row in episodes.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (e in row) {
                        EpisodeChip(e, e.number == current, e.number in watched, Modifier.weight(1f)) { play(e) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * EpisodeChip is an episode's number to tap, filled while it is the one the
 * viewer is on, faded and checked once watched; a lock for one that needs a
 * login.
 */
@Composable
fun EpisodeChip(episode: Episode, current: Boolean, watched: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val fg = when {
        current -> c.onPrimary
        watched -> c.onSurfaceVariant
        else -> c.onSurface
    }
    Box(
        modifier
            .alpha(if (episode.playable) 1f else 0.45f)
            .height(40.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (current) c.primary else c.onSurface.copy(alpha = 0.07f))
            .clickable(enabled = episode.playable, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (episode.playable) {
            Text("${episode.number}", color = fg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        } else {
            Icon(Icons.Filled.Lock, contentDescription = "需要登录", tint = fg, modifier = Modifier.size(14.dp))
        }
        if (watched) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已看过",
                tint = if (current) c.onPrimary else c.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(10.dp),
            )
        }
    }
}
