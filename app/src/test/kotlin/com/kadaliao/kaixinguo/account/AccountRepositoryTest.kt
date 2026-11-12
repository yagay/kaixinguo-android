package com.kadaliao.kaixinguo.account

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRepositoryTest {
    private class MemoryStore(
        var session: StoredWebsiteSession? = null,
    ) : AccountPersistence {
        override fun load() = session
        override fun save(session: StoredWebsiteSession) { this.session = session }
        override fun clear() { session = null }
    }

    private class Gateway(
        private val verifyBlock: (String) -> WebsiteProfile,
    ) : WebsiteAccountGateway {
        override fun verify(cookie: String): WebsiteProfile = verifyBlock(cookie)
    }

    @Test
    fun successfulWebsiteAuthenticationIsSavedButNotCalledCloudAuth() = runTest {
        val store = MemoryStore()
        val repo = AccountRepository(
            store,
            Gateway { WebsiteProfile("已验证", "web-only") },
            this,
            StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        assertEquals(AccountPhase.SIGNED_OUT, repo.state.value.phase)
        repo.signInWithOfficialWebsite("sessionid=sample")
        advanceUntilIdle()
        assertEquals(AccountPhase.WEBSITE_SIGNED_IN, repo.state.value.phase)
        assertTrue(repo.state.value.websiteSignedIn)
        assertNotNull(store.session)
        assertTrue(repo.state.value.message.contains("云端"))
    }

    @Test
    fun logoutInvalidatesAnInFlightLogin() = runTest {
        val store = MemoryStore()
        val repo = AccountRepository(
            store, Gateway { WebsiteProfile("late") },
            this, StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        repo.signInWithOfficialWebsite("sessionid=sample")
        repo.signOut()
        advanceUntilIdle()
        assertEquals(AccountPhase.SIGNED_OUT, repo.state.value.phase)
        assertNull(store.session)
    }

    @Test
    fun transientNetworkFailureKeepsSavedSessionEncryptedAtRest() = runTest {
        val store = MemoryStore(StoredWebsiteSession("sessionid=sample", "old", "", 1))
        val repo = AccountRepository(
            store, Gateway { throw IOException("Offline") },
            this, StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        assertEquals(AccountPhase.UNAVAILABLE, repo.state.value.phase)
        assertTrue(repo.state.value.hasSavedSession)
        assertNotNull(store.session)
    }

    @Test
    fun rejectedWebsiteLoginCannotBecomeVerified() = runTest {
        val store = MemoryStore()
        val repo = AccountRepository(
            store, Gateway { throw WebsiteAuthException() },
            this, StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        repo.signInWithOfficialWebsite("sessionid=sample")
        advanceUntilIdle()
        assertEquals(AccountPhase.EXPIRED, repo.state.value.phase)
        assertFalse(repo.state.value.websiteSignedIn)
        assertNull(store.session)
    }

    @Test
    fun failedReloginPreservesPreviousEncryptedSession() = runTest {
        val old = StoredWebsiteSession("sessionid=old", "原账号", "old-id", 1)
        val store = MemoryStore(old)
        var allow = true
        val repo = AccountRepository(
            store,
            Gateway {
                if (!allow) throw WebsiteAuthException()
                WebsiteProfile("原账号", "old-id")
            },
            this,
            StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        assertEquals(AccountPhase.WEBSITE_SIGNED_IN, repo.state.value.phase)
        allow = false
        repo.signInWithOfficialWebsite("sessionid=new")
        advanceUntilIdle()
        assertEquals(AccountPhase.EXPIRED, repo.state.value.phase)
        assertTrue(repo.state.value.hasSavedSession)
        assertEquals("sessionid=old", store.session?.cookie)
    }

    @Test
    fun profileParserRejectsExplicitSignedOutStatusEvenWithUserId() {
        val raw = JSONObject(
            """{"message":"success","data":{"has_login":false,"user_info":{"user_id":"42"}}}""",
        )
        val result = runCatching { parseWebsiteProfile(raw) }
        assertTrue(result.exceptionOrNull() is WebsiteAuthException)
    }

    @Test
    fun profileParserAcceptsAffirmativeStatusAndName() {
        val raw = JSONObject(
            """{"message":"success","data":{"has_login":true,"user_info":{"user_id":"42","nickname":"红果用户"}}}""",
        )
        val profile = parseWebsiteProfile(raw)
        assertEquals("红果用户", profile.nickname)
        assertEquals("42", profile.websiteUserId)
    }
}
