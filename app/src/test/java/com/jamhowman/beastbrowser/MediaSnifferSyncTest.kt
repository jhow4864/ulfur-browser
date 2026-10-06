package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.media.MediaSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The extension's DRM list (media/sniffer-core.js) and the app's hard block (MediaSniffer) must agree. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSnifferSyncTest {
    private val core = listOf("src/main/assets", "app/src/main/assets").map { File(it, "extensions/beast-helper/media/sniffer-core.js") }.first { it.isFile }

    private fun jsHosts(): Set<String> {
        val body = Regex("""BEAST_DRM_HOSTS\s*=\s*(?:Object\.freeze\()?\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(core.readText())!!.groupValues[1]
        return Regex("""["']([a-z0-9.\-]+)["']""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    @Test fun extensionAndAppListsMatch() {
        val js = jsHosts()
        val app = MediaSniffer.drmSuffixes.toSet()
        assertTrue("JS list looks wrong: $js", js.size >= 25)
        assertEquals("only in extension: ${js - app}, only in app: ${app - js}", app, js)
        js.forEach { assertTrue(it, MediaSniffer.isDrmHost(it)); assertTrue(it, MediaSniffer.isDrmHost("https://cdn.$it/v.mp4")) }
    }

    @Test fun addedHostsAndIplayerPath() {
        listOf(
            "https://i.ytimg.com/vi/x/hq.jpg", "https://usher.ttvnw.net/x.m3u8", "https://tv.apple.com/gb/show/x",
            "https://www.peacocktv.com/watch", "https://www.paramountplus.com/x", "https://www.crunchyroll.com/x",
            "https://www.itv.com/watch/x", "https://www.channel4.com/programmes/x", "https://audio-ak.scdn.co/x",
            "https://atv-ps.amazonvideo.com/x", "https://www.bbc.co.uk/iplayer/episode/b0abc",
        ).forEach { assertTrue(it, MediaSniffer.isDrmHost(it)) }
        listOf(
            "https://www.bbc.co.uk/news/articles/x", "bbc.co.uk", "https://apple.com/tv", "https://notnetflix.com/x.mp4",
            "https://youtube.com.example.org/v.mp4", "https://archive.org/download/a/a.mp4",
        ).forEach { assertFalse(it, MediaSniffer.isDrmHost(it)) }
    }
}
