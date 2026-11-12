package com.kadaliao.kaixinguo.account

import com.kadaliao.kaixinguo.model.Watch
import java.time.Instant

/**
 * Read-only comparison for website history vs on-device history.
 * Website history currently lacks a trustworthy updatedAt timestamp, so
 * conflicts are never resolved by a guessed "newer" value.
 */
data class HistoryComparison(
    val localOnly: Set<String>,
    val websiteOnly: Set<String>,
    val shared: Set<String>,
) {
    val totalDistinct: Int get() = localOnly.size + websiteOnly.size + shared.size
}

internal fun compareHistory(
    localIDs: Collection<String>,
    websiteIDs: Collection<String>,
): HistoryComparison {
    val left = localIDs.filter { it.isNotBlank() }.toSet()
    val right = websiteIDs.filter { it.isNotBlank() }.toSet()
    return HistoryComparison(left - right, right - left, left intersect right)
}

/**
 * Deterministic offline checkpoint conflict rules for a future verified
 * App sync bridge. Do not use to mutate real cloud data until credentials
 * and the relevant App endpoints are independently validated.
 */
data class SyncCheckpoint(
    val seriesId: String,
    val episode: Int,
    val positionMs: Long,
    val updatedAt: Instant,
)

enum class SyncDecision { LOCAL_NEWER, REMOTE_NEWER, IDENTICAL, NEEDS_REVIEW, UNKNOWN }

internal fun decideCheckpoint(local: SyncCheckpoint?, remote: SyncCheckpoint?): SyncDecision {
    if (local == null || remote == null) return SyncDecision.UNKNOWN
    if (local.seriesId != remote.seriesId || local.episode !in 1..100000 ||
        remote.episode !in 1..100000 || local.positionMs < 0 || remote.positionMs < 0) {
        return SyncDecision.NEEDS_REVIEW
    }
    if (local.updatedAt == Instant.EPOCH || remote.updatedAt == Instant.EPOCH) {
        return SyncDecision.NEEDS_REVIEW
    }
    return when {
        local.updatedAt > remote.updatedAt -> SyncDecision.LOCAL_NEWER
        local.updatedAt < remote.updatedAt -> SyncDecision.REMOTE_NEWER
        local.episode == remote.episode && local.positionMs == remote.positionMs ->
            SyncDecision.IDENTICAL
        else -> SyncDecision.NEEDS_REVIEW
    }
}
