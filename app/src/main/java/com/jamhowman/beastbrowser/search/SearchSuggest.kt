package com.jamhowman.beastbrowser.search

import com.jamhowman.beastbrowser.browser.UrlUtils
import com.jamhowman.beastbrowser.data.BrowserDb
import com.jamhowman.beastbrowser.data.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

enum class SuggestKind { SEARCH, HISTORY, BOOKMARK }

data class SuggestItem(
    val title: String,
    val subtitle: String?,
    /** Text to put in the address bar / navigate to. */
    val fill: String,
    val kind: SuggestKind,
)

object SearchSuggest {
    private const val TIMEOUT_MS = 2_500
    private const val MAX_REMOTE = 6
    private const val MAX_LOCAL = 6

    /**
     * Build suggestion rows for [query].
     * [includeLocal] false in private tabs (no history/bookmarks).
     * Remote suggestions use the user's selected [engine], only when [includeRemote]
     * (off in private tabs and when Settings → Search suggestions is off) and never for URL-like input.
     */
    suspend fun load(
        query: String,
        engine: SearchEngine,
        db: BrowserDb,
        includeLocal: Boolean,
        includeRemote: Boolean = false,
    ): List<SuggestItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()

        val out = ArrayList<SuggestItem>(16)

        // Always offer "Search … for this"
        out += SuggestItem(
            title = "Search ${engine.label} for \"$q\"",
            subtitle = null,
            fill = q,
            kind = SuggestKind.SEARCH,
        )

        if (includeLocal) {
            for (e in db.searchLocal(q, MAX_LOCAL)) {
                val isBm = db.isBookmarked(e.url)
                out += SuggestItem(
                    title = e.title.ifBlank { e.url },
                    subtitle = e.url,
                    fill = e.url,
                    kind = if (isBm) SuggestKind.BOOKMARK else SuggestKind.HISTORY,
                )
            }
        }

        ensureActive() // typed on while the local query ran: don't hit the network for a stale query
        val remote = if (includeRemote && !looksLikeUrl(q)) fetchRemote(engine, q) else emptyList()
        val seen = out.map { it.fill.lowercase() }.toMutableSet()
        for (s in remote) {
            val key = s.lowercase()
            if (key in seen || key == q.lowercase()) continue
            seen += key
            out += SuggestItem(
                title = s,
                subtitle = "Search ${engine.label}",
                fill = s,
                kind = SuggestKind.SEARCH,
            )
            if (out.count { it.kind == SuggestKind.SEARCH && it.subtitle != null } >= MAX_REMOTE) break
        }
        out
    }

    private fun fetchRemote(engine: SearchEngine, query: String): List<String> {
        val url = engine.suggestUrl(query) ?: return emptyList()
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "BeastBrowser/2.3")
                instanceFollowRedirects = true // HttpURLConnection never follows https -> http
                useCaches = false
            }
            try {
                if (conn.responseCode !in 200..299) return emptyList()
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseSuggestions(body)
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * True for anything that might be a URL/host being typed (e.g. "github.com/me", "192.168.1.1",
     * "intranet:8080", "user@host"), so it never leaks to a search engine.
     */
    internal fun looksLikeUrl(input: String): Boolean {
        val t = input.trim()
        if (t.isEmpty()) return false
        if (UrlUtils.fromInput(t, SearchEngine.DUCKDUCKGO) != SearchEngine.DUCKDUCKGO.searchUrl(t)) return true
        if (t.any { it.isWhitespace() }) return false
        return t.startsWith("www.", ignoreCase = true) || t.any { it == '.' || it == '/' || it == ':' || it == '@' }
    }

    /** DDG type=list / Google firefox client / Brave: ["query", ["a","b",…]] or flat ["a","b"]. */
    internal fun parseSuggestions(body: String): List<String> {
        val root = JSONArray(body)
        val list = when {
            root.length() >= 2 && root.optJSONArray(1) != null -> root.getJSONArray(1)
            else -> root
        }
        val out = ArrayList<String>(list.length())
        for (i in 0 until list.length()) {
            when (val v = list.get(i)) {
                is String -> if (v.isNotBlank()) out += v
                is JSONArray -> {
                    // DDG sometimes returns objects; type=list is strings. Brave is strings.
                    val s = v.optString(0)
                    if (s.isNotBlank()) out += s
                }
                is org.json.JSONObject -> {
                    val s = v.optString("phrase").ifBlank { v.optString("q") }
                    if (s.isNotBlank()) out += s
                }
            }
        }
        return out
    }
}
