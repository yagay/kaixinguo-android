package com.kadaliao.kaixinguo.account

import com.kadaliao.kaixinguo.model.Card
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * Account-authenticated recommendation *experiment* using the actual Hongguo
 * App feed routes. We require an affirmative App gateway identity AND a
 * business-user ID before displaying any result as an account feed.
 *
 * Unlike a generic cookie-backed feed, this rejects anonymous fallback.
 * It never sends website credentials to the anonymous SDK or to other hosts.
 * It does not report impressions, clicks, watching activity or upload history.
 *
 * The registered-device request signer is NOT implemented in this module.
 * If the platform rejects unsigned requests, return an explicit unavailable
 * state rather than falsely calling anonymous results personalized.
 */
internal interface AccountRecommendationPort {
    @Throws(IOException::class)
    fun verifyBusinessIdentity(websiteCookie: String): BusinessIdentity

    @Throws(IOException::class)
    fun page(websiteCookie: String, cursor: String, identity: BusinessIdentity): AccountRecommendPage
}

internal data class BusinessIdentity(val id: String) {
    init { require(validBusinessId(id)) }
    override fun toString(): String = "BusinessIdentity([redacted])"
}

internal class AccountRecommendationException(
    val kind: Kind,
) : IOException(
    when (kind) {
        Kind.UNVERIFIED -> "红果 App 没有确认该会话的业务账号身份"
        Kind.SIGNATURE_REQUIRED -> "红果 App 接口要求注册设备及有效签名"
        Kind.EXPIRED -> "红果账号会话已失效"
        Kind.RATE_LIMITED -> "红果账号请求过于频繁，请稍后再试"
        Kind.RESPONSE_CHANGED -> "账号推荐接口返回结构发生变化"
        Kind.NETWORK -> "账号推荐网络连接失败"
    },
) {
    enum class Kind { UNVERIFIED, SIGNATURE_REQUIRED, EXPIRED, RATE_LIMITED, RESPONSE_CHANGED, NETWORK }
}

/** Cursor belongs to one verified account AND one particular recommendation session. */
internal data class AccountRecommendCursor(
    val session: String = "",
    val offset: Int = 0,
    val flow: Cell? = null,
    val recent: List<String> = emptyList(),
) {
    data class Cell(val cellId: String, val planId: String, val versionTag: String)

    fun encode(): String = JSONObject().apply {
        put("v", 1)
        put("session", session)
        put("offset", offset)
        flow?.let {
            put("cell", JSONObject().put("id", it.cellId).put("plan", it.planId).put("tag", it.versionTag))
        }
        put("recent", JSONArray(recent.takeLast(80)))
    }.toString()

    companion object {
        fun parse(raw: String): AccountRecommendCursor {
            if (raw.isBlank()) return AccountRecommendCursor()
            if (raw.length > 12000) throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
            val json = try { JSONObject(raw) } catch (_: Exception) {
                throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
            }
            if (json.optInt("v") != 1) throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
            val session = json.optString("session")
            val offset = json.optInt("offset", -1)
            if (!validCursorText(session, 4096) || offset !in 0..1000000) {
                throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
            }
            val flow = json.optJSONObject("cell")?.let {
                val id = it.optString("id")
                val plan = it.optString("plan")
                val tag = it.optString("tag")
                if (!id.matches(Regex("[0-9]{1,30}")) || !plan.matches(Regex("[0-9]{1,30}")) ||
                    !validCursorText(tag, 4096)) {
                    throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
                }
                Cell(id, plan, tag)
            }
            val recentJson = json.optJSONArray("recent") ?: JSONArray()
            val seen = (0 until minOf(recentJson.length(), 80))
                .map { recentJson.optString(it) }
                .filter { it.matches(Regex("[0-9]{1,30}")) }.distinct()
            return AccountRecommendCursor(session, offset, flow, seen)
        }
    }
}

internal data class AccountRecommendPage(
    val cards: List<Card>,
    val cursor: String,
    val hasMore: Boolean,
)

