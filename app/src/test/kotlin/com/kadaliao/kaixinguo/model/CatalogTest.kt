package com.kadaliao.kaixinguo.model

import dev.openhongguo.Album
import dev.openhongguo.ErrorCode
import dev.openhongguo.OpenhongguoException
import dev.openhongguo.Page
import dev.openhongguo.Playback
import dev.openhongguo.PlaybackResult
import dev.openhongguo.PlatformType
import dev.openhongguo.Season
import dev.openhongguo.Series
import dev.openhongguo.SeriesStatus
import dev.openhongguo.SeriesSummary
import dev.openhongguo.Stream
import dev.openhongguo.Suggestion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogTest {
    @Test
    fun chineseNumbers() {
        val cases = mapOf("一" to 1, "三" to 3, "十" to 10, "十一" to 11, "二十" to 20, "二十三" to 23, "两" to 2, "12" to 12, "一百零五" to 105)
        for ((s, want) in cases) assertEquals(s, want, chineseNumber(s))
    }

    /**
     * Search lists the seasons of a series in their order, where the first of
     * them came, and leaves the rest alone; another series of the same name
     * without the colon keeps to itself.
     */
    @Test
    fun ordersSeasonsInSearch() {
        val titles = listOf("持械入宋：第一季", "持械入宋：第十季", "一品布衣", "持械入宋：第二季", "持械入宋第五季", "持械入宋：第十一季", "持械入宋第三季")
        val cards = titles.mapIndexed { i, t -> Card("$i", t) }
        assertEquals(
            "持械入宋：第一季,持械入宋：第二季,持械入宋：第十季,持械入宋：第十一季,一品布衣,持械入宋第三季,持械入宋第五季",
            orderSeasons(cards).joinToString(",") { it.title },
        )
    }

    /**
     * Seasons the server sends without numbers take them from their titles,
     * the first season being the one title without a number; seasons with no
     * telling keep their order.
     */
    @Test
    fun numbersSeasons() {
        val cases = listOf(
            listOf(Season(title = "b", index = 2), Season(title = "a", index = 1)) to "1:a,2:b",
            listOf(Season(title = "持械入宋：第十一季"), Season(title = "持械入宋：第一季"), Season(title = "持械入宋：第二季")) to
                "1:持械入宋：第一季,2:持械入宋：第二季,11:持械入宋：第十一季",
            listOf(Season(title = "浅浅归聿第三季"), Season(title = "浅浅归聿"), Season(title = "浅浅归聿第二季")) to
                "1:浅浅归聿,2:浅浅归聿第二季,3:浅浅归聿第三季",
            listOf(Season(title = "甲"), Season(title = "乙"), Season(title = "丙")) to "1:甲,2:乙,3:丙",
        )
        for ((input, want) in cases) assertEquals(want, numberSeasons(input).joinToString(",") { "${it.index}:${it.title}" })
    }

    @Test
    fun labels() {
        assertEquals("4165万", heat(41_658_011))
        assertEquals("1.2亿", heat(120_000_000))
        assertEquals("1亿", heat(100_000_000))
        assertEquals("9999", heat(9_999))
        assertEquals("", episodeCount(0))
        assertEquals("80 集", episodeCount(80))
        assertEquals("今天", dayLabel("20261105", today = "20261105"))
        assertEquals("明天", dayLabel("20261106", today = "20261105"))
        assertEquals("昨天", dayLabel("20261104", today = "20261105"))
        assertEquals("12月1日", dayLabel("20261201", today = "20261105"))
        val s = Series(id = "1", title = "浅浅归聿", episodeCount = 130, status = SeriesStatus.END, heat = 41_658_011)
        assertEquals("全 130 集  ·  热度 4165万", metaLine(s))
        assertEquals("更新至 12 集", episodesLabel(Series(episodeCount = 12, status = SeriesStatus.UPDATING)))
    }

    /** The best stream plays, unless the viewer picked a lower definition: then the best up to it, or the lowest there is. */
    @Test
    fun picksStream() {
        val streams = listOf(
            Stream(definition = "720p", height = 720, bitrate = 900, url = "a"),
            Stream(definition = "1080p", height = 1080, bitrate = 1200, url = "b"),
            Stream(definition = "1080p", height = 1080, bitrate = 2400, url = ""), // no URL
            Stream(definition = "540p", bitrate = 500, url = "c"),
        )
        assertEquals("b", pickStream(streams)?.url)
        assertNull(pickStream(emptyList()))
        assertEquals("a", pickStream(streams, near = 720)?.url)
        assertEquals("c", pickStream(streams, near = 600)?.url)
        assertEquals("c", pickStream(streams, near = 360)?.url)
        assertEquals("b", pickStream(streams, near = 2160)?.url)

        // A picture on its end counts by its short side.
        val upright = listOf(Stream(width = 720, height = 1280, url = "d"), Stream(width = 1080, height = 1920, url = "e"))
        assertEquals(listOf(720, 1080), upright.map(::lines))
        assertEquals("d", pickStream(upright, near = 720)?.url)
    }

    @Test
    fun hexKeys() {
        assertEquals(16, hexBytes("00112233445566778899aabbccddeeff").size)
        assertEquals(0xff.toByte(), hexBytes("00112233445566778899aabbccddeeff")[15])
        assertEquals(0, hexBytes("abc").size)
        assertEquals(0, hexBytes("zz").size)
    }

    /** A search page with nothing new ends the feed, rather than the grid asking for the same page forever. */
    @Test
    fun repeatedPageEndsFeed() = runTest {
        val f = Feed(backgroundScope, null)
        f.add(FeedPage(listOf(Card("a", "A"), Card("b", "B")), "20", more = true))
        f.add(FeedPage(listOf(Card("b", "B"), Card("a", "A")), "40", more = true))
        assertFalse(f.more)
        assertEquals(2, f.cards.size)
    }

    /** Cached recommendations show at once, and the first fresh page goes before them, so the grid never shrinks. */
    @Test
    fun freshPageJoinsCache() = runTest {
        val catalog = FakeCatalog()
        val model = testModel(backgroundScope, catalog)
        model.writeHomeCache(listOf(Card("old1", "旧一"), Card("x", "旧版 X")))
        catalog.discoverPages[""] = Page(
            items = listOf(SeriesSummary(id = "x", title = "X", episodeCount = 30), SeriesSummary(id = "new", title = "新")),
            cursor = "c1",
            hasMore = true,
        )
        val again = testModel(backgroundScope, catalog, cache = model.cacheDir)
        assertEquals(listOf("old1", "x"), again.home.cards.map { it.id })
        assertTrue(again.home.stale)
        again.home.loadMore()
        runCurrent()
        assertEquals(listOf("x", "new", "old1"), again.home.cards.map { it.id })
        assertEquals("30 集", again.home.cards[0].sub)
    }

    /** The recommendations skip server pages that bring nothing. */
    @Test
    fun discoverSkipsEmptyPages() = runTest {
        val catalog = FakeCatalog()
        val model = testModel(backgroundScope, catalog)
        catalog.discoverPages[""] = Page(items = emptyList(), cursor = "1", hasMore = true)
        catalog.discoverPages["1"] = Page(items = listOf(SeriesSummary(id = "a", title = "A")), cursor = "2", hasMore = true)
        model.home.loadMore()
        runCurrent()
        assertEquals(listOf("a"), model.home.cards.map { it.id })
        assertEquals(listOf("discover:", "discover:1"), catalog.calls)
    }

    /** A feed that fails tries again by itself while it has nothing to show. */
    @Test
    fun feedRetriesByItself() = runTest {
        var attempts = 0
        val f = Feed(backgroundScope) {
            attempts++
            if (attempts == 1) throw IOException("timed out")
            FeedPage(listOf(Card("a", "A")))
        }
        f.loadMore()
        runCurrent()
        assertNotNull(f.error)
        assertTrue(f.retrying)
        assertEquals("网络连接不稳定，请稍后重试", friendly(f.error))
        advanceTimeBy(2100) // the first retry waits two seconds
        runCurrent()
        assertEquals(listOf("a"), f.cards.map { it.id })
        assertNull(f.error)
        assertFalse(f.retrying)
    }

    /** Search reads the 剧集 tab, falls back to 综合, and fills in the covers the results come without. */
    @Test
    fun searchFallsBackAndFillsCovers() = runTest {
        val catalog = FakeCatalog()
        catalog.search = { _, o ->
            if (o.tab == "剧集") throw OpenhongguoException(ErrorCode.PARAM_INVALID, "bad tab")
            Page(
                items = listOf(SeriesSummary(id = "1", title = "持械入宋：第二季"), SeriesSummary(id = "2", title = "持械入宋：第一季")),
                cursor = "20",
                hasMore = true,
            )
        }
        catalog.batch["1"] = Series(id = "1", coverUrl = "https://c/1.heic", episodeCount = 80, status = SeriesStatus.END)
        val page = AppModel.search(catalog, "持械入宋", "")
        assertEquals(listOf("1", "2"), page.cards.map { it.id })
        assertEquals("https://c/1.heic", page.cards[0].cover)
        assertEquals("全 80 集", page.cards[0].sub)
        assertEquals(listOf("search:持械入宋:剧集", "search:持械入宋:"), catalog.calls)

        // The feed puts the seasons in order.
        val model = testModel(backgroundScope, catalog)
        val f = model.searchFeed(" 持械入宋 ")
        f.loadMore()
        runCurrent()
        assertEquals(listOf("2", "1"), f.cards.map { it.id })
    }

    /** What anonymous search can't find is an empty result, not an error. */
    @Test
    fun searchFindsNothing() = runTest {
        val catalog = FakeCatalog()
        catalog.search = { _, _ -> throw OpenhongguoException(ErrorCode.ANONYMOUS_LIMITED, "") }
        assertTrue(AppModel.search(catalog, "无", "").cards.isEmpty())
    }

    /** Suggestions show their text, not the series ID a suggestion that leads straight to a series carries as its keyword. */
    @Test
    fun suggestionsAreText() = runTest {
        val catalog = FakeCatalog()
        val model = testModel(backgroundScope, catalog)
        catalog.suggestions = listOf(
            Suggestion(text = "持械入宋：第一季", keyword = "7671966197985856536", kind = 2),
            Suggestion(text = "总裁系列", keyword = "总裁系列", kind = 1),
            Suggestion(text = " 持械入宋：第一季 ", keyword = "1", kind = 2),
            Suggestion(text = "", keyword = "7666404568275635224", kind = 2),
        )
        assertEquals(listOf("持械入宋：第一季", "总裁系列"), model.suggestions(" 持械 "))
        assertTrue(model.suggestions("  ").isEmpty())
    }

    /** A series' page loads its episodes, and its seasons, which every season's page then shares. */
    @Test
    fun seriesAndSeasons() = runTest {
        val catalog = FakeCatalog()
        val model = testModel(backgroundScope, catalog)
        catalog.series["1"] = Series(id = "1", title = "浅浅归聿", episodeCount = 130, platform = PlatformType.OUTER, isMultiSeason = true)
        catalog.episodes["1"] = episodes("1", 130, locked = setOf(3))
        catalog.albums["1"] = Album(
            seasons = listOf(
                Season(seriesId = "s2", title = "浅浅归聿第二季", episodeCount = 80),
                Season(seriesId = "1", title = "浅浅归聿", episodeCount = 130),
                Season(seriesId = "s3", title = "浅浅归聿第三季"),
            ),
        )
        val sp = model.seriesPage("1")
        runCurrent()
        assertEquals(130, sp.episodes.size)
        assertEquals(listOf("1–100", "101–130"), sp.parts)
        assertEquals(100, sp.partEpisodes.size)
        sp.part = 1
        assertEquals(101, sp.partEpisodes.first().number)
        assertEquals(listOf("1", "s2", "s3"), sp.seasons?.map { it.seriesId })
        assertEquals(3, model.seriesEntry("s2").seasons?.size)

        // After the last episode of a season comes the next season's first.
        assertEquals("1" to 6, model.following(sp, 5))
        assertNull(model.following(sp, 2)) // the third needs a login
        assertEquals("s2" to 1, model.following(sp, 130))

        // The series offers to go on where the viewer was.
        assertEquals("播放第 1 集", model.resumePoint(sp).label)
        model.history.record(Watch(seriesID = "1", title = "浅浅归聿", episode = 7, position = 42.0))
        val r = model.resumePoint(sp)
        assertEquals("继续看第 7 集", r.label)
        assertEquals(39.0, r.start, 0.0)
        model.history.record(Watch(seriesID = "1", episode = 130, finished = true))
        assertEquals("播放第 2 季", model.resumePoint(sp).label)
        assertEquals("s2", model.resumePoint(sp).seriesID)
    }

    /** A series that is gone says so. */
    @Test
    fun seriesGone() = runTest {
        val model = testModel(backgroundScope)
        val sp = model.seriesPage("missing")
        runCurrent()
        assertNull(sp.series)
        assertEquals("内容已下架或不存在", friendly(sp.error))
    }

    /** The player waits for the series, then tries the episode; a locked episode, or one the server won't give, says why. */
    @Test
    fun playerStates() = runTest {
        val catalog = FakeCatalog()
        val model = testModel(backgroundScope, catalog)
        catalog.series["1"] = Series(id = "1", title = "浅浅归聿", episodeCount = 3, platform = PlatformType.OUTER)
        catalog.episodes["1"] = episodes("1", 3, locked = setOf(3))
        val player = model.player
        player.play("1", 3)
        assertEquals(PlayerState.Resolving, player.state)
        assertTrue(player.isPresented)
        runCurrent()
        assertEquals(PlayerState.Failed("这一集需要登录才能观看"), player.state)

        player.play("1", 1)
        runCurrent()
        assertEquals(PlayerState.Failed("这一集暂时无法匿名播放，可以稍后再试"), player.state)
        assertEquals(1, model.history.get("1")?.episode)
        assertTrue(player.hasNext)
        assertFalse(player.hasPrevious)

        player.leave()
        assertFalse(player.isPresented)
    }

    /** A reel stays inside its tab when it starts, advances, retries, and leaves. */
    @Test
    fun reelsUseTheSharedPlayerWithoutOpeningTheFullScreenOverlay() = runTest {
        val catalog = FakeCatalog()
        val model = testModel(backgroundScope, catalog)
        catalog.series["1"] = Series(id = "1", title = "短剧", episodeCount = 3, platform = PlatformType.OUTER)
        catalog.episodes["1"] = episodes("1", 3)
        val player = model.player

        player.playReel("1", 1)
        assertTrue(player.reelMode)
        assertFalse(player.isPresented)
        runCurrent()
        player.ended()
        assertEquals(2, player.number)
        assertTrue(player.reelMode)
        assertFalse(player.isPresented)

        player.retry()
        assertTrue(player.reelMode)
        assertFalse(player.isPresented)
        player.stopReel()
        assertFalse(player.reelMode)
        assertFalse(player.isPresented)
    }

    /**
     * Holding the video plays it at double speed until the finger lifts; a
     * swipe down locks it on, and a later hold that swipes up goes back to the
     * speed the viewer chose.
     */
    @Test
    fun doubleSpeedWhileHeld() = runTest {
        val video = FakeVideo()
        val p = testModel(backgroundScope, video = video).player
        video.play() // as if playing
        p.chooseSpeed(1.5f)
        assertEquals(1.5f, video.speed)

        p.beginHold()
        assertEquals(DoubleSpeed.HELD, p.doubleSpeed)
        assertEquals(2f, video.speed)
        assertEquals(1.5f, p.speed)
        p.endHold(swiped = false)
        assertEquals(DoubleSpeed.OFF, p.doubleSpeed)
        assertEquals(1.5f, video.speed)

        p.beginHold()
        p.endHold(swiped = true)
        assertEquals(DoubleSpeed.LOCKED, p.doubleSpeed)
        assertEquals(2f, p.speed)
        assertEquals(2f, video.speed) // what the next episode plays at

        // Holding again only shows it; a swipe the wrong way changes nothing.
        p.beginHold()
        assertEquals(DoubleSpeed.LOCKED, p.doubleSpeed)
        p.endHold(swiped = false)
        assertEquals(DoubleSpeed.LOCKED, p.doubleSpeed)
        assertEquals(2f, video.speed)

        video.pause()
        p.beginHold()
        p.endHold(swiped = true)
        assertEquals(DoubleSpeed.OFF, p.doubleSpeed)
        assertEquals(1.5f, p.speed)
        assertEquals(1.5f, video.speed)
        assertFalse(video.wantsToPlay) // still paused

        // 2× picked from the speed menu counts as locked.
        p.chooseSpeed(2f)
        assertFalse(video.wantsToPlay)
        p.beginHold()
        assertEquals(DoubleSpeed.LOCKED, p.doubleSpeed)
    }

    /** The sleep timer pauses the video when its minutes are up, or stops it going on to the next episode. */
    @Test
    fun sleepTimer() = runTest {
        val catalog = FakeCatalog()
        val video = FakeVideo()
        val model = testModel(backgroundScope, catalog, video)
        catalog.series["1"] = Series(id = "1", title = "浅浅归聿", episodeCount = 3, platform = PlatformType.OUTER)
        catalog.episodes["1"] = episodes("1", 3)
        val p = model.player
        p.chooseSleep(Sleep.Minutes(30))
        assertNotNull(p.sleepAt)
        p.chooseSleep(Sleep.Off)
        assertNull(p.sleepAt)

        // Minutes up: the video pauses.
        video.play()
        p.chooseSleep(Sleep.Minutes(15))
        advanceTimeBy(15 * 60_000L + 1)
        assertFalse(video.wantsToPlay)
        assertEquals(Sleep.Off, p.sleep)

        p.play("1", 1)
        runCurrent()
        p.chooseSleep(Sleep.EndOfEpisode)
        p.ended()
        assertEquals(1, p.number) // no next episode
        assertEquals(Sleep.Off, p.sleep)
        assertEquals(true, model.history.get("1")?.finished)
        p.ended()
        assertEquals(2, p.number) // the timer is spent
    }

    /** A player waiting for a series that fails to load says why, rather than spinning forever. */
    @Test
    fun playerSaysWhySeriesFailed() = runTest {
        val model = testModel(backgroundScope)
        model.player.play("missing", 1)
        runCurrent()
        assertEquals(PlayerState.Failed("内容已下架或不存在"), model.player.state)
    }

    /** Leaving the player while an episode is on its way keeps it from starting behind the viewer's back. */
    @Test
    fun leavingCancelsTheStart() = runTest {
        val catalog = FakeCatalog()
        val video = FakeVideo()
        val model = testModel(backgroundScope, catalog, video)
        catalog.series["1"] = Series(id = "1", title = "甲", episodeCount = 2, platform = PlatformType.OUTER)
        catalog.episodes["1"] = episodes("1", 2)
        val sp = model.seriesPage("1")
        runCurrent()
        assertNotNull(sp.series)
        model.player.play("1", 1)
        model.player.leave()
        assertEquals(PlayerState.Idle, model.player.state)
        runCurrent()
        assertEquals(PlayerState.Idle, model.player.state) // not failed: the resolve's answer went unheard
        assertFalse(video.hasItem)
    }

    /**
     * Whether the picture is wider than tall comes from the stream at first,
     * then from the video, which knows better; another series starts afresh.
     */
    @Test
    fun widePicture() = runTest {
        val catalog = FakeCatalog()
        val video = FakeVideo()
        val p = testModel(backgroundScope, catalog, video).player
        catalog.playable("1", 2)
        catalog.playable("2", 1)
        catalog.playbacks["1e1"] = PlaybackResult(
            playback = Playback(episodeId = "1e1", streams = listOf(Stream(definition = "720p", width = 1280, height = 720, url = "https://cdn/1e1.mp4"))),
        )
        p.play("1", 1)
        runCurrent()
        assertTrue(p.wide)
        video.listener?.aspectChanged(9f / 16f)
        assertFalse(p.wide)
        video.listener?.aspectChanged(16f / 9f)
        assertTrue(p.wide)

        p.play("1", 2) // its stream doesn't say: the series is as it was
        runCurrent()
        assertTrue(p.wide)
        p.play("2", 1)
        assertFalse(p.wide)
    }

    /** Pulling the recommendations down puts the fresh ones first and goes on from where the grid had got to. */
    @Test
    fun mergingRefreshKeepsItsPlace() = runTest {
        var calls = 0
        val f = Feed(backgroundScope) { cursor ->
            calls++
            when (cursor) {
                "" -> FeedPage(listOf(Card("r$calls", "新$calls")), "p2", more = true)
                "p2" -> FeedPage(listOf(Card("b", "B")), "p3", more = true)
                else -> FeedPage(listOf(Card("c", "C")), "p4", more = true)
            }
        }
        f.loadMore()
        runCurrent()
        f.loadMore()
        runCurrent()
        assertEquals(2, f.cards.size)
        f.refresh(merging = true)
        assertEquals(listOf("r3", "r1", "b"), f.cards.map { it.id })
        f.loadMore()
        runCurrent()
        assertEquals("c", f.cards.last().id) // the third page, not the second again
        assertTrue(f.more)
    }
}

