package com.kadaliao.kaixinguo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kadaliao.kaixinguo.covers.CoverStore
import com.kadaliao.kaixinguo.model.AppModel
import com.kadaliao.kaixinguo.model.Card
import com.kadaliao.kaixinguo.playback.ExoVideo

val LocalModel = staticCompositionLocalOf<AppModel> { error("no model") }
val LocalRouter = staticCompositionLocalOf<Router> { error("no router") }
val LocalCovers = staticCompositionLocalOf<CoverStore> { error("no covers") }
val LocalVideo = staticCompositionLocalOf<ExoVideo> { error("no video") }

/** What tapping a card does in place of opening its series: in the player's search, play it. */
val LocalPlayCard = compositionLocalOf<((Card) -> Unit)?> { null }

/**
 * App is the app's tabs: a bar along the bottom on phones, a rail down the
 * side on tablets and unfolded phones; and over them, the player.
 */
@Composable
fun App(model: AppModel, router: Router, covers: CoverStore, video: ExoVideo, inPip: Boolean) {
    CompositionLocalProvider(
        LocalModel provides model,
        LocalRouter provides router,
        LocalCovers provides covers,
        LocalVideo provides video,
    ) {
        val player = model.player
        val layout = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfoV2())
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            NavigationSuiteScaffold(
                layoutType = layout,
                navigationSuiteItems = {
                    for (t in listOf(Tab.DISCOVER, Tab.CATALOG, Tab.MINE)) {
                        val on = router.tab == t
                        item(
                            selected = on,
                            onClick = { router.reselect(t) },
                            icon = { Icon(t.icon(on), contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                },
            ) {
                // A bar along the bottom keeps the pages clear of the system's
                // bar; beside a rail they stop short of it themselves.
                val clear = if (layout == NavigationSuiteType.NavigationBar) {
                    Modifier
                } else {
                    Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                }
                Box(clear) { TabHost(router, inPip) }
            }
            AnimatedVisibility(
                visible = player.isPresented && (inPip || router.tab != Tab.DISCOVER || router.top != null || !router.reelsActive),
                enter = slideInVertically(tween(280)) { it },
                exit = slideOutVertically(tween(240)) { it },
            ) {
                PlayerScreen(inPip)
            }
        }
        BackHandler(enabled = !player.isPresented && router.canGoBack) { router.back() }
    }
}

private fun Tab.icon(on: Boolean): ImageVector = when (this) {
    Tab.DISCOVER -> if (on) Icons.Filled.Home else Icons.Outlined.Home
    Tab.CATALOG -> if (on) Icons.Filled.SmartDisplay else Icons.Outlined.SmartDisplay
    Tab.RANK -> if (on) Icons.Filled.Leaderboard else Icons.Outlined.Leaderboard
    Tab.NEW -> if (on) Icons.Filled.CalendarMonth else Icons.Outlined.CalendarMonth
    Tab.MINE -> if (on) Icons.Filled.Star else Icons.Outlined.StarOutline
    Tab.SEARCH -> if (on) Icons.Filled.Search else Icons.Outlined.Search
}

/** A page of a tab's stack, as the stack shows it. */
private data class Entry(val tab: Tab, val depth: Int, val route: Route?) {
    val key get() = Router.key(tab, depth, route)
}

/**
 * TabHost shows the page on top of the tab showing. Each page keeps its
 * state, as where its grid had scrolled to, while another covers it or
 * another tab shows, until it leaves its stack.
 */
@Composable
private fun TabHost(router: Router, inPip: Boolean) {
    val holder = rememberSaveableStateHolder()
    DisposableEffect(holder) {
        router.forget = { holder.removeState(it) }
        onDispose { router.forget = null }
    }
    val stack = router.path(router.tab)
    val entry = Entry(router.tab, stack.size, stack.lastOrNull())
    AnimatedContent(
        targetState = entry,
        contentKey = { it.key },
        transitionSpec = {
            when {
                initialState.tab != targetState.tab || initialState.depth == targetState.depth ->
                    fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                targetState.depth > initialState.depth ->
                    slideInHorizontally(tween(260)) { it } togetherWith
                        (slideOutHorizontally(tween(260)) { -it / 4 } + fadeOut(tween(260)))
                else ->
                    (slideInHorizontally(tween(260)) { -it / 4 } + fadeIn(tween(260))) togetherWith
                        slideOutHorizontally(tween(260)) { it }
            }
        },
        label = "pages",
    ) { e ->
        holder.SaveableStateProvider(e.key) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                when (val r = e.route) {
                    null -> when (e.tab) {
                        Tab.DISCOVER -> OfficialReelsScreen(inPip)
                        Tab.CATALOG -> TheatreScreen()
                        Tab.RANK -> RankScreen()
                        Tab.NEW -> NewReleasesScreen()
                        Tab.MINE -> MineScreen()
                        Tab.SEARCH -> SearchScreen()
                    }
                    is Route.Series -> SeriesScreen(r.id)
                }
            }
        }
    }
}

/** StartFailed is what shows when the SDK could not start. */
@Composable
fun StartFailed(message: String) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("开心果打不开", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
