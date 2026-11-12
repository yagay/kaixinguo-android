package com.kadaliao.kaixinguo.account

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** A read-only official *website* API, not the signed Android app cloud API. */
internal interface WebsiteHistoryGateway {
    fun readHistory(cookie: String): List<CloudWatch>
}

internal class OfficialWebsiteHistoryGateway : WebsiteHistoryGateway {
    override fun readHistory(cookie: String): List<CloudWatch> {
        require(cookie.length in 1..16384 && cookie.none { it.code < 32 || it.code == 127 }) {
            "Invalid website session"
        }
        val conn = URL("https://hongguoduanju.com/api/pc/watch-history")
            .openConnection() as HttpsURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Cookie", cookie)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Referer", "https://hongguoduanju.com/")
        conn.setRequestProperty("User-Agent", WebPassportClient.Companion.run {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        })
        try {
            val status = conn.responseCode
            if (status == 401 || status == 403) throw WebsiteAuthException()
            if (status != 200) throw IOException("官网历史接口不可用（HTTP " + status + "）")
            val data = ByteArrayOutputStream()
            conn.inputStream.use { stream ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (data.size() + n > 1024 * 1024) throw IOException("官网历史返回数据过大")
                    data.write(buf, 0, n)
                }
            }
            val reply = try { JSONObject(data.toString("UTF-8")) }
                catch (_: Exception) { throw IOException("官网历史返回格式发生变化") }
            return parseWebsiteHistory(reply)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Conservative parser. Never treat unknown JSON or a guest envelope as an
 * empty account history. 19-digit identifiers must remain strings.
 */
internal fun parseWebsiteHistory(response: JSONObject): List<CloudWatch> {
    val code = response.opt("code")
    if (code != null && code !== JSONObject.NULL &&
        code.toString() !in setOf("0", "200")) throw IOException("官网历史请求被拒绝")
    val message = response.optString("message")
    if (message.isNotBlank() && message !in listOf("success", "ok", "OK")) {
        throw IOException("官网历史请求没有成功")
    }
    val payload = response.optJSONObject("data")
    val explicitLogin = payload?.opt("has_login") ?: response.opt("has_login")
    if (explicitLogin == false || explicitLogin == 0 || explicitLogin == "0")
        throw WebsiteAuthException()
    val rows = findHistoryArray(payload, response)
        ?: throw IOException("官网历史接口返回了未知的列表结构")
    if (rows.length() > 10000) throw IOException("历史记录数量超出单次读取限制")
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        if (row.optBoolean("is_delete")) return@mapNotNull null
        val series = row.optJSONObject("series") ?: row.optJSONObject("book") ?: row
        val id = series.stringId("series_id", "book_id_str", "book_id", "id")
            .ifBlank { row.stringId("series_id", "book_id_str", "book_id", "id") }
        if (!id.matches(Regex("[0-9]{1,30}"))) return@mapNotNull null
        val title = series.stringField("title", "series_title", "book_name", "name")
            .ifBlank { row.stringField("title", "series_title", "book_name") }
            .ifBlank { "剧集" }
        val cover = series.stringField("cover", "cover_url", "thumb_url", "series_cover_url")
            .takeIf { it.startsWith("https://") }.orEmpty()
        val episode = row.optInt("vid_index", 0).takeIf { it > 0 }
            ?: row.optInt("episode", 0).coerceAtLeast(1)
        val millis = row.optLong("position_ms", 0).takeIf { it > 0 }
            ?: row.optLong("current_play_time", 0).coerceAtLeast(0)
        CloudWatch(
            CloudSeries(id, title.take(180), cover),
            episode.coerceIn(1, 100000), millis.coerceIn(0, 24L * 3600 * 1000),
        )
    }.distinctBy { it.series.seriesId }
}

private fun findHistoryArray(data: JSONObject?, envelope: JSONObject): JSONArray? {
    val names = listOf("data_list", "history", "history_list", "watch_history",
        "watch_history_list", "list", "items", "records")
    for (source in listOfNotNull(data, envelope)) {
        for (name in names) source.optJSONArray(name)?.let { return it }
        source.optJSONObject("data")?.let { nested ->
            for (name in names) nested.optJSONArray(name)?.let { return it }
        }
    }
    return null
}

private fun JSONObject.stringField(vararg keys: String): String {
    for (key in keys) {
        if (isNull(key)) continue
        (opt(key) as? String)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    }
    return ""
}

private fun JSONObject.stringId(vararg keys: String): String {
    for (key in keys) {
        val value = opt(key)
        if (value is String && value.matches(Regex("[0-9]{1,30}"))) return value
        if (value is Long && value > 0) return value.toString()
        if (value is Int && value > 0) return value.toString()
    }
    return ""
}
