package com.kadaliao.kaixinguo.account

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRecommendationContractTest {
    private val appProfile = JSONObject(
        """{"code":0,"data":{"user_info":{"biz_user_id":"9223372036854775807"}}}"""
    )

    @Test fun websiteCookieAloneIsNeverBusinessIdentity() {
        assertTrue(runCatching { parseBusinessIdentity(appProfile, null) }.isFailure)
        assertTrue(runCatching { parseBusinessIdentity(appProfile, "0") }.isFailure)
        assertEquals("9223372036854775807", parseBusinessIdentity(appProfile, "1").id)
    }

    @Test fun gatewayMustReturnNonemptyBusinessId() {
        val profile = JSONObject("""{"code":0,"data":{"user_info":{"biz_user_id":""}}}""")
        assertTrue(runCatching { parseBusinessIdentity(profile, "1") }.isFailure)
    }

    @Test fun malformedCursorIsRejectedWithoutGuessingPagination() {
        assertTrue(runCatching { AccountRecommendCursor.parse("""{"v":1,"offset":-1}""") }.isFailure)
        assertTrue(runCatching { AccountRecommendCursor.parse("""{"v":1,"offset":0,"cell":{"id":"abc","plan":"1","tag":"x"}}""") }.isFailure)
    }

    @Test fun verifiedCellFlowUsesServerNextOffsetDespiteFalseHasMore() {
        val cursor = AccountRecommendCursor(
            session = "prior",
            flow = AccountRecommendCursor.Cell("100", "200", "v1"),
        )
        val response = JSONObject(
            """{"data":{"has_more":false,"next_offset":6,"session_id":"next","cell_view":{"cell_data":[{"video_data":[{"video_detail":{"series_id":"1234567890123456789","series_title":"示例短剧","series_cover":"https://example.com/cover.jpg"}}]}]}}}"""
        )
        val page = parseAccountRecommendationPage(response, cursor)
        assertTrue(page.hasMore)
        assertEquals(1, page.cards.size)
        assertEquals("1234567890123456789", page.cards.single().id)
        val next = AccountRecommendCursor.parse(page.cursor)
        assertEquals(6, next.offset)
        assertEquals("next", next.session)
    }

    @Test fun cursorStopsWhenOffsetExceedsBound() {
        val cursor = AccountRecommendCursor(
            flow = AccountRecommendCursor.Cell("1", "2", "v"),
            offset = 600,
        )
        val response = JSONObject(
            """{"data":{"next_offset":606,"cell_view":{"cell_data":[{"video_data":[{"series_id":"100001","series_title":"示例"}]}]}}}"""
        )
        val page = parseAccountRecommendationPage(response, cursor)
        assertFalse(page.hasMore)
    }
}
