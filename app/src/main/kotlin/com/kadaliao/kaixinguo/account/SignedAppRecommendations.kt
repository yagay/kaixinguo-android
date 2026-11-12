package com.kadaliao.kaixinguo.account

import com.hongguo.account.protocol.AccountProtocolException
import com.hongguo.account.protocol.ProtocolTransport
import com.hongguo.account.protocol.Signer
import com.hongguo.account.protocol.VendorConstants
import com.hongguo.account.protocol.cloudResponse
import com.hongguo.account.protocol.parseObject
import com.kadaliao.kaixinguo.diagnostics.Diagnostics
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Account recommendation traffic stays completely separate from the anonymous
 * openhongguo SDK, player, local collections and disk feed cache.
 */
internal interface AppRecommendationPort {
    fun verifyBusinessIdentity(session: StoredWebsiteSession): BusinessIdentity
    fun page(session: StoredWebsiteSession, cursor: String, identity: BusinessIdentity): AccountRecommendPage
}

internal interface AppRecommendationHttp {
    fun get(session: StoredWebsiteSession, path: String, query: Map<String, String>): AccountHttpReply
}

/**
 * Use the verified App cookie with the Hongguo App bookmall endpoints.
 *
 * Signed requests may be answered with the official code 110 for an App
 * account's device pair. As in the upstream Hongguo recommendation client,
 * retry that precise rejection using the same account cookie without signing.
 * Never send anonymous cookies, retry an OTP, or claim that a guest page
 * proves an authenticated account.
 */
internal class SignedAppRecommendationHttp(
    private val transport: ProtocolTransport = ProtocolTransport(OkHttpClient()),
) : AppRecommendationHttp {
    override fun get(session: StoredWebsiteSession, path: String, query: Map<String, String>): AccountHttpReply {
        require(session.source == SessionSource.APP)
        require(session.deviceId.matches(Regex("[1-9][0-9]{0,29}")) &&
            session.installId.matches(Regex("[1-9][0-9]{0,29}")))
        require(session.cookie.length in 1..16384 && session.cookie.none { it.code < 32 || it.code == 127 })
        if (!Regex("(?:^|;\\s*)sessionid(?:_ss)?=[^;\\s]+").containsMatchIn(session.cookie))
            throw AccountRecommendationException(AccountRecommendationException.Kind.EXPIRED)
        if (path !in setOf(TAB, CELL)) throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)

        val device = LinkedHashMap(VendorConstants.device).apply {
            put("device_id", session.deviceId)
            put("iid", session.installId)
            put("version_code", "74332")
            put("version_name", "7.4.3.32")
            put("manifest_version_code", "74332")
            put("update_version_code", "74332")
            put("pv_player", "74332")
        }
        val appCookie = if (Regex("(?:^|;\\s*)install_id=").containsMatchIn(session.cookie)) session.cookie
            else session.cookie + "; install_id=" + session.installId

        var rejection: AccountRecommendationException? = null
        for (host in HOSTS) {
            var unsigned = false
            for (attempt in 0..1) {
                Diagnostics.record("account_recommendations",
                    if (unsigned) "app_cookie_retry" else "app_signed_request")
                val request = if (!unsigned) {
                    val params = linkedMapOf<String, String>().apply {
                        putAll(device)
                        putAll(query)
                        put("version_code", "74332")
                        put("version_name", "7.4.3.32")
                        put("manifest_version_code", "74332")
                        put("update_version_code", "74332")
                        put("pv_player", "74332")
                        remove("_rticket")
                    }
                    val signed = Signer(device = device).sign(host + path, params)
                    Request.Builder().url(signed.url).header("Cookie", appCookie).apply {
                        signed.headers.forEach { (name, value) -> header(name, value) }
                    }.build()
                } else {
                    val params = linkedMapOf<String, String>().apply {
                        putAll(query)
                        put("version_code", "73532")
                        put("version_name", "7.3.5.32")
                        put("manifest_version_code", "73532")
                        put("update_version_code", "73532")
                    }
                    val search = params.entries.joinToString("&") {
                        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
                    }
                    Request.Builder().url(host + path + "?" + search)
                        .header("Cookie", appCookie)
                        .header("User-Agent", "com.dragon.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C)")
                        .header("Accept", "application/json")
                        .header("X-XS-From-Web", "0")
                        .header("Sdk-Version", "2")
                        .build()
                }
                val response = try {
                    transport.execute(request) {
                        AccountHttpReply(it.code, it.header("X-Tt-Agw-Login"),
                            parseObject(cloudResponse(it, 16 * 1024 * 1024)))
                    }
                } catch (e: AccountProtocolException) {
                    val kind = when (e.errorCode) {
                        401, 403 -> AccountRecommendationException.Kind.EXPIRED
                        429 -> AccountRecommendationException.Kind.RATE_LIMITED
                        else -> AccountRecommendationException.Kind.NETWORK
                    }
                    throw AccountRecommendationException(kind)
                } catch (_: IOException) {
                    // No anonymous downgrade on network errors or empty replies.
                    throw AccountRecommendationException(AccountRecommendationException.Kind.NETWORK)
                } catch (_: Exception) {
                    throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
                }
                val code = response.body.optInt("code", -1)
                if (!unsigned && code == 110) {
                    Diagnostics.record("account_recommendations", "app_signing_rejected_110")
                    unsigned = true
                    continue
                }
                if (response.gatewayLogin == "0")
                    throw AccountRecommendationException(AccountRecommendationException.Kind.UNVERIFIED)
                if (code == 0) {
                    Diagnostics.record("account_recommendations",
                        if (unsigned) "app_cookie_page_received" else "app_signed_page_received")
                    return response
                }
                rejection = when (code) {
                    110 -> AccountRecommendationException(AccountRecommendationException.Kind.SIGNATURE_REQUIRED)
                    13, 1003, 401, 403 -> AccountRecommendationException(AccountRecommendationException.Kind.EXPIRED)
                    429, 1206 -> AccountRecommendationException(AccountRecommendationException.Kind.RATE_LIMITED)
                    else -> AccountRecommendationException(AccountRecommendationException.Kind.NETWORK)
                }
                break
            }
        }
        throw rejection ?: AccountRecommendationException(AccountRecommendationException.Kind.NETWORK)
    }

    companion object {
        private const val TAB = "/reading/bookapi/bookmall/tab/v1/"
        private const val CELL = "/reading/bookapi/bookmall/cell/change/v1/"
        private val HOSTS = listOf(
            "https://api5-normal-sinfonlinea.fqnovel.com",
            "https://api5-normal-hl.fqnovel.com",
            "https://api5-normal-lf.fqnovel.com",
        )
    }
}

