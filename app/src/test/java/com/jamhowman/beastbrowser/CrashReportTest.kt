package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.crash.CrashFormat
import com.jamhowman.beastbrowser.crash.CrashIssue
import com.jamhowman.beastbrowser.crash.CrashReport
import com.jamhowman.beastbrowser.crash.CrashStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files

/** 2.5.1 opt-in crash reports: format, scrubbing, caps and the GitHub issue link. Plain JVM, no Android. */
class CrashReportTest {

    private fun report(
        trace: String = SAMPLE_TRACE,
        time: Long = 1_791_000_000_000,
        kind: CrashReport.Kind = CrashReport.Kind.APP,
    ) = CrashReport(
        time = time, kind = kind, appVersion = "2.5.1", versionCode = 17, android = "14", api = 34,
        device = "Google Pixel 8", gecko = "157.0", process = "com.jamhowman.beastbrowser", thread = "main",
        exception = CrashFormat.summary(trace), trace = trace,
    )

    // ---------------------------------------------------------------- formatting

    @Test fun formatRoundTripsEveryField() {
        val r = report()
        val text = CrashFormat.format(r)
        assertTrue(text.startsWith(CrashFormat.HEADER + "\n"))
        assertTrue(text.contains("\nversionCode: 17\n"))
        assertTrue(text.contains("\ndate: " + CrashFormat.timeUtc(r.time) + "\n"))
        assertEquals(r, CrashFormat.parse(text))
    }

    @Test fun parseRejectsOtherFiles() {
        assertNull(CrashFormat.parse(""))
        assertNull(CrashFormat.parse("hello\nworld"))
        assertNull(CrashFormat.parse(CrashFormat.HEADER + "\nformat: 1\n\ntrace")) // no time
    }

    @Test fun headerValuesStaySingleLineAndCapped() {
        val r = report().copy(thread = "a\nb", device = "x".repeat(1000))
        val parsed = CrashFormat.parse(CrashFormat.format(r))!!
        assertEquals("a b", parsed.thread)
        assertEquals(CrashFormat.MAX_FIELD_CHARS, parsed.device.length)
    }

    @Test fun summaryAndShortException() {
        val r = report()
        assertEquals("java.lang.IllegalStateException: Tab list is empty", r.exception)
        assertEquals("IllegalStateException: Tab list is empty", r.shortException)
        assertEquals("GeckoView content process crashed",
            report(trace = "").copy(exception = "GeckoView content process crashed").shortException)
    }

    @Test fun formatsUtcTimestamps() {
        assertEquals("1970-01-01 00:00:00 UTC", CrashFormat.timeUtc(0))
        assertEquals("2026-10-06 14:25:00 UTC", CrashFormat.timeUtc(1_791_296_700_000))
    }

    // ---------------------------------------------------------------- scrubbing

    @Test fun scrubsUrlsAndOtherIdentifyingStrings() {
        val cases = mapOf(
            "Failed to load https://bank.example.com/account?id=42 now" to "Failed to load <url> now",
            "http://user:pw@10.0.0.1:8080/x" to "<url>",
            "redirect to moz-extension://abc-123/page.html" to "redirect to <url>",
            "about:blank and about:config" to "<url> and <url>",
            "src=data:text/html;base64,PGgxPg==" to "src=<url>",
            "blob:https://site.org/uuid" to "<url>",
            "javascript:alert(1)" to "<url>",
            "visit www.example.org/path today" to "visit <url> today",
            "Unable to resolve host \"api.github.com\": No address" to "Unable to resolve host \"<url>\": No address",
            "host news.bbc.co.uk/article/1 timed out" to "host <url> timed out",
            "mail me at jane.doe@mail.example.de please" to "mail me at <email> please",
            "connect to 192.168.1.20 failed" to "connect to <ip> failed",
            "/storage/emulated/0/Download/Ulfur/secret.pdf: open failed" to "<path>: open failed",
            "/data/user/0/com.jamhowman.beastbrowser/files/private_downloads/x.mp4 (No such file)" to "<path> (No such file)",
        )
        for ((input, expected) in cases) assertEquals(input, expected, CrashFormat.scrub(input))
    }

