package com.kadaliao.kaixinguo.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * JSONFile keeps a value in a JSON file. The value itself belongs to its
 * owner on the main thread; the file only gets snapshots of it, written on
 * a thread of its own, so saving never holds up a frame.
 */
class JSONFile<T : Any>(
    val file: File,
    private val encode: (T) -> String,
    private val decode: (String) -> T,
) {
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "kaixinguo-store").apply { isDaemon = true } }
    private val pending = AtomicReference<T?>(null)

    /**
     * Reads the value; null when the file is missing or unreadable, so the
     * app opens clean rather than refusing to open.
     */
    fun load(): T? = try {
        decode(file.readText())
    } catch (e: Exception) {
        null
    }

    /** Saves the value. A snapshot still waiting to be written is dropped: only the latest matters. */
    fun save(x: T) {
        if (pending.getAndSet(x) != null) return // the write on its way takes this one
        writer.execute {
            val latest = pending.getAndSet(null) ?: return@execute
            write(latest)
        }
    }

    /** Waits until every value saved so far is on disk, as the app goes to the background. */
    fun flush() {
        writer.submit {}.get()
    }

    private fun write(x: T) {
        try {
            val text = encode(x)
            file.parentFile?.mkdirs()
            // Written beside the file and moved over it, so a crash halfway
            // through cannot leave half a file behind.
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            debugLog { "store: ${file.name}: $e" }
        }
    }
}

data class ImportResult(val added: Int = 0, val updated: Int = 0)

/** JSONStore keeps a list of values in a JSON file, as [JSONFile] does; an entry it can't read is skipped. */
class JSONStore<T>(
    file: File,
    encode: (T) -> JSONObject,
    decode: (JSONObject) -> T?,
) {
    private val json = JSONFile<List<T>>(
        file,
        encode = { list -> JSONArray().apply { for (x in list) put(encode(x)) }.toString() },
        decode = { text ->
            val a = JSONArray(text)
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(decode) }
        },
    )

    /** Reads the list; empty when the file is missing or unreadable. */
    fun load(): List<T> = json.load() ?: emptyList()

    fun save(list: List<T>) = json.save(list)

    fun flush() = json.flush()
}

/** Go's zero time, which the macOS app writes for a time it never set. */
val zeroTime: Instant = Instant.parse("0001-01-01T00:00:00Z")

/** Reads an RFC 3339 time, with or without fractions of a second, of any precision Go writes. */
fun parseRFC3339(s: String): Instant? =
    runCatching { OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(s) }.getOrNull()

/** Writes a time as Go reads it. */
fun formatRFC3339(t: Instant): String = DateTimeFormatter.ISO_INSTANT.format(t)

private fun JSONObject.ints(name: String): List<Int> {
    val a = optJSONArray(name) ?: return emptyList()
    return (0 until a.length()).map { a.optInt(it) }
}

/** Watch is where the viewer is in one series. */
data class Watch(
    val seriesID: String,
    val title: String = "",
    val cover: String = "",
    val episode: Int,
    /** Seconds into the episode. */
    val position: Double = 0.0,
    val duration: Double = 0.0,
    /** Episodes in the series. */
    val total: Int = 0,
    val finished: Boolean = false,
    val updated: Instant = Instant.now(),
    /** The episodes watched to the end, or nearly, in order. */
    val watched: List<Int> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("series_id", seriesID)
        .put("title", title)
        .put("cover", cover)
        .put("episode", episode)
        .put("position", if (position.isFinite()) position else 0.0)
        .put("duration", if (duration.isFinite()) duration else 0.0)
        .put("total", total)
        .put("finished", finished)
        .put("updated", formatRFC3339(updated))
        .put("watched", JSONArray(watched))

    companion object {
        fun fromJson(o: JSONObject): Watch? {
            val id = o.optString("series_id")
            if (id.isEmpty()) return null
            return Watch(
                seriesID = id,
                title = o.optString("title"),
                cover = o.optString("cover"),
                episode = o.optInt("episode"),
                position = o.optDouble("position", 0.0),
                duration = o.optDouble("duration", 0.0),
                total = o.optInt("total"),
                finished = o.optBoolean("finished"),
                updated = parseRFC3339(o.optString("updated")) ?: zeroTime,
                watched = o.ints("watched"),
            )
        }
    }
}

/** History keeps the series watched, the latest first, in history.json. */
class History(dir: File) {
    companion object {
        /** How many series the history keeps. */
        const val MAX = 200
    }

    private val store = JSONStore(File(dir, "history.json"), Watch::toJson, Watch::fromJson)
    var list by mutableStateOf(store.load())
        private set

    fun get(id: String): Watch? = list.firstOrNull { it.seriesID == id }

