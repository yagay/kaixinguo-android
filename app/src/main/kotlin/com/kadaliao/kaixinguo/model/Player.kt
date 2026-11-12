package com.kadaliao.kaixinguo.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.openhongguo.Episode
import dev.openhongguo.Playback
import dev.openhongguo.PlaybackResult
import dev.openhongguo.Stream
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/** What the video is doing, as the player hears it. */
enum class TimeControl { PAUSED, WAITING, PLAYING }

/** VideoListener hears what the video does. */
interface VideoListener {
    fun statusChanged()

    /** The episode played to its end. */
    fun ended()

    /** The episode can't go on; `message` says why, in the viewer's words. */
    fun failed(message: String)

    /** The episode's picture, as width over height, once the video knows it. */
    fun aspectChanged(aspect: Float)
}

/** VideoItem is an episode as the video plays it, with what the lock screen shows. */
class VideoItem(
    val uri: String,
    val episodeId: String,
    val title: String,
    val subtitle: String,
    val artwork: ByteArray?,
    val artworkUri: String,
)

/**
 * Video is what shows the episodes: ExoPlayer in the app, a fake in the
 * tests. It belongs to the main thread.
 */
interface Video {
    val hasItem: Boolean

    /** Seconds into the episode; NaN without one. */
    val position: Double

    /** The episode's length in seconds; NaN until known. */
    val duration: Double

    /** Whether it plays, or waits to, rather than being paused. */
    val wantsToPlay: Boolean
    val status: TimeControl

    /** The speed it plays at, now and for the episodes after. */
    var speed: Float
    var listener: VideoListener?

    fun load(item: VideoItem, start: Double)
    fun clear()
    fun play()
    fun pause()
    fun seek(seconds: Double)
}

/** Streams makes an episode's file on the CDN playable, decrypting it with its key, and returns its URI. */
fun interface Streams {
    fun register(sources: List<String>, key: ByteArray?): String
}

sealed interface PlayerState {
    data object Idle : PlayerState
    data object Resolving : PlayerState // finding the episode's file
    data object Buffering : PlayerState // the video loads
    data object Playing : PlayerState
    data class Failed(val message: String) : PlayerState
}

/** What a press and hold on the video has done to its speed. */
enum class DoubleSpeed {
    OFF,
    HELD, // until the finger lifts
    LOCKED, // until the viewer turns it off
}

/** When playback stops by itself: never, at the end of the episode playing, or some minutes from now. */
sealed interface Sleep {
    data object Off : Sleep
    data object EndOfEpisode : Sleep
    data class Minutes(val minutes: Int) : Sleep
}

