package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.media.PipPolicy
import com.jamhowman.beastbrowser.media.PipPolicy.MediaState
import com.jamhowman.beastbrowser.media.PipPolicy.Ratio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5 picture-in-picture: aspect ratio clamping and when PiP may start. */
class PipPolicyTest {
    @Test fun commonVideoSizesReduce() {
        assertEquals(Ratio(16, 9), PipPolicy.aspectRatio(1920, 1080))
        assertEquals(Ratio(4, 3), PipPolicy.aspectRatio(640, 480))
        assertEquals(Ratio(9, 16), PipPolicy.aspectRatio(1080, 1920)) // portrait shorts
        assertEquals(Ratio(1, 1), PipPolicy.aspectRatio(720, 720))
    }

    @Test fun unknownSizeFallsBackTo16by9() {
        assertEquals(PipPolicy.DEFAULT_RATIO, PipPolicy.aspectRatio(0, 0))
        assertEquals(PipPolicy.DEFAULT_RATIO, PipPolicy.aspectRatio(1920, 0))
        assertEquals(PipPolicy.DEFAULT_RATIO, PipPolicy.aspectRatio(-1, 400))
    }

    @Test fun extremeRatiosAreClampedToAndroidLimits() {
        // Android throws for ratios outside 1:2.39 .. 2.39:1
        assertEquals(Ratio(239, 100), PipPolicy.aspectRatio(3000, 1000))
        assertEquals(Ratio(100, 239), PipPolicy.aspectRatio(100, 1000))
        val cinema = PipPolicy.aspectRatio(2390, 1000) // exactly the limit stays as is
        assertEquals(2.39, cinema.value, 1e-9)
    }

    @Test fun ratioAlwaysWithinLimitsAndFitsInInts() {
        val sizes = listOf(1L to 1L, 1_000_003L to 999_983L, 7_680L to 4_320L, 123_457L to 51_659L, 5L to 12L, 12L to 5L)
        for ((w, h) in sizes) {
            val r = PipPolicy.aspectRatio(w, h)
            assertTrue("$w x $h -> $r", r.num in 1..10_000 && r.den in 1..10_000)
            assertTrue("$w x $h -> $r", r.value <= 2.39 + 1e-9 && r.value >= 1 / 2.39 - 1e-9)
            if (w.toDouble() / h in (1 / 2.39)..2.39) assertEquals(w.toDouble() / h, r.value, 0.01)
        }
    }

    @Test fun autoEnterNeedsAPlayingFullscreenVideo() {
        val playingFs = MediaState(playing = true, mediaFullscreen = true)
        assertTrue(PipPolicy.shouldAutoEnter(true, true, playingFs))
        assertTrue(PipPolicy.shouldAutoEnter(true, true, MediaState(playing = true, pageFullscreen = true)))
        assertFalse("paused", PipPolicy.shouldAutoEnter(true, true, MediaState(playing = false, mediaFullscreen = true)))
        assertFalse("not fullscreen (music / inline video)", PipPolicy.shouldAutoEnter(true, true, MediaState(playing = true)))
        assertFalse("setting off", PipPolicy.shouldAutoEnter(false, true, playingFs))
        assertFalse("no PiP on device", PipPolicy.shouldAutoEnter(true, false, playingFs))
    }

    @Test fun fullscreenButtonRules() {
        assertTrue(PipPolicy.fullscreenButtonShown(fullscreen = true, supported = true, enabled = true, inPip = false))
        assertFalse(PipPolicy.fullscreenButtonShown(fullscreen = false, supported = true, enabled = true, inPip = false))
        assertFalse(PipPolicy.fullscreenButtonShown(fullscreen = true, supported = false, enabled = true, inPip = false))
        assertFalse(PipPolicy.fullscreenButtonShown(fullscreen = true, supported = true, enabled = false, inPip = false))
        assertFalse(PipPolicy.fullscreenButtonShown(fullscreen = true, supported = true, enabled = true, inPip = true))
        assertEquals(3_000L, PipPolicy.FULLSCREEN_BUTTON_HIDE_MS)
        assertEquals(listOf(true, true, true, false, false), (0..4).map { PipPolicy.fullscreenButtonLabelled(it) })
    }

    @Test fun seekActionsOnlyForKnownLengthMedia() {
        assertTrue(PipPolicy.seekActionsAvailable(600.0, 3))
        assertFalse("live stream", PipPolicy.seekActionsAvailable(Double.POSITIVE_INFINITY, 3))
        assertFalse("unknown", PipPolicy.seekActionsAvailable(0.0, 3))
        assertFalse(PipPolicy.seekActionsAvailable(Double.NaN, 3))
        assertFalse("no room for 3 actions", PipPolicy.seekActionsAvailable(600.0, 1))
    }

    @Test fun seekTargetsStayInsideTheMedia() {
        assertEquals(50.0, PipPolicy.seekTarget(40.0, 10.0, 600.0), 1e-9)
        assertEquals(30.0, PipPolicy.seekTarget(40.0, -10.0, 600.0), 1e-9)
        assertEquals(0.0, PipPolicy.seekTarget(4.0, -10.0, 600.0), 1e-9)
        assertEquals(600.0, PipPolicy.seekTarget(595.0, 10.0, 600.0), 1e-9)
        // position extrapolated from the last MediaSession position state
        assertEquals(43.0, PipPolicy.estimatedPosition(40.0, 1.5, playing = true, elapsedSec = 2.0, duration = 600.0), 1e-9)
        assertEquals(40.0, PipPolicy.estimatedPosition(40.0, 1.0, playing = false, elapsedSec = 9.0, duration = 600.0), 1e-9)
        assertEquals(600.0, PipPolicy.estimatedPosition(599.0, 1.0, playing = true, elapsedSec = 9.0, duration = 600.0), 1e-9)
    }

    @Test fun menuActionNeedsPlayingMedia() {
        assertTrue(PipPolicy.canEnterManually(true, true, MediaState(playing = true)))
        assertFalse(PipPolicy.canEnterManually(true, true, MediaState(playing = false)))
        assertFalse(PipPolicy.canEnterManually(false, true, MediaState(playing = true)))
        assertFalse(PipPolicy.canEnterManually(true, false, MediaState(playing = true)))
    }
}
