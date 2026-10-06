package com.jamhowman.beastbrowser.media

/**
 * 2.5: picture-in-picture rules, kept free of Android types so they can be unit tested.
 *
 * Android rejects PiP aspect ratios outside 1:2.39 .. 2.39:1 (IllegalArgumentException), so the video's size is
 * clamped into that range. Unknown sizes fall back to 16:9.
 */
object PipPolicy {
    /** Widest ratio Android accepts, as a fraction (2.39:1). */
    const val MAX_NUM = 239
    const val MAX_DEN = 100
    val DEFAULT_RATIO = Ratio(16, 9)

    data class Ratio(val num: Int, val den: Int) {
        val value: Double get() = num.toDouble() / den
    }

    /** Aspect ratio for a video of [width] x [height] pixels, reduced and clamped to what Android allows. */
    fun aspectRatio(width: Long, height: Long): Ratio {
        if (width <= 0 || height <= 0) return DEFAULT_RATIO
        val max = MAX_NUM.toDouble() / MAX_DEN
        val r = width.toDouble() / height
        if (r > max) return Ratio(MAX_NUM, MAX_DEN)
        if (r < 1 / max) return Ratio(MAX_DEN, MAX_NUM)
        var w = width
        var h = height
        val g = gcd(w, h)
        w /= g; h /= g
        // Huge co-prime sizes: scale down while keeping the ratio (Rational takes ints).
        while (w > 10_000 || h > 10_000) { w = (w + 1) / 2; h = (h + 1) / 2 }
        return Ratio(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /** Media state of one tab, as reported by GeckoView's MediaSession delegate and fullscreen callbacks. */
    data class MediaState(
        val playing: Boolean = false,
        /** A media element (video) is fullscreen: MediaSession.Delegate.onFullscreen. */
        val mediaFullscreen: Boolean = false,
        /** The page is in DOM fullscreen: ContentDelegate.onFullScreen. */
        val pageFullscreen: Boolean = false,
    )

    /**
     * Leaving the app enters PiP on its own only for a playing video that is fullscreen, the case where the PiP
     * window shows just the video. Audio-only pages and fullscreen games never auto-enter.
     */
    fun shouldAutoEnter(enabled: Boolean, supported: Boolean, state: MediaState): Boolean =
        enabled && supported && state.playing && (state.mediaFullscreen || state.pageFullscreen)

    /** Designer SPEC (c): fullscreen PiP button hides after 3 s without a touch. */
    const val FULLSCREEN_BUTTON_HIDE_MS = 3_000L
    /** The button shows its "Picture-in-picture" label the first 3 times, then becomes a compact circle. */
    const val FULLSCREEN_BUTTON_LABEL_TIMES = 3
    /** PiP window seek actions jump this far. */
    const val SEEK_SECONDS = 10.0

    fun fullscreenButtonShown(fullscreen: Boolean, supported: Boolean, enabled: Boolean, inPip: Boolean) =
        fullscreen && supported && enabled && !inPip

    /** [timesShown] = fullscreen sessions that already showed the button (pref `pip_hint_count`). */
    fun fullscreenButtonLabelled(timesShown: Int) = timesShown < FULLSCREEN_BUTTON_LABEL_TIMES

    /** Back / forward 10 s actions only for media with a known, finite length (not live streams) and room for 3 actions. */
    fun seekActionsAvailable(duration: Double, maxActions: Int) =
        maxActions >= 3 && duration.isFinite() && duration > 0

    /** Where playback is now, from the last MediaSession position state ([elapsedSec] since it was reported). */
    fun estimatedPosition(position: Double, playbackRate: Double, playing: Boolean, elapsedSec: Double, duration: Double): Double {
        val p = if (playing && playbackRate > 0 && elapsedSec > 0) position + elapsedSec * playbackRate else position
        return if (duration.isFinite() && duration > 0) p.coerceIn(0.0, duration) else p.coerceAtLeast(0.0)
    }

    /** Seek target for a [delta]-second jump, kept inside the media. */
    fun seekTarget(position: Double, delta: Double, duration: Double): Double =
        (position + delta).coerceIn(0.0, if (duration.isFinite() && duration > 0) duration else Double.MAX_VALUE)

    /** The menu action is offered whenever media is playing in the tab (the user picks it explicitly). */
    fun canEnterManually(enabled: Boolean, supported: Boolean, state: MediaState): Boolean =
        enabled && supported && state.playing
}