/** Player is the episode the player shows, and the video that plays it. It belongs to the main thread. */
class Player internal constructor(
    private val app: AppModel,
    val video: Video,
    private val streams: Streams,
) {
    companion object {
        /** The speeds the speed menu offers. */
        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

        /** The speed a press and hold on the video plays at. */
        const val DOUBLE_RATE = 2f
    }

    var seriesID by mutableStateOf<String?>(null)
        private set
    var number by mutableIntStateOf(0)
        private set
    var state by mutableStateOf<PlayerState>(PlayerState.Idle)
        private set

    /** Whether the player's screen shows. */
    var isPresented by mutableStateOf(false)

    /** Reel mode plays inside the tab rather than opening a second player window. */
    var reelMode by mutableStateOf(false)
        private set

    /** Whether picture in picture shows the video, so leaving the screen keeps it playing. */
    var inPictureInPicture by mutableStateOf(false)

    /**
     * Whether the video is paused, rather than playing or waiting to. The
     * video only tells of changes, so this starts as the video does.
     */
    var paused by mutableStateOf(video.status == TimeControl.PAUSED)
        private set

    /**
     * The speed the viewer chose, from the speed menu or by locking double
     * speed on; the episodes after play at it too.
     */
    var speed by mutableFloatStateOf(1f)
        private set
    var doubleSpeed by mutableStateOf(DoubleSpeed.OFF)
        private set

    /** The speed before double speed, which it goes back to. */
    var baseRate by mutableFloatStateOf(1f)
        private set

    /** The definitions the episode comes in, as lines, best first: 1080, 720, …; and the one playing. */
    var qualities by mutableStateOf(emptyList<Int>())
        private set
    var quality by mutableIntStateOf(0)
        private set

    /** The definition the viewer picked, which later episodes keep to as near as they can; null for the best. */
    var preferredQuality by mutableStateOf<Int?>(null)
        private set
    var sleep by mutableStateOf<Sleep>(Sleep.Off)
        private set

    /** When a sleep in minutes pauses the video. */
    var sleepAt by mutableStateOf<Instant?>(null)
        private set

    /** Whether the episode's picture is wider than it is tall: from its stream at first, then from the video itself. */
    var wide by mutableStateOf(false)
        private set

    /** Whether the viewer has left the app, the episode paused until they come back. */
    var away by mutableStateOf(false)
        private set
    private var playOnReturn by mutableStateOf(false) // it played as they left
    private var startPaused by mutableStateOf(false) // the episode starting shows without playing

    private var start = 0.0 // where to start, in seconds
    private var gen = 0 // tells this start from later ones
    private var pending = false // waits for the series to load
    private var episodeID = "" // the episode the video plays
    private val futures = HashMap<String, Future>()
    private var sleepJob: Job? = null
    private var ticker: Job? = null

    /** Future is an episode's playback model being resolved, shared by the start of the episode and the prefetch before it. */
    private class Future {
        lateinit var result: Deferred<Result<PlaybackResult>>
        var outcome: Result<PlaybackResult>? = null
    }

    init {
        video.listener = object : VideoListener {
            override fun statusChanged() = timeControlChanged()
            override fun ended() = this@Player.ended()
            override fun failed(message: String) = itemFailed(message)
            override fun aspectChanged(aspect: Float) {
                if (episodeID.isNotEmpty()) wide = aspect > 1f
            }
        }
    }

    /** The series playing, once its page has loaded. */
    val page: SeriesPage? get() = seriesID?.let { app.seriesEntry(it) }

    // MARK: - Starting an episode

    /**
     * Opens the player on episode n of the series, from start seconds in;
     * `paused`, it shows the episode there without playing it.
     */
    fun play(seriesID: String, n: Int, start: Double = 0.0, paused: Boolean = false, inReels: Boolean = false) {
        gen++
        if (seriesID != this.seriesID) wide = false // another series may be shot the other way
        this.seriesID = seriesID
        number = n
        this.start = start
        startPaused = paused
        away = false // one asked for while away, as from the notification, plays there
        pending = false
        state = PlayerState.Resolving
        reelMode = inReels
        isPresented = !inReels
        // In picture in picture the old episode stays in the window until the next replaces it.
        stopItem(keep = inPictureInPicture)
        val sp = app.seriesEntry(seriesID)
        if (sp.series == null) {
            pending = true // fillPending starts it
            if (!sp.loading) app.loadSeries(sp)
            return
        }
        startEpisode(sp)
    }

    /** Use the existing player and auto-next-episode logic in the vertical reel feed. */
    fun playReel(seriesID: String, n: Int, start: Double = 0.0) =
        play(seriesID, n, start, inReels = true)

    /** When leaving the feed, prevent an in-flight resolution from starting hidden playback. */
    fun stopReel() {
        if (!reelMode) return
        reelMode = false
        video.pause()
        if (state == PlayerState.Resolving) {
            gen++
            pending = false
            state = PlayerState.Idle
        }
    }

    /** Starts the episode that waited for its series, or says why it can't. */
    internal fun fillPending(sp: SeriesPage) {
        if (!pending || seriesID != sp.id) return
        pending = false
        if (sp.series == null) {
            state = PlayerState.Failed(friendly(sp.error))
            return
        }
        startEpisode(sp)
    }

    private fun startEpisode(sp: SeriesPage) {
        val ep = sp.episode(number)
        if (ep == null) {
            state = PlayerState.Failed("没有第 $number 集")
            return
        }
        if (!ep.playable) {
            state = PlayerState.Failed("这一集需要登录才能观看")
            return
        }
        if (sp.isBook) {
            state = PlayerState.Failed("这是小说或有声书，不能播放")
            return
        }
        state = PlayerState.Resolving
        sp.reveal(number)
        record(sp, number, start, 0.0, finished = false)
        val gen = gen
        val start = start
        val paused = startPaused
        val f = resolve(ep)
        app.scope.launch {
            val result = f.result.await()
            if (this@Player.gen != gen) return@launch
            try {
                val item = makeItem(result.getOrThrow(), sp, ep)
                state = PlayerState.Buffering
                episodeID = ep.id
                video.load(item, start)
                when {
                    paused -> {}
                    away -> playOnReturn = true // the viewer left while it was on its way
                    else -> video.play()
                }
                if (doubleSpeed == DoubleSpeed.HELD) video.speed = DOUBLE_RATE // a finger still down, from the episode before
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = PlayerState.Failed(friendly(e))
                futures.remove(ep.id) // try afresh next time
            }
        }
    }

    /** The episode's playback model, resolving it unless a prefetch did or is doing it. */
    private fun resolve(ep: Episode): Future {
        futures[ep.id]?.let { f ->
            val o = f.outcome ?: return f
            val r = o.getOrNull()
            if (r != null && !expired(r.playback)) return f
        }
        val f = Future()
        val id = ep.id
        f.result = app.scope.async {
            attempt { app.catalog.resolvePlayback(id) }.also { f.outcome = it }
        }
        futures[id] = f
        return f
    }

    /**
     * The item that plays the stream of the model nearest the definition
     * the viewer picked, decrypting it with its key.
     */
    private fun makeItem(r: PlaybackResult, sp: SeriesPage, ep: Episode): VideoItem {
        val pb = r.playback
        val s = pickStream(pb.streams, preferredQuality) ?: throw AppError("这一集没有可以播放的视频")
        if (s.hls || s.dash || pb.audioUrl.isNotEmpty()) throw AppError("暂不支持这种视频格式")
        var key: ByteArray? = null
        if (pb.protected || s.protected) {
            val k = r.contentKey ?: throw (r.contentKeyError ?: AppError("无法解密这一集"))
            key = hexBytes(k.key)
            if (key.size != 16) throw AppError("无法解密这一集")
        }
        val sources = mutableListOf(s.url.trim())
        if (s.backupUrl.isNotBlank() && s.backupUrl != s.url) sources += s.backupUrl.trim()
        val uri = streams.register(sources, key)
        debugLog { "play: ${ep.id} ${s.definition} ${s.codec} encrypted=${key != null}" }
        qualities = playable(pb.streams).map(::lines).toSortedSet().sortedDescending()
        quality = lines(s)
        if (s.width > 0 && s.height > 0) wide = s.width > s.height
        val series = sp.series
        val cover = series?.coverUrl.orEmpty()
        return VideoItem(uri, ep.id, series?.title.orEmpty(), "第 ${ep.number} 集", app.artwork(cover), cover)
    }

    /** Lets the episode go, as another starts; `keep` leaves it on screen, paused, until the next replaces it. */
    private fun stopItem(keep: Boolean = false) {
        video.pause()
        episodeID = "" // what the old item does no longer counts
        if (!keep) video.clear()
    }

    // MARK: - Moving between episodes

    /** Plays the episode d after the one playing, if there is one. */
    fun step(d: Int) {
        val sp = page ?: return
        if (d > 0) {
            app.following(sp, number)?.let { play(it.first, it.second, inReels = reelMode) }
        } else if (sp.episode(number + d) != null) {
            play(sp.id, number + d, inReels = reelMode)
        }
    }

    val hasNext: Boolean get() = page?.let { app.following(it, number) } != null

    val hasPrevious: Boolean get() = page?.episode(number - 1) != null

    // MARK: - Pausing and speed

    /** Pauses the video, or plays it on; from the start again if it had ended. */
    fun togglePause() {
        if (!video.hasItem) return
        if (video.wantsToPlay && video.status != TimeControl.PAUSED) {
            video.pause()
            return
        }
        val d = video.duration
        if (d.isFinite() && video.position >= d - 0.5) video.seek(0.0)
        video.play()
    }

    /** Moves d seconds on through the episode, or back when d is negative. */
    fun skip(d: Double) {
        if (!video.hasItem) return
        var t = maxOf(0.0, video.position + d)
        val end = video.duration
        if (end.isFinite()) t = minOf(t, maxOf(0.0, end - 1))
        video.seek(t)
    }

    /** Plays at r from now on, for the episodes after too. */
    fun chooseSpeed(r: Float) {
        speed = r
        video.speed = r
    }

    /** Plays the episode on in the definition nearest q, and the episodes after in it too. */
    fun chooseQuality(q: Int) {
        preferredQuality = q
        val id = seriesID ?: return
        if (q == quality || !(state == PlayerState.Playing || state == PlayerState.Buffering)) return
        val t = video.position
        play(id, number, if (t.isFinite()) t else 0.0, inReels = reelMode)
    }

    fun chooseSleep(s: Sleep) {
        sleep = s
        sleepJob?.cancel()
        sleepJob = null
        sleepAt = null
        val m = (s as? Sleep.Minutes)?.minutes ?: return
        sleepAt = Instant.now().plusSeconds(m * 60L)
        sleepJob = app.scope.launch {
            delay(m * 60_000L)
            video.pause()
            sleep = Sleep.Off
            sleepAt = null
        }
    }

    /** Tries the episode again, afresh, from where the viewer got to. */
    fun retry() {
        val id = seriesID ?: return
        var from = start
        val w = app.history.get(id)
        if (w != null && w.episode == number && !w.finished) from = maxOf(from, w.position - 3)
        play(id, number, from, inReels = reelMode)
    }

    /**
     * Pauses, as the viewer leaves the player, unless picture in picture
     * goes on showing it. An episode still on its way won't start.
     */
    fun leave() {
        isPresented = false
        if (inPictureInPicture) return
        video.pause()
        if (state == PlayerState.Resolving) {
            gen++
            pending = false
            state = PlayerState.Idle
        }
    }

    // MARK: - Leaving the app

    /**
     * Whether the episode plays, or is on its way to, rather than paused:
     * what the viewer coming back to the app, or the next launch, goes on with.
     */
    val playing: Boolean
        get() = when {
            away -> playOnReturn
            state == PlayerState.Resolving -> !startPaused
            state == PlayerState.Buffering || state == PlayerState.Playing -> !paused
            else -> false
        }

    /**
     * Pauses the episode as the viewer leaves the app, noting where they got
     * to and whether it played, so that coming back plays it on.
     */
    fun leaveApp() {
        if ((!isPresented && !reelMode) || away) return
        playOnReturn = playing
        away = true
        video.pause()
        progress()
    }

    /** Plays the episode on as the viewer comes back to the app, if it played as they left. */
    fun returnToApp() {
        if (!away) return
        val play = playOnReturn
        away = false
        playOnReturn = false
        if (play && (isPresented || reelMode) && video.hasItem && (state == PlayerState.Buffering || state == PlayerState.Playing)) video.play()
    }

    // MARK: - Double speed while held

    /** Starts a press and hold on the video: double speed until it ends, unless double speed is locked on already. */
    fun beginHold() {
        if (speed == DOUBLE_RATE) { // locked here, or picked from the speed menu
            doubleSpeed = DoubleSpeed.LOCKED
            return
        }
        doubleSpeed = DoubleSpeed.HELD
        baseRate = speed
        video.speed = DOUBLE_RATE
        video.play()
    }

    /**
     * Ends a press and hold. A swipe during it flips the lock: down while
     * held locks double speed on, for the episodes after too; up while
     * locked goes back to the speed before.
     */
    fun endHold(swiped: Boolean) {
        when {
            doubleSpeed == DoubleSpeed.HELD && swiped -> {
                doubleSpeed = DoubleSpeed.LOCKED
                chooseSpeed(DOUBLE_RATE)
            }
            (doubleSpeed == DoubleSpeed.HELD && !swiped) || (doubleSpeed == DoubleSpeed.LOCKED && swiped) -> {
                doubleSpeed = DoubleSpeed.OFF
                chooseSpeed(baseRate)
            }
        }
    }

    // MARK: - What the video does

    private fun timeControlChanged() {
        val status = video.status
        paused = status == TimeControl.PAUSED
        if (away && status != TimeControl.PAUSED) {
            // Played from the notification or a headset: the viewer has it in hand from there.
            away = false
            playOnReturn = false
        }
        tick(status == TimeControl.PLAYING)
        if (episodeID.isEmpty()) return
        val sp = page ?: return
        when (status) {
            TimeControl.PLAYING -> if (state != PlayerState.Playing) {
                state = PlayerState.Playing
                prefetch(sp)
            }
            TimeControl.WAITING -> if (state == PlayerState.Playing) state = PlayerState.Buffering
            TimeControl.PAUSED -> progress()
        }
    }

    /** Records where the viewer is every 3 seconds while the video plays. */
    private fun tick(on: Boolean) {
        if (!on) {
            ticker?.cancel()
            ticker = null
            return
        }
        if (ticker != null) return
        ticker = app.scope.launch {
            while (isActive) {
                delay(3000)
                progress()
            }
        }
    }

    private fun itemFailed(message: String) {
        if (episodeID.isEmpty()) return
        debugLog { "player: $message" }
        state = PlayerState.Failed(message)
        futures.remove(episodeID)
    }

    /** Resolves the next episode while this one plays. */
    private fun prefetch(sp: SeriesPage) {
        val next = sp.episode(number + 1)
        if (next != null && next.playable) {
            resolve(next)
        } else {
            sp.nextSeason()?.let { app.seriesPage(it.seriesId) } // its episodes, ready to go on
        }
    }

    private fun progress() {
        if (episodeID.isEmpty() || !video.hasItem) return
        val sp = page ?: return
        val t = video.position
        if (!t.isFinite() || t <= 0) return
        val d = video.duration
        record(sp, number, t, if (d.isFinite()) d else 0.0, finished = false)
    }

    /** On to the next episode, or the next season's first, unless the sleep timer stops here. */
    internal fun ended() {
        val sp = page ?: return
        episodeID = "" // the pause that follows the end is no progress
        record(sp, number, 0.0, 0.0, finished = true)
        if (sleep == Sleep.EndOfEpisode) {
            chooseSleep(Sleep.Off)
            return
        }
        app.following(sp, number)?.let { play(it.first, it.second) }
    }

    private fun record(sp: SeriesPage, n: Int, position: Double, duration: Double, finished: Boolean) {
        val s = sp.series ?: return
        app.history.record(
            Watch(
                seriesID = sp.id, title = s.title, cover = s.coverUrl, episode = n, position = position,
                duration = duration, total = sp.episodes.size, finished = finished,
                watched = if (watchedEnough(position, duration, finished)) listOf(n) else emptyList(),
            ),
        )
    }
}

