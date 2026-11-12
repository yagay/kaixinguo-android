package com.kadaliao.kaixinguo.account

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * One-use, independently written official website passport client.
 * Never talks to non-official hosts, never bypasses security verification.
 * Only authenticates the *website* account; not an app business identity.
 */
internal class WebPassportClient {
    data class Challenge(val image: ByteArray, val expiresAt: Long)
    data class SmsReceipt(val masked: String, val retryAt: Long)
    enum class QrStatus { PENDING, SCANNED, CONFIRMED, EXPIRED }
    private data class Cookie(val value: String, val path: String, val expiresAt: Long)

    private val jar = linkedMapOf<String, Cookie>()
    private var token = ""
    private var mobile = ""
    private var sent = false
    private var retryAt = 0L
    private val usedCodes = mutableSetOf<String>()
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        jar.clear()
        token = ""
        mobile = ""
        sent = false
    }

    fun startQr(): Challenge {
        active()
        val result = checked(call("/passport/web/get_qrcode/", params = mapOf("next" to ORIGIN + "/")))
        token = result.optString("token").takeIf { it.length in 1..4096 }
            ?: throw IOException("二维码令牌无效")
        val encoded = result.optString("qrcode").substringAfter("base64,")
        if (encoded.length !in 1..700000) throw IOException("二维码图片大小异常")
        val image = try { Base64.decode(encoded, Base64.DEFAULT) }
        catch (_: Exception) { throw IOException("二维码图片格式异常") }
        if (image.isEmpty() || image.size > 512000) throw IOException("二维码图片无效")
        val expires = result.optLong("expire_time", 180)
        val seconds = if (expires > 1000000000L) expires - System.currentTimeMillis() / 1000
            else expires
        return Challenge(image, System.currentTimeMillis() + seconds.coerceIn(1, 300) * 1000L)
    }

    fun pollQr(): QrStatus {
        active()
        if (token.isBlank()) throw IOException("请先获取二维码")
        val reply = checked(call(
            "/passport/web/check_qrconnect/",
            params = mapOf("next" to ORIGIN + "/", "token" to token),
        ))
        return when (reply.optString("status").lowercase()) {
            "5", "expired" -> QrStatus.EXPIRED
            "2", "scanned" -> QrStatus.SCANNED
            "3", "confirmed" -> {
                val redirect = reply.optString("redirect_url")
                if (redirect.isNotBlank()) followRedirect(redirect)
                if (hasSession()) QrStatus.CONFIRMED else QrStatus.PENDING
            }
            else -> QrStatus.PENDING
        }
    }

    fun sendSms(number: String): SmsReceipt {
        active()
        if (System.currentTimeMillis() < retryAt) throw IOException("请等待发送倒计时结束")
        val parsed = phone(number)
        // Website security challenges are honored; no automatic workaround.
        if ("passport_csrf_token" !in jar) startQr()
        val result = checked(call("/passport/web/send_code/", form = mapOf(
            "mobile" to xorHex(parsed), "type" to xorHex("24"),
            "mix_mode" to "1", "fixed_mix_mode" to "1",
        )))
        mobile = parsed
        sent = true
        usedCodes.clear()
        val seconds = result.optInt("retry_time", 60).coerceIn(30, 3600)
        retryAt = System.currentTimeMillis() + seconds * 1000L
        val digits = parsed.substringAfter(' ')
        return SmsReceipt(digits.take(3) + "****" + digits.takeLast(4), retryAt)
    }

    fun verifySms(code: String): String {
        active()
        if (!sent || mobile.isBlank()) throw IOException("请先获取验证码")
        if (!code.matches(Regex("[0-9]{4,8}"))) throw IOException("请输入 4–8 位验证码")
        if (!usedCodes.add(code)) throw IOException("这条验证码已提交过")
        sent = false
        try {
            checked(call("/passport/web/sms_login/", form = mapOf(
                "mobile" to xorHex(mobile), "code" to xorHex(code),
                "login_only" to "true", "mix_mode" to "1", "fixed_mix_mode" to "1",
            )))
        } catch (e: PassportException) {
            if (e.code == 1102) sent = true
            throw e
        }
        return sessionCookie()
    }

    fun sessionCookie(): String {
        active()
        if (!hasSession()) throw IOException("官网尚未确认登录")
        return jar.entries.filter { it.value.expiresAt > System.currentTimeMillis() && it.value.path == "/" }
            .joinToString("; ") { it.key + "=" + it.value.value }
    }

    private fun hasSession(): Boolean = listOf("sessionid", "sessionid_ss").any {
        jar[it]?.let { c -> c.expiresAt > System.currentTimeMillis() && c.value.isNotEmpty() } ?: false
    }

    private fun followRedirect(raw: String) {
        var url = URL(URL(ORIGIN + "/"), raw)
        repeat(5) {
            val next = execute(url, null).second ?: return
            url = next
        }
        throw IOException("网页登录跳转次数超限")
    }

    private fun checked(json: JSONObject): JSONObject {
        val data = json.optJSONObject("data")
        if (json.optString("message") == "success" && data != null) return data
        val code = data?.optInt("error_code", -1) ?: -1
        throw PassportException(code, when {
            code == 1105 || data?.has("verify_center_decision_conf") == true ->
                "平台要求滑块或人工验证，请使用「网页内登录」"
            code == 1102 -> "验证码错误，请重新输入"
            code == 1103 || code == 1104 -> "验证码已过期"
            code == 1206 || code == 1106 -> "操作频繁，请稍后再试"
            code == 13 || code == 1003 -> "登录会话已过期"
            else -> "登录接口不可用（错误码 " + code + "）"
        })
    }

    private fun call(path: String, params: Map<String, String> = emptyMap(),
                     form: Map<String, String>? = null): JSONObject {
        val q = mapOf(
            "aid" to "513755", "passport_jssdk_version" to "3.4.10",
            "passport_jssdk_type" to "normal", "account_sdk_source" to "web",
            "is_from_ttaccountsdk" to "1", "language" to "zh",
        ) + params
        val suffix = q.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val (body, redirect) = execute(URL(ORIGIN + path + "?" + suffix), form)
        if (redirect != null) throw IOException("登录请求发生非预期跳转")
        return try { JSONObject(body) } catch (_: Exception) { throw IOException("登录响应格式异常") }
    }

    private fun execute(url: URL, form: Map<String, String>?): Pair<String, URL?> {
        active()
        if (!safe(url)) throw IOException("已拒绝非官方网站的登录跳转")
        val conn = url.openConnection() as HttpsURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Referer", ORIGIN + "/")
        conn.setRequestProperty("Origin", ORIGIN)
        conn.setRequestProperty("Accept", "application/json, text/javascript")
        val now = System.currentTimeMillis()
        val available = jar.filter { it.value.expiresAt > now && url.path.startsWith(it.value.path) }
        if (available.isNotEmpty()) {
            conn.setRequestProperty("Cookie", available.entries.joinToString("; ") {
                it.key + "=" + it.value.value
            })
        }
        jar["passport_csrf_token"]?.let {
            conn.setRequestProperty("x-tt-passport-csrf-token", it.value)
        }
        if (form != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val body = form.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        try {
            val status = conn.responseCode
            conn.headerFields.orEmpty().forEach { (key, values) ->
                if (key.equals("Set-Cookie", ignoreCase = true)) values.orEmpty().forEach(::acceptCookie)
            }
            active()
            if (status in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw IOException("登录跳转缺少地址")
                return "" to URL(url, location)
            }
            if (status !in 200..299) throw IOException("认证服务器暂不可用（HTTP " + status + "）")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { stream ->
                val bytes = ByteArray(4096)
                while (true) {
                    val n = stream.read(bytes)
                    if (n < 0) break
                    if (out.size() + n > 512000) throw IOException("认证响应过大")
                    out.write(bytes, 0, n)
                }
            }
            return out.toString("UTF-8") to null
        } finally { conn.disconnect() }
    }

    private fun acceptCookie(header: String) {
        val parts = header.split(';').map(String::trim)
        val name = parts.firstOrNull()?.substringBefore('=')?.trim().orEmpty()
        val value = parts.firstOrNull()?.substringAfter('=', "")?.trim().orEmpty()
        if (!name.matches(Regex("[a-zA-Z0-9_-]{1,80}")) ||
            value.length !in 1..8192 || value.any { it.code < 32 }) return
        var expires = System.currentTimeMillis() + 7200000L
        var path = "/"
        for (part in parts.drop(1)) {
            val key = part.substringBefore('=').trim().lowercase()
            val attr = part.substringAfter('=', "").trim()
            when (key) {
                "domain" -> if (attr.trimStart('.') != "hongguoduanju.com") return
                "path" -> path = attr
                "max-age" -> attr.toLongOrNull()?.let {
                    expires = System.currentTimeMillis() + it.coerceIn(0, 31536000) * 1000
                }
            }
        }
        if (!path.startsWith("/") || path.length > 128) return
        if (expires <= System.currentTimeMillis()) jar.remove(name)
        else jar[name] = Cookie(value, path, expires)
    }

    private fun active() { if (cancelled) throw IOException("登录操作已取消") }

    companion object {
        private const val ORIGIN = "https://hongguoduanju.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        internal fun phone(number: String): String {
            val text = number.trim()
            val digits = text.filter(Char::isDigit)
            if (text.startsWith("+")) {
                val chunks = text.drop(1).trim().split(Regex("\\s+"), limit = 2)
                require(chunks.size == 2 && chunks[0].length in 1..3 &&
                    chunks[1].filter(Char::isDigit).length in 5..13 &&
                    digits.length in 8..15) { "国际号码请填写 +国家代码 空格 手机号" }
                return "+" + chunks[0] + " " + chunks[1].filter(Char::isDigit)
            }
            require(digits.matches(Regex("1[3-9][0-9]{9}"))) { "请输入正确的11位手机号" }
            return "+86 " + digits
        }
        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
        internal fun xorHex(text: String): String = text.toByteArray(Charsets.UTF_8).joinToString("") {
            ((it.toInt() and 255) xor 5).toString(16).padStart(2, '0')
        }
        private fun safe(url: URL) = url.protocol == "https" && url.host == "hongguoduanju.com" &&
            url.userInfo == null && url.port in listOf(-1, 443)
    }
}

internal class PassportException(val code: Int, message: String) : IOException(message)
