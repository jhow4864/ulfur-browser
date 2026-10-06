package com.jamhowman.beastbrowser.downloads

import java.net.URI
import java.util.Locale

/**
 * Minimal M3U8 parser for HLS downloads (2.3.6).
 *
 * Handles master playlists (`#EXT-X-STREAM-INF` variants) and media playlists with
 * `#EXTINF`, `#EXT-X-MEDIA-SEQUENCE`, `#EXT-X-KEY` / `#EXT-X-SESSION-KEY`, `#EXT-X-MAP` (fMP4 init)
 * and `#EXT-X-BYTERANGE`. Only unencrypted streams are downloadable: any key other than
 * `METHOD=NONE` (AES-128, SAMPLE-AES, Widevine / PlayReady / FairPlay) is flagged so the caller
 * refuses the stream. Keys are only parsed for that check; they are never fetched.
 */
object HlsPlaylist {
    const val ENCRYPTED_MESSAGE = "Encrypted HLS streams can't be saved"

    private val DRM_KEYFORMAT = Regex(
        "(widevine|playready|com\\.apple\\.streamingkeydelivery|com\\.microsoft|urn:uuid:edef8ba9|urn:uuid:9a04f079|urn:uuid:94ce86fb)",
    )
    private val ATTR = Regex("([A-Z0-9-]+)=(\"(?:\\\\.|[^\"])*\"|[^,]*)")
    private val RESOLUTION = Regex("^\\d+x\\d+$")
    private val GENERIC_NAME = Regex("(?i)(index|playlist|master|chunklist)")

    enum class Container { MPEG_TS, FMP4 }

    data class ByteRange(val length: Long, val offset: Long)

    data class InitSegment(val url: String, val byteRange: ByteRange? = null)

    class KeyInfo(
        val method: String,
        val uri: String? = null,
        val iv: ByteArray? = null,
        val keyFormat: String = "",
    ) {
        val isNone: Boolean get() = method == "NONE"
        val isDrm: Boolean
            get() = method.startsWith("SAMPLE-AES") ||
                DRM_KEYFORMAT.containsMatchIn(keyFormat) ||
                uri?.startsWith("skd:", ignoreCase = true) == true
        /** Any encryption at all, AES-128 included. Only METHOD=NONE is downloadable. */
        val isUnsupported: Boolean get() = !isNone
    }

    data class Segment(
        val url: String,
        val durationSec: Double,
        val mediaSequence: Long,
        val key: KeyInfo,
        val byteRange: ByteRange? = null,
    )

    data class Variant(val url: String, val bandwidth: Long = 0, val width: Int = 0, val height: Int = 0)

    /** A parsed playlist: either a master ([isMaster], [variants]) or a media playlist ([segments]). */
    data class MediaPlaylist(
        val segments: List<Segment>,
        val init: InitSegment? = null,
        val mediaSequenceStart: Long = 0,
        /** Some key other than METHOD=NONE was seen. */
        val encrypted: Boolean = false,
        /** A DRM key format / SAMPLE-AES was seen. */
        val drm: Boolean = false,
        val isMaster: Boolean = false,
        val variants: List<Variant> = emptyList(),
        val container: Container = Container.MPEG_TS,
    ) {
        val hasUnsupportedEncryption: Boolean get() = drm || segments.any { it.key.isUnsupported }

        /** Highest resolution, then highest bandwidth. */
        fun bestVariantUrl(): String? =
            variants.maxWithOrNull(compareBy<Variant> { it.height }.thenBy { it.bandwidth })?.url
    }

    fun parse(text: String, baseUrl: String): MediaPlaylist {
        val empty = MediaPlaylist(emptyList())
        if (text.isBlank()) return empty
        val lines = text.removePrefix("\uFEFF").split(Regex("\\r?\\n")).map { it.trim() }
        if (lines.isEmpty() || !lines[0].startsWith("#EXTM3U")) return empty

        val segments = ArrayList<Segment>()
        val variants = ArrayList<Variant>()
        val seenVariants = LinkedHashSet<String>()
        var key = KeyInfo("NONE")
        var encrypted = false
        var drm = false
        var isMaster = false
        var duration: Double? = null
        var sequence = 0L
        var byteRange: ByteRange? = null
        var init: InitSegment? = null

        var i = 1
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    line.substringAfter(':').trim().toLongOrNull()?.let { sequence = it }

                line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:") -> {
                    val a = parseAttrs(line.substringAfter(':'))
                    val k = KeyInfo(
                        method = (a["METHOD"] ?: "NONE").uppercase(Locale.ROOT),
                        uri = a["URI"],
                        iv = a["IV"]?.let { parseIv(it) },
                        keyFormat = a["KEYFORMAT"].orEmpty(),
                    )
                    if (!k.isNone) encrypted = true
                    if (k.isDrm) drm = true
                    // Session keys only flag the playlist; they don't apply to segments.
                    if (line.startsWith("#EXT-X-KEY:")) key = k
                }

