package com.jamhowman.beastbrowser.webapp

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 2.12 home quick-pick icons (docs/design-drop-2.12.md §B). Pure JVM policy (what may be fetched, cache naming and
 * age, size floor, outline plate) plus the network fetch; bitmap decoding lives in the Android loader.
 * Privacy rules: https only, no cookies (fresh HttpURLConnection, no CookieHandler, caches off), ~1 MB cap, nothing
 * at all while the home page is shown in Private / Ghost, and never for a tile that was added from a private tab.
 */
object QuickPickIcons {
    const val MAX_BYTES = 1024 * 1024
    const val MIN_EDGE = 32
    const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000
    /** A failed lookup is retried after a day, not on every home-page draw. */
    const val RETRY_FAILED_MS = 24L * 60 * 60 * 1000

    fun cacheName(origin: String): String =
        MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    /** The https origin a tile's icon belongs to, or null (http, junk, other schemes: letter tile only). */
    fun httpsOrigin(url: String): String? = WebApps.origin(url)?.takeIf { it.startsWith("https://") }

    /**
     * Whether to go to the network for [url]'s icon. [privateState]: the home page is showing for a Private / Ghost tab.
     * [tileFromPrivate]: the tile was added from a private tab. [cachedAt] / [failedAt]: file times, 0 if absent.
     */
    fun shouldFetch(url: String, privateState: Boolean, tileFromPrivate: Boolean, cachedAt: Long, failedAt: Long, now: Long): Boolean {
        if (privateState || tileFromPrivate) return false
        if (httpsOrigin(url) == null) return false
        if (cachedAt > 0 && now - cachedAt < REFRESH_MS) return false
        if (failedAt > 0 && now - failedAt < RETRY_FAILED_MS) return false
        return true
    }

    /** Below 32 px (or undecodable) the letter tile is used instead: upscaled tiny favicons look blurry. */
    fun usable(w: Int, h: Int) = w > 0 && h > 0 && minOf(w, h) >= MIN_EDGE

    /** https-only try-order: page link icons (largest first), then /apple-touch-icon.png … /favicon.ico. No SVG / data:. */
    fun candidates(url: String, pageIcons: () -> List<PageIcon>): List<String> {
        val o = httpsOrigin(url) ?: return emptyList()
        return PageIcons.candidates(null, o + "/", pageIcons).map { it.url }
            .filter { it.startsWith("https://") && !PageIcons.isSvg(it, null) }.distinct().toList()
    }

    /**
     * Spec: transparent or very dark icons on dark themes get a 1 dp outline plate (3:1 non-text contrast).
     * [transparentFraction] of edge pixels with alpha < 50%; [meanLuminance] 0..1 of opaque pixels.
     */
    fun needsOutline(darkTheme: Boolean, transparentFraction: Float, meanLuminance: Float): Boolean =
        darkTheme && (transparentFraction > 0.25f || meanLuminance < 0.18f)

    /** Request headers we send: nothing identifying, and never a Cookie. */
    fun requestHeaders(accept: String): Map<String, String> = mapOf("Accept" to accept)

    /** GET over https only (redirects to http are refused), capped at [max] bytes; null on any failure. */
    fun download(url: String, accept: String, max: Int = MAX_BYTES, truncateOk: Boolean = false): ByteArray? {
        var u = url
        repeat(5) {
            if (!u.startsWith("https://")) return null
            val c = URL(u).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = false
                c.useCaches = false
                requestHeaders(accept).forEach { (k, v) -> c.setRequestProperty(k, v) }
                val code = c.responseCode
                if (code in 300..399) { u = PageIcons.redirectTarget(u, c.getHeaderField("Location")) ?: return null; return@repeat }
                if (code !in 200..299) return null
                if (c.contentLengthLong > max && !truncateOk) return null
                val out = ByteArrayOutputStream()
                c.inputStream.use { inp ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > max) { if (truncateOk) break else return null }
                    }
                }
                return out.toByteArray()
            } finally { c.disconnect() }
        }
        return null
    }

    fun pageLinks(origin: String): List<PageIcon> = runCatching {
        val bytes = download("$origin/", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5", PageIcons.MAX_HTML_CHARS, truncateOk = true)
            ?: return emptyList()
        PageIcons.parse(String(bytes, Charsets.UTF_8), "$origin/")
    }.getOrDefault(emptyList())

    fun files(dir: File, origin: String): Pair<File, File> = cacheName(origin).let { File(dir, "$it.png") to File(dir, "$it.none") }
}
