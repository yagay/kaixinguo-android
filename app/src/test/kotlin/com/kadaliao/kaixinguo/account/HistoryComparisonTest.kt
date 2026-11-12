package com.kadaliao.kaixinguo.account

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryComparisonTest {
    @Test fun snapshotsDoNotConfuseLocalWithWebsiteHistory() {
        val result = compareHistory(
            listOf("101", "102", "102", "103"),
            listOf("102", "103", "104", "104"),
        )
        assertEquals(setOf("101"), result.localOnly)
        assertEquals(setOf("104"), result.websiteOnly)
        assertEquals(setOf("102", "103"), result.shared)
        assertEquals(4, result.totalDistinct)
    }

    @Test fun newerRewatchAtEarlierEpisodeMustWinOverOldLaterEpisode() {
        val oldCloud = SyncCheckpoint("100", 12, 30000, Instant.parse("2026-10-01T00:00:00Z"))
        val rewatch = SyncCheckpoint("100", 2, 12000, Instant.parse("2026-10-02T00:00:00Z"))
        assertEquals(SyncDecision.LOCAL_NEWER, decideCheckpoint(rewatch, oldCloud))
    }

    @Test fun equalTimeDifferentProgressRequiresUserReview() {
        val at = Instant.parse("2026-10-02T00:00:00Z")
        val a = SyncCheckpoint("100", 2, 12000, at)
        val b = SyncCheckpoint("100", 3, 9000, at)
        assertEquals(SyncDecision.NEEDS_REVIEW, decideCheckpoint(a, b))
    }

    @Test fun cannotResolveWithoutProvenTimestamps() {
        val noTime = SyncCheckpoint("100", 2, 12000, Instant.EPOCH)
        val known = SyncCheckpoint("100", 2, 12000, Instant.parse("2026-10-02T00:00:00Z"))
        assertEquals(SyncDecision.NEEDS_REVIEW, decideCheckpoint(noTime, known))
        assertEquals(SyncDecision.UNKNOWN, decideCheckpoint(null, known))
    }

    @Test fun accountLatestCheckpointCanWinEvenWithLowerPosition() {
        val account = SyncCheckpoint("100", 4, 1000, Instant.parse("2026-10-03T00:00:00Z"))
        val device = SyncCheckpoint("100", 5, 9000, Instant.parse("2026-10-02T00:00:00Z"))
        assertEquals(SyncDecision.REMOTE_NEWER, decideCheckpoint(device, account))
    }
}
