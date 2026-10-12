package com.jamhowman.beastbrowser.data

import androidx.core.graphics.ColorUtils
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 2.9 theme packs (design-drop-2.9.md §1; format in docs/theme-packs.md).
 *
 * A pack is either a `.ulfurtheme` zip holding `theme.json` (plus optional README.txt / LICENSE.txt), or the bare
 * `theme.json`. It recolours the palette tokens Ulfur paints with in code ([ThemePalette]); views styled from XML
 * follow the pack's `base` preset. Every pack is untrusted: [ThemePackParser] validates it strictly before anything
 * is shown, and nothing is stored or applied until the user taps Apply ([ThemePacks.apply]).
 */
data class ThemePack(
    val name: String,
    val author: String,
    val version: String,
    val base: ThemePreset,
    /** Colours the pack sets, per mode. Keys: [ThemePackParser.COLOR_KEYS]. */
    val dark: Map<String, Int>,
    val light: Map<String, Int>,
) {
    val hasDark get() = dark.isNotEmpty()
    val hasLight get() = light.isNotEmpty()
    /** Content id: stable for the same colours and metadata (used as the stored file name). */
    val id: String get() = sha256(toJson().toString()).take(16)

    /** The colour for [key] in [night] mode, or null when the pack leaves it to Ulfur's default (the base preset). */
    fun colorFor(key: String, night: Boolean): Int? {
        val own = if (night) dark else light
        val other = if (night) light else dark
        return own[key] ?: if (key in ThemePackParser.MODE_FREE_KEYS) other[key] else null
    }

    /** The palette Ulfur actually paints with when this pack is applied (also what the preview and contrast use). */
    fun applyTo(base: ThemePalette): ThemePalette {
        val n = base.night
        return base.copy(
            color = colorFor("accent", n) ?: base.color,
            accentStart = colorFor("accentStart", n) ?: base.accentStart,
            accentEnd = colorFor("accentEnd", n) ?: base.accentEnd,
            onColor = colorFor("onAccent", n) ?: base.onColor,
            accentText = colorFor("accentText", n) ?: base.accentText,
            surfaceTint = colorFor("surfaceTint", n) ?: base.surfaceTint,
            eye = colorFor("eye", n) ?: base.eye,
        )
    }

    fun palette(night: Boolean): ThemePalette = applyTo(ThemePalette.of(base, night))

    /** Canonical JSON (what's stored on Apply: never the original file). */
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", ThemePackParser.FORMAT)
        put("schema", ThemePackParser.SCHEMA)
        put("name", name); put("author", author); put("version", version)
        put("base", base.key)
        put("colors", JSONObject().apply {
            if (hasDark) put("dark", JSONObject().apply { dark.toSortedMap().forEach { (k, v) -> put(k, ThemeColor.hex(v)) } })
            if (hasLight) put("light", JSONObject().apply { light.toSortedMap().forEach { (k, v) -> put(k, ThemeColor.hex(v)) } })
        })
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** Why a pack was refused; each maps to one plain-words message (design drop "Bad packs"). */
enum class ThemePackError { NOT_A_THEME, TOO_LARGE, BAD_CONTENTS }

sealed class ThemePackResult {
    data class Ok(val pack: ThemePack) : ThemePackResult()
    data class Bad(val error: ThemePackError, val detail: String) : ThemePackResult()
}

object ThemePackParser {
    const val FORMAT = "ulfurtheme"
    const val SCHEMA = 1
    const val EXTENSION = ".ulfurtheme"
    const val JSON_NAME = "theme.json"

    /** Whole file as picked (zip or JSON). */
    const val MAX_FILE_BYTES = 256 * 1024
    /** theme.json itself, uncompressed. */
    const val MAX_JSON_BYTES = 64 * 1024
    /** Every entry together, uncompressed (counted from the bytes actually inflated, not the headers). */
    const val MAX_TOTAL_UNCOMPRESSED = 512 * 1024
    const val MAX_ENTRIES = 8
    /** Uncompressed / compressed, per entry and for the whole archive (zip-bomb check). */
    const val MAX_RATIO = 100
    const val MAX_TEXT = 48

    private val ALLOWED_ENTRIES = setOf(JSON_NAME, "README.txt", "LICENSE.txt")

    /** Colour tokens a pack may set (unknown keys are ignored). */
    val COLOR_KEYS = listOf("accent", "accentStart", "accentEnd", "onAccent", "accentText", "surfaceTint", "eye")
    /** Same value in light and dark unless the pack says otherwise (the presets don't vary these by mode). */
    val MODE_FREE_KEYS = setOf("accent", "accentStart", "accentEnd", "onAccent", "eye")

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    fun parse(bytes: ByteArray): ThemePackResult {
        if (bytes.size > MAX_FILE_BYTES) return bad(ThemePackError.TOO_LARGE, "file is ${bytes.size} bytes")
        if (bytes.isEmpty()) return bad(ThemePackError.NOT_A_THEME, "empty file")
        val json = if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(ZIP_MAGIC)) {
            when (val r = unzip(bytes)) { is Unzipped.Json -> r.bytes; is Unzipped.Fail -> return r.bad }
        } else bytes
        if (json.size > MAX_JSON_BYTES) return bad(ThemePackError.TOO_LARGE, "theme.json is ${json.size} bytes")
        return parseJson(json)
    }

    private sealed class Unzipped {
        class Json(val bytes: ByteArray) : Unzipped()
        class Fail(val bad: ThemePackResult.Bad) : Unzipped()
    }

    private fun fail(e: ThemePackError, d: String) = Unzipped.Fail(bad(e, d))

    /** True for a plain file name we allow: no directories, no traversal, no absolute or Windows paths. */
    fun safeEntryName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 64 && '/' !in name && '\\' !in name && ':' !in name &&
            name != "." && name != ".." && !name.startsWith(".") && name.all { it.code in 0x21..0x7E }

    private fun unzip(bytes: ByteArray): Unzipped {
        var json: ByteArray? = null
        val seen = HashSet<String>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                val buf = ByteArray(8192)
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (seen.size >= MAX_ENTRIES) return fail(ThemePackError.TOO_LARGE, "more than $MAX_ENTRIES entries")
                    val name = e.name
                    if (e.isDirectory || !safeEntryName(name)) return fail(ThemePackError.BAD_CONTENTS, "entry '$name'")
                    if (name !in ALLOWED_ENTRIES) return fail(ThemePackError.BAD_CONTENTS, "entry '$name' not allowed")
                    if (!seen.add(name)) return fail(ThemePackError.BAD_CONTENTS, "duplicate entry '$name'")
                    val cap = if (name == JSON_NAME) MAX_JSON_BYTES else MAX_TOTAL_UNCOMPRESSED
                    val out = java.io.ByteArrayOutputStream()
                    var n = 0L
                    while (true) {
                        val r = zin.read(buf)
                        if (r < 0) break
                        n += r; total += r
                        if (n > cap || total > MAX_TOTAL_UNCOMPRESSED) return fail(ThemePackError.TOO_LARGE, "'$name' inflates past the cap")
                        if (name == JSON_NAME) out.write(buf, 0, r)
                    }
                    val compressed = e.compressedSize
                    if (compressed > 0 && n > compressed * MAX_RATIO) return fail(ThemePackError.TOO_LARGE, "'$name' ratio ${n / compressed}")
                    if (name == JSON_NAME) json = out.toByteArray()
                }
            }
        } catch (t: Exception) {
            return fail(ThemePackError.NOT_A_THEME, "unreadable zip: ${t.javaClass.simpleName}")
        }
        if (total > bytes.size.toLong() * MAX_RATIO) return fail(ThemePackError.TOO_LARGE, "archive ratio")
        return json?.let { Unzipped.Json(it) } ?: fail(ThemePackError.NOT_A_THEME, "no theme.json")
    }

    fun parseJson(bytes: ByteArray): ThemePackResult {
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: Exception) { return bad(ThemePackError.NOT_A_THEME, "not UTF-8") }
        if (!shallow(text)) return bad(ThemePackError.NOT_A_THEME, "nested too deeply")
        val root = try {
            val tok = JSONTokener(text)
            val v = tok.nextValue()
            if (tok.more() && tok.nextClean() != 0.toChar()) return bad(ThemePackError.NOT_A_THEME, "trailing data")
            v as? JSONObject ?: return bad(ThemePackError.NOT_A_THEME, "not a JSON object")
        } catch (e: Exception) { return bad(ThemePackError.NOT_A_THEME, "broken JSON") }

        if (root.opt("format") != FORMAT) return bad(ThemePackError.NOT_A_THEME, "format isn't '$FORMAT'")
        val schema = root.opt("schema")
        if (schema !is Int || schema != SCHEMA) return bad(ThemePackError.NOT_A_THEME, "unsupported schema '$schema'")
        val name = text(root, "name", required = true) ?: return bad(ThemePackError.NOT_A_THEME, "missing or bad name")
        val author = text(root, "author", required = false) ?: return bad(ThemePackError.NOT_A_THEME, "bad author")
        val version = text(root, "version", required = false) ?: return bad(ThemePackError.NOT_A_THEME, "bad version")
        val base = when (val b = root.opt("base")) {
            null -> ThemePreset.DEFAULT
            is String -> ThemePreset.parse(b) ?: return bad(ThemePackError.NOT_A_THEME, "unknown base '$b'")
            else -> return bad(ThemePackError.NOT_A_THEME, "bad base")
        }
        val colors = root.opt("colors") as? JSONObject ?: return bad(ThemePackError.NOT_A_THEME, "missing colors")
        val dark = colorsOf(colors, "dark") ?: return bad(ThemePackError.NOT_A_THEME, "bad dark colour")
        val light = colorsOf(colors, "light") ?: return bad(ThemePackError.NOT_A_THEME, "bad light colour")
        if (dark.isEmpty() && light.isEmpty()) return bad(ThemePackError.NOT_A_THEME, "no colours")
        return ThemePackResult.Ok(ThemePack(name, author, version, base, dark, light))
    }

    /** Nesting depth ≤ [MAX_DEPTH] (org.json recurses; a deep file must not blow the stack). */
    private fun shallow(text: String): Boolean {
        var depth = 0; var inStr = false; var esc = false
        for (c in text) {
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{', '[' -> if (++depth > MAX_DEPTH) return false
                '}', ']' -> depth--
            }
        }
        return true
    }

    private const val MAX_DEPTH = 8

    /** Null = invalid. Missing optional text is "". Control characters are refused. */
    private fun text(o: JSONObject, key: String, required: Boolean): String? {
        val v = o.opt(key) ?: return if (required) null else ""
        if (v !is String) return null
        val t = v.trim()
        if (t.length > MAX_TEXT || t.any { Character.isISOControl(it) || it in "\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069" }) return null
        if (required && t.isEmpty()) return null
        return t
    }

    /** Null = a known key has a bad value. Unknown keys are ignored. Absent mode = empty map. */
    private fun colorsOf(colors: JSONObject, mode: String): Map<String, Int>? {
        val m = colors.opt(mode) ?: return emptyMap()
        if (m !is JSONObject) return null
        val out = LinkedHashMap<String, Int>()
        for (key in COLOR_KEYS) {
            val v = m.opt(key) ?: continue
            out[key] = parseColor(v) ?: return null
        }
        return out
    }

    /** Opaque `#RRGGBB` only (text contrast can't be judged on translucent colours). */
    fun parseColor(v: Any?): Int? {
        if (v !is String || !Regex("#[0-9A-Fa-f]{6}").matches(v)) return null
        return ThemeColor.parse(v)
    }

    private fun bad(e: ThemePackError, d: String) = ThemePackResult.Bad(e, d)
}

