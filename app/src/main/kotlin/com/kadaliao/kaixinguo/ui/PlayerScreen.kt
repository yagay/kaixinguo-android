@file:OptIn(ExperimentalMaterial3Api::class)

package com.kadaliao.kaixinguo.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kadaliao.kaixinguo.model.Card
import com.kadaliao.kaixinguo.model.DoubleSpeed
import com.kadaliao.kaixinguo.model.Fav
import com.kadaliao.kaixinguo.model.Player
import com.kadaliao.kaixinguo.model.PlayerState
import com.kadaliao.kaixinguo.model.Sleep
import com.kadaliao.kaixinguo.model.favLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PlayerScreen is the episode playing, its video filling the screen: what
 * plays and how fast above it, the episodes below it on phones and beside
 * it on tablets. Swiping up goes on to the next episode, and down back to
 * the one before. In picture in picture only the video shows.
 */
@Composable
fun PlayerScreen(inPip: Boolean) {
    val model = LocalModel.current
    val player = model.player
    val video = player.video
    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenHeightDp < 500 // a phone on its side: the video alone
    val panel = !landscape && config.screenWidthDp >= 600
    var showEpisodes by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var resumeAfterSearch by remember { mutableStateOf(false) }
    val hud = remember { PlayerHud() }
    val rotation = rememberRotation()

    // Pauses the episode for a search, to go on with it after.
    fun openSearch() {
        resumeAfterSearch = video.wantsToPlay
        video.pause()
        searching = true
    }

    // Leaves the search: to play the series picked, or the episode as it was.
    fun closeSearch(picked: Card?) {
        searching = false
        if (picked != null) model.playSeason(picked.id) else if (resumeAfterSearch) video.play()
    }

    BackHandler(enabled = !inPip) { if (searching) closeSearch(null) else player.leave() }
    PlayerWindow(
        hideBars = landscape || inPip,
        keepOn = !player.paused && (player.state == PlayerState.Playing || player.state == PlayerState.Buffering),
    )
    PlayerTheme {
        val keys = remember { FocusRequester() }
        LaunchedEffect(searching) { if (!searching) runCatching { keys.requestFocus() } }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .focusRequester(keys).focusable()
                .onPreviewKeyEvent { e -> !searching && e.type == KeyEventType.KeyDown && playerKey(player, e.key) },
        ) {
            // One layout for all, so the video keeps its place, and its
            // surface, as the device turns or picture in picture starts.
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    VideoArea(hud, edge = landscape || panel, chrome = !inPip, rotation = rotation, landscape = landscape, search = ::openSearch, modifier = Modifier.weight(1f).fillMaxWidth())
                    if (!inPip && !landscape && !panel) EpisodeBar { showEpisodes = true }
                }
                if (!inPip && panel) {
                    EpisodePanel(
                        Modifier.width(320.dp).fillMaxHeight().background(Color(0xFF171717))
                            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)),
                        steps = true,
                    )
                }
            }
            AnimatedVisibility(
                visible = searching && !inPip,
                enter = slideInHorizontally(tween(250)) { it },
                exit = slideOutHorizontally(tween(250)) { it },
            ) {
                PlayerSearch(::closeSearch)
            }
        }
        if (showEpisodes && !inPip) {
            ModalBottomSheet(onDismissRequest = { showEpisodes = false }, containerColor = Color(0xFF171717)) {
                EpisodePanel(Modifier.fillMaxHeight(0.85f)) { showEpisodes = false }
            }
        }
    }
}

/** The keys of a keyboard that work the video: space, the arrows and Esc. */
private fun playerKey(player: Player, key: Key): Boolean {
    when (key) {
        Key.Spacebar -> player.togglePause()
        Key.DirectionLeft -> player.skip(-10.0)
        Key.DirectionRight -> player.skip(10.0)
        Key.DirectionUp -> player.step(-1)
        Key.DirectionDown -> player.step(1)
        Key.Escape -> player.leave()
        else -> return false
    }
    return true
}

