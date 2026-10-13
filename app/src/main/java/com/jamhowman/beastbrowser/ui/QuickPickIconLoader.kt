package com.jamhowman.beastbrowser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.jamhowman.beastbrowser.webapp.QuickPickIcons
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** 2.12: disk + memory cache for quick-pick icons, loaded off the main thread. See [QuickPickIcons] for the rules. */
object QuickPickIconLoader {
    private const val PX = 128
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "ulfur-qp-icons").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val mem = LruCache<String, Bitmap>(64)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "quickpick-icons").apply { mkdirs() }

    /** Memory-cached icon for [url]'s origin, if any (main thread safe). */
    fun cached(url: String): Bitmap? = QuickPickIcons.httpsOrigin(url)?.let { mem.get(it) }

    /**
     * Calls [done] on the main thread with the icon (cache first; network only when [allowNetwork] and the policy
     * agrees), or null for the letter tile. Never throws; broken / SVG / tiny images give null.
     */
    fun load(ctx: Context, url: String, allowNetwork: Boolean, fromPrivate: Boolean, done: (Bitmap?) -> Unit) {
        val origin = QuickPickIcons.httpsOrigin(url) ?: return done(null)
        mem.get(origin)?.let { done(it); if (!allowNetwork) return }
        val d = dir(ctx)
        io.execute {
            val (png, none) = QuickPickIcons.files(d, origin)
            var bmp = mem.get(origin) ?: if (png.exists()) decodeFile(png) else null
            val now = System.currentTimeMillis()
            if (QuickPickIcons.shouldFetch(url, !allowNetwork, fromPrivate, if (png.exists()) png.lastModified() else 0L,
                    if (none.exists()) none.lastModified() else 0L, now) && inFlight.add(origin)) {
                try {
                    val fresh = fetch(origin)
                    if (fresh != null) {
                        runCatching { png.outputStream().use { fresh.compress(Bitmap.CompressFormat.PNG, 100, it) }; none.delete() }
                        bmp = fresh
                    } else if (bmp == null) runCatching { none.writeText(now.toString()) }
                    else png.setLastModified(now) // keep the old icon a while longer
                } finally { inFlight.remove(origin) }
            }
            bmp?.let { mem.put(origin, it) }
            val result = bmp
            main.post { done(result) }
        }
    }

    private fun fetch(origin: String): Bitmap? {
        for (u in QuickPickIcons.candidates(origin) { QuickPickIcons.pageLinks(origin) }) {
            val bytes = runCatching { QuickPickIcons.download(u, "image/png,image/webp,image/*;q=0.8") }.getOrNull() ?: continue
            decode(bytes)?.let { return it }
        }
        return null
    }

    private fun decodeFile(f: File): Bitmap? = runCatching { decode(f.readBytes()) }.getOrNull()

    /** Bounds first (SVG / HTML / garbage → 0 → null), then a down-sampled decode; null below 32 px. */
    fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (!QuickPickIcons.usable(o.outWidth, o.outHeight)) return null
        var sample = 1
        while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= PX) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (!QuickPickIcons.usable(b.width, b.height)) null else b
    }.getOrNull()

    /** Edge transparency and mean luminance for [QuickPickIcons.needsOutline]. */
    fun stats(b: Bitmap): Pair<Float, Float> {
        val w = b.width; val h = b.height
        var edge = 0; var clear = 0; var lum = 0.0; var opaque = 0
        val step = maxOf(1, minOf(w, h) / 16)
        for (y in 0 until h step step) for (x in 0 until w step step) {
            val c = b.getPixel(x, y)
            val isEdge = x < step || y < step || x >= w - step || y >= h - step
            if (isEdge) { edge++; if (Color.alpha(c) < 128) clear++ }
            if (Color.alpha(c) >= 128) {
                opaque++; lum += (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0
            }
        }
        return (if (edge == 0) 0f else clear.toFloat() / edge) to (if (opaque == 0) 0f else (lum / opaque).toFloat())
    }
}
