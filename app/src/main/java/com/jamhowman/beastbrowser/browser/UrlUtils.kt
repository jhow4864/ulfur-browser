package com.jamhowman.beastbrowser.browser

import android.net.Uri
import com.jamhowman.beastbrowser.data.SearchEngine

object UrlUtils {
    const val HOME = "beast://home"

    private val schemeRe = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")
    private val ipv4Re = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val hostRe = Regex("^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z][a-zA-Z0-9\\-]{1,62}$")

    /** Turns address-bar input into a URL: explicit URLs pass through, host-like input gets https://, else search. */
    fun fromInput(input: String, engine: SearchEngine): String {
        val t = input.trim()
        if (t.isEmpty()) return HOME
        val lower = t.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:") ||
            lower.startsWith("file://") || lower.startsWith("data:") || lower == HOME) {
            return t
        }
        if (!t.contains(' ') && looksLikeHost(t)) return "https://$t"
        // "foo:bar" with an unknown scheme and spaces etc. -> search
        if (schemeRe.containsMatchIn(t) && !t.contains(' ') && (lower.startsWith("intent:") || lower.startsWith("mailto:") || lower.startsWith("tel:"))) return t
        return engine.searchUrl(t)
    }

    fun looksLikeHost(s: String): Boolean {
        val hostPart = s.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = hostPart.substringBefore(':').let { if (it.contains('@')) it.substringAfter('@') else it }
        val port = hostPart.substringAfter(':', "")
        if (port.isNotEmpty() && port.any { !it.isDigit() }) return false
        if (host.equals("localhost", true)) return true
        if (ipv4Re.matches(host)) return host.split('.').all { it.toInt() in 0..255 }
        return hostRe.matches(host)
    }

    /** http://x -> https://x, or null if not an http URL. */
    fun httpsVersion(url: String): String? =
        if (url.regionMatches(0, "http://", 0, 7, ignoreCase = true)) "https://" + url.substring(7) else null

    fun host(url: String?): String? = try { url?.let { Uri.parse(it).host } } catch (e: Exception) { null }

    fun isHttp(url: String?) = url?.startsWith("http://", true) == true
    fun isHttps(url: String?) = url?.startsWith("https://", true) == true
}
