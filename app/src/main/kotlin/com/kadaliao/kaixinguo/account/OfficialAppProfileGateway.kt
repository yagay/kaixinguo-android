package com.kadaliao.kaixinguo.account

import com.hongguo.account.protocol.ProtocolTransport
import com.hongguo.account.protocol.Signer
import com.hongguo.account.protocol.VendorConstants
import com.hongguo.account.protocol.cloudResponse
import com.hongguo.account.protocol.parseObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Verify App business identity; a session cookie alone is not a logged-in account. */
internal interface AppProfileGateway {
    fun verify(cookie: String, deviceId: String, installId: String): WebsiteProfile
}

internal class OfficialAppProfileGateway(
    private val transport: ProtocolTransport = ProtocolTransport(OkHttpClient()),
) : AppProfileGateway {
    override fun verify(cookie: String, deviceId: String, installId: String): WebsiteProfile {
        require(cookie.length in 1..16384 && cookie.none { it.code < 32 || it.code == 127 })
        require(deviceId.matches(Regex("[0-9]{1,30}")) && installId.matches(Regex("[0-9]{1,30}")))
        require(Regex("(?:^|;\\s*)sessionid(?:_ss)?=[^;\\s]+").containsMatchIn(cookie))
        val device = LinkedHashMap(VendorConstants.device).apply {
            put("device_id", deviceId)
            put("iid", installId)
            for (field in listOf("version_code", "manifest_version_code", "update_version_code", "pv_player"))
                put(field, "72232")
            put("version_name", "7.2.2.32")
        }
        val url = "https://api5-normal-sinfonlinea.fqnovel.com/reading/user/info/v1/"
        val signed = Signer(device = device).sign(url, device)
        val request = Request.Builder().url(signed.url).header("Cookie", cookie)
        signed.headers.forEach { (name, value) -> request.header(name, value) }
        return transport.execute(request.build()) { response ->
            val gateway = response.header("X-Tt-Agw-Login")
            val reply = parseObject(cloudResponse(response))
            if (reply.optInt("code", -1) != 0) throw IOException("红果账号资料未通过验证")
            val data = reply.optJSONObject("data") ?: throw IOException("红果账号没有返回业务资料")
            val id = data.opt("biz_user_id")?.toString().orEmpty().trim()
            if (!validBusinessId(id) || id == deviceId || id == installId ||
                (!id.matches(Regex("[0-9]{1,30}")) && gateway != "1")) {
                throw IOException("红果 App 尚未确认业务账号身份；可先使用官方 App 登录一次后重试")
            }
            val name = data.optString("user_name").takeIf { it.isNotBlank() && it.length <= 120 }
                ?: "红果账号"
            WebsiteProfile(name, id.take(128))
        }
    }
}