/** PlayerWindow darkens the system bars around the video, hides them when it fills the screen, and keeps the screen on while it plays. */
@Composable
private fun PlayerWindow(hideBars: Boolean, keepOn: Boolean) {
    val view = LocalView.current
    val window = LocalActivity.current?.window ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    DisposableEffect(controller) {
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNav = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNav
        }
    }
    DisposableEffect(hideBars) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hideBars) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * The player screen's [Rotation], which hears the phone turn while a press
 * holds the screen, and lets go as the player closes.
 */
@Composable
private fun rememberRotation(): Rotation? {
    val activity = LocalActivity.current ?: return null
    val rotation = remember(activity) { Rotation(activity) }
    val holding = rotation.held != null
    DisposableEffect(rotation, holding) {
        val listener = object : OrientationEventListener(activity) {
            override fun onOrientationChanged(degrees: Int) = rotation.phoneTurned(degrees)
        }
        if (holding) listener.enable()
        onDispose { listener.disable() }
    }
    DisposableEffect(rotation) { onDispose { rotation.release() } }
    return rotation
}

/**
 * Rotation turns the screen at a press: on its side, and upright again.
 * The way pressed holds until the phone is turned that way too, when the
 * screen goes back to following the phone, if auto-rotate is on; and as
 * the player closes.
 */
private class Rotation(private val activity: Activity) {
    /** The way a press turned the screen, while it holds: true on its side, false upright. */
    var held by mutableStateOf<Boolean?>(null)
        private set

    fun turn(landscape: Boolean) {
        held = landscape
        activity.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    /** Lets the screen follow the phone again, and the auto-rotate setting. */
    fun release() {
        if (held == null) return
        held = null
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /**
     * Hears the phone turn, in degrees from upright. With auto-rotate off
     * the screen wouldn't follow the phone, so the press holds.
     */
    fun phoneTurned(degrees: Int) {
        val held = held ?: return
        if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) return // lying flat
        val side = degrees in 60..120 || degrees in 240..300
        val upright = degrees <= 30 || degrees >= 330
        if ((if (held) side else upright) && autoRotate()) release()
    }

    private fun autoRotate() =
        Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
}

/**
 * PlayerHud is what the fingers on the video are doing, for what shows over
 * it: a press and hold as it goes, the video following a swipe, and a word
 * on what the last one did.
 */
private class PlayerHud {
    var holding by mutableStateOf(false)

    /** Whether the held finger has swiped far enough that letting go flips the lock. */
    var swiped by mutableStateOf(false)

    /** How far the video has followed a swipe up or down, in pixels. */
    var offset by mutableFloatStateOf(0f)

    /** What the last gesture did, or couldn't, said for a moment. */
    var toast by mutableStateOf<Toast?>(null)

    fun say(text: String) {
        toast = Toast(text)
    }
}

/** A word for a moment; each one shows anew, the same words or not. */
private class Toast(val text: String)

private val shadowed = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.4f), blurRadius = 6f))

/**
 * VideoArea is the video and what lies over it: what plays and how fast at
 * the top, how far it has got at the bottom, and what a press, a pause or a
 * failure shows. The video goes up under the status bar; `edge` says it
 * goes down to the bottom of the screen too, with no episode bar below.
 */
@Composable
private fun VideoArea(hud: PlayerHud, edge: Boolean, chrome: Boolean, rotation: Rotation?, landscape: Boolean, search: () -> Unit, modifier: Modifier) {
    Box(modifier.clipToBounds()) {
        VideoSurface(Modifier.fillMaxSize().offset { IntOffset(0, hud.offset.roundToInt()) })
        if (!chrome) return@Box
        Gestures(hud, Modifier.fillMaxSize())
        PauseMark(Modifier.align(Alignment.Center))
        PlayerOverlay()
        Column(Modifier.fillMaxSize()) {
            TopBar(rotation, landscape, search)
            SpeedHint(hud)
            Spacer(Modifier.weight(1f))
            // With nothing under the video, the bar keeps clear of the system's.
            ProgressBar(
                if (edge) {
                    Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                } else {
                    Modifier
                },
            )
        }
    }
}

