package com.jamhowman.beastbrowser

import android.app.Application
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.media.MediaSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.8: Media Radar is opt-in and off by default. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaRadarOptInTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun clean() {
        Prefs.init(ctx)
        Prefs.sp.edit(commit = true) { clear() }
    }

    @Test fun offByDefault() {
        assertFalse(Prefs.mediaRadar)
    }

    @Test fun clearAllEmptiesEveryTab() {
        val a = GeckoSession(); val b = GeckoSession()
        MediaSniffer.clearAll()
        assertEquals(0, MediaSniffer.count(a))
        assertEquals(0, MediaSniffer.count(b))
        assertFalse(MediaSniffer.hasMedia(a))
    }
}