    /** Moves w to the front. The episodes watched before stay watched, with w's own added. */
    fun record(w: Watch) {
        var next = w.copy(updated = Instant.now())
        val old = get(w.seriesID)
        if (old != null && old.watched.isNotEmpty()) {
            next = next.copy(watched = (old.watched + next.watched).toSortedSet().toList())
        }
        list = listOf(next) + list.asSequence().filter { it.seriesID != w.seriesID }.take(MAX - 1)
        store.save(list)
    }

    fun remove(id: String) {
        if (get(id) == null) return
        list = list.filter { it.seriesID != id }
        store.save(list)
    }

    /**
     * Offline-only backup import. Never removes history and never uploads it.
     * A record may replace an existing one only if expressly requested and
     * its recorded update time is strictly newer.
     */
    fun importEntries(incoming: List<Watch>, preferNewer: Boolean): ImportResult {
        val values = list.associateByTo(LinkedHashMap()) { it.seriesID }
        var added = 0
        var updated = 0
        for (candidate in incoming) {
            if (candidate.seriesID.isBlank() || candidate.episode < 1) continue
            val current = values[candidate.seriesID]
            if (current == null) {
                if (values.size >= MAX) continue
                values[candidate.seriesID] = candidate
                added++
            } else if (preferNewer && candidate.updated > current.updated) {
                values[candidate.seriesID] = candidate
                updated++
            }
        }
        if (added != 0 || updated != 0) {
            list = values.values.sortedByDescending { it.updated }.take(MAX)
            store.save(list)
        }
        return ImportResult(added, updated)
    }

    fun flush() = store.flush()
}

/**
 * Fav is a series the viewer saved, with what a grid needs to show it
 * without asking the server again. It keeps the episodes rather than the
 * line its grid showed, which may be a chart's score or a day's heat and
 * would be stale on the 收藏 page.
 */
data class Fav(
    val seriesID: String,
    val title: String,
    val cover: String = "",
    val episodes: Int = 0,
    val added: Instant = Instant.now(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("series_id", seriesID)
        .put("title", title)
        .put("cover", cover)
        .put("episodes", episodes)
        .put("added", formatRFC3339(added))

    companion object {
        /** The series as it would be saved. */
        fun of(k: Card) = Fav(k.id, k.title, k.cover, k.episodes)

        fun fromJson(o: JSONObject): Fav? {
            val id = o.optString("series_id")
            if (id.isEmpty()) return null
            return Fav(
                seriesID = id,
                title = o.optString("title"),
                cover = o.optString("cover"),
                episodes = o.optInt("episodes"),
                added = parseRFC3339(o.optString("added")) ?: zeroTime,
            )
        }
    }
}

/** Favorites keeps the series saved, the latest first, in favorites.json. */
class Favorites(dir: File) {
    private val store = JSONStore(File(dir, "favorites.json"), Fav::toJson, Fav::fromJson)
    var list by mutableStateOf(store.load())
        private set
    private val ids = list.mapTo(HashSet()) { it.seriesID }

    fun has(id: String): Boolean {
        // Read list, so a view that asks follows the changes.
        list.size
        return id in ids
    }

    /** Saves the series, or drops it when it is saved already, and reports whether it is saved now. */
    fun toggle(x: Fav): Boolean {
        if (has(x.seriesID)) {
            remove(x.seriesID)
            return false
        }
        add(x)
        return true
    }

    /** Saves the series, newest first. */
    fun add(x: Fav) {
        if (x.seriesID.isEmpty() || x.seriesID in ids) return
        ids += x.seriesID
        list = listOf(x.copy(added = Instant.now())) + list
        store.save(list)
    }

    fun remove(id: String) {
        if (!ids.remove(id)) return
        list = list.filter { it.seriesID != id }
        store.save(list)
    }

    /** Merge local favorites without silently deleting or uploading anything. */
    fun importEntries(incoming: List<Fav>, preferNewer: Boolean): ImportResult {
        val values = list.associateByTo(LinkedHashMap()) { it.seriesID }
        var added = 0
        var updated = 0
        for (candidate in incoming) {
            if (candidate.seriesID.isBlank()) continue
            val old = values[candidate.seriesID]
            if (old == null) {
                if (values.size >= 1200) continue
                values[candidate.seriesID] = candidate
                added++
            } else if (preferNewer && candidate.added > old.added) {
                values[candidate.seriesID] = candidate
                updated++
            }
        }
        if (added != 0 || updated != 0) {
            list = values.values.sortedByDescending { it.added }
            ids.clear()
            ids.addAll(list.map { it.seriesID })
            store.save(list)
        }
        return ImportResult(added, updated)
    }

    /** The saved series as a grid shows them, the latest first. */
    val cards: List<Card>
        get() = list.map { Card(it.seriesID, it.title, it.cover, episodeCount(it.episodes), episodes = it.episodes) }

    fun flush() = store.flush()
}
