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
    private val historyGateway: WebsiteHistoryGateway = OfficialWebsiteHistoryGateway(),
    private val recommendationPort: AccountRecommendationPort = OfficialAccountRecommendations(),
) {
    constructor(context: Context) : this(
        AccountStore(context), OfficialWebsiteGateway(),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val guard = Any()
    private var generation = 0L
    private var recommendationEpoch = 0L
    private val mutable = MutableStateFlow(AccountUiState(phase = AccountPhase.RESTORING))
    val state: StateFlow<AccountUiState> = mutable.asStateFlow()
    private var recommendationProof: BusinessIdentity? = null
    private val recommendationMutable = MutableStateFlow(AccountRecommendationState())
    val recommendations: StateFlow<AccountRecommendationState> = recommendationMutable.asStateFlow()
    private val historyMutable = MutableStateFlow(WebsiteHistoryUiState())
    val websiteHistory: StateFlow<WebsiteHistoryUiState> = historyMutable.asStateFlow()

    init {
        restore()
    }

    fun restore() {
        val token = synchronized(guard) { ++generation }
        mutable.value = AccountUiState(phase = AccountPhase.RESTORING, sessionRevision = token)
        resetRecommendations()
        historyMutable.value = WebsiteHistoryUiState()
        scope.launch {
            val saved = try {
                withContext(io) { synchronized(guard) { store.load() } }
            } catch (_: Exception) {
                if (isCurrent(token)) mutable.value = AccountUiState(
                    phase = AccountPhase.UNAVAILABLE,
                    message = "已保存的账号资料无法解密。可退出账号后重新登录。",
                    hasSavedSession = true,
                    sessionRevision = token,
                )
                Diagnostics.record("account", "restore_failed")
                return@launch
            }
            if (!isCurrent(token)) return@launch
            if (saved == null) {
                mutable.value = AccountUiState(sessionRevision = token)
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
        historyMutable.value = WebsiteHistoryUiState()
        resetRecommendations()
        mutable.value = AccountUiState(
            phase = AccountPhase.VERIFYING,
            sessionRevision = token,
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
                    verifiedAtEpochMs = saved.verifiedAtEpochMs,
                    sessionRevision = token,
                )
                Diagnostics.record("account", "website_verified")
            } catch (_: WebsiteAuthException) {
                if (isCurrent(token)) mutable.value = AccountUiState(
                    phase = AccountPhase.EXPIRED,
                    hasSavedSession = hadSavedSession,
                    sessionRevision = token,
                    message = "官网未确认登录，请重新打开官网登录页面完成验证",
                )
                Diagnostics.record("account", "website_rejected")
            } catch (_: Exception) {
                if (isCurrent(token)) mutable.value = AccountUiState(
                    phase = AccountPhase.UNAVAILABLE,
                    hasSavedSession = hadSavedSession,
                    sessionRevision = token,
                    message = "官网账号核验暂不可用，请检查网络后重新登录",
                )
                Diagnostics.record("account", "website_verification_unavailable")
            }
        }
    }

    fun recheck() {
        restore()
    }

    private fun resetRecommendations() {
        synchronized(guard) {
            recommendationEpoch++
            recommendationProof = null
        }
        recommendationMutable.value = AccountRecommendationState()
    }

    /** Reset only the account recommendation attempt, not the logged-in session. */
    fun retryAccountRecommendations() {
        if (!mutable.value.websiteSignedIn) return
        val attempt = recommendationMutable.value.attempt + 1
        synchronized(guard) {
            recommendationEpoch++
            recommendationProof = null
        }
        recommendationMutable.value = AccountRecommendationState(attempt = attempt)
        Diagnostics.record("account_recommendations", "retry_requested")
    }

    /**
     * This is the only credentialed feed entrypoint. Cookies never leave the
     * repository and are never passed through Compose, SDK, logs or disk cache.
     */
    internal suspend fun fetchAccountRecommendations(cursor: String): AccountRecommendPage {
        val (token, epoch) = synchronized(guard) { generation to recommendationEpoch }
        if (!mutable.value.websiteSignedIn) {
            throw AccountRecommendationException(AccountRecommendationException.Kind.EXPIRED)
        }
        if (cursor.isEmpty()) {
            recommendationMutable.value = recommendationMutable.value.copy(
                phase = AccountRecommendationPhase.CHECKING,
                message = "正在确认红果 App 业务账号身份",
            )
        }
        Diagnostics.record("account_recommendations", if (cursor.isEmpty()) "first_page_requested" else "next_page_requested")
        try {
            val response = withContext(io) {
                val saved = synchronized(guard) {
                    if (generation == token && recommendationEpoch == epoch) store.load() else null
                } ?: throw AccountRecommendationException(AccountRecommendationException.Kind.EXPIRED)
                val proof = synchronized(guard) {
                    if (generation == token && recommendationEpoch == epoch) recommendationProof else null
                } ?: recommendationPort.verifyBusinessIdentity(saved.cookie).also {
                    synchronized(guard) {
                        if (generation == token && recommendationEpoch == epoch) recommendationProof = it
                    }
                }
                if (!isCurrentRecommendation(token, epoch)) throw CancellationException("Account changed")
                recommendationPort.page(saved.cookie, cursor, proof)
            }
            if (!isCurrentRecommendation(token, epoch) || !mutable.value.websiteSignedIn) {
                throw CancellationException("Account changed")
            }
            recommendationMutable.value = recommendationMutable.value.copy(
                phase = AccountRecommendationPhase.READY,
                message = "已取得红果 App 网关确认的账号推荐响应",
            )
            Diagnostics.record("account_recommendations", "verified_page_loaded")
            return response
        } catch (stop: CancellationException) {
            throw stop
        } catch (failure: Exception) {
            if (isCurrentRecommendation(token, epoch)) {
                val issue = failure as? AccountRecommendationException
                val phase = when (issue?.kind) {
                    AccountRecommendationException.Kind.SIGNATURE_REQUIRED ->
                        AccountRecommendationPhase.SIGNATURE_REQUIRED
                    AccountRecommendationException.Kind.EXPIRED ->
                        AccountRecommendationPhase.EXPIRED
                    AccountRecommendationException.Kind.RATE_LIMITED ->
                        AccountRecommendationPhase.RATE_LIMITED
                    AccountRecommendationException.Kind.UNVERIFIED ->
                        AccountRecommendationPhase.UNVERIFIED
                    else -> AccountRecommendationPhase.UNAVAILABLE
                }
                val message = issue?.message ?: "无法确认账号推荐接口，本次使用匿名推荐"
                recommendationMutable.value = recommendationMutable.value.copy(phase = phase, message = message)
                Diagnostics.record("account_recommendations", when (phase) {
                    AccountRecommendationPhase.SIGNATURE_REQUIRED -> "signing_required"
                    AccountRecommendationPhase.EXPIRED -> "session_expired"
                    AccountRecommendationPhase.RATE_LIMITED -> "rate_limited"
                    AccountRecommendationPhase.UNVERIFIED -> "identity_unverified"
                    else -> "network_or_format_unavailable"
                })
            }
            // This failure is handled by the Reel source selector. No fake
            // authenticated cards may be displayed.
            throw failure
        }
    }

    /** Read-only website history. It cannot grant access to the signed App cloud. */
    fun loadWebsiteHistory() {
        if (!mutable.value.websiteSignedIn) return
        val token = synchronized(guard) { generation }
        historyMutable.value = historyMutable.value.copy(loading = true, error = "")
        Diagnostics.record("account", "website_history_requested")
        scope.launch {
            try {
                val history = withContext(io) {
                    val saved = synchronized(guard) {
                        if (generation == token) store.load() else null
                    } ?: throw WebsiteAuthException()
                    historyGateway.readHistory(saved.cookie)
                }
                if (!isCurrent(token) || !mutable.value.websiteSignedIn) return@launch
                historyMutable.value = WebsiteHistoryUiState(
                    records = history, loading = false, loaded = true,
                )
                Diagnostics.record("account", "website_history_loaded")
            } catch (_: WebsiteAuthException) {
                if (isCurrent(token)) historyMutable.value = WebsiteHistoryUiState(
                    error = "官网历史访问未授权，请重新登录",
                )
                Diagnostics.record("account", "website_history_unauthorized")
            } catch (_: Exception) {
                if (isCurrent(token)) historyMutable.value = historyMutable.value.copy(
                    loading = false,
                    error = "官网历史接口暂不可用，或返回结构发生变化。不会影响本机观看历史。",
                )
                Diagnostics.record("account", "website_history_unavailable")
            }
        }
    }

    fun signOut() {
        val token = synchronized(guard) {
            generation++
            recommendationProof = null
            recommendationEpoch++
            store.clear()
            generation
        }
        recommendationMutable.value = AccountRecommendationState()
        mutable.value = AccountUiState(
            message = "已退出官网账号，本机收藏和历史未清除",
            sessionRevision = token,
        )
        historyMutable.value = WebsiteHistoryUiState()
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
                verifiedAtEpochMs = System.currentTimeMillis(),
                sessionRevision = token,
            )
            Diagnostics.record("account", "restored_verified")
        } catch (_: WebsiteAuthException) {
            if (!isCurrent(token)) return
            mutable.value = AccountUiState(
                phase = AccountPhase.EXPIRED, hasSavedSession = true,
                sessionRevision = token,
                message = "官网登录已失效，请重新登录",
            )
            Diagnostics.record("account", "restored_expired")
        } catch (_: Exception) {
            if (!isCurrent(token)) return
            mutable.value = AccountUiState(
                phase = AccountPhase.UNAVAILABLE, hasSavedSession = true,
                sessionRevision = token,
                message = "网络或账号接口暂不可用，已加密保留会话，可重新验证",
            )
            Diagnostics.record("account", "restore_network_unavailable")
        }
    }

    private fun isCurrent(token: Long): Boolean = synchronized(guard) { token == generation }

    private fun isCurrentRecommendation(token: Long, epoch: Long): Boolean =
        synchronized(guard) { token == generation && epoch == recommendationEpoch }
}
