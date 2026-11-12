package com.kadaliao.kaixinguo.account

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFollowsTest {
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