/** VideoSurface shows the video, fitted to its frame, black around it. */
@Composable
private fun VideoSurface(modifier: Modifier) {
    val video = LocalVideo.current
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setBackgroundColor(android.graphics.Color.BLACK)
                player = video.exo
            }
        },
        modifier = modifier,
        onRelease = { it.player = null },
    )
}

/** What a finger down on the video turned out to be, before a long press would. */
private sealed interface Touch {
    data object Up : Touch
    data class Moved(val by: Offset) : Touch
}

/**
 * Gestures reads the fingers on the video: a tap pauses, a swipe moves
 * between episodes, and a press and hold plays at double speed.
 */
@Composable
private fun Gestures(hud: PlayerHud, modifier: Modifier) {
    val player = LocalModel.current.player
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val slop = viewConfiguration.touchSlop
                val touch = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var t: Touch? = null
                    while (t == null) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        t = when {
                            c == null || !c.pressed -> Touch.Up
                            (c.position - down.position).getDistance() > slop -> Touch.Moved(c.position - down.position)
                            else -> null
                        }
                    }
                    t
                }
                when {
                    touch == null -> hold(down, player, hud, haptic)
                    touch == Touch.Up -> player.togglePause()
                    touch is Touch.Moved && abs(touch.by.y) > abs(touch.by.x) -> swipe(down, player, hud, scope)
                    else -> untilUp(down) // sideways: nothing
                }
            }
        },
    )
}

private suspend fun AwaitPointerEventScope.untilUp(down: PointerInputChange) {
    while (true) {
        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return
        if (!c.pressed) return
    }
}

/**
 * Plays at double speed while a finger holds the video; swiping down locks
 * it on, and swiping up in a later hold turns it off.
 */
private suspend fun AwaitPointerEventScope.hold(down: PointerInputChange, player: Player, hud: PlayerHud, haptic: HapticFeedback) {
    val far = 40.dp.toPx()
    player.beginHold()
    hud.toast = null
    hud.holding = true
    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    var lifted = false
    try {
        while (true) {
            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) {
                lifted = true
                break
            }
            c.consume()
            val dy = c.position.y - down.position.y
            val swiped = if (player.doubleSpeed == DoubleSpeed.LOCKED) dy < -far else dy > far
            if (swiped != hud.swiped) {
                hud.swiped = swiped
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    } finally {
        val was = player.doubleSpeed
        player.endHold(swiped = lifted && hud.swiped)
        hud.holding = false
        hud.swiped = false
        if (was == DoubleSpeed.HELD && player.doubleSpeed == DoubleSpeed.LOCKED) {
            hud.say("已锁定 2 倍速，长按上滑可取消")
        } else if (was == DoubleSpeed.LOCKED && player.doubleSpeed == DoubleSpeed.OFF) {
            hud.say("已" + restoring(player.baseRate))
        }
    }
}

/**
 * Swiping up plays the next episode, and down the one before: the video
 * follows the finger, and goes on out if it went far or fast enough.
 */
private suspend fun AwaitPointerEventScope.swipe(down: PointerInputChange, player: Player, hud: PlayerHud, scope: CoroutineScope) {
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var dy = 0f
    var lifted = false
    while (true) {
        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
        tracker.addPosition(c.uptimeMillis, c.position)
        if (!c.pressed) {
            lifted = true
            break
        }
        c.consume()
        dy = c.position.y - down.position.y
        val can = if (dy < 0) player.hasNext else player.hasPrevious
        hud.offset = if (can) dy else dy / 4 // nowhere to go: it gives a little
    }
    val d = if (dy < 0) 1 else -1
    val can = if (d > 0) player.hasNext else player.hasPrevious
    val h = size.height.toFloat()
    val vy = tracker.calculateVelocity().y
    val far = abs(dy) > h / 6 || (abs(vy) > 700.dp.toPx() && vy * dy > 0)
    if (!lifted || !can || !far) {
        scope.launch { animate(hud.offset, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f)) { v, _ -> hud.offset = v } }
        if (lifted && !can && abs(dy) > 50.dp.toPx()) hud.say(if (d > 0) "已经是最后一集了" else "已经是第一集了")
        return
    }
    scope.launch {
        animate(hud.offset, if (dy < 0) -h else h, animationSpec = tween(200, easing = FastOutLinearInEasing)) { v, _ -> hud.offset = v }
        player.step(d)
        hud.offset = 0f // the screen black, until the next episode
    }
}

