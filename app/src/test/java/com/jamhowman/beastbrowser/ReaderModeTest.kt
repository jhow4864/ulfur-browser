package com.jamhowman.beastbrowser

import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.browser.HelperSessions
import com.jamhowman.beastbrowser.media.DetectedMedia
import com.jamhowman.beastbrowser.reader.ReaderMode
import com.jamhowman.beastbrowser.reader.ReadingListDb
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderModeTest {
    private val helper = listOf("src/main/assets", "app/src/main/assets").map { File(it, "extensions/beast-helper") }.first { it.isDirectory }

    @Test fun helperManifestWiresReaderAndSniffer() {
        val m = JSONObject(File(helper, "manifest.json").readText())
        val perms = m.getJSONArray("permissions").toString()
        listOf("nativeMessaging", "nativeMessagingFromContent", "webRequest", "webRequestBlocking", "tabs", "<all_urls>", "geckoViewAddons")
            .forEach { assertTrue(it, perms.contains("\"$it\"")) }
        assertEquals(JSONArray(listOf("media/sniffer-core.js", "background.js")).toString(), m.getJSONObject("background").getJSONArray("scripts").toString())
        val cs = m.getJSONArray("content_scripts").getJSONObject(0)
        assertEquals("reader/Readability-readerable.js", cs.getJSONArray("js").getString(0))
        assertEquals("content.js", cs.getJSONArray("js").getString(1))
        assertTrue(m.has("experiment_apis"))
        listOf("content.js", "reader/Readability.js", "reader/Readability-readerable.js", "reader/LICENSE-Readability.txt",
            "reader/reader.html", "reader/reader.js", "reader/reader.css").forEach { assertTrue(it, File(helper, it).isFile) }
        assertTrue(File(helper, "reader/LICENSE-Readability.txt").readText().contains("Apache License"))
        assertTrue(File(helper, "content.js").readText().contains("connectNative(NATIVE_APP)"))
        assertTrue(File(helper, "content.js").readText().contains("\"${HelperSessions.NATIVE_APP}\""))
        // reader page: strict CSP, no inline script
        val html = File(helper, "reader/reader.html").readText()
        assertTrue(html.contains("script-src 'self'"))
        assertFalse(Regex("<script>").containsMatchIn(html))
    }

    @Test fun mediaMessageMapsToDetectedMedia() {
        val msg = JSONObject("""{"type":"media","pageUrl":"https://news.example.org/s","title":"Sample",
            "items":[
              {"url":"https://cdn.example.net/v/720/i.m3u8","mime":"application/vnd.apple.mpegurl","kind":"hls","quality":"720p",
               "w":1280,"h":720,"bytes":-1,"bandwidth":2800000,"codecs":"avc1.4d401f,mp4a.40.2","master":"https://cdn.example.net/v/master.m3u8"},
              {"url":"https://files.example.org/a.mp4","mime":"video/mp4","kind":"progressive","quality":"Video","w":0,"h":0,"bytes":10000000},
              {"url":"blob:https://x/1","kind":"progressive"},
              {"url":"https://files.example.org/b.webm","kind":"progressive"}]}""")
        val items = HelperSessions.parseMedia(msg)
        assertEquals(3, items.size)
        val hls = items[0]
        assertEquals(DetectedMedia.Kind.HLS, hls.kind)
        assertEquals("720p", hls.qualityLabel)
        assertEquals(1280, hls.width); assertEquals(720, hls.height)
        assertEquals(-1L, hls.bytes)
        assertEquals(2800000L, hls.bandwidth)
        assertEquals("https://cdn.example.net/v/master.m3u8", hls.masterUrl)
        assertEquals("avc1.4d401f,mp4a.40.2", hls.codecs)
        assertEquals("https://news.example.org/s", hls.pageUrl)
        assertEquals("Sample", hls.title)
        assertEquals(16, hls.id.length)
        assertEquals(HelperSessions.stableId(hls.url), hls.id)
        val mp4 = items[1]
        assertEquals(DetectedMedia.Kind.PROGRESSIVE, mp4.kind)
        assertEquals(10000000L, mp4.bytes)
        assertNull(mp4.masterUrl)
        assertEquals(-1L, mp4.bandwidth)
        assertEquals("video/mp4", items[2].mime)
        assertEquals("Video", items[2].qualityLabel)
        assertTrue(HelperSessions.parseMedia(JSONObject("{\"type\":\"media\"}")).isEmpty())
    }

    @Test fun readerUrls() {
        val base = "moz-extension://1234-abcd/"
        val u = ReaderMode.readerUrl(base, "id", "abc", "https://example.org/a b?x=1&y=2#frag")
        assertTrue(ReaderMode.isReaderUrl(u))
        assertEquals("https://example.org/a b?x=1&y=2#frag", ReaderMode.originalUrl(u))
        assertEquals("abc", ReaderMode.fragmentParam(u, "id"))
        assertFalse(ReaderMode.isReaderUrl("https://example.org/reader/reader.html"))
        assertFalse(ReaderMode.isReaderUrl("moz-extension://x/popup.html"))
        assertNull(ReaderMode.originalUrl(ReaderMode.readerUrl(base, "id", "1", "javascript:alert(1)")))
        assertNull(ReaderMode.originalUrl("https://example.org/"))
    }

    @Test fun readingListRoundTrip() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ReaderMode.init(ctx)
        val db = ReadingListDb.get(ctx)
        val article = JSONObject().put("url", "https://example.org/story").put("title", "Sample story").put("byline", "A. Writer")
            .put("siteName", "Example").put("excerpt", "Short").put("content", "<p>Hello</p>").put("length", 1200)
            .put("lang", "en").put("dir", "ltr").put("publishedTime", "2026-10-01")
        val id = db.save(ReaderMode.fromJson(article, now = 1000))
        assertEquals(id, db.save(ReaderMode.fromJson(article.put("title", "Sample story (updated)"), now = 2000))) // same URL → same row
        assertEquals(1, db.count())
        val got = db.get(id)!!
        assertEquals("Sample story (updated)", got.title)
        assertEquals("<p>Hello</p>", got.html)
        assertEquals(200, got.words)
        assertEquals("", db.list().single().html) // list doesn't load bodies
        val back = ReaderMode.toJson(got)
        assertEquals("Example", back.getString("siteName"))
        assertEquals("<p>Hello</p>", back.getString("content"))
        db.setRead(id, true)
        assertTrue(db.get(id)!!.read)
        db.delete(id)
        assertEquals(0, db.count())
    }
}
