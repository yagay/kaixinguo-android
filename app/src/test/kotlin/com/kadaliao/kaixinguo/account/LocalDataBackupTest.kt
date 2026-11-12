package com.kadaliao.kaixinguo.account

import com.kadaliao.kaixinguo.model.Fav
import com.kadaliao.kaixinguo.model.Favorites
import com.kadaliao.kaixinguo.model.History
import com.kadaliao.kaixinguo.model.Watch
import com.kadaliao.kaixinguo.model.tempDir
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LocalDataBackupTest {
    private val moment = Instant.parse("2026-11-12T12:00:00Z")

    private fun example() = LocalDataBackup(
        history = listOf(Watch("123456", "剧甲", episode = 7, position = 42.0, updated = moment)),
        favorites = listOf(Fav("654321", "剧乙", added = moment)),
        follows = listOf(FollowEntry("345678", "剧丙", latestEpisodes = 12, seenEpisodes = 10)),
        exportedAt = moment,
    )

    @Test fun fullRoundTripContainsOnlyLocalRecords() {
        val exported = LocalDataBackupCodec.encode(example())
        val imported = LocalDataBackupCodec.decode(exported)
        assertEquals(example(), imported)
        assertFalse(exported.contains("sessionid"))
        assertFalse(exported.contains("\"cookie\""))
        assertFalse(exported.contains("install_id"))
        assertTrue(exported.contains("kaixinguo-local-data"))
    }

    @Test fun previewDoesNotMutateHistoryOrFavoriteRecords() {
        val before = example()
        val preview = previewLocalImport(
            before, historyIDs = setOf("123456"),
            favoriteIDs = emptySet(), followIDs = setOf("345678"),
        )
        assertEquals(0, preview.historyNew)
        assertEquals(1, preview.historyExisting)
        assertEquals(1, preview.favoritesNew)
        assertEquals(1, preview.followsExisting)
        assertEquals(7, before.history.single().episode)
    }

    @Test fun importOnlyMissingIsIdempotent() {
        val root = tempDir()
        val history = History(root)
        val favorites = Favorites(root)
        val snapshot = example()
        val a = history.importEntries(snapshot.history, preferNewer = false)
        val b = favorites.importEntries(snapshot.favorites, preferNewer = false)
        assertEquals(1, a.added)
        assertEquals(1, b.added)
        assertEquals(0, history.importEntries(snapshot.history, preferNewer = false).added)
        assertEquals(0, favorites.importEntries(snapshot.favorites, preferNewer = false).added)
        assertEquals(1, history.list.size)
        assertEquals(1, favorites.list.size)
        history.flush()
        favorites.flush()
        assertEquals("123456", History(root).list.single().seriesID)
        assertEquals("654321", Favorites(root).list.single().seriesID)
    }

    @Test fun newerMergeNeverReplacesWithoutPermission() {
        val root = tempDir()
        val history = History(root)
        val favorites = Favorites(root)
        history.importEntries(
            listOf(Watch("123456", "已有历史", episode = 3, updated = moment)),
            preferNewer = false,
        )
        favorites.importEntries(
            listOf(Fav("654321", "原有收藏", added = moment)),
            preferNewer = false,
        )
        val future = moment.plusSeconds(180)
        val newHistory = Watch("123456", "更新记录", episode = 6, updated = future)
        val newFav = Fav("654321", "更新收藏", added = future)
        assertEquals(0, history.importEntries(listOf(newHistory), false).updated)
        assertEquals(0, favorites.importEntries(listOf(newFav), false).updated)
        assertEquals(3, history.list.single().episode)
        assertEquals("原有收藏", favorites.list.single().title)
        assertEquals(1, history.importEntries(listOf(newHistory), true).updated)
        assertEquals(1, favorites.importEntries(listOf(newFav), true).updated)
        assertEquals(6, history.list.single().episode)
        assertEquals("更新收藏", favorites.list.single().title)
        history.flush()
        favorites.flush()
    }

    @Test fun cannotImportUnknownVersionOrInvalidIds() {
        val raw = LocalDataBackupCodec.encode(example())
        assertTrue(runCatching {
            LocalDataBackupCodec.decode(raw.replace("\"schema\": 1", "\"schema\": 9"))
        }.isFailure)
        val invalid = JSONObject(raw)
        invalid.put("history", JSONArray().put(
            JSONObject().put("series_id", "../not-a-series").put("episode", 3),
        ))
        assertTrue(runCatching { LocalDataBackupCodec.decode(invalid.toString()) }.isFailure)
    }

    @Test fun rejectsOversizedBackupBeforeParsing() {
        val payload = "x".repeat(LocalDataBackupCodec.MAX_FILE_BYTES + 1)
        assertTrue(runCatching { LocalDataBackupCodec.decode(payload) }.isFailure)
    }
}