/** PauseMark says the video is paused; a tap anywhere on it plays on. */
@Composable
private fun PauseMark(modifier: Modifier) {
    val player = LocalModel.current.player
    if (player.paused && (player.state == PlayerState.Playing || player.state == PlayerState.Buffering)) {
        Icon(Icons.Filled.PlayArrow, contentDescription = "已暂停", tint = Color.White.copy(alpha = 0.85f), modifier = modifier.size(76.dp))
    }
}

/**
 * PlayerOverlay is what covers the video while it isn't playing: a spinner
 * while it loads or waits for more, or what went wrong. Swipes go through
 * it, to the episodes either side.
 */
@Composable
private fun PlayerOverlay() {
    val player = LocalModel.current.player
    when (val s = player.state) {
        PlayerState.Resolving -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        PlayerState.Buffering -> if (!player.paused) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        }
        is PlayerState.Failed -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(s.message, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = player::retry) { Text("重试") }
                    if (player.hasNext) OutlinedButton(onClick = { player.step(1) }) { Text("下一集") }
                }
            }
        }
        else -> {}
    }
}

// MARK: - Over the video

/** TopBar is what plays, and how fast, over the top of the video, with the way out, to turn the screen, to search and to more. */
@Composable
private fun TopBar(rotation: Rotation?, landscape: Boolean, search: () -> Unit) {
    val player = LocalModel.current.player
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)))) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 4.dp, end = 4.dp, bottom = 20.dp).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = player::leave) {
                Icon(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = "退出播放", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("第 ${player.number} 集", color = Color.White, style = MaterialTheme.typography.titleMedium.merge(shadowed), fontWeight = FontWeight.SemiBold)
                player.page?.series?.title?.let {
                    Text(it, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall.merge(shadowed), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SpeedMenu()
            if (rotation != null) RotateButton(rotation, landscape)
            IconButton(onClick = search) { Icon(Icons.Filled.Search, contentDescription = "搜索", tint = Color.White) }
            MoreMenu()
        }
    }
}

/**
 * RotateButton turns the screen on its side for a video wider than tall,
 * and upright again: on phones, where on its side the screen shows the
 * video alone. It stays while the screen is held on its side, so there is
 * always a way back, even to a series shot upright.
 */
@Composable
private fun RotateButton(rotation: Rotation, landscape: Boolean) {
    val player = LocalModel.current.player
    if (LocalConfiguration.current.smallestScreenWidthDp >= 600 || !(player.wide || rotation.held == true)) return
    IconButton(onClick = { rotation.turn(landscape = !landscape) }) {
        Icon(Icons.Filled.ScreenRotation, contentDescription = if (landscape) "竖屏观看" else "横屏观看", tint = Color.White)
    }
}

/** A speed as the player writes it: 1.25, 2. */
private fun rateText(r: Float): String = if (r == r.toInt().toFloat()) "${r.toInt()}" else "$r"

/** What going back from double speed says: to the speed the viewer chose. */
private fun restoring(rate: Float): String = if (rate == 1f) "恢复正常速度" else "恢复 ${rateText(rate)} 倍速"

/** SpeedMenu shows the speed the video plays at, and changes it. */
@Composable
private fun SpeedMenu() {
    val player = LocalModel.current.player
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(CircleShape).clickable { open = true }.height(44.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Speed, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (player.speed == 1f) "倍速" else "${rateText(player.speed)}x", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SpeedItems(player) { open = false }
        }
    }
}

