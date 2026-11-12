package com.kadaliao.kaixinguo.account

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AccountCloudContractTest {
    @Test fun websiteHistoryParsesStringIdentifiersWithoutPrecisionLoss() {
        val id = "9223372036854775807"
        val json = JSONObject(
            """{"code":0,"data":{"list":[{"book_id_str":"9223372036854775807","book_name":"短剧甲","vid_index":7,"position_ms":18000}]}}""",
        )
        val watches = parseWebsiteHistory(json)
        assertEquals(1, watches.size)
        assertEquals(id, watches[0].series.seriesId)
        assertEquals("短剧甲", watches[0].series.title)
        assertEquals(7, watches[0].episode)
        assertEquals(18000, watches[0].positionMs)
    }

    @Test fun websiteHistoryRejectsExplicitGuestResponse() {
        val json = JSONObject(
            """{"code":0,"data":{"has_login":false,"list":[]}}""",
        )
        assertTrue(runCatching { parseWebsiteHistory(json) }.exceptionOrNull() is WebsiteAuthException)
    }

    @Test fun websiteHistoryRejectsUnknownResponseInsteadOfClaimingEmpty() {
        val unknown = JSONObject("""{"code":0,"data":{"notice":"not supported"}}""")
        assertTrue(runCatching { parseWebsiteHistory(unknown) }.exceptionOrNull() is IOException)
    }

    @Test fun websiteHistoryCanReturnActualEmptyArray() {
        val json = JSONObject("""{"code":0,"data":{"items":[]}}""")
        assertTrue(parseWebsiteHistory(json).isEmpty())
    }

    @Test fun websiteHistoryExcludesInvalidIds() {
        val json = JSONObject(
            """{"code":0,"data":{"history":[{"series_id":"wrong","title":"No"},{"series_id":"123456","title":"Yes"}]}}""",
        )
        val rows = parseWebsiteHistory(json)
        assertEquals(listOf("123456"), rows.map { it.series.seriesId })
    }

    @Test fun websiteSmsPhoneNormalizesMainlandAndInternational() {
        assertEquals("+86 13800138000", WebPassportClient.phone("13800138000"))
        assertEquals("+44 7123456789", WebPassportClient.phone("+44 7123456789"))
        assertTrue(runCatching { WebPassportClient.phone("+447123456789") }.isFailure)
    }

    @Test fun websiteSmsEncodingUsesByteLevelTransformation() {
        assertEquals("3531", WebPassportClient.xorHex("04"))
        assertFalse(WebPassportClient.xorHex("24").contains("24"))
    }
}
