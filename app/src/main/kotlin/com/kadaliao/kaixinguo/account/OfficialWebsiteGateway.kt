package com.kadaliao.kaixinguo.account

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Only verifies the user's official website session. No guessed app account ID. */
internal interface WebsiteAccountGateway {
    @Throws(IOException::class)
    fun verify(cookie: String): WebsiteProfile
}

internal class WebsiteAuthException : IOException("Official website session was not authenticated")

internal class OfficialWebsiteGateway : WebsiteAccountGateway {
    override fun verify(cookie: String): WebsiteProfile {
        check(cookie.length in 1..16384 && cookie.none { it.code < 32 || it.code == 127 }) {
            "Invalid session"
        }
        check(Regex("(?:^|;\\s*)sessionid(?:_ss)?=[^;\\s]+").containsMatchIn(cookie)) {
            "Missing website session"
        }
        val url = URL(
            "https://hongguoduanju.com/passport/account/info/v2/" +
                "?aid=513755&passport_jssdk_version=3.4.10&passport_jssdk_type=normal" +
                "&account_sdk_source=web&is_from_ttaccountsdk=1&language=zh",
        )
        val conn = url.openConnection() as HttpsURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 12_000
        conn.instanceFollowRedirects = false
        conn.requestMethod = "GET"
        conn.setRequestProperty("Cookie", cookie)
        conn.setRequestProperty("Accept", "application/json, text/javascript")
        conn.setRequestProperty("Origin", "https://hongguoduanju.com")
        conn.setRequestProperty("Referer", "https://hongguoduanju.com/")
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        )
        Regex("(?:^|;\\s*)passport_csrf_token=([^;]+)").find(cookie)?.groupValues?.get(1)
            ?.let { conn.setRequestProperty("x-tt-passport-csrf-token", it) }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                code == HttpURLConnection.HTTP_FORBIDDEN) throw WebsiteAuthException()
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Official website returned HTTP " + code)
            val bytes = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (bytes.size() + n > 65536) throw IOException("Website response is too large")
                    bytes.write(buf, 0, n)
                }
            }
            return parseWebsiteProfile(JSONObject(bytes.toString(Charsets.UTF_8.name())))

        } catch (error: org.json.JSONException) {
            throw IOException("Official website response format changed")
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * A pure, testable boundary: a public nickname or HTTP 200 is not enough to
 * claim authentication. The website must explicitly affirm login or provide
 * an authenticated user identity in a successful passport response.
 */
internal fun parseWebsiteProfile(reply: JSONObject): WebsiteProfile {
    if (reply.optString("message") != "success") throw WebsiteAuthException()
    val data = reply.optJSONObject("data") ?: throw WebsiteAuthException()
    val user = data.optJSONObject("user_info") ?: data.optJSONObject("user") ?: data
    val flag = data.opt("has_login")
    val signedIn = flag == true || flag == 1 || flag == "1" || flag == "true"
    val id = sequenceOf(
        user.optString("user_id"), user.optString("uid"), user.optString("id"),
    ).firstOrNull { it.isNotBlank() && it != "0" }.orEmpty()
    if (flag != null && !signedIn) throw WebsiteAuthException()
    if (!signedIn && id.isBlank()) throw WebsiteAuthException()
    val name = sequenceOf(
        user.optString("nickname"), user.optString("name"), data.optString("nickname"),
    ).firstOrNull { it.isNotBlank() }.orEmpty().take(120)
    return WebsiteProfile(name.ifBlank { "红果官网用户" }, id.take(128))
}
