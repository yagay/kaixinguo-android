package com.kadaliao.kaixinguo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kadaliao.kaixinguo.model.Session

/** Tab is one of the app's tabs. */
enum class Tab(val label: String) {
    DISCOVER("推荐"),
    RANK("排行榜"),
    NEW("新剧"),
    MINE("我的"),
    SEARCH("搜索"),
    REELS("刷剧"),
}

/** Route is a page a tab's stack can push. */
sealed interface Route {
    data class Series(val id: String) : Route
    data object Settings : Route
    data object Account : Route
    data object LocalFollows : Route

    /** The route as a session saves it. */
    val saved: String
        get() = when (this) {
            is Series -> "series:$id"
            Settings -> "settings"
            Account -> "account"
            LocalFollows -> "local_follows"
        }

    companion object {
        /** The route a session saved; null for one this version doesn't know. */
        fun parse(s: String): Route? {
            if (s == "settings") return Settings
            if (s == "account") return Account
            if (s == "local_follows") return LocalFollows
            val (kind, arg) = s.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            return when (kind) {
                "series" -> if (arg.isNotEmpty()) Series(arg) else null
                else -> null
            }
        }
    }
}

/** Router is where the viewer is: the tab, and the pages pushed in each. It belongs to the main thread. */
class Router {
    var tab by mutableStateOf(Tab.DISCOVER)
        private set
    private val paths = Tab.entries.associateWith { mutableStateListOf<Route>() }

    /** What the search field holds, and the search whose results show. */
    var searchText by mutableStateOf("")
    var searchQuery by mutableStateOf("")

    /** Told the key of each page that leaves a stack, so its saved state goes too. */
    internal var forget: ((String) -> Unit)? = null

    fun path(t: Tab): List<Route> = paths.getValue(t)

    /** The page on top of the tab showing; null on the tab's own page. */
    val top: Route? get() = paths.getValue(tab).lastOrNull()

    fun select(t: Tab) {
        tab = t
    }

    /** Selects the tab; selecting the one showing goes back to its own page. */
    fun reselect(t: Tab) {
        if (tab != t) {
            tab = t
            return
        }
        while (paths.getValue(t).isNotEmpty()) pop(t)
    }

    /** Pushes the page in the tab showing. */
    fun open(r: Route) {
        paths.getValue(tab).add(r)
    }

    /** Shows the page in place of the one on top, as another season of the same series: Back still leaves the series. */
    fun replaceTop(r: Route) {
        val p = paths.getValue(tab)
        if (p.isEmpty()) {
            p.add(r)
            return
        }
        forget?.invoke(key(tab, p.size, p.last()))
        p[p.lastIndex] = r
    }

    /** Shows the results for q in the search tab. */
    fun search(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        searchText = t
        searchQuery = t
    }

    val canGoBack: Boolean get() = paths.getValue(tab).isNotEmpty() || tab != Tab.DISCOVER

    /** Goes back a page, or from another tab's own page to the first tab's. */
    fun back() {
        if (paths.getValue(tab).isNotEmpty()) {
            pop(tab)
        } else if (tab != Tab.DISCOVER) {
            tab = Tab.DISCOVER
        }
    }

    /** The session s, with the tab, the pages in each and the search, for the next launch to open there. */
    fun saveTo(s: Session): Session = s.copy(
        tab = tab.name,
        paths = paths.filterValues { it.isNotEmpty() }.entries.associate { (t, p) -> t.name to p.map { it.saved } },
        searchText = searchText,
        searchQuery = searchQuery,
    )

    /** Goes back to the tab, pages and search the session saved, skipping what this version doesn't know. */
    fun restore(s: Session) {
        tab = Tab.entries.firstOrNull { it.name == s.tab } ?: Tab.DISCOVER
        for (t in Tab.entries) {
            val p = paths.getValue(t)
            p.clear()
            s.paths[t.name]?.mapNotNullTo(p, Route::parse)
        }
        searchText = s.searchText
        searchQuery = s.searchQuery
    }

    private fun pop(t: Tab) {
        val p = paths.getValue(t)
        forget?.invoke(key(t, p.size, p.last()))
        p.removeAt(p.lastIndex)
    }

    companion object {
        /** Names a page of a stack, for the state it keeps while out of sight. */
        fun key(tab: Tab, depth: Int, route: Route?) = "$tab/$depth/$route"
    }
}
