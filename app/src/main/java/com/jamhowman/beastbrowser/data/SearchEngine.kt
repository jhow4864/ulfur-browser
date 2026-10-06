package com.jamhowman.beastbrowser.data

import android.net.Uri

enum class SearchEngine(val key: String, val label: String, private val template: String) {
    DUCKDUCKGO("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    BRAVE("brave", "Brave Search", "https://search.brave.com/search?q=%s"),
    STARTPAGE("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s"),
    GOOGLE("google", "Google", "https://www.google.com/search?q=%s");

    fun searchUrl(query: String): String = template.replace("%s", Uri.encode(query))

    /** Autocomplete endpoint for this engine, or null if we have none. */
    fun suggestUrl(query: String): String? {
        val q = Uri.encode(query.trim())
        if (q.isEmpty()) return null
        return when (this) {
            DUCKDUCKGO -> "https://duckduckgo.com/ac/?q=$q&type=list"
            BRAVE -> "https://search.brave.com/api/suggest?q=$q"
            GOOGLE -> "https://suggestqueries.google.com/complete/search?client=firefox&q=$q"
            // Startpage has no public suggest API. Don't send the user's keystrokes to an engine
            // they didn't pick: local history/bookmark suggestions only.
            STARTPAGE -> null
        }
    }

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: DUCKDUCKGO
    }
}
