package com.jamhowman.beastbrowser.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.databinding.ItemRadarNodeBinding
import com.jamhowman.beastbrowser.databinding.OverlayMediaRadarBinding
import com.jamhowman.beastbrowser.downloads.DlFormat
import com.jamhowman.beastbrowser.media.DetectedMedia
import com.jamhowman.beastbrowser.media.MediaSniffer
import org.mozilla.geckoview.GeckoSession
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Media Radar (2.3.7): full-screen dimmed overlay where every stream detected on the page orbits
 * the centre as a pulsing accent node. Tap a node → Media Save for just that stream;
 * long-press → toggle its quality/size label.
 */
object MediaRadar {
    private const val TEXT = 0xFFF2F2F7.toInt()
    private const val HINT = 0xFF6E6E7C.toInt()
    private const val PULSE_MS = 1200L
    private const val PULSE_ALPHA = 0.35f

    private val animators = mutableListOf<AnimatorSet>()

    fun show(
        activity: AppCompatActivity,
        binding: OverlayMediaRadarBinding,
        session: GeckoSession,
        isPrivate: Boolean,
        pageUrl: String,
        accentColor: Int,
        onAccentColor: Int,
    ) {
        stopPulses()
        binding.radarStage.removeAllViews()
        binding.radarTitle.setTextColor(TEXT)
        binding.radarCount.setTextColor(accentColor)
        binding.radarClose.imageTintList = ColorStateList.valueOf(TEXT)
        binding.radarEmpty.setTextColor(HINT)

        val streams = MediaSniffer.forSession(session)
        binding.radarCount.text = when (streams.size) {
            0 -> activity.getString(R.string.media_radar_empty)
            1 -> "1 stream"
            else -> "${streams.size} streams"
        }
        if (isPrivate && streams.isNotEmpty()) binding.radarCount.append(" · private")
        binding.radarEmpty.isVisible = streams.isEmpty()
        binding.radarClose.setOnClickListener { hide(binding) }
        binding.radarRoot.setOnClickListener { /* swallow taps so the page underneath doesn't get them */ }
        binding.radarRoot.isVisible = true
        if (streams.isEmpty()) return
        // Stage size is only known after layout.
        binding.radarStage.post { placeNodes(activity, binding, streams, session, isPrivate, pageUrl, accentColor, onAccentColor) }
    }

    fun hide(binding: OverlayMediaRadarBinding) {
        stopPulses()
        binding.radarStage.removeAllViews()
        binding.radarRoot.isVisible = false
    }

    fun isShowing(binding: OverlayMediaRadarBinding): Boolean = binding.radarRoot.isVisible

    private fun placeNodes(
        activity: AppCompatActivity,
        binding: OverlayMediaRadarBinding,
        streams: List<DetectedMedia>,
        session: GeckoSession,
        isPrivate: Boolean,
        pageUrl: String,
        accentColor: Int,
        onAccentColor: Int,
    ) {
        val stage = binding.radarStage
        val w = stage.width
        val h = stage.height
        if (w <= 0 || h <= 0) return
        val node = dp(activity, 72)
        val positions = orbitPositions(streams.size, w, h, node, dp(activity, 96))
        streams.forEachIndexed { i, media ->
            val nb = ItemRadarNodeBinding.inflate(LayoutInflater.from(activity), stage, false)
            nb.radarKind.text = kindLabel(media)
            nb.radarCore.setCardBackgroundColor(accentColor)
            nb.radarKind.setTextColor(onAccentColor)
            nb.radarPulse.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accentColor)
            }
            nb.radarPulse.alpha = PULSE_ALPHA
            nb.radarMeta.text = metaLabel(media)
            nb.radarMeta.isVisible = false

            val (cx, cy) = positions[i]
            val lp = FrameLayout.LayoutParams(node, node).apply {
                leftMargin = (cx - node / 2f).toInt().coerceIn(0, (w - node).coerceAtLeast(0))
                topMargin = (cy - node / 2f).toInt().coerceIn(0, (h - node).coerceAtLeast(0))
            }
            stage.addView(nb.root, lp)
            startPulse(nb.radarPulse, i)

