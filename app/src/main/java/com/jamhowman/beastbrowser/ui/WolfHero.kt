package com.jamhowman.beastbrowser.ui

import android.animation.ValueAnimator
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.annotation.VisibleForTesting
import androidx.vectordrawable.graphics.drawable.SeekableAnimatedVectorDrawable
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.Prefs

/**
 * The new-tab hero (roadmap item 19, SPEC.md "Animated wolf"): a breathing glow while the home page is up, a ring
 * that follows Gecko's progress while a load started from the home page hasn't painted yet, a one-shot eye flash when
 * that load finishes, and Ghost's slower, dimmer pulse.
 *
 * Reduce motion: with system animations off ([ValueAnimator.areAnimatorsEnabled]) or the "Animated wolf" switch off,
 * the wolf rests on its static frame, with no pulse and no flash. The loading ring still shows progress because it
 * carries information; it jumps to each value when system animations are off. Call [refresh] from onResume so a change
 * to either setting is picked up, and whenever the theme or realm changes (the drawables read `?attr/ulfur*`).
 */
class WolfHero(private val view: ImageView) {
    enum class State { HIDDEN, IDLE, LOADING, DONE }

    var state = State.HIDDEN
        private set
    /** Pulse and flash allowed: system animations on and the switch on. */
    var motion = true
        private set
    var ghost = false
        private set
    /** Last progress drawn on the ring (0..100). */
    @VisibleForTesting var shownProgress = 0
        private set

    /** The idle pulse (or Ghost's) is running. */
    var pulsing = false
        private set

    private var systemMotion = true
    private var idle: AnimatedVectorDrawable? = null
    private var done: AnimatedVectorDrawable? = null
    private var ring: SeekableAnimatedVectorDrawable? = null
    private var smoother: ValueAnimator? = null

    private val doneCallback = object : Animatable2.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable) = backToIdle()
    }
    private val doneFallback = Runnable { backToIdle() }
    private val main = Handler(Looper.getMainLooper())

    private fun backToIdle() {
        if (state != State.DONE) return
        main.removeCallbacks(doneFallback)
        state = State.IDLE
        showIdle()
    }

    /** Re-reads the motion settings and re-inflates the drawables against the view's current theme. */
    fun refresh(ghost: Boolean) {
        this.ghost = ghost
        systemMotion = ValueAnimator.areAnimatorsEnabled()
        motion = systemMotion && Prefs.wolfAnimation
        view.imageAlpha = if (ghost) GHOST_ALPHA else 255
        stopAll()
        idle = null; done = null; ring = null
        when (state) {
            State.HIDDEN -> {}
            State.IDLE, State.DONE -> { state = State.IDLE; showIdle() }
            State.LOADING -> seek(shownProgress)
        }
    }

    /** The home page became visible (and the activity is resumed). No-op if it already was. */
    fun onHomeShown() {
        if (state != State.HIDDEN) return
        state = State.IDLE
        showIdle()
    }

    /** The home page is covered or gone (a page, the tab switcher, the app in the background). */
    fun onHomeHidden() {
        if (state == State.HIDDEN) return
        stopAll()
        state = State.HIDDEN
        shownProgress = 0
        // Back to the rest frame, so a stopped pulse doesn't freeze mid-breath when the view next shows.
        view.setImageDrawable(idleDrawable())
    }

    /** Gecko progress (0..100) for a load started from the home page. */
    fun onProgress(progress: Int) {
        if (state == State.HIDDEN || state == State.DONE) return
        val p = progress.coerceIn(0, 100)
        if (state != State.LOADING) {
            stopAll()
            state = State.LOADING
            shownProgress = 0
            view.setImageDrawable(ringDrawable().also { it.currentPlayTime = 0 })
        }
        if (!systemMotion || p <= shownProgress) { smoother?.cancel(); seek(p); return }
        val from = shownProgress
        smoother?.cancel()
        smoother = ValueAnimator.ofInt(from, p).apply {
            duration = SMOOTH_MS
            interpolator = AnimationUtils.loadInterpolator(view.context, android.R.interpolator.linear_out_slow_in)
            addUpdateListener { if (state == State.LOADING) seek(it.animatedValue as Int) }
            start()
        }
    }

    /**
     * The load finished. Plays the eye flash on success (not in Ghost, not with motion off), then goes back to idle.
     * Returns true if the flash is playing, so the caller can keep the home page up for [DONE_MS].
     */
    fun onPageStop(success: Boolean): Boolean {
        if (state != State.LOADING) return false
        smoother?.cancel()
        if (success && motion && !ghost) {
            state = State.DONE
            val d = doneDrawable()
            view.setImageDrawable(d)
            d.registerAnimationCallback(doneCallback)
            d.start()
            main.postDelayed(doneFallback, DONE_MS + 50) // in case the end callback never comes
            return true
        }
        state = State.IDLE
        showIdle()
        return false
    }

    private fun showIdle() {
        val d = idleDrawable()
        view.setImageDrawable(d)
        if (motion) { d.start(); pulsing = true }
    }

    private fun seek(p: Int) {
        shownProgress = p
        val r = ringDrawable()
        if (view.drawable !== r) view.setImageDrawable(r)
        r.currentPlayTime = p * 10L // 0..100 -> 0..1000 ms, the length of avd_wolf_progress
    }

    private fun stopAll() {
        smoother?.cancel(); smoother = null
        main.removeCallbacks(doneFallback)
        idle?.stop()
        pulsing = false
        done?.let { it.unregisterAnimationCallback(doneCallback); it.stop() }
    }

    private fun idleDrawable(): AnimatedVectorDrawable = idle ?: (view.context.getDrawable(
        if (ghost) R.drawable.avd_wolf_ghost_idle else R.drawable.avd_wolf_idle
    )!!.mutate() as AnimatedVectorDrawable).also { idle = it }

    private fun doneDrawable(): AnimatedVectorDrawable = done
        ?: (view.context.getDrawable(R.drawable.avd_wolf_done)!!.mutate() as AnimatedVectorDrawable).also { done = it }

    private fun ringDrawable(): SeekableAnimatedVectorDrawable = ring
        ?: SeekableAnimatedVectorDrawable.create(view.context, R.drawable.avd_wolf_progress)!!.also { ring = it }

    companion object {
        /** Ghost: the mark at 78% opacity (SPEC.md "Ghost" row). */
        const val GHOST_ALPHA = 199
        const val SMOOTH_MS = 250L
        /** Length of avd_wolf_done. */
        const val DONE_MS = 450L
    }
}