internal class SignedAppRecommendations(
    private val gateway: AppProfileGateway = OfficialAppProfileGateway(),
    private val http: AppRecommendationHttp = SignedAppRecommendationHttp(),
) : AppRecommendationPort {
    override fun verifyBusinessIdentity(session: StoredWebsiteSession): BusinessIdentity {
        if (session.source != SessionSource.APP) throw AccountRecommendationException(AccountRecommendationException.Kind.UNVERIFIED)
        // Verify the business profile again for a restored or rotated login.
        val verified = try { gateway.verify(session.cookie, session.deviceId, session.installId) }
        catch (_: Exception) { throw AccountRecommendationException(AccountRecommendationException.Kind.UNVERIFIED) }
        if (!validBusinessId(verified.websiteUserId) || verified.websiteUserId != session.websiteUserId)
            throw AccountRecommendationException(AccountRecommendationException.Kind.UNVERIFIED)
        return BusinessIdentity(verified.websiteUserId)
    }

    override fun page(session: StoredWebsiteSession, cursor: String, identity: BusinessIdentity): AccountRecommendPage {
        if (session.source != SessionSource.APP || identity.id != session.websiteUserId)
            throw AccountRecommendationException(AccountRecommendationException.Kind.UNVERIFIED)
        val state = AccountRecommendCursor.parse(cursor)
        if (state.offset > 600) throw AccountRecommendationException(AccountRecommendationException.Kind.RESPONSE_CHANGED)
        val query = linkedMapOf(
            "aid" to "8662", "app_name" to "novelread", "channel" to "update_64",
            "device_platform" to "android", "os" to "android", "ssmix" to "a",
            "device_type" to "25053RT47C", "device_brand" to "Redmi",
            "language" to "zh", "os_api" to "36", "os_version" to "16",
            "resolution" to "1280*2772", "dpi" to "520", "ac" to "wifi",
            "version_code" to "74332", "version_name" to "7.4.3.32",
            "manifest_version_code" to "74332", "update_version_code" to "74332",
            "tab_type" to "16", "bottom_tab_type" to "7", "landing_bottom_tab_type" to "7",
            "offset" to state.offset.toString(),
            "client_req_type" to if (state.flow == null && state.offset == 0) "1" else "2",
        )
        state.session.takeIf { it.isNotBlank() }?.let { query["session_id"] = it }
        state.flow?.let {
            query["cell_id"] = it.cellId
            query["plan_id"] = it.planId
            query["version_tag"] = it.versionTag
            query["client_template"] = "7"
            query["change_type"] = "0"
            query["client_req_type"] = "2"
            query["filter_ids"] = state.recent.takeLast(80).joinToString(",")
        }
        val path = if (state.flow == null) "/reading/bookapi/bookmall/tab/v1/"
            else "/reading/bookapi/bookmall/cell/change/v1/"
        val reply = http.get(session, path, query)
        if (reply.gatewayLogin == "0") throw AccountRecommendationException(AccountRecommendationException.Kind.UNVERIFIED)
        if (reply.body.optInt("code", -1) != 0) throw AccountRecommendationException(AccountRecommendationException.Kind.UNVERIFIED)
        // The account was verified via the App profile. Not every valid
        // feed response supplies X-Tt-Agw-Login; unlike a guest cookie, this
        // session has already passed the signed profile identity check.
        return parseAccountRecommendationPage(reply.body, state)
    }
}
