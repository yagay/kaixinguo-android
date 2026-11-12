// SPDX-License-Identifier: GPL-3.0-only
// Kaixinguo integration adapter. See NOTICE-THIRD-PARTY.md.
package com.hongguo.account.protocol

import org.json.JSONObject
import java.io.IOException

/** App and website credentials are not interchangeable. */
enum class AccountCredentialSource { APP, WEBSITE }

/** Never print or log this object; its cookie and device fields are sensitive. */
data class AccountCredentials(
    val cookie: String,
    val deviceId: String,
    val installId: String,
    val device: Map<String, String> = emptyMap(),
    val source: AccountCredentialSource = AccountCredentialSource.APP,
) {
    override fun toString(): String = "AccountCredentials([redacted])"
}

internal open class AccountProtocolException(
    message: String,
    val errorCode: Int? = null,
    val requiresLogin: Boolean = false,
    val emptyResponse: Boolean = false,
) : IOException(message)

internal fun JSONObject.str(key: String): String =
    if (isNull(key)) "" else opt(key)?.toString()?.trim().orEmpty()

/** Keep 16+ digit JSON identifiers lossless on Android's JSONTokener. */
internal fun preserveLargeAccountIntegers(raw: String): String {
    val out = StringBuilder(raw.length)
    var index = 0
    var quoted = false
    var escaped = false
    while (index < raw.length) {
        val c = raw[index]
        if (quoted) {
            out.append(c)
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (c == '"') quoted = false
            index++
        } else if (c == '"') {
            out.append(c)
            quoted = true
            index++
        } else if (c == '-' || c in '0'..'9') {
            val start = index++
            while (index < raw.length && (raw[index] in '0'..'9' || raw[index] in ".eE+-"))
                index++
            val number = raw.substring(start, index)
            if (number.matches(Regex("[0-9]{16,}")))
                out.append('"').append(number).append('"')
            else out.append(number)
        } else {
            out.append(c)
            index++
        }
    }
    return out.toString()
}

internal fun parseObject(body: String): JSONObject = try {
    JSONObject(preserveLargeAccountIntegers(body))
} catch (_: Exception) {
    throw AccountProtocolException("账号接口响应格式异常")
}