    @Test fun scrubKeepsCodeNames() {
        for (s in listOf(
            "java.io.IOException: closed",
            "java.net.UnknownHostException",
            "kotlin.io.FilesKt",
            "org.mozilla.geckoview.GeckoResult",
            "com.jamhowman.beastbrowser.ui.MainActivity",
            "Attempt to invoke virtual method 'int java.util.List.size()' on a null object reference",
            "Caused by: java.lang.RuntimeException: boom",
            "GeckoView 157.0.20260924084938",
        )) assertEquals(s, CrashFormat.scrub(s))
    }

    @Test fun traceScrubsMessagesButNotFrames() {
        val e = IllegalStateException("Can't open https://secret.example.com/inbox",
            RuntimeException("cause at www.private.net/x"))
        val trace = CrashFormat.traceOf(e)
        assertFalse(trace, trace.contains("secret.example.com"))
        assertFalse(trace, trace.contains("private.net"))
        assertTrue(trace.startsWith("java.lang.IllegalStateException: Can't open <url>"))
        assertTrue(trace.contains("Caused by: java.lang.RuntimeException: cause at <url>"))
        assertTrue(trace.contains("\tat com.jamhowman.beastbrowser.CrashReportTest.traceScrubsMessagesButNotFrames("))
    }

    // ---------------------------------------------------------------- caps and truncation

    @Test fun truncateTraceCapsLinesAndTotal() {
        val longLine = "java.lang.Error: " + "x".repeat(2000)
        val cut = CrashFormat.truncateTrace(longLine)
        assertTrue(cut.length <= CrashFormat.MAX_LINE_CHARS + 20)
        assertTrue(cut.endsWith("… (trimmed)"))

        val many = (1..5000).joinToString("\n") { "\tat a.B.c$it(B.kt:$it)" }
        val t = CrashFormat.truncateTrace(many, maxChars = 1000)
        assertTrue(t.length <= 1000)
        assertTrue(t, Regex("""\t\.\.\. \(\d+ more lines trimmed\)$""").containsMatchIn(t))
        assertEquals(many, CrashFormat.truncateTrace(many, maxChars = many.length + 1))
    }

    @Test fun hugeTracesAreCappedInTheFile() {
        val deep = "java.lang.StackOverflowError\n" + (1..20_000).joinToString("\n") { "\tat a.B.recurse(B.kt:$it)" }
        val r = report(trace = deep)
        val text = CrashFormat.format(r)
        assertTrue(text.toByteArray().size <= CrashFormat.MAX_FILE_BYTES)
        assertTrue(CrashFormat.parse(text)!!.trace.length <= CrashFormat.MAX_TRACE_CHARS)

        // Non-ASCII messages: the byte cap still holds (2 bytes per ü).
        val unicode = report(trace = (1..100).joinToString("\n") { "java.lang.Error: " + "ü".repeat(400) })
        val capped = CrashFormat.format(unicode, maxBytes = 4096)
        assertTrue(capped.toByteArray().size <= 4096)
        assertTrue(CrashFormat.parse(capped)!!.trace.startsWith("java.lang.Error: ü"))
    }

    @Test fun storeKeepsNewestFiveAndSkipsJunk() {
        val dir = Files.createTempDirectory("crash").toFile()
        try {
            val store = CrashStore(dir)
            for (i in 1..8) assertNotNull(store.save(report(time = 1_000L * i)))
            assertEquals(CrashStore.KEEP, store.count())
            assertEquals(listOf(8000L, 7000L, 6000L, 5000L, 4000L), store.reports().map { it.report.time })

            // Same millisecond: both kept, distinct names.
            store.save(report(time = 9000)); store.save(report(time = 9000, kind = CrashReport.Kind.CONTENT))
            store.save(report(time = 9000))
            assertEquals(3, store.files().count { CrashStore.timeOf(it) == 9000L })

            File(dir, "notes.txt").writeText("x")
            File(dir, "crash-1-app.txt.tmp").apply { writeText("partial"); setLastModified(0) }
            File(dir, "crash-99999-app.txt").writeText("x".repeat(CrashFormat.MAX_FILE_BYTES + 1))
            store.prune()
            assertFalse(File(dir, "notes.txt").exists())
            assertFalse(File(dir, "crash-1-app.txt.tmp").exists())
            assertFalse(File(dir, "crash-99999-app.txt").exists())
            assertTrue(store.files().all { it.length() <= CrashFormat.MAX_FILE_BYTES })

            val newest = store.reports().first()
            assertFalse("outside the store", store.delete(File(dir.parentFile, newest.file.name)))
            assertTrue(store.delete(newest.file))
            store.deleteAll()
            assertEquals(0, store.count())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- GitHub issue link

    private fun params(url: String): Map<String, String> = url.substringAfter('?').split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, "UTF-8")
    }

