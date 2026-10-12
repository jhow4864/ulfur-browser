package com.jamhowman.beastbrowser.data

import androidx.core.graphics.ColorUtils
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Theme packs (2.9 schema 1: accent tokens; 2.10 schema 2: + surfaces, text and background images).
 * Format: docs/theme-packs.md. Every pack is untrusted: [ThemePackParser] validates it strictly, images are sniffed,
 * bounds-checked and re-encoded ([ThemeImageCodec]) before anything is shown, and nothing is stored or applied until
 * Apply ([ThemePackStore.apply]).
 */
data class ThemePack(
    val name: String,
    val author: String,
    val version: String,
    val base: ThemePreset,
    val dark: Map<String, Int>,
    val light: Map<String, Int>,
    val schema: Int = 1,
    val imageSpecs: Map<ImageSlot, ImageSpec> = emptyMap(),
    val images: Map<ImageSlot, ThemeImage> = emptyMap(),
) {
    val hasDark get() = dark.isNotEmpty()
    val hasLight get() = light.isNotEmpty()

    /** Content id: canonical JSON plus every (re-encoded) image. Also the stored file name. */
    val id: String get() {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(toJson().toString().toByteArray())
        ImageSlot.entries.forEach { s -> images[s]?.let { md.update(s.key.toByteArray()); md.update(it.bytes) } }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    fun colorFor(key: String, night: Boolean): Int? {
        val own = if (night) dark else light
        val other = if (night) light else dark
        return own[key] ?: if (key in ThemePackParser.MODE_FREE_KEYS) other[key] else null
    }

    fun surfaces(night: Boolean): ThemeSurfaces? {
        if (schema < 2) return null
        val m = if (night) dark else light
        val s = ThemeSurfaces(m["background"], m["surface"], m["surfaceVariant"], m["text"], m["textSecondary"],
            m["toolbar"], m["onToolbar"], m["divider"], if (images.isEmpty()) null else imagesKey())
        return if (s == ThemeSurfaces()) null else s
    }

    private fun imagesKey() = images.entries.joinToString(",") { "${it.key.key}:${it.value.bytes.contentHashCode()}" }

    /** The palette Ulfur paints with when this pack is applied (also what the preview and the contrast check use). */
    fun applyTo(base: ThemePalette, surfaces: Boolean = true): ThemePalette {
        val n = base.night
        return base.copy(
            color = colorFor("accent", n) ?: base.color,
            accentStart = colorFor("accentStart", n) ?: base.accentStart,
            accentEnd = colorFor("accentEnd", n) ?: base.accentEnd,
            onColor = colorFor("onAccent", n) ?: base.onColor,
            accentText = colorFor("accentText", n) ?: base.accentText,
            surfaceTint = colorFor("surfaceTint", n) ?: base.surfaceTint,
            eye = colorFor("eye", n) ?: base.eye,
            surfaces = if (surfaces) surfaces(n) else null,
        )
    }

    fun palette(night: Boolean): ThemePalette = applyTo(ThemePalette.of(base, night))

    /** Scrim actually drawn on [slot] in [night] mode (auto-raised toward 4.5:1 when the pack allows it). */
    fun scrimVerdict(slot: ImageSlot, night: Boolean): ImageContrast.Verdict? {
        val img = images[slot] ?: return null
        val spec = imageSpecs[slot] ?: return null
        val (text, scrim) = slotColors(slot, night)
        return ImageContrast.resolve(img, spec, text, scrim)
    }

    /** Text colour and scrim colour of [slot]. */
    fun slotColors(slot: ImageSlot, night: Boolean): kotlin.Pair<Int, Int> {
        val r = (surfaces(night) ?: ThemeSurfaces()).resolved(night)
        return if (slot.onBackground) r.text to r.background else r.onToolbar to r.toolbar
    }

    /** Canonical JSON (what's stored on Apply: never the original file). Images point at Ulfur's re-encoded copies. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", ThemePackParser.FORMAT)
        put("schema", schema)
        put("name", name); put("author", author); put("version", version)
        put("base", base.key)
        put("colors", JSONObject().apply {
            if (dark.isNotEmpty()) put("dark", JSONObject().apply { dark.toSortedMap().forEach { (k, v) -> put(k, ThemeColor.hex(v)) } })
            if (light.isNotEmpty()) put("light", JSONObject().apply { light.toSortedMap().forEach { (k, v) -> put(k, ThemeColor.hex(v)) } })
        })
        if (imageSpecs.isNotEmpty()) put("images", JSONObject().apply {
            imageSpecs.entries.sortedBy { it.key.ordinal }.forEach { (slot, s) ->
                put(slot.key, JSONObject().apply {
                    put("file", storedName(slot))
                    put("fit", "cover")
                    put("focus", JSONObject().apply { put("x", s.focusX.toDouble()); put("y", s.focusY.toDouble()) })
                    put("scrim", s.scrim.toDouble()); put("scrimAuto", s.scrimAuto); put("scrimMax", s.scrimMax.toDouble())
                })
            }
        })
    }

    fun storedName(slot: ImageSlot) = "images/${slot.key}.${images[slot]?.ext ?: "png"}"
}

enum class ThemePackError { NOT_A_THEME, TOO_LARGE, BAD_CONTENTS, BAD_IMAGE }

sealed class ThemePackResult {
    data class Ok(val pack: ThemePack) : ThemePackResult()
    data class Bad(val error: ThemePackError, val detail: String) : ThemePackResult()
}

object ThemePackParser {
    const val FORMAT = "ulfurtheme"
    const val SCHEMA = 1
    const val SCHEMA_MAX = 2
    const val EXTENSION = ".ulfurtheme"
    const val JSON_NAME = "theme.json"
    const val IMAGES_DIR = "images/"

    /** Schema 1 (unchanged from 2.9). */
    const val MAX_FILE_BYTES_V1 = 256 * 1024
    const val MAX_TOTAL_V1 = 512 * 1024
    /** Schema 2: whole file as picked (the importer reads at most this + 1). */
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    const val MAX_IMAGES = 4
    const val MAX_TOTAL_V2 = 6 * 1024 * 1024
    const val MAX_JSON_BYTES = 64 * 1024
    const val MAX_TOTAL_UNCOMPRESSED = MAX_TOTAL_V1
    const val MAX_ENTRIES = 8
    const val MAX_RATIO = 100
    const val MAX_TEXT = 48

    private val ALLOWED_ENTRIES = setOf(JSON_NAME, "README.txt", "LICENSE.txt")

    val COLOR_KEYS = listOf("accent", "accentStart", "accentEnd", "onAccent", "accentText", "surfaceTint", "eye")
    val MODE_FREE_KEYS = setOf("accent", "accentStart", "accentEnd", "onAccent", "eye")
    /** Schema 2 only (ignored as unknown keys in schema 1). Per mode, no cross-mode fallback. */
    val SURFACE_KEYS = listOf("background", "surface", "surfaceVariant", "text", "textSecondary", "toolbar", "onToolbar", "divider")

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    fun parse(bytes: ByteArray, codec: ThemeImageCodec? = null): ThemePackResult {
        if (bytes.size > MAX_FILE_BYTES) return bad(ThemePackError.TOO_LARGE, "file is ${bytes.size} bytes")
        if (bytes.isEmpty()) return bad(ThemePackError.NOT_A_THEME, "empty file")
        val isZip = bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(ZIP_MAGIC)
        val z = if (isZip) when (val r = unzip(bytes)) { is Unzipped.Ok -> r; is Unzipped.Fail -> return r.bad }
        else Unzipped.Ok(bytes, emptyMap(), bytes.size.toLong())
        if (z.json.size > MAX_JSON_BYTES) return bad(ThemePackError.TOO_LARGE, "theme.json is ${z.json.size} bytes")
        if (peekSchema(z.json) != 2) {
            // Schema 1 (or not a pack at all) keeps 2.9's limits exactly, and has no images.
            if (bytes.size > MAX_FILE_BYTES_V1) return bad(ThemePackError.TOO_LARGE, "schema 1 file over $MAX_FILE_BYTES_V1")
            if (z.total > MAX_TOTAL_V1) return bad(ThemePackError.TOO_LARGE, "schema 1 contents over $MAX_TOTAL_V1")
            if (z.images.isNotEmpty()) return bad(ThemePackError.BAD_CONTENTS, "images need schema 2")
        }
        return parseJson(z.json, z.images, codec, sanitize = true)
    }

    private fun peekSchema(json: ByteArray): Int? = try {
        val t = String(json, Charsets.UTF_8).removePrefix("\uFEFF")
        if (!shallow(t)) null else (JSONTokener(t).nextValue() as? JSONObject)?.opt("schema") as? Int
    } catch (_: Exception) { null }

    private sealed class Unzipped {
        class Ok(val json: ByteArray, val images: Map<String, ByteArray>, val total: Long) : Unzipped()
        class Fail(val bad: ThemePackResult.Bad) : Unzipped()
    }

    private fun fail(e: ThemePackError, d: String) = Unzipped.Fail(bad(e, d))

    fun safeEntryName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 64 && '/' !in name && '\\' !in name && ':' !in name &&
            name != "." && name != ".." && !name.startsWith(".") && name.all { it.code in 0x21..0x7E }

    /** `images/<plain name>`: one level, same name rules. */
    fun safeImageEntry(name: String): Boolean = name.startsWith(IMAGES_DIR) && safeEntryName(name.removePrefix(IMAGES_DIR))

    private fun unzip(bytes: ByteArray): Unzipped {
        var json: ByteArray? = null
        val images = LinkedHashMap<String, ByteArray>()
        val seen = HashSet<String>()
        var entries = 0
        var total = 0L
        var textTotal = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                val buf = ByteArray(16384)
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) return fail(ThemePackError.TOO_LARGE, "more than $MAX_ENTRIES entries")
                    val name = e.name
                    if (e.isDirectory) {
                        if (name == IMAGES_DIR && seen.add(name)) continue
                        return fail(ThemePackError.BAD_CONTENTS, "directory '$name'")
                    }
                    val isImage = safeImageEntry(name)
                    if (!isImage && (!safeEntryName(name) || name !in ALLOWED_ENTRIES)) return fail(ThemePackError.BAD_CONTENTS, "entry '$name'")
                    if (!seen.add(name)) return fail(ThemePackError.BAD_CONTENTS, "duplicate entry '$name'")
                    if (isImage && images.size >= MAX_IMAGES) return fail(ThemePackError.TOO_LARGE, "more than $MAX_IMAGES images")
                    val cap = when { name == JSON_NAME -> MAX_JSON_BYTES; isImage -> MAX_IMAGE_BYTES; else -> MAX_TOTAL_V1 }
                    val out = java.io.ByteArrayOutputStream()
                    var n = 0L
                    while (true) {
                        val r = zin.read(buf)
                        if (r < 0) break
                        n += r; total += r
                        if (n > cap) return fail(ThemePackError.TOO_LARGE, "'$name' is over $cap bytes")
                        if (total > MAX_TOTAL_V2) return fail(ThemePackError.TOO_LARGE, "contents over $MAX_TOTAL_V2 bytes")
                        if (name == JSON_NAME || isImage) out.write(buf, 0, r)
                    }
                    val compressed = e.compressedSize
                    // Images are exempt: their inflated size is capped (2 MiB) and their pixels are capped from the
                    // header, and a flat-colour image legitimately compresses past 100:1.
                    if (!isImage) textTotal += n
                    if (!isImage && compressed > 0 && n > compressed * MAX_RATIO) return fail(ThemePackError.TOO_LARGE, "'$name' ratio ${n / compressed}")
                    if (name == JSON_NAME) json = out.toByteArray()
                    if (isImage) images[name] = out.toByteArray()
                }
            }
        } catch (t: Exception) {
            return fail(ThemePackError.NOT_A_THEME, "unreadable zip: ${t.javaClass.simpleName}")
        }
        if (textTotal > bytes.size.toLong() * MAX_RATIO) return fail(ThemePackError.TOO_LARGE, "archive ratio")
        return json?.let { Unzipped.Ok(it, images, total) } ?: fail(ThemePackError.NOT_A_THEME, "no theme.json")
    }

    /**
     * [images]: zip entries under images/ (import), or Ulfur's own stored files with [sanitize] = false (already
     * re-encoded; only decoded for the readability sample).
     */
    fun parseJson(
        bytes: ByteArray,
        images: Map<String, ByteArray> = emptyMap(),
        codec: ThemeImageCodec? = null,
        sanitize: Boolean = true,
    ): ThemePackResult {
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
        if (schema !is Int || schema !in SCHEMA..SCHEMA_MAX) return bad(ThemePackError.NOT_A_THEME, "unsupported schema '$schema'")
        val name = text(root, "name", required = true) ?: return bad(ThemePackError.NOT_A_THEME, "missing or bad name")
        val author = text(root, "author", required = false) ?: return bad(ThemePackError.NOT_A_THEME, "bad author")
        val version = text(root, "version", required = false) ?: return bad(ThemePackError.NOT_A_THEME, "bad version")
        val base = when (val b = root.opt("base")) {
            null -> ThemePreset.DEFAULT
            is String -> ThemePreset.parse(b) ?: return bad(ThemePackError.NOT_A_THEME, "unknown base '$b'")
            else -> return bad(ThemePackError.NOT_A_THEME, "bad base")
        }
        val keys = if (schema >= 2) COLOR_KEYS + SURFACE_KEYS else COLOR_KEYS
        val colors = root.opt("colors") as? JSONObject ?: return bad(ThemePackError.NOT_A_THEME, "missing colors")
        val dark = colorsOf(colors, "dark", keys) ?: return bad(ThemePackError.NOT_A_THEME, "bad dark colour")
        val light = colorsOf(colors, "light", keys) ?: return bad(ThemePackError.NOT_A_THEME, "bad light colour")

        var specs: Map<ImageSlot, ImageSpec> = emptyMap()
        val decoded = LinkedHashMap<ImageSlot, ThemeImage>()
        if (schema >= 2) {
            specs = imageSpecs(root.opt("images")) ?: return bad(ThemePackError.NOT_A_THEME, "bad images")
            val referenced = specs.values.map { it.file }
            if (referenced.toSet().size != referenced.size) return bad(ThemePackError.NOT_A_THEME, "one file used by two slots")
            images.keys.firstOrNull { it !in referenced }?.let { return bad(ThemePackError.BAD_CONTENTS, "unreferenced '$it'") }
            for ((slot, spec) in specs) {
                val raw = images[spec.file] ?: return bad(ThemePackError.NOT_A_THEME, "missing '${spec.file}'")
                if (raw.size > MAX_IMAGE_BYTES) return bad(ThemePackError.TOO_LARGE, "'${spec.file}' over $MAX_IMAGE_BYTES")
                val sniff = ImageSniffer.sniff(raw)
                if (sniff is ImageSniffer.Result.Refused) return bad(
                    if (sniff.reason == "too many pixels") ThemePackError.TOO_LARGE else ThemePackError.BAD_IMAGE, "'${spec.file}': ${sniff.reason}")
                val c = codec ?: return bad(ThemePackError.BAD_IMAGE, "no image decoder")
                val img = (if (sanitize) c.sanitize(raw, slot.maxSide) else c.load(raw, spec.file.substringAfterLast('.')))
                    ?: return bad(ThemePackError.BAD_IMAGE, "'${spec.file}' can't be decoded")
                decoded[slot] = img
            }
            // Point every spec at Ulfur's own re-encoded file name (images/<slot>.<png|jpg>).
            specs = specs.mapValues { (slot, sp) -> sp.copy(file = "images/${slot.key}.${decoded.getValue(slot).ext}") }
        } else if (images.isNotEmpty()) return bad(ThemePackError.BAD_CONTENTS, "images need schema 2")

        if (dark.isEmpty() && light.isEmpty() && decoded.isEmpty()) return bad(ThemePackError.NOT_A_THEME, "no colours")
        return ThemePackResult.Ok(ThemePack(name, author, version, base, dark, light, schema, specs, decoded))
    }

    private fun imageSpecs(v: Any?): Map<ImageSlot, ImageSpec>? {
        if (v == null) return emptyMap()
        if (v !is JSONObject) return null
        val out = LinkedHashMap<ImageSlot, ImageSpec>()
        for (key in v.keys()) {
            val slot = ImageSlot.from(key) ?: continue // unknown slots ignored
            val o = v.opt(key) as? JSONObject ?: return null
            val file = o.opt("file") as? String ?: return null
            if (!safeImageEntry(file)) return null
            if ((o.opt("fit") ?: "cover") != "cover") return null
            var fx = 0.5f; var fy = 0.5f
            o.opt("focus")?.let { f ->
                if (f !is JSONObject) return null
                fx = unit(f.opt("x") ?: 0.5) ?: return null
                fy = unit(f.opt("y") ?: 0.5) ?: return null
            }
            val scrim = unit(o.opt("scrim") ?: 0.0, max = 0.9) ?: return null
            val auto = when (val a = o.opt("scrimAuto")) { null -> true; is Boolean -> a; else -> return null }
            val sMax = unit(o.opt("scrimMax") ?: 0.85, max = 0.9) ?: return null
            if (sMax < scrim) return null
            out[slot] = ImageSpec(slot, file, fx, fy, scrim, auto, sMax)
        }
        return out
    }

    private fun unit(v: Any, max: Double = 1.0): Float? {
        val d = (v as? Number)?.toDouble() ?: return null
        return if (d.isNaN() || d < 0.0 || d > max) null else d.toFloat()
    }

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

    private fun text(o: JSONObject, key: String, required: Boolean): String? {
        val v = o.opt(key) ?: return if (required) null else ""
        if (v !is String) return null
        val t = v.trim()
        if (t.length > MAX_TEXT || t.any { Character.isISOControl(it) || it in "\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069" }) return null
        if (required && t.isEmpty()) return null
        return t
    }

    private fun colorsOf(colors: JSONObject, mode: String, keys: List<String>): Map<String, Int>? {
        val m = colors.opt(mode) ?: return emptyMap()
        if (m !is JSONObject) return null
        val out = LinkedHashMap<String, Int>()
        for (key in keys) {
            val v = m.opt(key) ?: continue
            out[key] = parseColor(v) ?: return null
        }
        return out
    }

    fun parseColor(v: Any?): Int? {
        if (v !is String || !Regex("#[0-9A-Fa-f]{6}").matches(v)) return null
        return ThemeColor.parse(v)
    }

    private fun bad(e: ThemePackError, d: String) = ThemePackResult.Bad(e, d)
}

