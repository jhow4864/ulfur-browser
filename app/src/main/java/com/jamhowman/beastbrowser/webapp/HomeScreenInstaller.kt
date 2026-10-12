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
 *   favicon if ≥96 px → white letter tile on theme_color / Ulfur red). Icons are fetched off the main thread.
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
        spec?.let { WebApps.pickManifestIcon(it.icons) }?.let { ic ->
            fetch(ic.src)?.takeIf { WebApps.bigEnough(it.width, it.height) }?.let { bmp ->
                return if (ic.maskable) IconCompat.createWithAdaptiveBitmap(fullBleed(bmp))
                else IconCompat.createWithAdaptiveBitmap(inset(bmp, spec.backgroundColor ?: 0xFFFFFFFF.toInt()))
            }
        }
        for (u in WebApps.fallbackIconUrls(spec?.startUrl ?: pageUrl)) {
            // Never upscale a tiny favicon: below 96 px a letter tile looks better.
            fetch(u)?.takeIf { WebApps.bigEnough(it.width, it.height) }?.let {
                return IconCompat.createWithAdaptiveBitmap(inset(it, spec?.backgroundColor ?: 0xFFFFFFFF.toInt()))
            }
        }
        return letterIcon(WebApps.letterTile(pageUrl, spec?.themeColor))
    }

    private fun fetch(src: String): Bitmap? = runCatching {
        val bytes = if (src.startsWith("data:")) {
            val comma = src.indexOf(',')
            if (comma < 0 || !src.substring(0, comma).contains(";base64")) return null
            Base64.decode(src.substring(comma + 1), Base64.DEFAULT)
        } else {
            val c = URL(src).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = true
                c.setRequestProperty("Accept", "image/png,image/webp,image/*;q=0.8")
                if (c.responseCode !in 200..299) return null
                val out = ByteArrayOutputStream()
                c.inputStream.use { inp ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); if (out.size() > MAX_BYTES) return null
                    }
                }
                out.toByteArray()
            } finally { c.disconnect() }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= ICON_PX) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Maskable icons are designed full-bleed: just scale to the adaptive canvas. */
    private fun fullBleed(src: Bitmap): Bitmap = Bitmap.createScaledBitmap(src, ICON_PX, ICON_PX, true)

    /** Regular icons go inside the adaptive safe zone on the app's background colour. */
    private fun inset(src: Bitmap, bg: Int): Bitmap {
        val out = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(bg or 0xFF000000.toInt())
        val box = ICON_PX * 0.58f
        val scale = box / maxOf(src.width, src.height)
        val w = src.width * scale; val h = src.height * scale
        val dst = RectF((ICON_PX - w) / 2, (ICON_PX - h) / 2, (ICON_PX + w) / 2, (ICON_PX + h) / 2)
        c.drawBitmap(src, Rect(0, 0, src.width, src.height), dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
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
