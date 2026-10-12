package com.jamhowman.beastbrowser.webapp

import java.net.URI
import java.security.MessageDigest

/**
 * Issue #34: the parts of a web app manifest Ulfur uses for "Add to Home screen", plus the pure decisions
 * (icon choice, scope, stable shortcut id, colours). No Android types here so it is unit-testable on the JVM.
 */
data class ManifestIcon(
    val src: String,
    /** Largest declared edge in px; [ANY] for sizes="any" (SVG); 0 when unknown. */
    val size: Int,
    val purposes: Set<String> = setOf("any"),
) {
    val maskable get() = "maskable" in purposes
    val anyPurpose get() = purposes.isEmpty() || "any" in purposes

    companion object {
        const val ANY = Int.MAX_VALUE

        /** "48x48 192x192" / "any" → largest edge. */
        fun parseSizes(sizes: String?): Int = sizes.orEmpty().trim().split(Regex("\\s+")).maxOfOrNull { s ->
            if (s.equals("any", true)) ANY
            else s.lowercase().split('x').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.max() ?: 0
        } ?: 0
    }
}

enum class Display { FULLSCREEN, STANDALONE, MINIMAL_UI, BROWSER;
    companion object {
        fun of(s: String?) = when (s?.trim()?.lowercase()) {
            "fullscreen" -> FULLSCREEN
            "standalone" -> STANDALONE
            "minimal-ui" -> MINIMAL_UI
            "browser" -> BROWSER
            else -> STANDALONE // spec default for an installed app
        }
    }
}

data class WebAppSpec(
    val startUrl: String,
    val scope: String,
    val id: String? = null,
    val name: String? = null,
    val shortName: String? = null,
    val display: Display = Display.STANDALONE,
    val themeColor: Int? = null,
    val backgroundColor: Int? = null,
    /** Raw manifest orientation ("portrait", "landscape-primary", "any"...). */
    val orientation: String? = null,
    val icons: List<ManifestIcon> = emptyList(),
) {
    /** A manifest with display "browser" is just a bookmark: open it as a normal tab. */
    val standalone get() = display != Display.BROWSER

    fun inScope(url: String) = WebApps.inScope(url, scope)
}

/** What to draw on the shortcut. */
sealed class IconChoice {
    data class Remote(val icon: ManifestIcon) : IconChoice()
    /** The site's first letter in white on [color]. */
    data class Letter(val letter: String, val color: Int) : IconChoice()
}

object WebApps {
    /** Ulfur red (@color/gx_red), used for letter tiles when the site has no theme_color. */
    const val ULFUR_RED = 0xFFFA1E4E.toInt()
    /** Manifest icons smaller than this are skipped (launchers want ~192 px). */
    const val MIN_MANIFEST_ICON = 144
    /** Downloaded icons (favicon, apple-touch-icon, or a manifest icon that lied) smaller than this are never upscaled. */
    const val MIN_DECODED_ICON = 96

    /**
     * Preferred manifest icon: the largest maskable icon (full-bleed adaptive), else the largest "any" icon of at least
     * [MIN_MANIFEST_ICON] px. Null means fall back to favicons / a letter tile.
     */
    fun pickManifestIcon(icons: List<ManifestIcon>): ManifestIcon? {
        val usable = icons.filter { it.src.startsWith("http://") || it.src.startsWith("https://") || it.src.startsWith("data:image/") }
            .filterNot { it.src.substringBefore('?').endsWith(".svg", true) } // BitmapFactory can't decode SVG
        usable.filter { it.maskable && it.size >= MIN_DECODED_ICON }.maxByOrNull { it.size }?.let { return it }
        return usable.filter { it.anyPurpose && it.size >= MIN_MANIFEST_ICON }.maxByOrNull { it.size }
    }

    /** Whether a decoded bitmap of this size is good enough, or we should draw a letter tile instead. */
    fun bigEnough(width: Int, height: Int) = minOf(width, height) >= MIN_DECODED_ICON

