package com.kadaliao.kaixinguo.account

import com.kadaliao.kaixinguo.model.Fav
import com.kadaliao.kaixinguo.model.Watch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * User-owned local-data portability, NOT Hongguo cloud sync.
 * It intentionally does not export website cookies, device IDs, login tokens
 * or account identifiers. The exported file still contains private viewing
 * history and must be handled as sensitive by the user.
 */
data class LocalDataBackup(
    val history: List<Watch>,
    val favorites: List<Fav>,
    val follows: List<FollowEntry>,
    val exportedAt: Instant,
)

object LocalDataBackupCodec {
    const val MAX_FILE_BYTES: Int = 2 * 1024 * 1024
    const val MAX_HISTORY = 200
    const val MAX_FAVORITES = 1200
    const val MAX_FOLLOWS = 300
    private const val FORMAT = "kaixinguo-local-data"
    private const val SCHEMA = 1

    fun encode(data: LocalDataBackup): String {
        require(data.history.size <= MAX_HISTORY)
        require(data.favorites.size <= MAX_FAVORITES)
        require(data.follows.size <= MAX_FOLLOWS)
        return JSONObject()
            .put("format", FORMAT)
            .put("schema", SCHEMA)
            .put("exported_at", data.exportedAt.toString())
            .put("history", JSONArray().apply { data.history.forEach { put(it.toJson()) } })
            .put("favorites", JSONArray().apply { data.favorites.forEach { put(it.toJson()) } })
            .put("follows", JSONArray().apply { data.follows.forEach { put(it.toJson()) } })
            .toString(2)
            .also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) }
    }

    fun decode(raw: String): LocalDataBackup {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "备份文件过大" }
        val obj = try { JSONObject(raw) } catch (_: Exception) {
            throw IllegalArgumentException("不是有效的 JSON 备份文件")
        }
        require(obj.optString("format") == FORMAT && obj.optInt("schema") == SCHEMA) {
            "备份格式或版本不受支持"
        }
        val timestamp = runCatching { Instant.parse(obj.getString("exported_at")) }
            .getOrElse { throw IllegalArgumentException("缺少有效的导出时间") }
        val history = readArray(obj, "history", MAX_HISTORY) { Watch.fromJson(it)?.takeIf(::validWatch) }
        val favorites = readArray(obj, "favorites", MAX_FAVORITES) { Fav.fromJson(it)?.takeIf(::validFav) }
        val follows = readArray(obj, "follows", MAX_FOLLOWS) { FollowEntry.fromJson(it) }
        return LocalDataBackup(
            history = history.distinctBy { it.seriesID },
            favorites = favorites.distinctBy { it.seriesID },
            follows = follows.distinctBy { it.id },
            exportedAt = timestamp,
        )
    }

    private fun <T : Any> readArray(
        source: JSONObject, name: String, max: Int, decode: (JSONObject) -> T?,
    ): List<T> {
        val items = source.optJSONArray(name)
            ?: throw IllegalArgumentException("备份文件缺少 " + name)
        require(items.length() <= max) { "备份条目数量超出限制" }
        return (0 until items.length()).map { index ->
            val value = items.optJSONObject(index)
                ?: throw IllegalArgumentException("备份条目结构无效")
            decode(value) ?: throw IllegalArgumentException("备份中存在无效剧集记录")
        }
    }

    private fun validWatch(w: Watch): Boolean =
        validId(w.seriesID) && w.episode in 1..100000 &&
            w.position.isFinite() && w.position >= 0 && w.position <= 24 * 3600 &&
            w.duration.isFinite() && w.duration >= 0 && w.duration <= 24 * 3600 &&
            w.title.length <= 250 && w.cover.length <= 2048 &&
            w.total in 0..100000 && w.watched.size <= 100000

    private fun validFav(f: Fav): Boolean =
        validId(f.seriesID) && f.title.length <= 250 &&
            f.cover.length <= 2048 && f.episodes in 0..100000

    private fun validId(id: String): Boolean = id.matches(Regex("[0-9]{1,30}"))
}

/** Preview-only counts. Import must always require a separate explicit action. */
data class LocalImportPreview(
    val historyNew: Int,
    val historyExisting: Int,
    val favoritesNew: Int,
    val favoritesExisting: Int,
    val followsNew: Int,
    val followsExisting: Int,
)

fun previewLocalImport(
    backup: LocalDataBackup,
    historyIDs: Set<String>,
    favoriteIDs: Set<String>,
    followIDs: Set<String>,
): LocalImportPreview = LocalImportPreview(
    historyNew = backup.history.count { it.seriesID !in historyIDs },
    historyExisting = backup.history.count { it.seriesID in historyIDs },
    favoritesNew = backup.favorites.count { it.seriesID !in favoriteIDs },
    favoritesExisting = backup.favorites.count { it.seriesID in favoriteIDs },
    followsNew = backup.follows.count { it.id !in followIDs },
    followsExisting = backup.follows.count { it.id in followIDs },
)
