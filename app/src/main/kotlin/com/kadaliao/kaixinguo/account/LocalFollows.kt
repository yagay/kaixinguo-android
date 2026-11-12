package com.kadaliao.kaixinguo.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kadaliao.kaixinguo.diagnostics.Diagnostics
import com.kadaliao.kaixinguo.model.Catalog
import com.kadaliao.kaixinguo.model.JSONStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * A locally managed follow list. No account cookie, cloud subscription,
 * implicit server mutation or push-notification permission is involved.
 */
data class FollowEntry(
    val id: String,
    val title: String,
    val cover: String = "",
    val latestEpisodes: Int = 0,
    val seenEpisodes: Int = 0,
    val checkedAt: Long = 0L,
) {
    val hasNewEpisodes: Boolean get() = latestEpisodes > seenEpisodes
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("cover", cover)
        .put("latest", latestEpisodes).put("seen", seenEpisodes)
        .put("checked_at", checkedAt)

    companion object {
        fun fromJson(json: JSONObject): FollowEntry? {
            val id = json.optString("id")
            if (!id.matches(Regex("[0-9]{1,30}"))) return null
            val latest = json.optInt("latest").coerceIn(0, 100000)
            return FollowEntry(
                id = id,
                title = json.optString("title").take(180).ifBlank { "短剧" },
                cover = json.optString("cover").take(1024).takeIf { it.startsWith("https://") }.orEmpty(),
                latestEpisodes = latest,
                seenEpisodes = json.optInt("seen").coerceIn(0, latest),
                checkedAt = json.optLong("checked_at").coerceAtLeast(0),
            )
        }
    }
}

/** Ignore metadata decreases; first discovery establishes a baseline, not a false update. */
internal fun mergeEpisodeCount(
    old: FollowEntry,
    observed: Int,
    checkedAt: Long,
): FollowEntry {
    if (observed <= 0) return old.copy(checkedAt = checkedAt)
    val newest = maxOf(old.latestEpisodes, observed.coerceAtMost(100000))
    return old.copy(
        latestEpisodes = newest,
        seenEpisodes = if (old.latestEpisodes == 0) newest else old.seenEpisodes,
        checkedAt = checkedAt,
    )
}

internal interface FollowPersistence {
    fun load(): List<FollowEntry>
    fun save(items: List<FollowEntry>)
}

internal class JsonFollowPersistence(dir: File) : FollowPersistence {
    private val file = JSONStore(File(dir, "follows-local-v1.json"), FollowEntry::toJson, FollowEntry::fromJson)
    override fun load(): List<FollowEntry> = file.load()
    override fun save(items: List<FollowEntry>) = file.save(items)
}

class LocalFollows internal constructor(
    private val catalog: Catalog,
    private val scope: CoroutineScope,
    private val storage: FollowPersistence,
) {
    constructor(catalog: Catalog, scope: CoroutineScope, dir: File) :
        this(catalog, scope, JsonFollowPersistence(dir))

    var entries by mutableStateOf(
        storage.load().distinctBy { it.id }.take(300),
    )
        private set
    var checking by mutableStateOf(false)
        private set
    var checkedCount by mutableStateOf(0)
        private set
    var failedCount by mutableStateOf(0)
        private set
    private var runId = 0

    val unreadCount: Int get() = entries.count { it.hasNewEpisodes }

    fun contains(id: String): Boolean {
        entries.size // Compose snapshot read
        return entries.any { it.id == id }
    }

    fun toggle(id: String, title: String, cover: String, episodes: Int) {
        if (contains(id)) {
            remove(id)
        } else {
            follow(id, title, cover, episodes)
        }
    }

    fun follow(id: String, title: String, cover: String, episodes: Int) {
        if (!id.matches(Regex("[0-9]{1,30}")) || contains(id)) return
        val count = episodes.coerceIn(0, 100000)
        val entry = FollowEntry(
            id, title.take(180).ifBlank { "短剧" },
            cover.takeIf { it.startsWith("https://") }.orEmpty().take(1024),
            count, count,
        )
        entries = listOf(entry) + entries.take(299)
        storage.save(entries)
        Diagnostics.record("follows", "local_follow_added")
    }

    fun remove(id: String) {
        if (!contains(id)) return
        entries = entries.filterNot { it.id == id }
        storage.save(entries)
        Diagnostics.record("follows", "local_follow_removed")
    }

    fun markRead(id: String) {
        val next = entries.map {
            if (it.id == id) it.copy(seenEpisodes = it.latestEpisodes) else it
        }
        if (next != entries) {
            entries = next
            storage.save(entries)
            Diagnostics.record("follows", "local_updates_acknowledged")
        }
    }

    /** Only an explicit tap checks the existing anonymous catalog; no periodic polling. */
    fun checkUpdates() {
        if (checking || entries.isEmpty()) return
        val token = ++runId
        checking = true
        checkedCount = 0
        failedCount = 0
        Diagnostics.record("follows", "local_check_started")
        val ids = entries.map { it.id }
        scope.launch {
            try {
                for (id in ids) {
                    if (runId != token) return@launch
                    val fresh = try { catalog.getSeries(id) }
                    catch (stop: CancellationException) { throw stop }
                    catch (_: Exception) {
                        failedCount++
                        continue
                    }
                    if (runId != token) return@launch
                    val old = entries.firstOrNull { it.id == id } ?: continue
                    val merged = mergeEpisodeCount(
                        old.copy(
                            title = fresh.title.ifBlank { old.title }.take(180),
                            cover = fresh.coverUrl.takeIf { it.startsWith("https://") }
                                ?.take(1024) ?: old.cover,
                        ),
                        fresh.episodeCount,
                        System.currentTimeMillis(),
                    )
                    if (old != merged) {
                        entries = entries.map { if (it.id == id) merged else it }
                        storage.save(entries)
                    }
                    checkedCount++
                }
            } finally {
                if (runId == token) {
                    checking = false
                    Diagnostics.record(
                        "follows",
                        if (failedCount == 0) "local_check_completed" else "local_check_partial",
                    )
                }
            }
        }
    }
}