    fun letterTile(url: String, themeColor: Int?) =
        IconChoice.Letter(letter(url), (themeColor ?: ULFUR_RED) or 0xFF000000.toInt())

    fun letter(url: String): String =
        host(url).removePrefix("www.").firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "U"

    /** Favicon fallbacks, best first, for when the manifest has nothing usable. */
    fun fallbackIconUrls(pageUrl: String): List<String> {
        val o = origin(pageUrl) ?: return emptyList()
        return listOf("$o/apple-touch-icon.png", "$o/apple-touch-icon-precomposed.png", "$o/favicon.ico")
    }

    /**
     * Stable shortcut id per site: the manifest id if there is one, else the start_url's origin (or the page's origin
     * without a manifest). Re-pinning the same site therefore updates its shortcut instead of adding a second one.
     */
    fun shortcutId(spec: WebAppSpec?, pageUrl: String): String {
        val key = spec?.id?.takeIf { it.isNotBlank() }?.let { normalise(it) }
            ?: origin(spec?.startUrl ?: pageUrl) ?: pageUrl
        val d = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return "webapp-" + d.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Launcher label: short_name, name, page title, host; capped at 24 chars. */
    fun label(spec: WebAppSpec?, title: String?, url: String): String {
        val base = listOf(spec?.shortName, spec?.name, title).firstOrNull { !it.isNullOrBlank() }?.trim()
            ?: host(url).removePrefix("www.").ifEmpty { url }
        return if (base.length > 24) base.take(23).trimEnd() + "…" else base
    }

    /** Defensive launch check: http(s) start_url and scope, and start_url inside the scope. */
    fun isLaunchable(spec: WebAppSpec): Boolean =
        origin(spec.startUrl) != null && origin(spec.scope) != null && inScope(spec.startUrl, spec.scope)

    /** True if [url] is same-origin with [scope] and under its path (W3C "within scope"). */
    fun inScope(url: String, scope: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val s = runCatching { URI(scope) }.getOrNull() ?: return false
        if (origin(u) == null || origin(u) != origin(s)) return false
        val sp = s.rawPath.orEmpty().ifEmpty { "/" }
        val up = u.rawPath.orEmpty().ifEmpty { "/" }
        return up.startsWith(sp) || (sp.endsWith("/") && up + "/" == sp)
    }

    /** Manifest default scope: start_url with its last path segment, query and fragment removed. */
    fun defaultScope(startUrl: String): String {
        val u = runCatching { URI(startUrl) }.getOrNull() ?: return startUrl
        val o = origin(u) ?: return startUrl
        val path = u.rawPath.orEmpty().ifEmpty { "/" }
        return o + path.substring(0, path.lastIndexOf('/') + 1)
    }

    /** CSS colours as Gecko hands them over: #rgb, #rgba, #rrggbb, #rrggbbaa, rgb()/rgba(). Alpha is dropped. */
    fun parseColor(s: String?): Int? {
        val c = s?.trim()?.lowercase() ?: return null
        if (c.startsWith("#")) {
            val h = c.substring(1)
            val full = when (h.length) {
                3, 4 -> h.take(3).map { "$it$it" }.joinToString("")
                6, 8 -> h.take(6)
                else -> return null
            }
            return full.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
        }
        val m = Regex("""rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)""").find(c) ?: return null
        val (r, g, b) = m.destructured.toList().map { it.toInt().coerceIn(0, 255) }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Luminance check so the status bar icons stay readable on the theme colour. */
    fun isLight(color: Int): Boolean {
        val r = (color shr 16) and 0xFF; val g = (color shr 8) and 0xFF; val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) > 160
    }

    fun origin(url: String): String? = runCatching { origin(URI(url)) }.getOrNull()

    private fun origin(u: URI): String? {
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = u.host?.lowercase() ?: return null
        val port = u.port.takeIf { it != -1 && !(scheme == "http" && it == 80) && !(scheme == "https" && it == 443) }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }

    private fun normalise(id: String) = id.trim().substringBefore('#')

    private fun host(url: String) = runCatching { URI(url).host.orEmpty() }.getOrDefault("").lowercase()
}
