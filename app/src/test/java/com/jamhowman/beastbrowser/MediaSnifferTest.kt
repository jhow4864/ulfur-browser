
package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.media.DetectedMedia
import com.jamhowman.beastbrowser.media.MediaSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSnifferTest {
    @Test fun blocksYoutubeAndNetflix() {
        assertTrue(MediaSniffer.isDrmHost("https://www.youtube.com/watch?v=x"))
        assertTrue(MediaSniffer.isDrmHost("youtu.be"))
        assertTrue(MediaSniffer.isDrmHost("https://netflix.com/title/1"))
        assertTrue(MediaSniffer.isDrmHost("googlevideo.com"))
        assertFalse(MediaSniffer.isDrmHost("https://example.com/video.mp4"))
    }

    @Test fun publishStripsDrmItems() {
        val session = GeckoSession()
        MediaSniffer.publish(session, listOf(
            DetectedMedia("1", "https://www.youtube.com/watch?v=1", pageUrl = "https://youtube.com"),
            DetectedMedia("2", "https://cdn.example.com/a.mp4", qualityLabel = "720p", pageUrl = "https://example.com"),
        ))
        assertEquals(1, MediaSniffer.count(session))
        assertEquals("https://cdn.example.com/a.mp4", MediaSniffer.forSession(session).first().url)
        MediaSniffer.clear(session)
        assertEquals(0, MediaSniffer.count(session))
    }
}
