package com.jamhowman.beastbrowser.webapp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.jamhowman.beastbrowser.ui.MainActivity
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Issue #34: "Add to Home screen" that actually lands on the home screen.
 *
 * - Pinned shortcut with the site's own icon (maskable manifest icon → manifest icon ≥144 px → apple-touch-icon /
 *   page <link> icons (largest declared first) → fixed-path favicons if ≥96 px → white letter tile on theme_color / Ulfur red). Icons are fetched off the main thread.
 * - Sites with a manifest open in [WebAppActivity] (own task per app); sites without one open a normal Ulfur tab.
 * - A stable id per site, so re-adding updates the existing shortcut instead of duplicating it.
 * - requestPinShortcut gets a callback; if the launcher never confirms (ColorOS/Realme "Create home screen
 *   shortcuts" permission off, which fails silently) the caller is told so it can point at App info.
 */
object HomeScreenInstaller {
    private const val TAG = "UlfurA2HS"
    private const val ICON_PX = 432 // 108dp at xxxhdpi
    private const val MAX_BYTES = 4 * 1024 * 1024
    const val CONFIRM_TIMEOUT_MS = 5_000L

    sealed class Result {
        /** Launcher asked; confirmation will arrive through [PinResultReceiver] (or not, see [CONFIRM_TIMEOUT_MS]). */
        data class Requested(val id: String, val label: String) : Result()
        /** Already on the home screen: icon/label refreshed in place. */
        data class Updated(val label: String) : Result()
        /** isRequestPinShortcutSupported is false or the request was refused outright. */
        object Unsupported : Result()
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ulfur-a2hs").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val confirmed = ConcurrentHashMap.newKeySet<String>()

    fun markConfirmed(id: String) { confirmed += id }
    fun isConfirmed(id: String) = id in confirmed

    fun install(ctx: Context, title: String?, pageUrl: String, spec: WebAppSpec?, done: (Result) -> Unit) {
        val app = ctx.applicationContext
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(app)) { done(Result.Unsupported); return }
        val usable = spec?.takeIf { it.standalone }
        val id = WebApps.shortcutId(usable, pageUrl)
        val label = WebApps.label(usable ?: spec, title, pageUrl)
        io.execute {
            val icon = runCatching { buildIcon(usable ?: spec, pageUrl) }.getOrElse {
                Log.w(TAG, "icon failed: ${it.javaClass.simpleName}")
                letterIcon(WebApps.letterTile(pageUrl, spec?.themeColor))
            }
            main.post { done(pin(app, id, label, title, pageUrl, usable, icon)) }
        }
    }

    private fun pin(ctx: Context, id: String, label: String, title: String?, pageUrl: String, spec: WebAppSpec?, icon: IconCompat): Result {
        val intent = if (spec != null) WebAppActivity.intent(ctx, spec)
        else Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl), ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val info = ShortcutInfoCompat.Builder(ctx, id)
            .setShortLabel(label)
            .setLongLabel(spec?.name?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() } ?: label)
            .setIcon(icon)
            .setIntent(intent)
            .build()
        val pinned = runCatching {
            ShortcutManagerCompat.getShortcuts(ctx, ShortcutManagerCompat.FLAG_MATCH_PINNED).any { it.id == id }
        }.getOrDefault(false)
        if (pinned && runCatching { ShortcutManagerCompat.updateShortcuts(ctx, listOf(info)) }.getOrDefault(false)) {
            return Result.Updated(label)
        }
        confirmed -= id
        val cb = PendingIntent.getBroadcast(
            ctx, id.hashCode(),
            Intent(ctx, PinResultReceiver::class.java).setAction(PinResultReceiver.ACTION).putExtra(PinResultReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ok = runCatching { ShortcutManagerCompat.requestPinShortcut(ctx, info, cb.intentSender) }
            .onFailure { Log.w(TAG, "requestPinShortcut: ${it.javaClass.simpleName}") }.getOrDefault(false)
        return if (ok) Result.Requested(id, label) else Result.Unsupported
    }

    // ------------------------------------------------------------------ icons (background thread)

    private fun buildIcon(spec: WebAppSpec?, pageUrl: String): IconCompat {
        // 2.8.1: manifest icons, then the page's own <link> icons (fetched only if needed), then fixed paths.
        val cands = PageIcons.candidates(spec, pageUrl) { pageLinks(pageUrl) }
        val hit = PageIcons.firstUsable(cands, ::fetch) { it.width to it.height }
            ?: return letterIcon(WebApps.letterTile(pageUrl, spec?.themeColor))
        val (c, bmp) = hit
        Log.d(TAG, "icon from ${c.source} ${bmp.width}x${bmp.height}")
        return IconCompat.createWithAdaptiveBitmap(if (c.fullBleed) fullBleed(bmp) else inset(bmp))
    }

    /** GET [url], following up to 5 redirects (including http→https, which HttpURLConnection won't do itself). */
    private fun download(url: String, accept: String, max: Int): ByteArray? {
        var u = url
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = false
                c.setRequestProperty("Accept", accept)
                val code = c.responseCode
                if (code in 300..399) { u = PageIcons.redirectTarget(u, c.getHeaderField("Location")) ?: return null; return@repeat }
                if (code !in 200..299) return null
                val out = ByteArrayOutputStream()
                c.inputStream.use { inp ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); if (out.size() > max) { if (max == MAX_BYTES) return null else break }
                    }
                }
                return out.toByteArray()
            } finally { c.disconnect() }
        }
        return null
    }

    /** The live page's icon links. GeckoView only hands us the manifest, so read the page's HTML head. */
    private fun pageLinks(pageUrl: String): List<PageIcon> = runCatching {
        if (WebApps.origin(pageUrl) == null) return emptyList()
        val bytes = download(pageUrl, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5", PageIcons.MAX_HTML_CHARS) ?: return emptyList()
        PageIcons.parse(String(bytes, Charsets.UTF_8), pageUrl)
    }.getOrDefault(emptyList())

    private fun fetch(src: String): Bitmap? = runCatching {
        val bytes = if (src.startsWith("data:")) {
            val comma = src.indexOf(',')
            if (comma < 0 || !src.substring(0, comma).contains(";base64")) return null
            Base64.decode(src.substring(comma + 1), Base64.DEFAULT)
        } else download(src, "image/png,image/webp,image/*;q=0.8", MAX_BYTES) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= ICON_PX) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Maskable icons are designed full-bleed: just scale to the adaptive canvas. */
    private fun fullBleed(src: Bitmap): Bitmap = Bitmap.createScaledBitmap(src, ICON_PX, ICON_PX, true)

    /** Non-maskable icons go inside the 66/108 adaptive safe zone on their own corner colour (white if transparent). */
    private fun inset(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val mx = src.width - 1; val my = src.height - 1
        c.drawColor(PageIcons.cornerColor(intArrayOf(src.getPixel(0, 0), src.getPixel(mx, 0), src.getPixel(0, my), src.getPixel(mx, my))))
        val r = PageIcons.safeZoneRect(ICON_PX, src.width, src.height)
        c.drawBitmap(src, Rect(0, 0, src.width, src.height), RectF(r[0], r[1], r[2], r[3]), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    fun letterIcon(tile: IconChoice.Letter): IconCompat {
        val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(tile.color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = ICON_PX * 0.36f
        }
        c.drawText(tile.letter, ICON_PX / 2f, ICON_PX / 2f - (p.descent() + p.ascent()) / 2f, p)
        return IconCompat.createWithAdaptiveBitmap(bmp)
    }
}

/** Launcher's confirmation that a shortcut was actually pinned (the IntentSender passed to requestPinShortcut). */
class PinResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        intent.getStringExtra(EXTRA_ID)?.let { HomeScreenInstaller.markConfirmed(it) }
    }

    companion object {
        const val ACTION = "com.jamhowman.beastbrowser.action.SHORTCUT_PINNED"
        const val EXTRA_ID = "id"
    }
}
