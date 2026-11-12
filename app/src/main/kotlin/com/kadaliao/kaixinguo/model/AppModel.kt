package com.kadaliao.kaixinguo.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.openhongguo.DiscoverOptions
import dev.openhongguo.EpisodeOptions
import dev.openhongguo.ErrorCode
import dev.openhongguo.HotSearch
import dev.openhongguo.NewReleaseQuery
import dev.openhongguo.Page
import dev.openhongguo.RankBoard
import dev.openhongguo.RankContent
import dev.openhongguo.SearchOptions
import dev.openhongguo.Series
import dev.openhongguo.SeriesSummary
import dev.openhongguo.ShortRankQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

/**
 * AppModel is the state the app shows. It belongs to the main thread: its
 * scope runs there, and work elsewhere hands its results back to it.
 */
class AppModel(
    val catalog: Catalog,
    dataDir: File,
    val cacheDir: File,
    val scope: CoroutineScope,
    video: Video,
    streams: Streams,
) {
    val history = History(dataDir)
    val favorites = Favorites(dataDir)

    /** The cover's JPEG, when the app has it, for what the lock screen shows. */
    var artwork: (String) -> ByteArray? = { null }

    // The catalog.
    val home = Feed(scope, discover(catalog))
    private val feeds = HashMap<String, Feed>()
    var rankContents by mutableStateOf(emptyList<RankContent>())
        private set

    /** The chart content chosen; empty for the one the server chose. */
    var rankContent by mutableStateOf("")

    /** The board chosen; empty for the one the server chose. */
    var rankBoard by mutableStateOf("")

    /** The day of new releases chosen; empty for today. */
    var newDate by mutableStateOf("")
    var newDates by mutableStateOf(emptyList<String>())
        private set
    var hot by mutableStateOf<HotSearch?>(null)
        private set
    var hotLoading by mutableStateOf(false)
        private set
    var hotError by mutableStateOf<Throwable?>(null)
        private set

    // Series.
    private val series = HashMap<String, SeriesPage>()

    val player = Player(this, video, streams)

    private val sessions = sessionFile(dataDir)

    /** Where the viewer was as the last launch left the app. */
    val lastSession: Session = sessions.load() ?: Session()

    /** The episode the last launch left the player on, until the launch's first window reopens it. */
    private var lastPlayer by mutableStateOf(lastSession.player)

    init {
        // Last launch's recommendations show at once; fresh ones join them.
        val cached = loadHomeCache()
        if (cached.isNotEmpty()) {
            home.add(FeedPage(cached, more = true))
            home.stale = true
        }
        home.onPage = { saveHomeCache(home.cards) }

        val s = lastSession
        rankContent = s.rankContent
        rankBoard = s.rankBoard
        if (s.day == todayKey()) newDate = s.newDate
        player.chooseSpeed(s.speed)
        s.quality?.let(player::chooseQuality)
    }

    /** Writes what is waiting to be saved, as the app leaves the screen. */
    fun flush() {
        history.flush()
        favorites.flush()
        sessions.flush()
    }

    // MARK: - The session

    /**
     * The model's part of where the viewer is: the chart and day chosen, the
     * speed and definition, and the episode the player shows. The router
     * adds the tab and pages before it is saved.
     */
    fun session(): Session {
        val p = player
        val id = p.seriesID
        val showing = if (p.isPresented && id != null) SessionPlayer(id, p.number, p.playing) else null
        return Session(
            rankContent = rankContent,
            rankBoard = rankBoard,
            newDate = newDate,
            day = todayKey(),
            speed = p.speed,
            quality = p.preferredQuality,
            player = showing ?: lastPlayer, // not reopened yet, it stays for the window to reopen
        )
    }

    fun saveSession(s: Session) = sessions.save(s)

    /**
     * Opens the player where the last launch left it, playing if it played,
     * from where the viewer got to. The launch's first window asks, and only
     * it: a later one finds nothing to reopen. A window opened by a link
     * passes false, to go where the link says instead.
     */
    fun reopenPlayer(open: Boolean = true) {
        val p = lastPlayer ?: return
        lastPlayer = null
        if (!open) return
        val w = history.get(p.seriesID)
        val start = if (w != null && w.episode == p.episode && !w.finished) maxOf(0.0, w.position - 3) else 0.0
        player.play(p.seriesID, p.episode, start, paused = !p.playing)
    }

    /** The feed of key, made with make the first time. */
    private fun feed(key: String, make: () -> Feed): Feed = feeds.getOrPut(key, make)

    // MARK: - 排行榜

    /** Contents with charts of series; the actors' chart has none. */
    val chartContents: List<RankContent>
        get() = rankContents.filter { it.id.isNotEmpty() && it.boards.isNotEmpty() && !it.name.contains("演员") }

    /** The content whose charts show: the one chosen, else the one the server chose. */
    val currentRankContent: String
        get() {
            val contents = chartContents
            if (rankContent.isNotEmpty()) return rankContent
            return contents.firstOrNull { it.selected }?.id ?: contents.firstOrNull()?.id ?: ""
        }

    /** The boards of the content showing. */
    val rankBoards: List<RankBoard>
        get() {
            val cur = currentRankContent
            return chartContents.firstOrNull { it.id == cur }?.boards ?: emptyList()
        }

    /** Whether the board shows: the one chosen, else the server's choice. */
    fun boardIsOn(b: RankBoard): Boolean = b.id == rankBoard || (rankBoard.isEmpty() && b.selected)

    val rankFeed: Feed
        get() {
            val content = rankContent
            val board = rankBoard
            return feed("rank:$content:$board") {
                Feed(scope) { cursor ->
                    val r = catalog.shortRank(ShortRankQuery(content = content, board = board, cursor = cursor))
                    FeedPage(cursor = r.cursor, more = r.hasMore).apply {
                        contents = r.contents
                        this.board = r.board
                        cards = r.items.filter { it.seriesId.isNotEmpty() }.map { // the others are actors' rows
                            Card(
                                it.seriesId, it.title, it.coverUrl,
                                sub = it.score.ifEmpty { episodeCount(it.episodeCount) },
                                rank = it.position, episodes = it.episodeCount,
                            )
                        }
                    }
                }.apply {
                    onPage = { p -> if (p.contents.isNotEmpty()) rankContents = p.contents }
                }
            }
        }

    // MARK: - 新剧上线

    val newFeed: Feed
        get() {
            val date = newDate
            return feed("new:$date") {
                Feed(scope) { cursor ->
                    val r = catalog.newReleases(NewReleaseQuery(date = date, cursor = cursor))
                    FeedPage(cursor = r.cursor, more = r.hasMore).apply {
                        dates = r.dates
                        cards = r.items.map {
                            val sub = if (!it.online && it.note.isNotEmpty()) it.note else it.heat
                            Card(it.seriesId, it.title, it.coverUrl, sub, episodes = it.episodeCount)
                        }
                    }
                }.apply {
                    onPage = { p -> if (p.dates.isNotEmpty()) newDates = p.dates }
                }
            }
        }

    /** Whether the day shows: the one chosen, else today. */
    fun dayIsOn(d: String): Boolean = d == newDate || (newDate.isEmpty() && d == todayKey())

    fun chooseDay(d: String) {
        newDate = if (d == todayKey()) "" else d
    }

    // MARK: - 搜索

    fun searchFeed(query: String): Feed {
        val q = query.trim()
        return feed("search:$q") {
            Feed(scope) { cursor -> search(catalog, q, cursor) }.apply { order = ::orderSeasons }
        }
    }

    /**
     * Words people complete the prefix to, as they type a search. They are
     * the suggestions' text: a suggestion that leads straight to a series
     * has its ID for a keyword, and not every such ID is a series the app
     * can open, so the text is what gets searched.
     */
    suspend fun suggestions(prefix: String): List<String> {
        val p = prefix.trim()
        if (p.isEmpty()) return emptyList()
        val list = attempt { catalog.suggest(p) }.getOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        return list.map { it.text.trim() }.filter { it.isNotEmpty() && seen.add(it) }
    }

    // MARK: - 热搜

    /** Loads what people search for, unless it is here or on its way. */
    fun loadHot(force: Boolean = false) {
        if (hotLoading || !(force || (hot == null && hotError == null))) return
        scope.launch { reloadHot() }
    }

    /** Loads what people search for again, and returns once it has. */
    suspend fun reloadHot() {
        if (hotLoading) return
        hotLoading = true
        hotError = null
        attempt { catalog.hotSearch() }
            .onSuccess { hot = it }
            .onFailure { if (hot == null) hotError = it }
        hotLoading = false
    }

    // MARK: - The home cache

    /** The first page of recommendations, so the next launch shows them at once. */
    internal val homeCacheFile: File get() = File(cacheDir, "home.json")

    internal fun loadHomeCache(): List<Card> = try {
        val a = JSONArray(homeCacheFile.readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Card::fromJson) }
    } catch (e: Exception) {
        emptyList()
    }

    internal fun writeHomeCache(cards: List<Card>) {
        try {
            val a = JSONArray()
            cards.take(48).forEach { a.put(it.toJson()) }
            cacheDir.mkdirs()
            val tmp = File(cacheDir, "home.json.tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(homeCacheFile)
        } catch (e: Exception) {
            debugLog { "home cache: $e" }
        }
    }

    private fun saveHomeCache(cards: List<Card>) {
        val snapshot = cards.take(48)
        scope.launch(Dispatchers.IO) { writeHomeCache(snapshot) }
    }

    // MARK: - Series

    /** The series' page, loading it unless it is here or on its way. */
    fun seriesPage(id: String): SeriesPage {
        val sp = seriesEntry(id)
        if (sp.series == null && !sp.loading && sp.error == null) loadSeries(sp)
        return sp
    }

    /** The series' page, without loading it. */
    fun seriesEntry(id: String): SeriesPage = series.getOrPut(id) { SeriesPage(id) }

    fun loadSeries(sp: SeriesPage) {
        sp.loading = true
        sp.error = null
        val id = sp.id
        scope.launch {
            val s = launchAttempt { catalog.getSeries(id) }
            val e = launchAttempt { catalog.listEpisodes(id, EpisodeOptions()).items }
            val series = s.await()
            val episodes = e.await()
            sp.loading = false
            val seriesError = series.exceptionOrNull()
            val episodesError = episodes.exceptionOrNull()
            when {
                seriesError != null -> sp.error = seriesError
                episodesError != null && !isCode(episodesError, ErrorCode.ANONYMOUS_LIMITED) -> sp.error = episodesError
                else -> {
                    val got = series.getOrThrow()
                    sp.series = got
                    sp.episodes = episodes.getOrDefault(emptyList())
                    if (got.isMultiSeason && sp.seasons == null) loadSeasons(sp)
                }
            }
            player.fillPending(sp) // starts the episode, or says why it can't
        }
    }

    private fun <T> CoroutineScope.launchAttempt(block: suspend () -> T) = async { attempt { block() } }

    /** Lists the seasons of a series in several, and gives the list to the page of each. */
    private fun loadSeasons(sp: SeriesPage) {
        if (sp.seasonsLoading) return
        sp.seasonsLoading = true
        val id = sp.id
        scope.launch {
            val album = attempt { catalog.seasons(id) }.getOrNull()
            sp.seasonsLoading = false
            if (album == null || album.seasons.size < 2) {
                sp.seasons = emptyList() // one season: nothing to choose
                return@launch
            }
            val seasons = numberSeasons(album.seasons)
            for (se in seasons) seriesEntry(se.seriesId).seasons = seasons
            sp.seasons = seasons
        }
    }

    /** The episode after episode n: the next of the season, else the first of the next season. */
    fun following(sp: SeriesPage, n: Int): Pair<String, Int>? {
        sp.episode(n + 1)?.let { next -> return if (next.playable) sp.id to next.number else null }
        sp.nextSeason()?.let { ns -> return ns.seriesId to 1 }
        return null
    }

    /** Where to go on from, and what the button says. */
    data class Resume(val seriesID: String, val number: Int, val start: Double, val label: String)

    fun resumePoint(sp: SeriesPage): Resume {
        val w = history.get(sp.id)
        if (w == null || sp.episodes.isEmpty()) return Resume(sp.id, 1, 0.0, "播放第 1 集")
        if (w.finished) {
            if (sp.episode(w.episode + 1) != null) return Resume(sp.id, w.episode + 1, 0.0, "播放第 ${w.episode + 1} 集")
            sp.nextSeason()?.let { ns -> return Resume(ns.seriesId, 1, 0.0, "播放" + seasonLabel(ns)) }
            return Resume(sp.id, 1, 0.0, "从头再看")
        }
        return Resume(sp.id, w.episode, maxOf(0.0, w.position - 3), "继续看第 ${w.episode} 集")
    }

    /** Plays a season from where the viewer left it, else from its first episode. */
    fun playSeason(id: String) {
        val sp = series[id]
        if (sp?.series != null) {
            val r = resumePoint(sp)
            player.play(r.seriesID, r.number, r.start)
            return
        }
        val w = history.get(id)
        if (w != null && !w.finished) {
            player.play(id, w.episode, maxOf(0.0, w.position - 3))
            return
        }
        player.play(id, 1)
    }

    /** Plays the series from where the viewer left it, as its button does. */
    fun resume(sp: SeriesPage) {
        val r = resumePoint(sp)
        player.play(r.seriesID, r.number, r.start)
    }

    companion object {
        /**
         * Reads the recommended channel one server page at a time, so each
         * shows as soon as it comes; the grid asks for the next as it nears
         * its end.
         */
        internal fun discover(catalog: Catalog): suspend (String) -> FeedPage = { cursor ->
            val out = FeedPage(cursor = cursor)
            for (i in 0 until 3) { // past pages that bring nothing
                val p = catalog.discover(DiscoverOptions(cursor = out.cursor))
                out.cards = out.cards + p.items.map(Card::of)
                out.cursor = p.cursor
                out.more = p.hasMore
                if (out.cards.isNotEmpty() || !p.hasMore) break
            }
            out
        }

        internal suspend fun search(catalog: Catalog, q: String, cursor: String): FeedPage {
            val limited = { e: Throwable -> isCode(e, ErrorCode.ANONYMOUS_LIMITED, ErrorCode.END_OF_LIST) }
            // The 剧集 tab lists each season of a series; 综合 shows its first
            // season alone, among clips.
            val first = attempt { catalog.search(q, SearchOptions(cursor = cursor, pageSize = 20, tab = "剧集")) }
            val r: Page<SeriesSummary> = first.getOrElse { e ->
                if (limited(e)) return FeedPage() // nothing found
                attempt { catalog.search(q, SearchOptions(cursor = cursor, pageSize = 20)) }.getOrElse { e2 ->
                    if (limited(e2)) return FeedPage()
                    throw e2
                }
            }
            val out = FeedPage(r.items.map(Card::of), r.cursor, r.hasMore && r.items.isNotEmpty())
            // Search results come without covers: read them from the details.
            val missing = r.items.filter { it.coverUrl.isEmpty() }.map { it.id }
            if (missing.isNotEmpty()) {
                val details = attempt { catalog.getSeriesBatch(missing) }.getOrNull()
                if (details != null) {
                    val byID = HashMap<String, Series>()
                    for (d in details) d.series?.let { byID[it.id] = it }
                    out.cards = out.cards.map { k ->
                        val s = byID[k.id] ?: return@map k
                        if (s.episodeCount > 0) {
                            k.copy(cover = s.coverUrl, sub = episodesLabel(s), episodes = s.episodeCount)
                        } else {
                            k.copy(cover = s.coverUrl)
                        }
                    }
                }
            }
            return out
        }
    }
}
