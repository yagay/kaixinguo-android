package com.kadaliao.kaixinguo.model

import dev.openhongguo.Album
import dev.openhongguo.DiscoverOptions
import dev.openhongguo.Episode
import dev.openhongguo.EpisodeOptions
import dev.openhongguo.ErrorCode
import dev.openhongguo.HotSearch
import dev.openhongguo.NewReleasePage
import dev.openhongguo.NewReleaseQuery
import dev.openhongguo.OpenhongguoClient
import dev.openhongguo.OpenhongguoException
import dev.openhongguo.Page
import dev.openhongguo.PlaybackResult
import dev.openhongguo.SearchOptions
import dev.openhongguo.Series
import dev.openhongguo.SeriesDetail
import dev.openhongguo.SeriesSummary
import dev.openhongguo.ShortRankQuery
import dev.openhongguo.ShortRanking
import dev.openhongguo.Suggestion
import com.kadaliao.kaixinguo.diagnostics.Diagnostics
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Catalog is what the app asks of the Hongguo SDK. [SdkCatalog] is the
 * real one; the tests have one that needs no network.
 */
interface Catalog {
    suspend fun discover(options: DiscoverOptions): Page<SeriesSummary>
    suspend fun search(query: String, options: SearchOptions): Page<SeriesSummary>
    suspend fun suggest(prefix: String): List<Suggestion>
    suspend fun hotSearch(): HotSearch
    suspend fun newReleases(query: NewReleaseQuery): NewReleasePage
    suspend fun getSeries(seriesId: String): Series
    suspend fun getSeriesBatch(seriesIds: List<String>): List<SeriesDetail>
    suspend fun listEpisodes(seriesId: String, options: EpisodeOptions): Page<Episode>
    suspend fun seasons(seriesId: String): Album
    suspend fun shortRank(query: ShortRankQuery): ShortRanking
    suspend fun resolvePlayback(episodeId: String): PlaybackResult
}

/** SdkCatalog is the catalog of the openhongguo client. */
class SdkCatalog(private val client: OpenhongguoClient) : Catalog {
    override suspend fun discover(options: DiscoverOptions) = client.discover(options)
    override suspend fun search(query: String, options: SearchOptions) = client.search(query, options)
    override suspend fun suggest(prefix: String) = client.suggest(prefix)
    override suspend fun hotSearch() = client.hotSearch()
    override suspend fun newReleases(query: NewReleaseQuery) = client.newReleases(query)
    override suspend fun getSeries(seriesId: String) = client.getSeries(seriesId)
    override suspend fun getSeriesBatch(seriesIds: List<String>) = client.getSeriesBatch(seriesIds)
    override suspend fun listEpisodes(seriesId: String, options: EpisodeOptions) = client.listEpisodes(seriesId, options)
    override suspend fun seasons(seriesId: String) = client.seasons(seriesId)
    override suspend fun shortRank(query: ShortRankQuery) = client.shortRank(query)
    override suspend fun resolvePlayback(episodeId: String) = client.resolvePlayback(episodeId)
}

/** Says what went wrong in the viewer's words. */
fun friendly(error: Throwable?): String {
    if (error == null) return ""
    Diagnostics.record("catalog", "error_displayed")
    if (error is OpenhongguoException) {
        return when (error.code) {
            ErrorCode.TRANSPORT -> "网络连接不稳定，请稍后重试"
            ErrorCode.RATE_LIMITED -> "请求太频繁，请稍等片刻再试"
            ErrorCode.ANONYMOUS_LIMITED -> "这一集暂时无法匿名播放，可以稍后再试"
            ErrorCode.AUTHENTICATION_REQUIRED -> "这一集需要登录才能观看"
            ErrorCode.CONTENT_UNAVAILABLE, ErrorCode.ALBUM_UNAVAILABLE -> "内容已下架或不存在"
            ErrorCode.CHALLENGE_REQUIRED, ErrorCode.DEVICE_REJECTED -> "服务器暂时拒绝了请求，请稍后再试"
            else -> error.message.orEmpty()
        }
    }
    if (error is AppError) return error.message
    if (error is IOException) return "网络连接不稳定，请稍后重试"
    return error.message ?: error.toString()
}

/** AppError is a failure the app itself describes. */
class AppError(override val message: String) : Exception(message)

internal fun isCode(error: Throwable?, vararg codes: ErrorCode): Boolean =
    error is OpenhongguoException && error.code in codes

/** Runs `block`, its failure a result rather than a throw; cancellation still throws. */
internal inline fun <T> attempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/** Where debug builds send what the app does; nothing in release builds. */
var debugLogger: ((String) -> Unit)? = null

internal fun debugLog(message: () -> String) {
    debugLogger?.invoke(message())
}
