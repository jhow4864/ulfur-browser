package com.jamhowman.beastbrowser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ImageView
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.vectordrawable.graphics.drawable.SeekableAnimatedVectorDrawable
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.ThemePreset
import com.jamhowman.beastbrowser.ui.WolfHero
import com.jamhowman.beastbrowser.ui.WolfHero.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Item 19: the new-tab wolf's states, reduce motion, and that its gradients pick up the theme overlay. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "night-xxhdpi")
class WolfHeroTest {
    private val out = File(System.getProperty("preview.dir") ?: "build/previews").apply { mkdirs() }

    @Before fun reset() {
        Prefs.init(ApplicationProvider.getApplicationContext())
        Prefs.sp.edit(commit = true) { clear() }
    }

    private fun hero(preset: ThemePreset = ThemePreset.BLOOD_MOON): Pair<ImageView, WolfHero> {
        val c = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Beast).apply {
            theme.applyStyle(preset.overlay, true)
        }
        val v = ImageView(c)
        val px = (140 * c.resources.displayMetrics.density).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, px, px)
        return v to WolfHero(v).also { it.refresh(ghost = preset == ThemePreset.GHOST) }
    }

    private fun snap(v: ImageView, name: String? = null): Bitmap {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(0xFF0E0E12.toInt()); v.draw(this) }
        if (name != null) File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun idleLoadingDoneAndBackToIdle() {
        val (v, w) = hero()
        assertEquals(State.HIDDEN, w.state)
        w.onHomeShown()
        assertEquals(State.IDLE, w.state)
        assertTrue(w.pulsing)
        snap(v, "wolf_idle_blood_moon.png")

        w.onProgress(30); w.onProgress(60)
        assertEquals(State.LOADING, w.state)
        assertTrue(v.drawable is SeekableAnimatedVectorDrawable)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertEquals(60, w.shownProgress)
        assertEquals(600L, (v.drawable as SeekableAnimatedVectorDrawable).currentPlayTime)
        snap(v, "wolf_loading_60_blood_moon.png")

        assertTrue("success plays the eye flash", w.onPageStop(true))
        assertEquals(State.DONE, w.state)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(WolfHero.DONE_MS + 100))
        assertEquals(State.IDLE, w.state)

        w.onHomeHidden()
        assertEquals(State.HIDDEN, w.state)
        assertFalse(w.pulsing)
        w.onProgress(50)
        assertEquals("no ring while the home page is hidden", State.HIDDEN, w.state)
    }

    @Test fun failedLoadSkipsTheFlash() {
        val (_, w) = hero()
        w.onHomeShown(); w.onProgress(20)
        assertFalse(w.onPageStop(false))
        assertEquals(State.IDLE, w.state)
    }

    @Test fun switchOffKeepsTheRingButNoPulseOrFlash() {
        Prefs.wolfAnimation = false
        val (v, w) = hero()
        assertFalse(w.motion)
        w.onHomeShown()
        assertFalse("rests on the static frame", w.pulsing)
        w.onProgress(40); idle()
        assertEquals(State.LOADING, w.state)
        assertFalse(w.onPageStop(true))
        assertEquals(State.IDLE, w.state)
    }

    @Test fun systemAnimationsOffJumpsTheRing() {
        android.provider.Settings.Global.putFloat(
            ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.java).invoke(null, 0f)
        try {
            val (_, w) = hero()
            assertFalse(w.motion)
            w.onHomeShown(); w.onProgress(70)
            assertEquals("no easing: drawn at once", 70, w.shownProgress)
        } finally {
            android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.java).invoke(null, 1f)
        }
    }

    @Test fun ghostIsDimSlowAndNeverFlashes() {
        val (v, w) = hero(ThemePreset.GHOST)
        assertEquals(WolfHero.GHOST_ALPHA, v.imageAlpha)
        w.onHomeShown()
        snap(v, "wolf_idle_ghost.png")
        w.onProgress(50); idle()
        assertFalse(w.onPageStop(true))
        assertEquals(State.IDLE, w.state)
    }

    @Test fun gradientsFollowTheTheme() {
        val (bv, b) = hero(ThemePreset.BLOOD_MOON)
        val (fv, f) = hero(ThemePreset.FROST)
        b.onHomeShown(); f.onHomeShown()
        b.onHomeHidden(); f.onHomeHidden() // rest frame, no pulse
        val bb = snap(bv); val fb = snap(fv, "wolf_rest_frost.png")
        assertNotEquals("Blood Moon and Frost wolves render differently", bb.sameAs(fb), true)
        // Somewhere in the mark, Frost is blue-dominant and Blood Moon red-dominant.
        fun dominant(bmp: Bitmap, red: Boolean): Boolean {
            for (y in 0 until bmp.height step 4) for (x in 0 until bmp.width step 4) {
                val c = bmp.getPixel(x, y)
                val r = Color.red(c); val bl = Color.blue(c)
                if (red && r > 180 && r > bl + 80) return true
                if (!red && bl > 180 && bl > r + 80) return true
            }
            return false
        }
        assertTrue(dominant(bb, red = true))
        assertTrue(dominant(fb, red = false))
    }
}
