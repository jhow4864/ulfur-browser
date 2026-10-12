package com.jamhowman.beastbrowser.webapp

import org.json.JSONArray
import org.json.JSONObject

/** Reads Gecko's processed manifest (ContentDelegate.onWebAppManifest) and round-trips a [WebAppSpec] through a shortcut extra. */
object WebAppJson {

    /** Null if the manifest has no usable http(s) start_url. Relative URLs are resolved against [pageUrl]. */
    fun fromManifest(m: JSONObject, pageUrl: String): WebAppSpec? {
        val start = resolve(pageUrl, m.optStr("start_url")) ?: pageUrl
        if (WebApps.origin(start) == null) return null
        // A start_url on another origin is invalid per spec: fall back to the page.
        val startUrl = if (WebApps.origin(start) == WebApps.origin(pageUrl)) start else pageUrl
        val scope = resolve(startUrl, m.optStr("scope"))?.takeIf { WebApps.inScope(startUrl, it) }
            ?: WebApps.defaultScope(startUrl)
        return WebAppSpec(
            startUrl = startUrl,
            scope = scope,
            id = m.optStr("id")?.let { resolve(startUrl, it) },
            name = m.optStr("name"),
            shortName = m.optStr("short_name"),
            display = Display.of(m.optStr("display")),
            themeColor = WebApps.parseColor(m.optStr("theme_color")),
            backgroundColor = WebApps.parseColor(m.optStr("background_color")),
            orientation = m.optStr("orientation"),
            icons = icons(m.optJSONArray("icons"), startUrl),
        )
    }

    private fun icons(a: JSONArray?, base: String): List<ManifestIcon> {
        a ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val src = o.optStr("src")?.let { if (it.startsWith("data:")) it else resolve(base, it) } ?: return@mapNotNull null
            ManifestIcon(src, ManifestIcon.parseSizes(words(o.opt("sizes"))), words(o.opt("purpose")).lowercase()
                .split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet().ifEmpty { setOf("any") })
        }
    }

    /** Gecko gives sizes/purpose as arrays; raw manifests use space-separated strings. */
    private fun words(v: Any?): String = when (v) {
        is JSONArray -> (0 until v.length()).joinToString(" ") { v.optString(it) }
        is String -> v
        else -> ""
    }

    fun toJson(s: WebAppSpec): String = JSONObject().apply {
        put("start_url", s.startUrl); put("scope", s.scope)
        s.id?.let { put("id", it) }; s.name?.let { put("name", it) }; s.shortName?.let { put("short_name", it) }
        put("display", s.display.name)
        s.themeColor?.let { put("theme", it) }; s.backgroundColor?.let { put("bg", it) }
        s.orientation?.let { put("orientation", it) }
    }.toString()

    fun fromJson(json: String?): WebAppSpec? = runCatching {
        val o = JSONObject(json ?: return null)
        WebAppSpec(
            startUrl = o.getString("start_url"), scope = o.getString("scope"),
            id = o.optStr("id"), name = o.optStr("name"), shortName = o.optStr("short_name"),
            display = runCatching { Display.valueOf(o.getString("display")) }.getOrDefault(Display.STANDALONE),
            themeColor = if (o.has("theme")) o.getInt("theme") else null,
            backgroundColor = if (o.has("bg")) o.getInt("bg") else null,
            orientation = o.optStr("orientation"),
        )
    }.getOrNull()?.takeIf { WebApps.origin(it.startUrl) != null }

    private fun resolve(base: String, ref: String?): String? =
        ref?.takeIf { it.isNotBlank() }?.let { runCatching { java.net.URI(base).resolve(it.trim()).toString() }.getOrNull() }

    private fun JSONObject.optStr(k: String): String? =
        if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null
}
