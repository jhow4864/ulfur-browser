package com.jamhowman.beastbrowser.autofill

import com.jamhowman.beastbrowser.util.Domains
import java.net.IDN
import java.net.URI
import java.util.Base64

/**
 * 2.10 Ulfur #2: the single place that decides whether a saved login may be filled. Pure (no Android views), so every
 * rule is unit-tested in AutofillPolicyTest.
 *
 * - Websites match by registrable domain (eTLD+1) of the ASCII (punycode) host, compared exactly. Lookalikes
 *   (`examp1e.com`, `example.com.evil.net`, `evil-example.com`, Cyrillic homographs, other `.co.uk` sites) differ in
 *   their registrable domain and never match. A login saved for https is never offered on http.
 * - Apps match only an `android://<sha256 of signing cert>@<package>` login, and only when the requesting app is
 *   signed with that certificate.
 * - Never in private or Ghost tabs; never into hidden fields or cross-origin frames.
 */
object FillPolicy {

    data class Site(val scheme: String, val host: String, val registrable: String)

    /** Parses an origin/URL into a [Site], or null if it isn't an http(s) URL with a valid host. */
    fun site(origin: String?): Site? {
        val raw = origin?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withScheme = if ("://" in raw) raw else "https://$raw"
        val u = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (u.rawUserInfo != null) return null // https://example.com@evil.net
        val host0 = u.host ?: return null
        val host = runCatching { IDN.toASCII(host0.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase() }.getOrNull() ?: return null
        if (host.isEmpty() || !host.contains('.') && host != "localhost") return null
        return Site(scheme, host, Domains.registrable(host))
    }

    /** True when a login saved for [savedOrigin] may be offered on [pageOrigin]. */
    fun webMatch(savedOrigin: String?, pageOrigin: String?): Boolean {
        val saved = site(savedOrigin) ?: return false
        val page = site(pageOrigin) ?: return false
        if (saved.scheme == "https" && page.scheme != "https") return false
        if (isIp(saved.host) || isIp(page.host)) return saved.host == page.host
        return saved.registrable.isNotEmpty() && saved.registrable == page.registrable
    }

    /**
     * Gecko's onLoginFetch passes a bare host ("example.com", no scheme). Adding "https://" there would let an http
     * page pass the https-only rule, so the scheme comes from the tab's real URL, and only when its host is the
     * same host. Anything else (other host, no tab URL, non-http(s)) gives null = offer nothing.
     */
    fun fetchOrigin(domain: String?, pageUrl: String?): String? {
        val d = domain?.trim().orEmpty()
        if (d.isEmpty()) return null
        val page = site(pageUrl) ?: return null
        if ("://" in d) {
            val s = site(d) ?: return null
            return if (s.scheme == page.scheme && s.host == page.host) "${s.scheme}://${s.host}" else null
        }
        val host = site("https://$d")?.host ?: return null
        return if (host == page.host) "${page.scheme}://$host" else null
    }

    private fun isIp(h: String) = h.startsWith("[") || h.all { it.isDigit() || it == '.' }

    /** `android://<hash>@<package>/` as used by Chrome/Firefox/Google Password Manager exports. */
    data class AppOrigin(val certSha256: ByteArray, val packageName: String)

    fun appOrigin(origin: String?): AppOrigin? {
        val s = origin?.trim() ?: return null
        if (!s.startsWith("android://")) return null
        val rest = s.removePrefix("android://").trimEnd('/')
        val at = rest.lastIndexOf('@'); if (at <= 0) return null
        val hash = rest.substring(0, at); val pkg = rest.substring(at + 1)
        if (!Regex("""[a-zA-Z][\w]*(\.[a-zA-Z][\w]*)+""").matches(pkg)) return null
        val bytes = decodeHash(hash) ?: return null
        if (bytes.size != 32) return null
        return AppOrigin(bytes, pkg)
    }

    private fun decodeHash(h: String): ByteArray? {
        val hex = h.replace(":", "")
        if (hex.length == 64 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
            return ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return runCatching { Base64.getUrlDecoder().decode(h.replace('+', '-').replace('/', '_')) }.getOrNull()
    }

    /**
     * True when a login saved for [savedOrigin] may be filled into app [packageName], whose current signing
     * certificates hash to [signerSha256s]. A package-name match alone is never enough.
     */
    fun appMatch(savedOrigin: String?, packageName: String, signerSha256s: List<ByteArray>): Boolean {
        val a = appOrigin(savedOrigin) ?: return false
        return a.packageName == packageName && signerSha256s.any { it.contentEquals(a.certSha256) }
    }

    /** What the page's helper script saw for the focused login field (see content.js `loginFocus`). */
    data class FieldContext(val topFrame: Boolean, val sameOriginFrame: Boolean, val visible: Boolean, val atMs: Long)

    const val FIELD_CONTEXT_MAX_AGE_MS = 5_000L

    enum class Decision { ALLOW, PRIVATE_TAB, NO_FIELD_INFO, HIDDEN_FIELD, CROSS_ORIGIN_FRAME }

    /** In-browser fill gate. [privateOrGhost]: the tab is private, or the realm is Ghost. */
    fun browserFill(privateOrGhost: Boolean, field: FieldContext?, nowMs: Long): Decision = when {
        privateOrGhost -> Decision.PRIVATE_TAB
        field == null || nowMs - field.atMs > FIELD_CONTEXT_MAX_AGE_MS || field.atMs > nowMs -> Decision.NO_FIELD_INFO
        !field.topFrame && !field.sameOriginFrame -> Decision.CROSS_ORIGIN_FRAME
        !field.visible -> Decision.HIDDEN_FIELD
        else -> Decision.ALLOW
    }
}