    @Test fun issueUrlIsPrefilledForTheBugForm() {
        val r = report()
        val url = CrashIssue.url(r)
        assertTrue(url.startsWith("https://github.com/jhow4864/ulfur-browser/issues/new?"))
        assertTrue(url.length <= CrashIssue.MAX_URL)
        assertFalse("fully encoded", url.substringAfter('?').any { it == ' ' || it == '\n' || it == '#' || it == '`' })
        val p = params(url)
        assertEquals("bug_report.yml", p["template"])
        assertEquals("bug", p["labels"])
        assertEquals("Crash: IllegalStateException: Tab list is empty", p["title"])
        assertEquals("2.5.1", p["version"])
        assertEquals("Google Pixel 8", p["phone"])
        assertEquals("14", p["android"])
        assertNotNull(p["what"])
        for (key in listOf("extra", "body")) {
            val v = p.getValue(key)
            assertTrue(v.contains("- Ulfur: 2.5.1 (17)"))
            assertTrue(v.contains("- Android: 14 (API 34)"))
            assertTrue(v.contains("- GeckoView: 157.0"))
            assertTrue(v.contains("```\n" + SAMPLE_TRACE + "\n```"))
            assertFalse(v.contains(CrashIssue.TRIM_NOTE))
        }
    }

    @Test fun longTracesAreTrimmedToFitTheUrl() {
        val sections = listOf("java.lang.IllegalStateException: outer", "Caused by: java.lang.RuntimeException: inner")
        val trace = sections.joinToString("\n") { h -> h + "\n" + (1..400).joinToString("\n") { "\tat com.example.Deep.call$it(Deep.kt:$it)" } }
        val url = CrashIssue.url(report(trace = trace))
        assertTrue(url.length.toString(), url.length <= CrashIssue.MAX_URL)
        assertTrue(url.length > CrashIssue.MAX_URL - 600) // trimmed close to the limit, not to nothing
        val extra = params(url).getValue("extra")
        assertTrue(extra.contains("java.lang.IllegalStateException: outer\n\tat com.example.Deep.call1(Deep.kt:1)"))
        assertTrue("root cause survives", extra.contains("Caused by: java.lang.RuntimeException: inner\n\tat com.example.Deep.call1(Deep.kt:1)"))
        assertTrue(Regex("""\t\.\.\. \d+ frames trimmed""").containsMatchIn(extra))
        assertTrue(extra.contains(CrashIssue.TRIM_NOTE))
    }

    @Test fun hugeMessagesStillFit() {
        val trace = "java.lang.Error: " + "é".repeat(30_000) + "\n\tat a.B.c(B.kt:1)"
        for (limit in listOf(CrashIssue.MAX_URL, 3_000)) {
            val url = CrashIssue.url(report(trace = trace), limit)
            assertTrue(url.length <= limit)
            assertTrue(params(url).getValue("extra").contains("java.lang.Error: é"))
        }
    }

    @Test fun contentCrashIssue() {
        val r = report(trace = "", kind = CrashReport.Kind.CONTENT).copy(exception = "GeckoView content process crashed", thread = "")
        val p = params(CrashIssue.url(r))
        assertEquals("Crash: web content process (GeckoView 157.0)", p["title"])
        assertTrue(p.getValue("extra").contains("No Java stack trace"))
        assertTrue(p.getValue("extra").contains("- Type: Web content process crash"))
    }

    @Test fun titleIsCapped() {
        val r = report(trace = "java.lang.Error: " + "z".repeat(500))
        assertEquals(CrashIssue.MAX_TITLE, CrashIssue.title(r).length)
    }

    private companion object {
        val SAMPLE_TRACE = listOf(
            "java.lang.IllegalStateException: Tab list is empty",
            "\tat com.jamhowman.beastbrowser.ui.MainActivity.selectTab(MainActivity.kt:512)",
            "\tat com.jamhowman.beastbrowser.ui.MainActivity.onCreate(MainActivity.kt:266)",
            "\tat android.app.Activity.performCreate(Activity.java:8595)",
            "Caused by: java.lang.IndexOutOfBoundsException: Index: 0, Size: 0",
            "\tat java.util.ArrayList.get(ArrayList.java:437)",
            "\t... 3 more",
        ).joinToString("\n")
    }
}