private fun validCursorText(text: String, max: Int): Boolean =
    text.length <= max && text.none { it.code < 32 || it.code == 127 }

internal fun validBusinessId(id: String): Boolean =
    id.length in 1..1024 && id != "0" && id != "null" && id.isNotBlank() &&
        id.none { it.isWhitespace() || it.isISOControl() || it.isSurrogate() }

/**
 * The header comes from the App gateway, not from the public website login.
 * Code 0, a public nickname, or a registered device are never identity proof.
 */
internal fun parseBusinessIdentity(body: JSONObject, gatewayHeader: String?): BusinessIdentity {
    val code = body.optInt("code", -1)
    if (code != 0) throw businessFailure(code)
    if (gatewayHeader != "1") {
        throw AccountRecommendationException(AccountRecommendationException.Kind.UNVERIFIED)
    }
    val data = body.optJSONObject("data")
        ?: throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
    val user = data.optJSONObject("user_info") ?: data
    val raw = user.opt("biz_user_id")
    val id = if (raw is String) raw else ""
    if (!validBusinessId(id)) {
        throw AccountRecommendationException(AccountRecommendationException.Kind.UNVERIFIED)
    }
    return BusinessIdentity(id)
}

private fun businessFailure(code: Int): AccountRecommendationException =
    AccountRecommendationException(
        when (code) {
            13, 1003, 401, 403 -> AccountRecommendationException.Kind.EXPIRED
            110 -> AccountRecommendationException.Kind.SIGNATURE_REQUIRED
            429, 1206 -> AccountRecommendationException.Kind.RATE_LIMITED
            else -> AccountRecommendationException.Kind.UNVERIFIED
        },
    )

internal data class AccountHttpReply(val status: Int, val gatewayLogin: String?, val body: JSONObject)

/** Fakeable gateway transport; never log response bodies or raw URLs. */
internal fun interface AccountRecommendationTransport {
    fun get(relativePath: String, query: Map<String, String>, cookie: String): AccountHttpReply
}

internal class HttpsAccountRecommendationTransport : AccountRecommendationTransport {
    override fun get(
        relativePath: String,
        query: Map<String, String>,
        cookie: String,
    ): AccountHttpReply {
        if (relativePath !in setOf(PROFILE, TAB, CELL)) {
            throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
        }
        if (cookie.length !in 1..16384 || cookie.any { it.code < 32 || it.code == 127 } ||
            !Regex("(?:^|;\\s*)sessionid(?:_ss)?=[^;\\s]+").containsMatchIn(cookie)) {
            throw AccountRecommendationException(AccountRecommendationException.Kind.EXPIRED)
        }
        val args = query.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
        val url = URL("$HOST$relativePath?$args")
        val conn = url.openConnection() as HttpsURLConnection
        conn.connectTimeout = 14000
        conn.readTimeout = 14000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Cookie", cookie)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-XS-From-Web", "0")
        conn.setRequestProperty("Sdk-Version", "2")
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                throw AccountRecommendationException(AccountRecommendationException.Kind.EXPIRED)
            }
            if (code == 429) throw AccountRecommendationException(AccountRecommendationException.Kind.RATE_LIMITED)
            if (code != 200) throw AccountRecommendationException(AccountRecommendationException.Kind.NETWORK)
            val out = ByteArrayOutputStream()
            conn.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    if (out.size() + size > 16 * 1024 * 1024) {
                        throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
                    }
                    out.write(buffer, 0, size)
                }
            }
            val json = try { JSONObject(out.toString("UTF-8")) } catch (_: Exception) {
                throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
            }
            AccountHttpReply(code, conn.getHeaderField("X-Tt-Agw-Login")?.trim(), json)
        } catch (e: AccountRecommendationException) {
            throw e
        } catch (_: IOException) {
            throw AccountRecommendationException(AccountRecommendationException.Kind.NETWORK)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val HOST = "https://api5-normal-sinfonlinea.fqnovel.com"
        private const val PROFILE = "/reading/user/info/v1/"
        private const val TAB = "/reading/bookapi/bookmall/tab/v1/"
        private const val CELL = "/reading/bookapi/bookmall/cell/change/v1/"
        private const val USER_AGENT =
            "com.dragon.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C)"
        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
    }
}