@Composable
private fun SpeedItems(player: Player, done: () -> Unit) {
    for (s in Player.speeds.reversed()) {
        Choice(if (s == 1f) "1x 正常" else "${rateText(s)}x", player.speed == s) {
            player.chooseSpeed(s)
            done()
        }
    }
}

@Composable
private fun Choice(label: String, on: Boolean, pick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { if (on) Icon(Icons.Filled.Check, contentDescription = null) },
        onClick = pick,
    )
}

private enum class More { MAIN, SPEED, QUALITY, SLEEP }

/**
 * MoreMenu sets how the episode plays: its speed, its definition and when
 * to stop; and saves the series, or opens its page.
 */
@Composable
private fun MoreMenu() {
    val model = LocalModel.current
    val router = LocalRouter.current
    val player = model.player
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(More.MAIN) }
    Box {
        IconButton(onClick = {
            page = More.MAIN
            open = true
        }) { Icon(Icons.Filled.MoreHoriz, contentDescription = "更多", tint = Color.White) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            when (page) {
                More.MAIN -> {
                    Entry("倍速", Icons.Filled.Speed, if (player.speed == 1f) "正常" else "${rateText(player.speed)}x") { page = More.SPEED }
                    if (player.qualities.isNotEmpty()) {
                        Entry("清晰度", Icons.Filled.HighQuality, "${player.quality}P") { page = More.QUALITY }
                    }
                    Entry("定时关闭", Icons.Filled.Bedtime, sleepLine(player)) { page = More.SLEEP }
                    val sp = player.page
                    val series = sp?.series
                    if (sp != null && series != null) {
                        HorizontalDivider()
                        val saved = model.favorites.has(sp.id)
                        DropdownMenuItem(
                            text = { Text(favLabel(saved) + "这部剧") },
                            leadingIcon = { Icon(if (saved) Icons.Outlined.StarOutline else Icons.Filled.Star, contentDescription = null) },
                            onClick = {
                                model.favorites.toggle(Fav.of(Card.of(series)))
                                open = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("剧集详情") },
                            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                            onClick = {
                                open = false
                                player.leave()
                                if (router.top != Route.Series(sp.id)) router.open(Route.Series(sp.id))
                            },
                        )
                    }
                }
                More.SPEED -> {
                    Heading("倍速") { page = More.MAIN }
                    SpeedItems(player) { open = false }
                }
                More.QUALITY -> {
                    Heading("清晰度") { page = More.MAIN }
                    for (q in player.qualities) {
                        Choice("${q}P", q == player.quality) {
                            player.chooseQuality(q)
                            open = false
                        }
                    }
                }
                More.SLEEP -> {
                    Heading("定时关闭") { page = More.MAIN }
                    val choices = listOf(Sleep.Off to "不开启", Sleep.EndOfEpisode to "播完本集") +
                        listOf(15, 30, 60, 90).map { Sleep.Minutes(it) to "$it 分钟" }
                    for ((s, label) in choices) {
                        Choice(label, player.sleep == s) {
                            player.chooseSleep(s)
                            open = false
                        }
                    }
                }
            }
        }
    }
}

/** A line of the menu that opens a list of choices, the one made beside it. */
@Composable
private fun Entry(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, open: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = { Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) },
        onClick = open,
    )
}

/** The head of a list of choices, which goes back to the menu. */
@Composable
private fun Heading(label: String, back: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontWeight = FontWeight.SemiBold) },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = "返回", modifier = Modifier.size(16.dp)) },
        onClick = back,
    )
    HorizontalDivider()
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

/** When the sleep timer stops the video: "播完本集", "22:30 停止". */
private fun sleepLine(player: Player): String = when (player.sleep) {
    Sleep.Off -> "未开启"
    Sleep.EndOfEpisode -> "播完本集"
    is Sleep.Minutes -> (player.sleepAt?.atZone(ZoneId.systemDefault())?.format(clockFormat) ?: "") + " 停止"
}

