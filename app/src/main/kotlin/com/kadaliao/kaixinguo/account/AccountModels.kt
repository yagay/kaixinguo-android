package com.kadaliao.kaixinguo.account

/** Website authentication is not the same as a verified Hongguo app business session. */
enum class AccountPhase {
    SIGNED_OUT, RESTORING, VERIFYING, WEBSITE_SIGNED_IN, UNAVAILABLE, EXPIRED,
}

data class WebsiteProfile(val nickname: String, val websiteUserId: String = "")

data class AccountUiState(
    val phase: AccountPhase = AccountPhase.SIGNED_OUT,
    val profile: WebsiteProfile? = null,
    val hasSavedSession: Boolean = false,
    val message: String = "",
    val verifiedAtEpochMs: Long = 0L,
    val sessionRevision: Long = 0L,
) {
    val websiteSignedIn: Boolean get() = phase == AccountPhase.WEBSITE_SIGNED_IN
}

/** Redacted on purpose. Never include login credentials in exceptions or diagnostics. */
internal class StoredWebsiteSession(
    val cookie: String,
    val nickname: String,
    val websiteUserId: String,
    val verifiedAtEpochMs: Long,
) {
    override fun toString(): String = "StoredWebsiteSession([redacted])"
}

/**
 * Future integration boundary. The anonymous openhongguo SDK cannot implement
 * these operations, so no UI claims that account cloud data is connected.
 */
interface CloudAccountPort {
    suspend fun accountHistory(): List<CloudWatch>
    suspend fun accountFavorites(): List<CloudSeries>
    suspend fun accountLikes(): List<CloudSeries>
    suspend fun updateProgress(seriesId: String, episode: Int, positionMs: Long): Boolean
}

data class CloudSeries(val seriesId: String, val title: String, val cover: String)
data class CloudWatch(val series: CloudSeries, val episode: Int, val positionMs: Long)

/** Website records are read-only and never merged with anonymous App records. */
data class WebsiteHistoryUiState(
    val records: List<CloudWatch> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String = "",
)


/**
 * Only App gateway-confirmed business sessions can advertise a connected
 * account feed. Website login alone is insufficient.
 */
enum class AccountRecommendationPhase {
    IDLE, CHECKING, READY, UNVERIFIED, SIGNATURE_REQUIRED, EXPIRED, RATE_LIMITED, UNAVAILABLE,
}

data class AccountRecommendationState(
    val phase: AccountRecommendationPhase = AccountRecommendationPhase.IDLE,
    val attempt: Int = 0,
    val message: String = "",
) {
    val ready: Boolean get() = phase == AccountRecommendationPhase.READY
    val blocked: Boolean get() = phase in setOf(
        AccountRecommendationPhase.UNVERIFIED,
        AccountRecommendationPhase.SIGNATURE_REQUIRED,
        AccountRecommendationPhase.EXPIRED,
        AccountRecommendationPhase.RATE_LIMITED,
        AccountRecommendationPhase.UNAVAILABLE,
    )
}