                line.startsWith("#EXT-X-MAP:") -> {
                    val a = parseAttrs(line.substringAfter(':'))
                    a["URI"]?.let { uri ->
                        init = InitSegment(resolve(uri, baseUrl), a["BYTERANGE"]?.let { parseByteRange(it, 0) })
                    }
                }

                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    // Without "@offset" a range continues right after the previous segment's range.
                    val prev = segments.lastOrNull()?.byteRange
                    byteRange = parseByteRange(line.substringAfter(':').trim(), prev?.let { it.offset + it.length } ?: 0)
                }

                line.startsWith("#EXTINF:") ->
                    duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0

                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val a = parseAttrs(line.substringAfter(':'))
                    var j = i + 1
                    while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                    if (j < lines.size) {
                        val url = resolve(lines[j], baseUrl)
                        if (seenVariants.add(url)) {
                            val res = a["RESOLUTION"]?.takeIf { RESOLUTION.matches(it) }?.split('x')
                            variants += Variant(
                                url = url,
                                bandwidth = a["BANDWIDTH"]?.toLongOrNull() ?: 0,
                                width = res?.get(0)?.toIntOrNull() ?: 0,
                                height = res?.get(1)?.toIntOrNull() ?: 0,
                            )
                        }
                        i = j
                    }
                    isMaster = true
                }

                line.isEmpty() || line.startsWith("#") -> {}

                duration != null -> {
                    segments += Segment(resolve(line, baseUrl), duration, sequence, key, byteRange)
                    sequence++
                    duration = null
                    byteRange = null
                }
            }
            i++
        }
        val container = if (init != null || segments.any { looksFmp4(it.url) }) Container.FMP4 else Container.MPEG_TS
        return MediaPlaylist(
            segments = segments,
            init = init,
            mediaSequenceStart = segments.firstOrNull()?.mediaSequence ?: 0,
            encrypted = encrypted,
            drm = drm,
            isMaster = isMaster,
            variants = variants,
            container = container,
        )
    }

    /** `KEY=value,KEY2="quoted, value"` → map with upper-cased keys and unquoted values. */
    internal fun parseAttrs(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ATTR.findAll(s).forEach { m ->
            var v = m.groupValues[2]
            if (v.length >= 2 && v.first() == '"' && v.last() == '"') v = v.substring(1, v.length - 1).replace("\\\"", "\"")
            out[m.groupValues[1].uppercase(Locale.ROOT)] = v
        }
        return out
    }

    fun fileExtension(container: Container): String = when (container) {
        Container.FMP4 -> "mp4"
        Container.MPEG_TS -> "ts"
    }

    fun mimeFor(container: Container): String = when (container) {
        Container.FMP4 -> "video/mp4"
        Container.MPEG_TS -> "video/mp2t"
    }

    /** Page title if there is one, else the playlist's file name ("index"/"master"… become "video"). */
    fun suggestFileName(title: String?, url: String, container: Container): String {
        var base = title?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/').substringBefore('?')
        if (base.isBlank()) base = "video"
        if (title.isNullOrBlank()) base = base.substringBeforeLast('.', base)
        if (base.isBlank()) base = "video"
        if (GENERIC_NAME.matches(base)) base = "video"
        return "$base.${fileExtension(container)}"
    }

    private fun looksFmp4(url: String): Boolean {
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        return path.endsWith(".m4s") || path.endsWith(".mp4") || path.endsWith(".cmfv") || path.endsWith(".cmfa")
    }

    private fun parseByteRange(s: String, defaultOffset: Long): ByteRange? {
        val length = s.substringBefore('@').toLongOrNull() ?: return null
        val offset = if ('@' in s) s.substringAfter('@').toLongOrNull() ?: defaultOffset else defaultOffset
        return ByteRange(length, offset)
    }

    private fun parseIv(s: String): ByteArray? {
        val hex = s.removePrefix("0x").removePrefix("0X")
        if (hex.length != 32 || hex.any { it !in "0123456789abcdefABCDEF" }) return null
        return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun resolve(ref: String, base: String): String =
        try { URI(base).resolve(ref.trim()).toString() } catch (_: Exception) { ref }
}