/** WCAG 2.x contrast of the text-on-background pairs a palette produces (design drop "Contrast check"). */
object ThemeContrast {
    const val MIN_TEXT = 4.5

    /** Ulfur's backgrounds (values/colors.xml, values-night/colors.xml): `bg` and `surface`. */
    const val BG_DARK = 0xFF0E0E12.toInt()
    const val SURFACE_DARK = 0xFF16161C.toInt()
    const val BG_LIGHT = 0xFFF4F4F7.toInt()
    const val SURFACE_LIGHT = 0xFFFFFFFF.toInt()

    data class Pair(val label: String, val text: Int, val background: Int, val ratio: Double)

    fun ratio(a: Int, b: Int): Double = ColorUtils.calculateContrast(a or 0xFF000000.toInt(), b or 0xFF000000.toInt())

    fun pairs(p: ThemePalette): List<Pair> {
        val bg = if (p.night) BG_DARK else BG_LIGHT
        val surface = if (p.night) SURFACE_DARK else SURFACE_LIGHT
        val list = mutableListOf(
            Pair("Text on accent", p.onColor, p.color, ratio(p.onColor, p.color)),
            Pair("Accent text on background", p.accentText, bg, ratio(p.accentText, bg)),
            Pair("Accent text on surface", p.accentText, surface, ratio(p.accentText, surface)),
        )
        if (p.gradientCarriesText) {
            list += Pair("Text on gradient start", p.onColor, p.accentStart, ratio(p.onColor, p.accentStart))
            list += Pair("Text on gradient end", p.onColor, p.accentEnd, ratio(p.onColor, p.accentEnd))
        }
        return list
    }

    fun lowContrast(p: ThemePalette): List<Pair> = pairs(p).filter { it.ratio < MIN_TEXT }
}
