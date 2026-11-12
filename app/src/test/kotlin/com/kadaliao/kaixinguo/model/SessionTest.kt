package com.kadaliao.kaixinguo.model

import com.kadaliao.kaixinguo.ui.Route
import com.kadaliao.kaixinguo.ui.Router
import com.kadaliao.kaixinguo.ui.Tab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    /**
     * The next launch opens where the viewer left the app: the tab, the
     * pages in each, the search, the chart and day, the speed and definition,
     * and the episode playing, from just before where they got to.
     */
    @Test
    fun launchOpensWhereTheViewerLeft() = runTest {
        val dir = tempDir()
        val catalog = FakeCatalog().apply { playable("1", 3) }
        val video = FakeVideo()
        val m = testModel(backgroundScope, catalog, video, dir = dir)
        val router = Router()
        router.select(Tab.RANK)
        router.open(Route.Series("7"))
        router.select(Tab.DISCOVER)
        router.open(Route.Series("8"))
        router.open(Route.Series("9"))
        router.search("重生")
        router.searchText = "重生之" // typed on, not searched yet
        m.rankContent = "c1"
        m.rankBoard = "b2"
        val tomorrow = todayKey(LocalDate.now().plusDays(1))
        m.chooseDay(tomorrow)
        m.player.chooseSpeed(1.5f)
        m.player.chooseQuality(720)

        m.player.play("1", 2)
        runCurrent()
        video.reports(TimeControl.PLAYING)
        video.position = 42.0
        m.player.leaveApp() // home: paused, where they got to noted
        assertFalse(video.wantsToPlay)
        m.saveSession(router.saveTo(m.session()))
        m.flush()

        val video2 = FakeVideo()
        val m2 = testModel(backgroundScope, catalog, video2, dir = dir)
        val router2 = Router()
        router2.restore(m2.lastSession)
        assertEquals(Tab.DISCOVER, router2.tab)
        assertEquals(listOf(Route.Series("8"), Route.Series("9")), router2.path(Tab.DISCOVER))
        assertEquals(listOf(Route.Series("7")), router2.path(Tab.RANK))
        assertEquals("重生之", router2.searchText)
        assertEquals("重生", router2.searchQuery)
        assertEquals("c1", m2.rankContent)
        assertEquals("b2", m2.rankBoard)
        assertEquals(tomorrow, m2.newDate)
        assertEquals(1.5f, m2.player.speed)
        assertEquals(1.5f, video2.speed)
        assertEquals(720, m2.player.preferredQuality)
        assertFalse(m2.player.isPresented) // until the window opens

        m2.reopenPlayer()
        assertTrue(m2.player.isPresented)
        runCurrent()
        assertEquals("1e2", video2.loaded?.episodeId)
        assertEquals(39.0, video2.position, 0.0)
        assertTrue(video2.wantsToPlay) // it played as they left

        // Only the launch's first window reopens it.
        m2.player.leave()
        m2.reopenPlayer()
        assertFalse(m2.player.isPresented)
    }

    /** An episode the viewer had paused reopens paused, where they paused it. */
    @Test
    fun pausedEpisodeReopensPaused() = runTest {
        val dir = tempDir()
        val catalog = FakeCatalog().apply { playable("1", 3) }
        val video = FakeVideo()
        val m = testModel(backgroundScope, catalog, video, dir = dir)
        m.player.play("1", 3)
        runCurrent()
        video.reports(TimeControl.PLAYING)
        video.position = 10.0
        m.player.togglePause()
        video.reports(TimeControl.PAUSED)
        assertFalse(m.player.playing)
        m.player.leaveApp()
        assertEquals(SessionPlayer("1", 3, playing = false), m.session().player)
        m.saveSession(m.session())
        m.flush()

        val video2 = FakeVideo()
        val m2 = testModel(backgroundScope, catalog, video2, dir = dir)
        m2.reopenPlayer()
        runCurrent()
        assertEquals("1e3", video2.loaded?.episodeId)
        assertEquals(7.0, video2.position, 0.0)
        assertFalse(video2.wantsToPlay)
        assertEquals(PlayerState.Buffering, m2.player.state) // the pause mark shows, not a spinner
        assertFalse(m2.player.playing)
    }

    /** Coming back to the app plays on what played as the viewer left, and only that. */
    @Test
    fun comingBackPlaysOn() = runTest {
        val catalog = FakeCatalog().apply { playable("1", 3) }
        val video = FakeVideo()
        val p = testModel(backgroundScope, catalog, video).player
        p.play("1", 1)
        runCurrent()
        video.reports(TimeControl.PLAYING)

        p.leaveApp()
        video.reports(TimeControl.PAUSED)
        assertTrue(p.away)
        assertTrue(p.playing) // what a session saved now says
        p.returnToApp()
        assertTrue(video.wantsToPlay)
        assertFalse(p.away)
        video.reports(TimeControl.PLAYING)

        // Paused by the viewer, it stays paused.
        p.togglePause()
        video.reports(TimeControl.PAUSED)
        p.leaveApp()
        p.returnToApp()
        assertFalse(video.wantsToPlay)

        // Played, then paused, from the notification while away: coming back leaves it paused.
        p.togglePause()
        video.reports(TimeControl.PLAYING)
        p.leaveApp()
        video.reports(TimeControl.PAUSED)
        video.play()
        video.reports(TimeControl.PLAYING)
        assertFalse(p.away)
        video.pause()
        video.reports(TimeControl.PAUSED)
        p.returnToApp()
        assertFalse(video.wantsToPlay)

        // An episode on its way as the viewer leaves waits for them.
        p.play("1", 2)
        p.leaveApp()
        runCurrent()
        assertEquals("1e2", video.loaded?.episodeId)
        assertFalse(video.wantsToPlay)
        assertTrue(p.playing)
        p.returnToApp()
        assertTrue(video.wantsToPlay)

        // Leaving with the player closed changes nothing.
        p.leave()
        p.leaveApp()
        assertFalse(p.away)
    }

    /** A window opened by a link drops the episode the last launch left, rather than reopening it later. */
    @Test
    fun linkDropsTheLastEpisode() = runTest {
        val dir = tempDir()
        File(dir, "data").mkdirs()
        File(dir, "data/session.json").writeText("""{"player":{"series_id":"1","episode":2,"playing":true}}""")
        val m = testModel(backgroundScope, dir = dir)
        assertEquals(SessionPlayer("1", 2, playing = true), m.session().player) // kept until a window opens
        m.reopenPlayer(open = false)
        assertFalse(m.player.isPresented)
        assertNull(m.session().player)
        m.reopenPlayer()
        assertFalse(m.player.isPresented)
    }

    /** What a later version saved, or a broken file, opens as much as this version understands. */
    @Test
    fun readsWhatItCan() {
        val s = Session.fromJson(
            JSONObject(
                """
                {"tab":"GONE","paths":{"RANK":["series:9","movie:3","series:"],"OLD":["series:1"]},
                 "speed":99,"quality":-1,"new_date":"2026-11-01","day":"2000-01-01",
                 "player":{"series_id":"","episode":2},"future":true}
                """.trimIndent(),
            ),
        )
        assertEquals(1f, s.speed)
        assertNull(s.quality)
        assertNull(s.player)
        val router = Router()
        router.restore(s)
        assertEquals(Tab.REELS, router.tab)
        assertEquals(listOf(Route.Series("9")), router.path(Tab.RANK))
        assertTrue(Tab.entries.filter { it != Tab.RANK }.all { router.path(it).isEmpty() })

        // The day chosen among new releases only holds the day it was saved.
        val dir = tempDir()
        File(dir, "data").mkdirs()
        File(dir, "data/session.json").writeText(s.toJson().toString())
        assertEquals("", runTestModel(dir).newDate)

        File(dir, "data/session.json").writeText("not json")
        assertEquals(Session(), runTestModel(dir).lastSession)

        // A round trip keeps everything.
        val full = Session(
            tab = "MINE", paths = mapOf("MINE" to listOf("series:1")), searchText = "a", searchQuery = "b",
            rankContent = "c", rankBoard = "d", newDate = "e", day = "f", speed = 1.25f, quality = 540,
            player = SessionPlayer("1", 4, playing = true),
        )
        assertEquals(full, Session.fromJson(JSONObject(full.toJson().toString())))
        assertEquals(Route.Series("x:y"), Route.parse(Route.Series("x:y").saved))
    }

    private fun runTestModel(dir: File): AppModel {
        var m: AppModel? = null
        runTest { m = testModel(backgroundScope, dir = dir) }
        return m!!
    }
}
