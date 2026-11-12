package com.kadaliao.kaixinguo.account

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kadaliao.kaixinguo.model.FakeCatalog
import com.kadaliao.kaixinguo.model.playable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class LocalFollowsTest {
    private class MemoryStore : FollowPersistence {
        var data = emptyList<FollowEntry>()
        override fun load() = data
        override fun save(items: List<FollowEntry>) { data = items.toList() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun aRealCheckChangesEpisodeCountWithoutACloudAccount() = runTest {
        val catalog = FakeCatalog()
        catalog.playable("111", 15)
        val saved = MemoryStore()
        val follows = LocalFollows(catalog, this, saved)
        follows.follow("111", "作品", "", 12)
        follows.checkUpdates()
        advanceUntilIdle()
        assertEquals(1, follows.unreadCount)
        assertEquals(15, follows.entries.single().latestEpisodes)
        follows.markRead("111")
        assertEquals(0, follows.unreadCount)
        assertEquals(15, saved.data.single().seenEpisodes)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun failingRequestsDoNotRemoveLocalFollows() = runTest {
        val catalog = FakeCatalog()
        val store = MemoryStore()
        val follows = LocalFollows(catalog, this, store)
        follows.follow("999", "原有记录", "", 8)
        follows.checkUpdates()
        advanceUntilIdle()
        assertEquals(1, follows.failedCount)
        assertEquals("原有记录", follows.entries.single().title)
        assertEquals(8, follows.entries.single().latestEpisodes)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun duplicateFollowToggleAndRemovalArePersisted() = runTest {
        val store = MemoryStore()
        val follows = LocalFollows(FakeCatalog(), this, store)
        follows.follow("100", "作品", "", 1)
        follows.follow("100", "重复", "", 2)
        assertEquals(1, follows.entries.size)
        follows.toggle("100", "作品", "", 1)
        assertTrue(store.data.isEmpty())
    }

    @Test fun firstCheckWithoutInitialMetadataDoesNotInventAnUpdate() {
        val original = FollowEntry("123", "A", latestEpisodes = 0, seenEpisodes = 0)
        val checked = mergeEpisodeCount(original, 45, 100)
        assertEquals(45, checked.latestEpisodes)
        assertEquals(45, checked.seenEpisodes)
        assertFalse(checked.hasNewEpisodes)
    }

    @Test fun aLaterEpisodeCreatesAnUnreadUpdate() {
        val previous = FollowEntry("123", "A", latestEpisodes = 45, seenEpisodes = 45)
        val next = mergeEpisodeCount(previous, 48, 200)
        assertTrue(next.hasNewEpisodes)
        assertEquals(3, next.latestEpisodes - next.seenEpisodes)
    }

    @Test fun serverMetadataDecreaseDoesNotHideAnUpdate() {
        val previous = FollowEntry("123", "A", latestEpisodes = 48, seenEpisodes = 45)
        val next = mergeEpisodeCount(previous, 43, 300)
        assertEquals(48, next.latestEpisodes)
        assertEquals(45, next.seenEpisodes)
    }

    @Test fun corruptRowsCannotBeLoadedAsFollowedSeries() {
        assertEquals(null, FollowEntry.fromJson(JSONObject("""{"id":"not_a_series","title":"A"}""")))
        val parsed = FollowEntry.fromJson(JSONObject("""{"id":"456","latest":100,"seen":999,"cover":"http://insecure.example/cover"}"""))
        assertEquals(100, parsed?.seenEpisodes)
        assertEquals("", parsed?.cover)
    }

    @Test fun storingAndRestoringEntryRetainsUnreadBaseline() {
        val before = FollowEntry("456", "作品", "https://example.com/poster", 66, 61, 120L)
        val after = FollowEntry.fromJson(before.toJson())
        assertEquals(before, after)
        assertTrue(after!!.hasNewEpisodes)
    }
}
