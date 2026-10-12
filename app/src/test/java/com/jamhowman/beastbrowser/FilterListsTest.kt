package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.filters.FilterFetcher
import com.jamhowman.beastbrowser.filters.FilterRules
import com.jamhowman.beastbrowser.filters.FilterStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Random

/** 2.10 Ulfur #3: filter-list management and element-picker rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterListsTest {
    private lateinit var dir: File
    private var clock = 1_000L
    private fun store() = FilterStore(dir) { clock++ }
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    @Before fun setUp() { dir = Files.createTempDirectory("filters").toFile() }

    // ---------------------------------------------------------------- add / remove / enable / update

    @Test fun addRemoveEnableUpdatePersist() {
        val s = store()
        val added = s.add("https://example.org/lists/annoyances.txt") as FilterStore.AddResult.Added
        assertEquals("annoyances.txt", added.list.title)
        val id = added.list.id
        s.update(id) { "! comment\n||ads.example^\nexample.com##.banner\n" }
        assertEquals(2, s.lists.single().ruleCount)
        assertTrue(s.compile().contains("||ads.example^"))
        s.setEnabled(id, false)
        assertFalse("disabled list leaves uBO", s.compile().contains("||ads.example^"))
        // Survives a restart.
        val again = store()
        assertEquals(false, again.lists.single().enabled)
        assertEquals(listOf("||ads.example^", "example.com##.banner"), again.rulesOf(id))
        again.setEnabled(id, true)
        again.remove(id)
        assertTrue(again.lists.isEmpty())
        assertTrue(again.rulesOf(id).isEmpty())
        assertTrue(store().lists.isEmpty())
    }

    @Test fun duplicatesAndListCap() {
        val s = store()
        assertTrue(s.add("https://a.example/x.txt") is FilterStore.AddResult.Added)
        assertTrue(s.add("https://a.example/x.txt") is FilterStore.AddResult.Rejected)
        for (i in 2..FilterRules.MAX_LISTS) assertTrue(s.add("https://a.example/$i.txt") is FilterStore.AddResult.Added)
        assertTrue(s.add("https://a.example/one-too-many.txt") is FilterStore.AddResult.Rejected)
    }

    @Test fun failedUpdateKeepsOldRules() {
        val s = store()
        val id = (s.add("https://a.example/x.txt") as FilterStore.AddResult.Added).list.id
        s.update(id) { "||keep.example^" }
        val after = s.update(id) { throw IOException("HTTP 503") }!!
        assertEquals("HTTP 503", after.error)
        assertEquals(listOf("||keep.example^"), s.rulesOf(id))
        assertEquals(1, after.ruleCount)
    }

    // ---------------------------------------------------------------- https only + size cap

    @Test fun onlyHttpsListAddresses() {
        listOf("http://a.example/x.txt", "ftp://a.example/x", "file:///sdcard/x.txt", "content://x/y", "javascript:alert(1)",
            "https://user:pw@a.example/x.txt", "https://127.0.0.1/x.txt", "https://localhost/x.txt", "https://[::1]/x.txt",
            "", "not a url", "data:text/plain,||x^").forEach {
            assertNotNull(it, FilterRules.checkListUrl(it))
            assertTrue(it, store().add(it) is FilterStore.AddResult.Rejected)
        }
        assertNull(FilterRules.checkListUrl("https://raw.githubusercontent.com/user/repo/main/list.txt"))
        assertNull(FilterRules.checkListUrl("HTTPS://filters.example/list.txt"))
    }

    @Test fun nonHttpsUrlNeverFetched() {
        // A stored list whose address became non-https (hand-edited file) is refused before any fetch.
        File(dir, "filters.json").writeText("""{"lists":[{"id":"abc","url":"http://a.example/x.txt","title":"x"}]}""")
        assertTrue(store().lists.isEmpty())
        try { FilterFetcher.fetch("http://a.example/x.txt"); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("https")) }
    }

    @Test fun sizeCapEnforcedWhileReading() {
        val cap = 1000L
        assertEquals(1000, FilterFetcher.readCapped(ByteArrayInputStream(ByteArray(1000) { 'a'.code.toByte() }), cap).length)
        try { FilterFetcher.readCapped(ByteArrayInputStream(ByteArray(1001)), cap); fail() } catch (_: FilterFetcher.TooLargeException) {}
        assertEquals(10L * 1024 * 1024, FilterRules.MAX_LIST_BYTES)
        val src = File(main, "java/com/jamhowman/beastbrowser/filters/FilterFetcher.kt").readText()
        assertTrue("redirects are checked, not followed blindly", src.contains("instanceFollowRedirects = false"))
        assertTrue(src.contains("readCapped(it, FilterRules.MAX_LIST_BYTES)"))
    }

    // ---------------------------------------------------------------- bad rules never crash

    @Test fun badRulesAreDroppedNotFatal() {
        val text = listOf(
            "[Adblock Plus 2.0]", "! Title: x", "||ok.example^", "example.com##+js(trusted-set-cookie, a, b)",
            "||x^\$replace=/a/b/", "!#include evil.txt", "a\u0000b##.x", "x".repeat(5000), "example.com#%#alert(1)",
            "example.com##.fine", "##.generic-ok", "@@||allow.example^", "example.com##:style(", "}}}{{{", "##",
        ).joinToString("\r\n")
        val r = FilterRules.sanitize(text)
        assertEquals(6, r.dropped)
        assertTrue(r.rules.containsAll(listOf("||ok.example^", "example.com##.fine", "##.generic-ok", "@@||allow.example^")))
        assertFalse(r.rules.any { it.contains("trusted-") || it.contains("\$replace") || it.startsWith("!#include") })
    }

    @Test fun sanitizerNeverThrowsOnGarbage() {
        val rnd = Random(42)
        repeat(300) {
            val bytes = ByteArray(rnd.nextInt(4000)).also { rnd.nextBytes(it) }
            FilterRules.sanitize(String(bytes, Charsets.UTF_8))
            FilterRules.sanitize(String(bytes, Charsets.ISO_8859_1))
            FilterRules.cleanSelector(String(bytes, Charsets.UTF_8))
            FilterRules.normalizeHost(String(bytes, Charsets.UTF_8))
        }
        FilterRules.sanitize(null)
        val s = store()
        val id = (s.add("https://a.example/x.txt") as FilterStore.AddResult.Added).list.id
        s.update(id) { String(ByteArray(20_000).also { rnd.nextBytes(it) }, Charsets.ISO_8859_1) }
        s.compile()
        // Corrupt metadata starts empty instead of crashing.
        File(dir, "filters.json").writeText("{not json")
        assertTrue(store().lists.isEmpty())
    }

    @Test fun ruleCountCapped() {
        val r = FilterRules.sanitize((0..FilterRules.MAX_RULES_PER_LIST + 5).joinToString("\n") { "||a$it.example^" })
        assertEquals(FilterRules.MAX_RULES_PER_LIST, r.rules.size)
        assertTrue(r.truncated)
    }

    // ---------------------------------------------------------------- element picker: scoped, visible, deletable

    @Test fun pickerRulesAreScopedToTheirSite() {
        val s = store()
        val r = s.addPickerRule("news.example.com", "div.ad > img")!!
        assertEquals("news.example.com##div.ad > img", r.filter)
        assertTrue(FilterRules.appliesTo(r, "news.example.com"))
        assertTrue(FilterRules.appliesTo(r, "m.news.example.com"))
        listOf("example.com", "other.com", "news.example.com.evil.net", "evilnews.example.com", "xnews.example.com", "", null).forEach {
            assertFalse("$it", FilterRules.appliesTo(r, it))
        }
        assertEquals(listOf(r), s.pickerRulesFor("news.example.com"))
        assertTrue(s.pickerRulesFor("other.com").isEmpty())
        // Every compiled picker line is host-scoped, never generic.
        s.addPickerRule("shop.example", "#promo")
        s.compile().lines().filter { it.contains("##") }.forEach { assertFalse(it, it.startsWith("##")) }
    }

    @Test fun pickerRejectsUnsafeHostsAndSelectors() {
        listOf(null, "", "*", "example.*", "a.com,b.com", "~a.com", "a.com##", "exa mple.com", "localhost", "a..com", "-a.com")
            .forEach { assertNull("$it", FilterRules.pickerRule(it, ".x")) }
        listOf(null, "", "x\n##body", "a{display:block}", ".x##+js(foo)", "body:style(color:red)", ".a:remove()",
            "+js(trusted-x)", ",.a", "x".repeat(600), "a#@#b").forEach { assertNull("$it", FilterRules.pickerRule("a.com", it)) }
        assertNotNull(FilterRules.pickerRule("Bücher.example", "#x"))
        assertEquals("xn--bcher-kva.example", FilterRules.pickerRule("Bücher.example", "#x")!!.host)
    }

    @Test fun pickerRulesListedAndDeletable() {
        val s = store()
        val a = s.addPickerRule("a.example", ".ad")!!
        s.addPickerRule("a.example", ".ad") // duplicate ignored
        val b = s.addPickerRule("b.example", "#sticky")!!
        assertEquals(listOf(a, b), store().pickerRules)
        s.removePickerRule(a)
        assertEquals(listOf(b), store().pickerRules)
        assertFalse(s.compile().contains("a.example##"))
    }

    @Test fun pickerWiringChecksHostAndPrivacy() {
        val act = File(main, "java/com/jamhowman/beastbrowser/ui/MainActivity.kt").readText()
        val fn = act.substringAfter("private fun startElementPicker(").substringBefore("\n    }\n")
        assertTrue(fn.contains("t.isPrivate"))
        assertTrue(fn.contains("replyHost != com.jamhowman.beastbrowser.filters.FilterRules.normalizeHost(pageHost)"))
        assertTrue(fn.contains("addPickerRule(pageHost,"))
        val bridge = File(main, "assets/extensions/ublock/js/beast-bridge.js").readText()
        assertTrue(bridge.contains("case 'setManaged':"))
        assertTrue("user's own filters outside the markers are kept", bridge.contains("stripManaged("))
    }

    // ---------------------------------------------------------------- Designer spec (2.10)

    @Test fun pickerFollowsDesignerSpec() {
        val js = File(main, "assets/extensions/beast-helper/content.js").readText()
        val picker = js.substringAfter("function startPicker(").substringBefore("function unhideLast(")
        assertTrue("accent highlight", picker.contains("accent") && picker.contains("border:2px solid \${color}"))
        assertTrue("bigger/smaller slider", picker.contains("slider.type = \"range\"") && picker.contains("\"Smaller\"") && picker.contains("\"Bigger\""))
        assertTrue("live preview", picker.contains("slider.addEventListener(\"input\", show)") && picker.contains("\"opacity\", \"0.15\""))
        assertTrue("rule shown", picker.contains("location.hostname + \"##\" + sel"))
        assertTrue("Hide primary next to Cancel", picker.contains("mk(\"Cancel\", false), hide = mk(\"Hide\", true)"))
        val act = File(main, "java/com/jamhowman/beastbrowser/ui/MainActivity.kt").readText()
        assertTrue(act.contains("private val PICKER_UNDO_MS = 10_000"))
        val fn = act.substringAfter("private fun startElementPicker(").substringBefore("\n    }\n")
        assertTrue("undo deletes the rule and unhides", fn.contains("removePickerRule(rule)") && fn.contains("unhideLast"))
        val strings = File(main, "res/values/strings.xml").readText()
        assertTrue(strings.contains(">Hidden on this site<") && strings.contains(">Undo<"))
    }

    @Test fun filterListsLiveInSettingsWithAddAtBottom() {
        assertTrue(File(main, "res/xml/preferences.xml").readText().contains("app:key=\"ulfur_filters\""))
        assertTrue(File(main, "java/com/jamhowman/beastbrowser/ui/SettingsActivity.kt").readText().contains("FiltersActivity::class.java"))
        val ui = File(main, "java/com/jamhowman/beastbrowser/ui/FiltersActivity.kt").readText()
        val render = ui.substringAfter("private fun render()")
        assertTrue("add-by-URL comes after the list rows", render.indexOf("R.string.filters_add") > render.indexOf("listRow(l)"))
        val row = ui.substringAfter("private fun listRow(")
        assertTrue("switch per list", row.contains("MaterialSwitch"))
        assertTrue("last updated + rule count", row.contains("R.string.filters_status, l.ruleCount"))
        assertTrue(File(main, "res/values/strings.xml").readText().contains(">Add list by URL<"))
    }

    // ---------------------------------------------------------------- Designer nudge: stale lists

    @Test fun staleNudgeOnlyPastSevenDays() {
        val day = FilterStore.DAY_MS
        clock = 100 * day
        val s = store()
        assertNull("no lists", s.staleDays(clock))
        val a = (s.add("https://a.example/a.txt") as FilterStore.AddResult.Added).list.id
        assertNull("never downloaded doesn't count", s.staleDays(clock))
        clock = 100 * day
        s.update(a) { "||a^" }
        assertNull(s.staleDays(100 * day + 7 * day))           // exactly 7 days: not yet
        assertEquals(7, s.staleDays(100 * day + 7 * day + 1))  // just over 7 days
        assertEquals(12, s.staleDays(100 * day + 12 * day + 5))
        s.setEnabled(a, false)
        assertNull("disabled lists don't nudge", s.staleDays(100 * day + 30 * day))
        s.setEnabled(a, true)
        clock = 125 * day
        val b = (s.add("https://a.example/b.txt") as FilterStore.AddResult.Added).list.id
        s.update(b) { "||b^" }
        assertEquals("oldest enabled list wins", 30, s.staleDays(130 * day + 1))
        clock = 130 * day
        s.update(a) { "||a^" }
        assertNull("after Update all nothing is stale", s.staleDays(130 * day + 1))
    }

    @Test fun staleNudgeLineRunsUpdateAll() {
        val ui = File(main, "java/com/jamhowman/beastbrowser/ui/FiltersActivity.kt").readText()
        val render = ui.substringAfter("private fun render()")
        val nudge = render.substringAfter("store.staleDays()").substringBefore("\n        }\n")
        assertTrue("at the top", render.indexOf("store.staleDays()") < render.indexOf("filters_lists_header"))
        assertTrue(nudge.contains("R.plurals.filters_stale") && nudge.contains("setOnClickListener { updateAll() }"))
        assertTrue(ui.substringAfter("private fun updateAll()").substringBefore("\n    }").contains("store.lists.map { it.id }"))
        val strings = File(main, "res/values/strings.xml").readText()
        assertTrue(strings.contains(">Lists are %d days old · Update<"))
    }
}
