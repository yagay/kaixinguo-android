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

internal fun parseObject(body: String): JSONObject = try {
    JSONObject(body)
} catch (_: Exception) {
    throw AccountProtocolException("账号接口响应格式异常")
}