/** WCAG 2.x contrast of the text-on-background pairs a palette produces. */
object ThemeContrast {
    const val MIN_TEXT = 4.5

    /** Ulfur's resource colours (values/colors.xml, values-night/colors.xml). */
    const val BG_DARK = 0xFF0E0E12.toInt()
    const val SURFACE_DARK = 0xFF16161C.toInt()
    const val SURFACE2_DARK = 0xFF1C1C24.toInt()
    const val TEXT_DARK = 0xFFF2F2F7.toInt()
    const val TEXT2_DARK = 0xFFA0A0AE.toInt()
    const val BG_LIGHT = 0xFFF4F4F7.toInt()
    const val SURFACE_LIGHT = 0xFFFFFFFF.toInt()
    const val SURFACE2_LIGHT = 0xFFEEEEF2.toInt()
    const val TEXT_LIGHT = 0xFF121218.toInt()
    const val TEXT2_LIGHT = 0xFF5C5C6A.toInt()

    data class Pair(val label: String, val text: Int, val background: Int, val ratio: Double)

    fun ratio(a: Int, b: Int): Double = ColorUtils.calculateContrast(a or 0xFF000000.toInt(), b or 0xFF000000.toInt())

    fun pairs(p: ThemePalette): List<Pair> {
        val r = (p.surfaces ?: ThemeSurfaces()).resolved(p.night)
        val list = mutableListOf(
            Pair("Text on accent", p.onColor, p.color, ratio(p.onColor, p.color)),
            Pair("Accent text on background", p.accentText, r.background, ratio(p.accentText, r.background)),
            Pair("Accent text on surface", p.accentText, r.surface, ratio(p.accentText, r.surface)),
        )
        if (p.gradientCarriesText) {
            list += Pair("Text on gradient start", p.onColor, p.accentStart, ratio(p.onColor, p.accentStart))
            list += Pair("Text on gradient end", p.onColor, p.accentEnd, ratio(p.onColor, p.accentEnd))
        }
        p.surfaces?.let { s ->
            list += Pair("Text on background", r.text, r.background, ratio(r.text, r.background))
            list += Pair("Text on surface", r.text, r.surface, ratio(r.text, r.surface))
            list += Pair("Text on surface variant", r.text, r.surfaceVariant, ratio(r.text, r.surfaceVariant))
            list += Pair("Secondary text on background", r.textSecondary, r.background, ratio(r.textSecondary, r.background))
            list += Pair("Secondary text on surface", r.textSecondary, r.surface, ratio(r.textSecondary, r.surface))
            list += Pair("Toolbar text on toolbar", r.onToolbar, r.toolbar, ratio(r.onToolbar, r.toolbar))
            // Parts still styled in XML keep Ulfur's own text colour: check it against the pack's backgrounds too.
            val xmlText = if (p.night) TEXT_DARK else TEXT_LIGHT
            if (s.background != null) list += Pair("Ulfur's own text on background", xmlText, r.background, ratio(xmlText, r.background))
            if (s.surface != null) list += Pair("Ulfur's own text on surface", xmlText, r.surface, ratio(xmlText, r.surface))
        }
        return list
    }

    fun lowContrast(p: ThemePalette): List<Pair> = pairs(p).filter { it.ratio < MIN_TEXT }

    /** Every warning for [pack] in [night]: colour pairs plus text over each image after its (auto) scrim. */
    fun warnings(pack: ThemePack, night: Boolean): List<String> =
        lowContrast(pack.palette(night)).map { it.label } +
            pack.images.keys.mapNotNull { s -> pack.scrimVerdict(s, night)?.takeIf { it.ratio < MIN_TEXT }?.let { "Text over the ${s.key} image" } }
}