/**
 * Account recommendation request protocol implemented independently from
 * GPL reference projects. Does not copy their signer or profile classes.
 */
internal class OfficialAccountRecommendations(
    private val transport: AccountRecommendationTransport = HttpsAccountRecommendationTransport(),
) : AccountRecommendationPort {
    override fun verifyBusinessIdentity(websiteCookie: String): BusinessIdentity {
        val reply = transport.get(
            "/reading/user/info/v1/",
            commonParams() + mapOf("version_code" to "73532", "version_name" to "7.3.5.32"),
            websiteCookie,
        )
        return parseBusinessIdentity(reply.body, reply.gatewayLogin)
    }

    override fun page(
        websiteCookie: String, cursor: String, identity: BusinessIdentity,
    ): AccountRecommendPage {
        val state = AccountRecommendCursor.parse(cursor)
        if (state.offset > 600 && state.flow == null) {
            throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
        }
        val params = commonParams().toMutableMap().apply {
            put("version_code", "73532")
            put("version_name", "7.3.5.32")
            put("manifest_version_code", "73532")
            put("update_version_code", "73532")
            put("tab_type", "16")
            put("bottom_tab_type", "7")
            put("landing_bottom_tab_type", "7")
            put("offset", state.offset.toString())
            put("client_req_type", if (state.offset == 0 && state.flow == null) "1" else "2")
            put("_rticket", System.currentTimeMillis().toString())
            if (state.session.isNotBlank()) put("session_id", state.session)
            state.flow?.let { flow ->
                put("cell_id", flow.cellId)
                put("plan_id", flow.planId)
                put("version_tag", flow.versionTag)
                put("client_template", "7")
                put("change_type", "0")
                put("client_req_type", "2")
                put("filter_ids", state.recent.takeLast(80).joinToString(","))
            }
        }
        val path = if (state.flow == null)
            "/reading/bookapi/bookmall/tab/v1/" else "/reading/bookapi/bookmall/cell/change/v1/"
        val reply = transport.get(path, params, websiteCookie)
        if (reply.body.optInt("code", -1) != 0) {
            throw businessFailure(reply.body.optInt("code", -1))
        }
        // The same gateway signal must remain true for *every* page.
        if (reply.gatewayLogin != "1") {
            throw AccountRecommendationException(AccountRecommendationException.Kind.UNVERIFIED)
        }
        return parseAccountRecommendationPage(reply.body, state)
    }

    companion object {
        private fun commonParams() = linkedMapOf(
            "aid" to "8662",
            "app_name" to "novelread",
            "channel" to "update_64",
            "device_platform" to "android",
            "os" to "android",
            "ssmix" to "a",
            "device_type" to "25053RT47C",
            "device_brand" to "Redmi",
            "language" to "zh",
            "os_api" to "36",
            "os_version" to "16",
            "resolution" to "1280*2772",
            "dpi" to "520",
            "ac" to "wifi",
        )
    }
}

