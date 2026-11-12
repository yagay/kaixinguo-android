package com.kadaliao.kaixinguo.account

import android.content.Context
import com.kadaliao.kaixinguo.diagnostics.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns website authentication independently of browsing, local history,
 * Player and the anonymous openhongguo SDK.
 *
 * Generation fences protect against an old network response restoring an
 * account after logout or a newer login. Storage writes are synchronized.
 */
class AccountRepository internal constructor(
    private val store: AccountPersistence,
    private val gateway: WebsiteAccountGateway,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(
        AccountStore(context), OfficialWebsiteGateway(),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val guard = Any()
    private var generation = 0L
    private val mutable = MutableStateFlow(AccountUiState(phase = AccountPhase.RESTORING))
    val state: StateFlow<AccountUiState> = mutable.asStateFlow()

    init {
        restore()
    }

    fun restore() {
        val token = synchronized(guard) { ++generation }
        mutable.value = AccountUiState(phase = AccountPhase.RESTORING)
        scope.launch {
            val saved = try {
                withContext(io) { synchronized(guard) { store.load() } }
            } catch (_: Exception) {
                if (isCurrent(token)) mutable.value = AccountUiState(
                    phase = AccountPhase.UNAVAILABLE,
                    message = "已保存的账号资料无法解密。可退出账号后重新登录。",
                    hasSavedSession = true,
                )
                Diagnostics.record("account", "restore_failed")
                return@launch
            }
            if (!isCurrent(token)) return@launch
            if (saved == null) {
                mutable.value = AccountUiState()
                return@launch
            }
            verifySaved(saved, token)
        }
    }

    fun signInWithOfficialWebsite(cookie: String) {
        val hadSavedSession = mutable.value.hasSavedSession
        if (cookie.isBlank() || cookie.length > 16384) {
            mutable.value = mutable.value.copy(message = "没有取得有效的官网登录会话")
            return
        }
        val token = synchronized(guard) { ++generation }
        mutable.value = AccountUiState(
            phase = AccountPhase.VERIFYING,
            hasSavedSession = hadSavedSession,
            message = "正在核验官网账号…",
        )
        Diagnostics.record("account", "official_login_received")
        scope.launch {
            try {
                val profile = withContext(io) { gateway.verify(cookie) }
                if (!isCurrent(token)) return@launch
                val saved = StoredWebsiteSession(
                    cookie, profile.nickname, profile.websiteUserId, System.currentTimeMillis(),
                )
                val persisted = withContext(io) {
                    synchronized(guard) {
                        if (generation != token) false
                        else {
                            store.save(saved)
                            true
                        }
                    }
                }
                if (!persisted || !isCurrent(token)) return@launch
                mutable.value = AccountUiState(
                    phase = AccountPhase.WEBSITE_SIGNED_IN,
                    profile = profile,
                    hasSavedSession = true,
                    message = "官网账号已验证；红果 App 云端资料尚未连接",
                )
                Diagnostics.record("account", "website_verified")
            } catch (_: WebsiteAuthException) {
                if (isCurrent(token)) mutable.value = AccountUiState(
                    phase = AccountPhase.EXPIRED,
                    hasSavedSession = hadSavedSession,
                    message = "官网未确认登录，请重新打开官网登录页面完成验证",
                )
                Diagnostics.record("account", "website_rejected")
            } catch (_: Exception) {
                if (isCurrent(token)) mutable.value = AccountUiState(
                    phase = AccountPhase.UNAVAILABLE,
                    hasSavedSession = hadSavedSession,
                    message = "官网账号核验暂不可用，请检查网络后重新登录",
                )
                Diagnostics.record("account", "website_verification_unavailable")
            }
        }
    }

    fun recheck() {
        restore()
    }

    fun signOut() {
        synchronized(guard) {
            generation++
            store.clear()
        }
        mutable.value = AccountUiState(message = "已退出官网账号，本机收藏和历史未清除")
        Diagnostics.record("account", "signed_out")
    }

    private suspend fun verifySaved(saved: StoredWebsiteSession, token: Long) {
        try {
            val profile = withContext(io) { gateway.verify(saved.cookie) }
            if (!isCurrent(token)) return
            mutable.value = AccountUiState(
                phase = AccountPhase.WEBSITE_SIGNED_IN,
                profile = profile, hasSavedSession = true,
                message = "官网账号已恢复；云端收藏和历史尚未连接",
            )
            Diagnostics.record("account", "restored_verified")
        } catch (_: WebsiteAuthException) {
            if (!isCurrent(token)) return
            mutable.value = AccountUiState(
                phase = AccountPhase.EXPIRED, hasSavedSession = true,
                message = "官网登录已失效，请重新登录",
            )
            Diagnostics.record("account", "restored_expired")
        } catch (_: Exception) {
            if (!isCurrent(token)) return
            mutable.value = AccountUiState(
                phase = AccountPhase.UNAVAILABLE, hasSavedSession = true,
                message = "网络或账号接口暂不可用，已加密保留会话，可重新验证",
            )
            Diagnostics.record("account", "restore_network_unavailable")
        }
    }

    private fun isCurrent(token: Long): Boolean = synchronized(guard) { token == generation }
}
