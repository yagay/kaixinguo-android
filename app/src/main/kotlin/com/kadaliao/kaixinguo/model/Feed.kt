package com.kadaliao.kaixinguo.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Feed is a list of series loaded a page at a time. It belongs to the main
 * thread: its scope runs there, and the views read its state there.
 */
class Feed(
    private val scope: CoroutineScope,
    private val fetch: (suspend (cursor: String) -> FeedPage)?,
    private val retryEmptyOnError: Boolean = true,
) {
    // Preserve the original Feed(scope) { suspend page } call sites:
    // adding a Boolean as the last primary-constructor parameter otherwise
    // prevents Kotlin's trailing-lambda syntax from resolving to fetch.
    constructor(
        scope: CoroutineScope,
        retryEmptyOnError: Boolean = true,
        fetch: suspend (cursor: String) -> FeedPage,
    ) : this(scope, fetch, retryEmptyOnError)

    var cards by mutableStateOf(emptyList<Card>())
        private set
    private var seen = HashSet<String>()
    private var cursor = ""
    var more by mutableStateOf(true)
        private set
    var started by mutableStateOf(false)
        internal set
    var loading by mutableStateOf(false)
        private set

    /** The cards came from the disk cache: the first page replaces them. */
    var stale by mutableStateOf(false)
        internal set
    var error by mutableStateOf<Throwable?>(null)
        private set
    private var errorAt = 0L // System.nanoTime of the last failure

    /** Retries made by themselves since the last success. */
    private var tries = 0

    /** Whether a retry is on its way by itself. */
    var retrying by mutableStateOf(false)
        private set

    internal var onPage: ((FeedPage) -> Unit)? = null

    /** Sorts the cards after each page, when set. */
    internal var order: ((List<Card>) -> List<Card>)? = null

    internal fun add(p: FeedPage) {
        var incoming = p.cards
        if (stale) {
            // Fresh series first, then those of the cache, so the grid
            // never shrinks under the viewer.
            incoming = incoming + cards
            cards = emptyList()
            seen = HashSet()
            stale = false
        }
        var list = ArrayList(cards)
        var added = 0
        for (k in incoming) {
            if (k.id.isNotEmpty() && seen.add(k.id)) {
                list += k
                added++
            }
        }
        cursor = p.cursor
        more = p.more
        // Search pages repeat what came before: a page with nothing new
        // ends the feed, or the grid would ask for more forever.
        if (added == 0 && p.cards.isNotEmpty()) more = false
        order?.let { list = ArrayList(it(list)) }
        cards = list
    }

    /** Whether the grid should ask for the next page. */
    val wantsMore: Boolean get() = fetch != null && (stale || more || !started)

    /**
     * Fetches the next page, unless one is on its way, the feed has no
     * more, or the last try failed a moment ago.
     */
    fun loadMore() {
        val fetch = fetch ?: return
        if (loading || (started && !stale && !more)) return
        if (error != null && System.nanoTime() - errorAt < 5_000_000_000L) return
        loading = true
        started = true
        val cursor = if (stale) "" else this.cursor
        scope.launch {
            try {
                val p = fetch(cursor)
                loading = false
                error = null
                tries = 0
                add(p)
                onPage?.invoke(p)
            } catch (e: CancellationException) {
                loading = false
                throw e
            } catch (e: Exception) {
                loading = false
                error = e
                errorAt = System.nanoTime()
                debugLog { "feed: $e" }
                scheduleRetry()
            }
        }
    }

    /**
     * Tries again by itself after 2, 4, 8 and 16 seconds, while the feed
     * has nothing to show; then it waits for the viewer.
     */
    private fun scheduleRetry() {
        if (!retryEmptyOnError || cards.isNotEmpty() || tries >= 4) {
            retrying = false
            return
        }
        val wait = 2000L shl tries
        tries++
        retrying = true
        scope.launch {
            delay(wait)
            retrying = false
            error = null
            loadMore()
        }
    }

    /** Fetches again after an error, now. */
    fun retry() {
        tries = 0
        error = null
        loadMore()
    }

    /**
     * Fetches the first page again, as the viewer pulls the grid down.
     * Merging keeps the cards there are after the fresh ones; else the
     * fresh page replaces them.
     */
    suspend fun refresh(merging: Boolean) {
        val fetch = fetch ?: return
        if (loading) return
        loading = true
        try {
            val p = fetch("")
            val had = cards.isNotEmpty()
            val oldCursor = cursor
            val oldMore = more
            if (merging) {
                stale = true
            } else {
                cards = emptyList()
                seen = HashSet()
            }
            error = null
            tries = 0
            add(p)
            if (merging && had) {
                // The grid goes on from where it had got to, not from the
                // fresh page's second.
                cursor = oldCursor
                more = oldMore
            }
            onPage?.invoke(p)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
            errorAt = System.nanoTime()
        } finally {
            loading = false
        }
    }

    companion object {
        /** A feed of these cards alone, with nothing to fetch. */
        fun of(scope: CoroutineScope, cards: List<Card>): Feed = Feed(scope, null).apply {
            add(FeedPage(cards))
            started = true
            more = false
        }
    }
}