/** Official single-column card grammar, with strict cursor advancement. */
internal fun parseAccountRecommendationPage(
    root: JSONObject,
    previous: AccountRecommendCursor,
): AccountRecommendPage {
    val data = root.optJSONObject("data")
        ?: throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
    val accountTab: JSONObject
    val rawCards: List<JSONObject>
    val session: String
    val nextFlow: AccountRecommendCursor.Cell?
    val nextOffset: Int

    if (previous.flow == null) {
        val tabs = data.optJSONArray("tab_item")
            ?: throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
        val chosen = (0 until tabs.length()).mapNotNull { tabs.optJSONObject(it) }
            .firstOrNull { it.opt("tab_type")?.toString() == "16" }
            ?: throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
        accountTab = chosen
        val cells = chosen.optJSONArray("cell_data")
            ?: throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
        rawCards = collectVideoCards(cells)
        session = chosen.optString("session_id").ifBlank { previous.session }
        nextFlow = if (chosen.optBoolean("bottom_unlimited") &&
            chosen.optInt("client_template") == 7) {
            val firstCell = cells.optJSONObject(0)
            val cell = firstCell?.stringID("cell_id_str", "cell_id").orEmpty()
            val plan = chosen.stringID("bookstore_id")
            val tag = chosen.optString("version_tag")
            if (cell.matches(Regex("[0-9]{1,30}")) &&
                plan.matches(Regex("[0-9]{1,30}")) && validCursorText(tag, 4096)) {
                AccountRecommendCursor.Cell(cell, plan, tag)
            } else null
        } else null
        nextOffset = if (nextFlow != null) 0 else previous.offset + rawCards.size.coerceAtLeast(1)
    } else {
        val view = data.optJSONObject("cell_view")
            ?: throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
        accountTab = view
        val cells = view.optJSONArray("cell_data")
            ?: JSONArray().put(view)
        rawCards = collectVideoCards(cells)
        session = data.optString("session_id").ifBlank { previous.session }
        nextFlow = previous.flow
        val candidate = data.optLong("next_offset", -1)
        if (candidate <= previous.offset || candidate > 1000000) {
            throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
        }
        nextOffset = candidate.toInt()
    }
    if (!validCursorText(session, 4096) || nextOffset !in 0..1000000) {
        throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
    }
    val candidates = rawCards.mapNotNull { row ->
        val details = row.optJSONObject("video_detail") ?: row
        val id = details.stringID("series_id", "book_id_str", "book_id")
        val title = details.stringValue("series_title", "book_name", "title")
        val cover = details.stringValue("series_cover", "cover", "cover_url")
        val episodes = details.optInt("episode_cnt", details.optInt("episode_count", 0))
        if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) null
        else Card(
            id = id, title = title.take(200),
            cover = cover.takeIf { it.startsWith("https://") }.orEmpty().take(2048),
            sub = "账号推荐", episodes = episodes.coerceIn(0, 100000),
        )
    }.distinctBy { it.id }
    val filtered = candidates.filter { it.id !in previous.recent }
    if (filtered.isEmpty()) {
        throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
    }
    val seen = (previous.recent + candidates.map { it.id }).takeLast(80)
    val next = AccountRecommendCursor(session, nextOffset, nextFlow, seen)
    // Official single-column cell/change may return has_more=false while still
    // advancing a valid next_offset. Bound the flow rather than ending early.
    val hasMore = nextOffset <= 600 && (nextFlow != null || data.optBoolean("has_more", true))
    return AccountRecommendPage(filtered, next.encode(), hasMore)
}

private fun collectVideoCards(cells: JSONArray): List<JSONObject> {
    val cards = ArrayList<JSONObject>()
    fun consume(row: JSONObject) {
        row.optJSONArray("video_data")?.let { videos ->
            for (i in 0 until minOf(videos.length(), 1000 - cards.size)) {
                videos.optJSONObject(i)?.let { cards.add(it) }
            }
        }
    }
    for (i in 0 until minOf(cells.length(), 100)) {
        val cell = cells.optJSONObject(i) ?: continue
        consume(cell)
        val children = cell.optJSONArray("cell_data")
        if (children != null) {
            for (j in 0 until minOf(children.length(), 100)) {
                children.optJSONObject(j)?.let(::consume)
            }
        }
    }
    return cards
}

private fun JSONObject.stringID(vararg keys: String): String {
    for (key in keys) {
        when (val value = opt(key)) {
            is String -> if (value.isNotBlank()) return value
            is Int -> if (value > 0) return value.toString()
            is Long -> if (value > 0L) return value.toString()
        }
    }
    return ""
}
private fun JSONObject.stringValue(vararg keys: String): String {
    for (key in keys) {
        val value = opt(key)
        if (value is String && value.isNotBlank()) return value
    }
    return ""
}