            nb.root.setOnClickListener {
                if (MediaSniffer.isDrmHost(media.url) || MediaSniffer.isDrmHost(pageUrl)) {
                    Toast.makeText(activity, "Can't download from this site", Toast.LENGTH_SHORT).show()
                } else {
                    hide(binding)
                    MediaSaveSheet.show(activity, session, isPrivate, pageUrl, accentColor, onlyId = media.id)
                }
            }
            nb.root.setOnLongClickListener {
                nb.radarMeta.isVisible = !nb.radarMeta.isVisible
                true
            }
        }
    }

    /**
     * Golden-angle spiral around the stage centre (below the [headerPx] header), squashed a little
     * vertically, then nudged apart for a few passes so nodes don't overlap. Returns node centres.
     */
    internal fun orbitPositions(count: Int, width: Int, height: Int, nodePx: Int, headerPx: Int): List<Pair<Float, Float>> {
        if (count <= 0) return emptyList()
        val cx = width / 2f
        val cy = headerPx + (height - headerPx) / 2f
        if (count == 1) return listOf(cx to cy)

        val minGap = nodePx * 1.15f
        val radius = min(width, height - headerPx) * 0.38f
        val goldenAngle = Math.PI * (3.0 - sqrt(5.0))
        val margin = nodePx / 2f + 8f
        val minX = margin
        val maxX = width - margin
        val minY = headerPx + margin
        val maxY = height - margin
        val out = ArrayList<Pair<Float, Float>>(count)
        for (i in 0 until count) {
            val t = i / (count - 1.0)
            val r = (0.22 + t * 0.78) * radius
            val a = i * goldenAngle
            var x = (cx + cos(a) * r).toFloat().coerceIn(minX, maxX)
            var y = (cy + sin(a) * r * 0.85).toFloat().coerceIn(minY, maxY)
            repeat(4) {
                for ((px, py) in out) {
                    val dx = x - px
                    val dy = y - py
                    val d = sqrt(dx * dx + dy * dy)
                    if (d < minGap && d > 0.1f) {
                        val push = (minGap - d) / d
                        x = (x + dx * push * 0.55f).coerceIn(minX, maxX)
                        y = (y + dy * push * 0.55f).coerceIn(minY, maxY)
                    } else if (d <= 0.1f) {
                        x += minGap * 0.5f
                        y += minGap * 0.3f
                    }
                }
            }
            out += x to y
        }
        return out
    }

    private fun startPulse(view: View, index: Int) {
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = PULSE_ALPHA
        fun anim(prop: android.util.Property<View, Float>, vararg values: Float) =
            ObjectAnimator.ofFloat(view, prop, *values).apply {
                duration = PULSE_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
            }
        val set = AnimatorSet().apply {
            playTogether(anim(View.SCALE_X, 1f, 1.6f), anim(View.SCALE_Y, 1f, 1.6f), anim(View.ALPHA, PULSE_ALPHA, 0f))
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = (index * 120L) % PULSE_MS
        }
        set.start()
        animators += set
    }

    private fun stopPulses() {
        animators.forEach { it.cancel() }
        animators.clear()
    }

    private fun kindLabel(m: DetectedMedia): String = when {
        m.kind == DetectedMedia.Kind.HLS -> "HLS"
        m.mime.contains("webm", ignoreCase = true) -> "WebM"
        m.mime.contains("audio", ignoreCase = true) -> "AUD"
        else -> "MP4"
    }

    private fun metaLabel(m: DetectedMedia): String = buildString {
        append(m.qualityLabel.ifBlank { "Video" })
        if (m.bytes > 0) append(" · ").append(DlFormat.bytes(m.bytes))
        else if (m.width > 0 && m.height > 0) append(" · ${m.width}×${m.height}")
    }
}