class StoreTest {
    /** What the viewer watched and saved survives a restart, in files the macOS app's Go code reads and writes too. */
    @Test
    fun historyAndFavoritesPersist() {
        val dir = tempDir()
        val h = History(dir)
        h.record(Watch(seriesID = "1", title = "甲", episode = 3, position = 12.5))
        h.record(Watch(seriesID = "2", title = "乙", episode = 1))
        h.record(Watch(seriesID = "1", title = "甲", episode = 4))
        h.flush()
        val f = Favorites(dir)
        assertTrue(f.toggle(Fav.of(Card("9", "收藏的剧", episodes = 60))))
        assertTrue(f.has("9"))
        f.add(Fav(seriesID = "8", title = "另一部"))
        f.flush()

        val h2 = History(dir)
        assertEquals(listOf("1", "2"), h2.list.map { it.seriesID })
        assertEquals(4, h2.get("1")?.episode)
        val f2 = Favorites(dir)
        assertEquals(listOf("8", "9"), f2.list.map { it.seriesID })
        assertEquals("60 集", f2.cards.last().sub)
        assertFalse(f2.toggle(Fav(seriesID = "9", title = "")))
        assertFalse(f2.has("9"))

        // A file the Go app wrote, with nanoseconds and an offset.
        val goDir = File(dir, "go").apply { mkdirs() }
        File(goDir, "history.json").writeText(
            """
            [{"series_id":"7","title":"浅浅归聿","episode":7,"position":42.1,"updated":"2026-11-05T16:02:03.123456789+08:00"},
             {"series_id":"8","title":"无进度","episode":1,"updated":"2026-11-05T08:00:00Z"}]
            """.trimIndent(),
        )
        val h3 = History(goDir)
        assertEquals(2, h3.list.size)
        assertEquals(42.1, h3.get("7")!!.position, 0.0)
        assertEquals(emptyList<Int>(), h3.get("7")?.watched)
        val updated = h3.get("7")!!.updated
        assertTrue(abs(updated.toEpochMilli() / 1000.0 - 1_793_865_723.123) < 0.01)
    }

    /** The episodes watched add up as the viewer goes on, and a later record that doesn't mention them keeps them. */
    @Test
    fun historyKeepsWatchedEpisodes() {
        val dir = tempDir()
        val h = History(dir)
        h.record(Watch(seriesID = "1", episode = 3, finished = true, watched = listOf(3)))
        h.record(Watch(seriesID = "1", episode = 1, watched = listOf(1)))
        h.record(Watch(seriesID = "1", episode = 2, position = 5.0))
        h.record(Watch(seriesID = "2", episode = 1))
        h.record(Watch(seriesID = "1", episode = 3, watched = listOf(3)))
        assertEquals(listOf(1, 3), h.get("1")?.watched)
        assertEquals(emptyList<Int>(), h.get("2")?.watched)
        h.flush()
        assertEquals(listOf(1, 3), History(dir).get("1")?.watched)

        assertTrue(watchedEnough(54.0, 60.0, finished = false))
        assertFalse(watchedEnough(53.0, 60.0, finished = false))
        assertFalse(watchedEnough(5.0, 0.0, finished = false)) // the length isn't known yet
        assertTrue(watchedEnough(0.0, 0.0, finished = true))
    }
}
