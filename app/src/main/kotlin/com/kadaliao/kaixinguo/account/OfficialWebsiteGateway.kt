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
            val reply = JSONObject(bytes.toString(Charsets.UTF_8.name()))
            if (reply.optString("message") != "success") throw WebsiteAuthException()
            val data = reply.optJSONObject("data") ?: throw WebsiteAuthException()
            val user = data.optJSONObject("user_info") ?: data.optJSONObject("user") ?: data
            val loginFlag = data.opt("has_login")
            val loggedIn = loginFlag == true || loginFlag == 1 ||
                loginFlag == "1" || loginFlag == "true"
            val userId = sequenceOf(
                user.optString("user_id"), user.optString("uid"), user.optString("id"),
            ).firstOrNull { it.isNotBlank() && it != "0" }.orEmpty()
            if (!loggedIn && userId.isBlank()) throw WebsiteAuthException()
            val nickname = sequenceOf(
                user.optString("nickname"), user.optString("name"),
                data.optString("nickname"),
            ).firstOrNull { it.isNotBlank() }.orEmpty().take(120)
            return WebsiteProfile(nickname.ifBlank { "红果官网用户" }, userId.take(128))
        } catch (error: org.json.JSONException) {
            throw IOException("Official website response format changed")
        } finally {
            conn.disconnect()
        }
    }
}