/** Seconds as minutes and seconds: 1:05. */
private fun clock(s: Double): String {
    if (!s.isFinite() || s <= 0) return "0:00"
    val n = s.toInt()
    return "%d:%02d".format(n / 60, n % 60)
}

/** ProgressBar is how far the episode has got, along the bottom of the video; dragging along it moves there. */
@Composable
private fun ProgressBar(modifier: Modifier) {
    val video = LocalModel.current.player.video
    var dragged by remember { mutableStateOf<Float?>(null) } // where a drag is, as a fraction
    var at by remember { mutableDoubleStateOf(0.0) }
    var total by remember { mutableDoubleStateOf(0.0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) {
            val d = video.duration
            total = if (d.isFinite() && d > 0) d else 0.0
            val t = video.position
            at = if (total > 0 && t.isFinite()) (t / total).coerceIn(0.0, 1.0) else 0.0
            delay(250)
        }
    }
    val shown = dragged ?: at.toFloat()
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        dragged?.let { f ->
            Text(
                "${clock(f * total)} / ${clock(total)}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        Canvas(
            Modifier.fillMaxWidth().height(28.dp).pointerInput(total) {
                if (total <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragged = (down.position.x / size.width).coerceIn(0f, 1f)
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        dragged = (c.position.x / size.width).coerceIn(0f, 1f)
                    }
                    val f = dragged ?: return@awaitEachGesture
                    video.seek(f * total)
                    // Keeps the bar there until the video has moved.
                    scope.launch {
                        delay(400)
                        dragged = null
                    }
                }
            },
        ) {
            if (total <= 0) return@Canvas
            val active = dragged != null
            val h = (if (active) 4.dp else 2.dp).toPx()
            val knob = (if (active) 14.dp else 7.dp).toPx()
            val y = size.height / 2
            val w = size.width
            drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y - h / 2), Size(w, h), CornerRadius(h / 2))
            drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(0f, y - h / 2), Size(w * shown, h), CornerRadius(h / 2))
            drawCircle(Color.White, knob / 2, Offset((w * shown).coerceIn(knob / 2, w - knob / 2), y))
        }
    }
}

/**
 * SpeedHint shows a press and hold on the video at work: double speed, and
 * which way to swipe to lock it on or turn it off; and, for a moment, what
 * a swipe did.
 */
@Composable
private fun SpeedHint(hud: PlayerHud) {
    val player = LocalModel.current.player
    val toast = hud.toast
    LaunchedEffect(toast) {
        if (toast == null) return@LaunchedEffect
        delay(2000)
        if (hud.toast === toast) hud.toast = null
    }
    Box(Modifier.fillMaxWidth().padding(top = 12.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(hud.holding, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            val locked = player.doubleSpeed == DoubleSpeed.LOCKED
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (locked) "已锁定 2 倍速" else "2 倍速播放中", if (locked) Icons.Filled.Lock else Icons.Filled.FastForward)
                SwipeHint(locked, hud.swiped, player.baseRate)
            }
        }
        if (!hud.holding && toast != null) Pill(toast.text)
    }
}

@Composable
private fun Pill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(
        Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Which way to swipe, or, once swiped far enough, what letting go does. */
@Composable
private fun SwipeHint(locked: Boolean, swiped: Boolean, base: Float) {
    val (icon, text) = when {
        !locked && !swiped -> Icons.Filled.KeyboardArrowDown to "下滑锁定 2 倍速"
        !locked -> Icons.Filled.Lock to "松手锁定 2 倍速"
        !swiped -> Icons.Filled.KeyboardArrowUp to "上滑取消 2 倍速"
        else -> Icons.AutoMirrored.Filled.Undo to "松手" + restoring(base)
    }
    val bounce by rememberInfiniteTransition(label = "bounce").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "bounce",
    )
    val fg = if (swiped) Color.Black else Color.White
    Row(
        Modifier.clip(CircleShape).background(if (swiped) Color.White else Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dy = if (swiped) 0f else (if (locked) -3f else 3f) * bounce
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp).offset(y = dy.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = fg, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

// MARK: - Under and beside the video

/** EpisodeBar opens the episodes, under the video on phones. */
@Composable
private fun EpisodeBar(open: () -> Unit) {
    val total = LocalModel.current.player.page?.episodes?.size ?: 0
    Row(
        Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
            .fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF262626))
            .clickable(onClick = open).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.GridView, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (total > 0) "选集 · 全 $total 集" else "选集", color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = Color.White)
    }
}

