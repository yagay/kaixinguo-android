package com.kadaliao.kaixinguo.account

import com.hongguo.account.protocol.parseObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignedAppRecommendationsTest {
    private val session = StoredWebsiteSession(
        cookie = "sessionid=app-account",
        nickname = "用户",
        websiteUserId = "300001",
        verifiedAtEpochMs = 1234,
        source = SessionSource.APP,
        deviceId = "12345",
        installId = "67890",
    )

    private val profile = object : AppProfileGateway {
        override fun verify(cookie: String, deviceId: String, installId: String): WebsiteProfile {
            assertEquals("sessionid=app-account", cookie)
            assertEquals("12345", deviceId)
            assertEquals("67890", installId)
            return WebsiteProfile("用户", "300001")
        }
    }

    private val response = JSONObject(
        """{"code":0,"data":{"tab_item":[{"tab_type":16,"session_id":"page-1","bottom_unlimited":false,"cell_data":[{"cell_data":[{"video_data":[{"video_detail":{"series_id":"400001","series_title":"真实账号短剧","series_cover":"https://example.com/cover.jpg","episode_cnt":12}}]}]}]}]}}""",
    )

    @Test fun appBusinessIdentityIsRequiredBeforeFetchingItsOwnFeed() {
        var requests = 0
        val port = SignedAppRecommendations(profile, object : AppRecommendationHttp {
            override fun get(session: StoredWebsiteSession, path: String, query: Map<String, String>): AccountHttpReply {
                requests++
                assertEquals(SessionSource.APP, session.source)
                assertEquals("/reading/bookapi/bookmall/tab/v1/", path)
                assertEquals("16", query["tab_type"])
                assertEquals("7", query["bottom_tab_type"])
                return AccountHttpReply(200, null, response)
            }
        })
        val identity = port.verifyBusinessIdentity(session)
        val page = port.page(session, "", identity)
        assertEquals("300001", identity.id)
        assertEquals(1, requests)
        assertEquals(listOf("400001"), page.cards.map { it.id })
        assertEquals("账号推荐", page.cards[0].sub)
        assertEquals("page-1", AccountRecommendCursor.parse(page.cursor).session)
    }

    @Test fun largeNumericSeriesIdStaysExactInAccountRecommendations() {
        val raw = """{"code":0,"data":{"tab_item":[{"tab_type":16,"session_id":"x","cell_data":[{"cell_data":[{"video_data":[{"video_detail":{"series_id":9223372036854775808,"series_title":"长编号短剧"}}]}]}]}]}}"""
        val body = parseObject(raw)
        val page = parseAccountRecommendationPage(body, AccountRecommendCursor())
        assertEquals("9223372036854775808", page.cards.single().id)
    }

    @Test fun websiteSessionDoesNotGainAppRecommendationPrivileges() {
        val port = SignedAppRecommendations(profile, object : AppRecommendationHttp {
            override fun get(session: StoredWebsiteSession, path: String, query: Map<String, String>) =
                error("must not send a website cookie as App auth")
        })
        val website = StoredWebsiteSession("sessionid=web", "游客", "300001", 1)
        assertTrue(runCatching { port.verifyBusinessIdentity(website) }.isFailure)
        assertTrue(runCatching { port.page(website, "", BusinessIdentity("300001")) }.isFailure)
    }

    @Test fun wrongBusinessIdentityCannotFetchAnotherAccountsFeed() {
        val port = SignedAppRecommendations(profile, object : AppRecommendationHttp {
            override fun get(session: StoredWebsiteSession, path: String, query: Map<String, String>) =
                error("identity changed")
        })
        assertTrue(runCatching { port.page(session, "", BusinessIdentity("300002")) }.isFailure)
    }

    @Test fun anonymousGatewaySignalNeverClaimsPersonalizedFeed() {
        val port = SignedAppRecommendations(profile, object : AppRecommendationHttp {
            override fun get(session: StoredWebsiteSession, path: String, query: Map<String, String>) =
                AccountHttpReply(200, "0", response)
        })
        assertTrue(runCatching { port.page(session, "", BusinessIdentity("300001")) }.isFailure)
    }

    @Test fun appLoginStateNowCallsTheAccountSource() = runTest {
        var calls = 0
        val store = object : AccountPersistence {
            private var current: StoredWebsiteSession? = session
            override fun load(): StoredWebsiteSession? = current
            override fun save(session: StoredWebsiteSession) { current = session }
            override fun clear() { current = null }
        }
        val appPort = object : AppRecommendationPort {
            override fun verifyBusinessIdentity(session: StoredWebsiteSession): BusinessIdentity {
                calls++
                return BusinessIdentity(session.websiteUserId)
            }
            override fun page(session: StoredWebsiteSession, cursor: String, identity: BusinessIdentity): AccountRecommendPage {
                calls++
                return parseAccountRecommendationPage(response, AccountRecommendCursor.parse(cursor))
            }
        }
        val repo = AccountRepository(
            store, object : WebsiteAccountGateway {
                override fun verify(cookie: String): WebsiteProfile = error("website gateway called for App session")
            }, this, StandardTestDispatcher(testScheduler),
            appGateway = profile, appRecommendationPort = appPort,
        )
        advanceUntilIdle()
        assertTrue(repo.state.value.appSignedIn)
        assertFalse(repo.state.value.websiteSignedIn)
        val page = repo.fetchAccountRecommendations("")
        assertEquals(2, calls)
        assertEquals(listOf("400001"), page.cards.map { it.id })
        assertTrue(repo.recommendations.value.ready)
        repo.signOut()
        assertFalse(repo.state.value.signedIn)
        assertTrue(runCatching { repo.fetchAccountRecommendations("") }.isFailure)
    }
}