/**
 * The stream of a model to play: of the definitions up to q, the best, or
 * the lowest if all are above it; the best of all when q is null. Of
 * streams the same, the richest.
 */
fun pickStream(streams: List<Stream>, near: Int? = null): Stream? {
    val ok = playable(streams)
    val order = compareBy<Stream>({ lines(it) }, { it.bitrate })
    if (near == null) return ok.maxWithOrNull(order)
    return ok.filter { lines(it) <= near }.maxWithOrNull(order) ?: ok.minByOrNull { lines(it) }
}

/** The streams with somewhere to fetch them from. */
fun playable(streams: List<Stream>): List<Stream> = streams.filter { it.url.isNotBlank() }

/**
 * A stream's definition, as lines: 720 for "720p", a picture 720 wide and
 * 1280 tall included; from the short side of its picture when the label
 * doesn't say.
 */
fun lines(s: Stream): Int {
    val digits = s.definition.takeWhile { it in '0'..'9' }.toIntOrNull() ?: 0
    if (digits > 0) return digits
    val short = minOf(s.width, s.height)
    return if (short > 0) short else maxOf(s.width, s.height)
}

/**
 * Whether an episode counts as watched: played to its end, or nine tenths
 * of the way, since viewers often skip its last seconds for the next.
 */
fun watchedEnough(position: Double, duration: Double, finished: Boolean): Boolean =
    finished || (duration > 0 && position >= duration * 0.9)

/** Whether the model's CDN URLs expire within a minute. */
fun expired(pb: Playback): Boolean =
    pb.expiresAt > 0 && pb.expiresAt * 1000 < System.currentTimeMillis() + 60_000

fun hexBytes(s: String): ByteArray {
    val t = s.trim()
    if (t.length % 2 != 0) return ByteArray(0)
    return ByteArray(t.length / 2) { i ->
        val hi = Character.digit(t[2 * i], 16)
        val lo = Character.digit(t[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return ByteArray(0)
        (hi * 16 + lo).toByte()
    }
}
