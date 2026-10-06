package com.jamhowman.beastbrowser.browser

import com.jamhowman.beastbrowser.util.Domains
import java.net.URI

/**
 * 2.5 forced dark mode (BETA). GeckoView has no algorithmic darkening (only preferredColorScheme, which
 * "Prefer dark websites" already uses), so the darkening itself is a CSS filter applied by the beast-siteprefs
 * extension (assets/extensions/beast-siteprefs/dark-core.js). This holds the app-side rules.
 */
object ForcedDark {
    private val HOST = Regex("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?)+$")

    /**
     * Turns what the user typed in "Add site" ("https://www.Example.com/page", "m.example.co.uk") into the
     * registrable domain stored in site_prefs, or null when it isn't a web site name.
     */
    fun normalizeSite(input: String?): String? {
        var s = input?.trim()?.lowercase() ?: return null
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if ("://" in s) {
            val uri = runCatching { URI(s) }.getOrNull() ?: return null
            if (uri.scheme != "http" && uri.scheme != "https") return null
            s = uri.host ?: return null
        } else {
            s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        }
        s = s.trimEnd('.')
        if (s.length > 253 || !HOST.matches(s) || s.all { it.isDigit() || it == '.' }) return null
        return Domains.registrable(s).ifEmpty { null }
    }

    /** The menu's "Dark page" tile: only while forced dark runs, and only on web pages. */
    fun menuTileShown(forceDarkActive: Boolean, url: String?, onPage: Boolean): Boolean =
        forceDarkActive && onPage && url != null && (url.startsWith("http://") || url.startsWith("https://"))
}
