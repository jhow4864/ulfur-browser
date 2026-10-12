package com.jamhowman.beastbrowser.ui


/**
 * "Add to Home Screen" helpers. Pinning, icons and the standalone web-app window live in
 * [com.jamhowman.beastbrowser.webapp.HomeScreenInstaller] (issue #34). Never offered for private or Ghost tabs, so private pages
 * can't end up on the home screen.
 */
object HomeShortcut {

    /** Offered only for a real http(s) page in a non-private tab. */
    fun eligible(url: String?, isPrivate: Boolean, onPage: Boolean): Boolean {
        if (isPrivate || !onPage || url.isNullOrBlank()) return false
        val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
        return scheme == "http" || scheme == "https"
    }

    /** Shortcut label: page title, else the host without "www.", capped for launchers. */
    fun label(title: String?, url: String): String {
        val t = title?.trim().orEmpty()
        val base = t.ifEmpty { host(url).ifEmpty { url } }
        return if (base.length > 24) base.take(23).trimEnd() + "…" else base
    }

    /** Letter shown on the icon: first letter or digit of the host, else "U". */
    fun letter(url: String): String =
        host(url).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "U"

    /** Stable id per URL, so pinning the same page twice updates rather than duplicates. */
    fun id(url: String): String = "page-" + Integer.toHexString(url.hashCode())

    internal fun host(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").removePrefix("www.")
}