private fun episodeLine(player: Player): String {
    val total = player.page?.episodes?.size ?: 0
    return if (total > 0) "第 ${player.number} 集 / 共 $total 集" else "第 ${player.number} 集"
}

/**
 * EpisodePanel is the series' title and its episodes, beside the video on
 * tablets, with buttons to move between them, and in a sheet on phones,
 * which `picked` closes once something plays.
 */
@Composable
private fun EpisodePanel(modifier: Modifier = Modifier, steps: Boolean = false, picked: () -> Unit = {}) {
    val model = LocalModel.current
    val player = model.player
    val sp = player.page
    val series = sp?.series
    if (sp == null || series == null) {
        Spinner(modifier)
        return
    }
    val watched = model.history.get(sp.id)?.watched?.toSet() ?: emptySet()
    Column(modifier) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(series.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(episodeLine(player), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (steps) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { player.step(-1) }, enabled = player.hasPrevious, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("上一集")
                    }
                    OutlinedButton(onClick = { player.step(1) }, enabled = player.hasNext, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("下一集")
                    }
                }
            }
            SeasonChips(sp, 20.dp, Modifier.bleed(20.dp)) { se ->
                model.playSeason(se.seriesId)
                picked()
            }
            PartPicker(sp, wide = false)
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        val grid = rememberLazyGridState()
        val episodes = sp.partEpisodes
        LaunchedEffect(player.number, sp.part) {
            val i = episodes.indexOfFirst { it.number == player.number }
            if (i < 0) return@LaunchedEffect
            grid.scrollToItem(i)
            grid.scrollBy(-(grid.layoutInfo.viewportSize.height / 2f) + 60f)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(46.dp),
            state = grid,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(episodes, key = { it.id }) { e ->
                EpisodeChip(e, e.number == player.number, e.number in watched) {
                    if (e.number != player.number) player.play(sp.id, e.number)
                    picked()
                }
            }
        }
    }
}

// MARK: - Searching from the player

/**
 * PlayerSearch looks for another series without leaving the player: the
 * hot searches, then what was searched for. Picking a series plays it; the
 * back button beside the field goes back to the episode.
 */
@Composable
private fun PlayerSearch(close: (Card?) -> Unit) {
    val model = LocalModel.current
    val focusManager = LocalFocusManager.current
    var text by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(text) {
        if (text.isEmpty()) query = ""
        if (text.isEmpty() || text == query) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        suggestions = model.suggestions(text)
    }
    fun submit(s: String) {
        val q = s.trim()
        if (q.isEmpty()) return
        text = q
        query = q
        suggestions = emptyList()
        focusManager.clearFocus()
    }
    CompositionLocalProvider(LocalPlayCard provides { card: Card -> close(card) }) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { close(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = "返回播放") }
                SearchField(text, { text = it }, { submit(text) }, Modifier.weight(1f), focus)
                TextButton(onClick = { submit(text) }) { Text("搜索", fontWeight = FontWeight.SemiBold) }
            }
            Box(Modifier.weight(1f)) {
                when {
                    suggestions.isNotEmpty() && text != query -> SuggestionList(suggestions, ::submit)
                    query.isEmpty() -> HotView()
                    else -> {
                        val feed = model.searchFeed(query)
                        FeedKey(feed) { FeedGrid(feed, "没有找到“$query”相关的短剧，换个词试试") }
                    }
                }
            }
        }
    }
}
