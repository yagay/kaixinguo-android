package com.kadaliao.kaixinguo.account

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * App Passport transport boundary. This implementation is independent of the
 * GPL-licensed Hongguo Android signer. It cannot send until a legitimate,
 * compatible signer/device identity is supplied.
 *
 * In particular, never send unsigned requests or claim a website cookie is
 * an App identity. No SMS or session is written to a log or file here.
 */
internal interface AppPassportSigner {
    val deviceId: String
    val installId: String
    fun sign(endpoint: String, method: String, parameters: Map<String, String>,
             body: ByteArray?): AppPassportRequest
}

internal class AppPassportRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
)

internal data class AppPassportResponse(
    val status: Int,
    val json: String,
    val setCookies: List<String> = emptyList(),
)

internal fun interface AppPassportTransport {
    fun exchange(request: AppPassportRequest): AppPassportResponse
}

internal class AppPassportHttp : AppPassportTransport {
    override fun exchange(request: AppPassportRequest): AppPassportResponse {
        val url = URL(request.url)
        if (url.protocol != "https" || url.host != "security.snssdk.com" ||
            url.port !in listOf(-1, 443) || url.toURI().userInfo != null ||
            url.path !in AppSmsLogin.allowedEndpoints) throw IOException("拒绝非官方 App Passport 接口")
        if (request.method !in setOf("GET", "POST") || (request.method == "GET" && request.body != null))
            throw IOException("不支持的短信请求格式")
        val connection = url.openConnection() as HttpsURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = request.method
        request.headers.forEach { (key, value) ->
            if (key.matches(Regex("[a-zA-Z0-9_-]{1,80}")) &&
                !key.equals("cookie", true) && value.length <= 4096 &&
                value.none { it == '\r' || it == '\n' }) {
                connection.setRequestProperty(key, value)
            }
        }
        if (request.body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.outputStream.use { it.write(request.body) }
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw PassportHttpException(status)
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val chunk = ByteArray(4096)
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    if (bytes.size() + n > 512_000) throw IOException("短信认证响应过大")
                    bytes.write(chunk, 0, n)
                }
            }
            return AppPassportResponse(status, bytes.toString("UTF-8"),
                connection.headerFields.orEmpty().entries.filter { it.key.equals("Set-Cookie", true) }
                    .flatMap { it.value.orEmpty() })
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * One session per attempt: SMS cooldown and one-time code rules live above
 * this layer too, so a network timeout never silently resends a text.
 */
internal class AppSmsLogin(
    private val signer: AppPassportSigner,
    private val transport: AppPassportTransport = AppPassportHttp(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var mobile = ""
    private var ticket = ""
    private var sent = false
    private var retryAt = 0L
    private val submitted = mutableSetOf<String>()
    private val cookies = linkedMapOf<String, String>()

    data class Receipt(val masked: String, val retryAt: Long)
    class Rejection(val errorCode: Int, val retryCode: Boolean, val requiresChallenge: Boolean,
                    message: String) : IOException(message)
    data class Session(val cookies: String, val deviceId: String, val installId: String) {
        override fun toString(): String = "AppSession([redacted])"
    }

    fun cancel() {
        sent = false
        mobile = ""
        ticket = ""
        cookies.clear()
        submitted.clear()
    }

    fun send(number: String): Receipt {
        require(signer.deviceId.matches(Regex("[0-9]{1,30}")) &&
            signer.installId.matches(Regex("[0-9]{1,30}"))) { "App 设备尚未有效注册" }
        if (clock() < retryAt) throw IOException("请等待验证码发送倒计时结束")
        val normalized = WebPassportClient.phone(number)
        require(normalized.startsWith("+86 ")) { "目前 App 短信协议仅验证了中国大陆手机号，请使用官网登录其他地区号码" }
        mobile = normalized.removePrefix("+86 ")
        ticket = ""
        sent = false
        cookies.clear()
        submitted.clear()
        val data = call(SEND, mapOf("mobile" to mobile, "type" to "24"), false)
        ticket = data.optString("mobile_ticket").take(1024)
        retryAt = clock() + data.optInt("retry_time", 60).coerceIn(30, 3600) * 1000L
        sent = true
        return Receipt(mobile.take(3) + "****" + mobile.takeLast(4), retryAt)
    }

    fun verify(code: String): Session {
        check(sent) { "请先发送验证码" }
        require(code.matches(Regex("[0-9]{4,8}"))) { "验证码格式无效" }
        if (!submitted.add(code)) throw IOException("同一个验证码已提交过")
        sent = false
        var rejectedSession = false
        for ((endpoint, post) in VERIFY_ENDPOINTS) {
            val values = linkedMapOf("mobile" to mobile, "code" to code,
                "type" to "24", "mobile_ticket" to ticket)
            val response = try { call(endpoint, values, post) }
            catch (rejection: Rejection) {
                if (rejection.errorCode == 1) {
                    rejectedSession = true
                    continue
                }
                sent = rejection.retryCode
                throw rejection
            }
            if (cookies["sessionid"].isNullOrBlank() && cookies["sessionid_ss"].isNullOrBlank()) {
                response.optString("mobile_ticket").takeIf { it.isNotBlank() }?.let { ticket = it.take(1024) }
                continue
            }
            return Session(cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" },
                signer.deviceId, signer.installId)
        }
        throw IOException(if (rejectedSession) "App 登录会话过期，请重新获取验证码"
            else "平台没有返回已登录会话，请使用网页登录")
    }

    private fun call(endpoint: String, values: Map<String, String>, form: Boolean): JSONObject {
        val parameters = linkedMapOf("aid" to "8662", "app_name" to "novelread",
            "device_platform" to "android").apply { putAll(values) }
        val body = if (form) parameters.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }.toByteArray(Charsets.UTF_8) else null
        val request = signer.sign("https://security.snssdk.com$endpoint",
            if (form) "POST" else "GET", parameters, body)
        // Fail closed if the signing provider changes the target, method or
        // body, instead of exposing OTPs or device credentials elsewhere.
        val url = runCatching { URL(request.url) }.getOrNull()
        if (url == null || url.protocol != "https" || url.host != "security.snssdk.com" ||
            url.port !in listOf(-1, 443) || url.path != endpoint || url.toURI().userInfo != null ||
            request.method != (if (form) "POST" else "GET") ||
            !(request.body contentEqualsNullable body)) throw IOException("App 请求签名未通过本地校验")
        val received = transport.exchange(request)
        for (line in received.setCookies) {
            val name = line.substringBefore('=').trim()
            val token = line.substringBefore(';').substringAfter('=', "").trim()
            if (name.matches(Regex("[a-zA-Z0-9_-]{1,80}")) &&
                token.length in 1..4096 && token.none { it.code < 32 }) cookies[name] = token
        }
        val reply = try { JSONObject(received.json) }
        catch (_: Exception) { throw IOException("App 短信响应格式异常") }
        val data = reply.optJSONObject("data") ?: JSONObject()
        val code = data.optInt("error_code", reply.optInt("error_code", 0))
        val challenge = data.has("verify_center_decision_conf") ||
            reply.has("verify_center_decision_conf") || data.optString("captcha").isNotBlank()
        if (challenge || reply.optString("message") != "success" || code != 0) {
            val retryCode = code in setOf(1102, 1202) && !challenge
            val message = when {
                challenge || code == 1105 -> "平台要求手动完成验证，请在官网登录窗口操作"
                code in setOf(1102, 1202) -> "验证码错误，请重新输入"
                code in setOf(1103, 1104, 1203) -> "验证码已失效，请重新获取"
                code in setOf(1106, 1107, 1206) -> "发送或登录过于频繁，请稍后重试"
                code == 2046 -> "App 设备未通过平台认证，请通过官网完成登录"
                else -> "红果 App 认证接口未接受本次请求（错误码 $code）"
            }
            throw Rejection(code, retryCode, challenge || code == 1105, message)
        }
        return data
    }

    companion object {
        private const val SEND = "/passport/mobile/send_code/v1/"
        private val VERIFY_ENDPOINTS = listOf(
            "/passport/mobile/sms_login/" to true,
            "/passport/mobile/sms_login_only/" to true,
            "/passport/mobile/sms_login_continue/" to true,
            "/passport/mobile/validate_code/v1/" to false,
        )
        val allowedEndpoints: Set<String> = (listOf(SEND) + VERIFY_ENDPOINTS.map { it.first }).toSet()
    }
}

private infix fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null
    else this.contentEquals(other)
