package com.jamhowman.beastbrowser.webapp

import java.net.URI

/**
 * 2.8.1: the page's own `<link rel=icon|apple-touch-icon…>` icons, for sites without a usable manifest icon (BBC Weather
 * links a 152 px apple-touch-icon-precomposed on static.files.bbci.co.uk; the fixed /apple-touch-icon.png is 57 px).
 * Pure JVM code so the ranking, parsing and geometry are unit-tested.
 */
data class PageIcon(
    val href: String,
    /** Largest declared edge; [ManifestIcon.ANY] for "any"; 0 when there is no usable sizes attribute. */
    val size: Int,
)

/** One icon to try. [fullBleed] is true only for a maskable manifest icon. */
data class IconCandidate(val url: String, val fullBleed: Boolean, val source: String)

object PageIcons {
    private val ICON_RELS = setOf("icon", "shortcut icon", "apple-touch-icon", "apple-touch-icon-precomposed")
    private val LINK = Regex("""<link\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val BASE = Regex("""<base\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*(?:=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?""")

    /** Only the document head matters; this also caps how much HTML we scan. */
    const val MAX_HTML_CHARS = 512 * 1024

    fun attrs(tag: String): Map<String, String> = ATTR.findAll(tag).associate { m ->
        m.groupValues[1].lowercase() to unescape(m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] })
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x2F;", "/").trim()

    /** Document base: the first `<base href>` resolved against the page, else the page URL. */
    fun documentBase(html: String, pageUrl: String): String {
        val href = BASE.find(html)?.let { attrs(it.groupValues[1])["href"] }?.takeIf { it.isNotBlank() } ?: return pageUrl
        return resolve(pageUrl, href)?.takeIf { WebApps.origin(it) != null } ?: pageUrl
    }

    /** All icon links in [html], hrefs resolved to absolute http(s) URLs (any host / CDN). SVG and mask-icon skipped. */
    fun parse(html: String, pageUrl: String): List<PageIcon> {
        val h = if (html.length > MAX_HTML_CHARS) html.substring(0, MAX_HTML_CHARS) else html
        val base = documentBase(h, pageUrl)
        return LINK.findAll(h).mapNotNull { m ->
            val a = attrs(m.groupValues[1])
            val rel = a["rel"]?.lowercase()?.trim()?.replace(Regex("\\s+"), " ") ?: return@mapNotNull null
            val isIcon = rel in ICON_RELS || rel.split(' ').any { it == "icon" || it == "apple-touch-icon" || it == "apple-touch-icon-precomposed" }
            if (!isIcon || rel.contains("mask-icon")) return@mapNotNull null
            val href = a["href"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (href.startsWith("data:")) return@mapNotNull null
            val abs = resolve(base, href)?.takeIf { WebApps.origin(it) != null } ?: return@mapNotNull null
            if (isSvg(abs, a["type"])) return@mapNotNull null
            PageIcon(abs, ManifestIcon.parseSizes(a["sizes"]))
        }.distinctBy { it.href }.toList()
    }

    fun isSvg(url: String, type: String?) =
        type?.lowercase()?.contains("svg") == true || url.substringBefore('#').substringBefore('?').endsWith(".svg", true)

    /** Sized links largest first ("any" counts as largest), then links with no sizes in page order. */
    fun rank(icons: List<PageIcon>): List<PageIcon> =
        icons.filter { it.size > 0 }.sortedByDescending { it.size } + icons.filter { it.size <= 0 }

    /**
     * Full try-order: maskable manifest icon (full-bleed), manifest "any" icon ≥144, page link icons, fixed-path
     * fallbacks. [pageIcons] is evaluated lazily, only once the manifest candidates are exhausted.
     */
    fun candidates(spec: WebAppSpec?, pageUrl: String, pageIcons: () -> List<PageIcon>): Sequence<IconCandidate> = sequence {
        val usable = spec?.icons.orEmpty()
            .filter { it.src.startsWith("http://") || it.src.startsWith("https://") || it.src.startsWith("data:image/") }
            .filterNot { isSvg(it.src, null) }
        usable.filter { it.maskable && it.size >= WebApps.MIN_DECODED_ICON }.sortedByDescending { it.size }
            .forEach { yield(IconCandidate(it.src, true, "manifest-maskable")) }
        usable.filter { it.anyPurpose && it.size >= WebApps.MIN_MANIFEST_ICON }.sortedByDescending { it.size }
            .forEach { yield(IconCandidate(it.src, false, "manifest")) }
        val seen = HashSet<String>()
        rank(runCatching(pageIcons).getOrDefault(emptyList())).forEach { if (seen.add(it.href)) yield(IconCandidate(it.href, false, "link")) }
        WebApps.fallbackIconUrls(spec?.startUrl ?: pageUrl).forEach { if (seen.add(it)) yield(IconCandidate(it, false, "fallback")) }
    }

    /**
     * Tries candidates in order; [load] returns a decoded image (or null on 404 / network error / undecodable), and
     * [dims] its width and height. The first image at least [WebApps.MIN_DECODED_ICON] px wins; null means letter tile.
     */
    fun <T> firstUsable(cands: Sequence<IconCandidate>, load: (String) -> T?, dims: (T) -> Pair<Int, Int>): Pair<IconCandidate, T>? {
        for (c in cands) {
            val img = runCatching { load(c.url) }.getOrNull() ?: continue
            val (w, h) = dims(img)
            if (WebApps.bigEnough(w, h)) return c to img
        }
        return null
    }

    // ---------------------------------------------------------------- geometry (Designer: adaptive safe zone)

    /** Adaptive icons are 108 dp with a 66 dp safe zone (21 dp ≈ 19.4% inset per side). */
    const val SAFE_ZONE = 66f / 108f

    /** Destination rect (left, top, right, bottom) for a [w]×[h] icon centred in the safe zone of a [canvas] square. */
    fun safeZoneRect(canvas: Int, w: Int, h: Int): FloatArray {
        val box = canvas * SAFE_ZONE
        val scale = box / maxOf(w, h)
        val dw = w * scale; val dh = h * scale
        return floatArrayOf((canvas - dw) / 2, (canvas - dh) / 2, (canvas + dw) / 2, (canvas + dh) / 2)
    }

    /**
     * Background for a non-maskable icon: the corner pixels' colour (most common opaque corner, top-left wins ties),
     * or white if every corner is (mostly) transparent. Always returned opaque.
     */
    fun cornerColor(corners: IntArray): Int {
        val opaque = corners.filter { (it ushr 24) >= 0xC0 }.map { it or 0xFF000000.toInt() }
        if (opaque.isEmpty()) return 0xFFFFFFFF.toInt()
        return opaque.groupingBy { it }.eachCount().maxByOrNull { (c, n) -> n * 10 - opaque.indexOf(c) }!!.key
    }

    /** Redirect target for a Location header, resolved against the current URL; only http(s). */
    fun redirectTarget(current: String, location: String?): String? =
        location?.let { resolve(current, it) }?.takeIf { WebApps.origin(it) != null }

    private fun resolve(base: String, ref: String): String? =
        runCatching { URI(base).resolve(ref.trim().replace(" ", "%20")).toString() }.getOrNull()
            ?.let { if (it.startsWith("//")) null else it }
}
