package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.data.SearchEngine
import com.jamhowman.beastbrowser.search.SearchSuggest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchSuggestTest {
    @Test fun parseGoogleFirefoxStyle() {
        val raw = """["cat",["cats","catch","cathedral"]]"""
        assertEquals(listOf("cats", "catch", "cathedral"), SearchSuggest.parseSuggestions(raw))
    }

    @Test fun parseFlatArray() {
        val raw = """["alpha","beta"]"""
        assertEquals(listOf("alpha", "beta"), SearchSuggest.parseSuggestions(raw))
    }

    @Test fun parseEmpty() {
        assertTrue(SearchSuggest.parseSuggestions("[]").isEmpty())
    }

    @Test fun urlLikeInputNeverQueriesEngine() {
        listOf("github.com", "github.com/jam", "https://x", "http://a", "192.168.1.1", "localhost",
            "intranet:8080", "www.goo", "user@host", "example.").forEach {
            assertTrue(it, SearchSuggest.looksLikeUrl(it))
        }
        listOf("cats", "best pizza near me", "what is 2.5 kg in lb").forEach {
            assertFalse(it, SearchSuggest.looksLikeUrl(it))
        }
    }

    @Test fun suggestEndpointsAreHttpsAndStartpageHasNone() {
        assertNull(SearchEngine.STARTPAGE.suggestUrl("cats"))
        SearchEngine.entries.mapNotNull { it.suggestUrl("cats") }.forEach { assertTrue(it, it.startsWith("https://")) }
    }

    @Test fun privateTabsAndToggleGateRemote() {
        val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        val ui = File(main, "java/com/jamhowman/beastbrowser/ui/MainActivity.kt").readText()
        assertTrue(ui.contains("val includeRemote = includeLocal && Prefs.searchSuggestions"))
        assertTrue(ui.contains("SearchSuggest.load(q, engine, db, includeLocal, includeRemote)"))
        val prefs = File(main, "res/xml/preferences.xml").readText()
        assertTrue(Regex("""app:key="search_suggestions" app:defaultValue="true"""").containsMatchIn(prefs))
        val core = File(main, "java/com/jamhowman/beastbrowser/search/SearchSuggest.kt").readText()
        assertTrue(core.contains("includeRemote && !looksLikeUrl(q)"))
        assertFalse("no cookie handling in suggest requests", core.contains("Cookie"))
        // App-wide default CookieHandler would make HttpURLConnection send cookies.
        val all = main.walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(all.contains("CookieHandler.setDefault"))
    }
}
