package com.jamhowman.beastbrowser.downloads

/**
 * 2.11.1: a vault file's real type. The stored MIME wins when it's specific; when it's missing or generic
 * (application/octet-stream, binary/octet-stream, …) the extension decides, and when that's missing or `.bin`,
 * the first bytes of the decrypted content do. Also fixes `.bin`/extensionless names to match.
 */
object VaultMime {
    const val GENERIC = "application/octet-stream"
    private val generic = setOf("", GENERIC, "binary/octet-stream", "application/x-binary", "application/unknown",
        "application/binary", "application/download", "application/force-download", "application/x-download")

    private val byExt = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska",
        "ts" to "video/mp2t", "mov" to "video/quicktime", "3gp" to "video/3gpp", "avi" to "video/x-msvideo",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg", "oga" to "audio/ogg",
        "opus" to "audio/ogg", "wav" to "audio/wav", "flac" to "audio/flac",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "pdf" to "application/pdf", "zip" to "application/zip", "txt" to "text/plain",
    )
    private val extFor = mapOf(
        "video/mp4" to "mp4", "video/webm" to "webm", "video/x-matroska" to "mkv", "video/mp2t" to "ts",
        "video/quicktime" to "mov", "video/3gpp" to "3gp", "video/x-msvideo" to "avi", "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a", "audio/aac" to "aac", "audio/ogg" to "ogg", "audio/wav" to "wav", "audio/flac" to "flac",
        "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp",
        "application/pdf" to "pdf", "application/zip" to "zip", "text/plain" to "txt",
    )

    fun isGeneric(mime: String?) = mime == null || mime.substringBefore(';').trim().lowercase() in generic

    fun fromExtension(name: String): String? = byExt[name.substringAfterLast('.', "").lowercase()]

    /** Type from magic bytes, or null. */
    fun sniff(h: ByteArray): String? {
        fun at(i: Int, vararg b: Int) = h.size >= i + b.size && b.indices.all { (h[i + it].toInt() and 0xFF) == b[it] }
        fun ascii(i: Int, s: String) = h.size >= i + s.length && s.indices.all { h[i + it].toInt() == s[it].code }
        return when {
            ascii(4, "ftyp") -> when {
                ascii(8, "qt  ") -> "video/quicktime"
                ascii(8, "M4A ") || ascii(8, "M4B ") -> "audio/mp4"
                ascii(8, "3gp") -> "video/3gpp"
                ascii(8, "avif") || ascii(8, "heic") -> null
                else -> "video/mp4"
            }
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> if (String(h.copyOf(minOf(h.size, 64)), Charsets.ISO_8859_1).contains("webm")) "video/webm" else "video/x-matroska"
            h.size > 188 && at(0, 0x47) && at(188, 0x47) -> "video/mp2t"
            ascii(0, "OggS") -> "audio/ogg"
            ascii(0, "fLaC") -> "audio/flac"
            ascii(0, "ID3") || (h.size > 1 && at(0, 0xFF) && (h[1].toInt() and 0xE0) == 0xE0 && (h[1].toInt() and 0x06) != 0) -> "audio/mpeg"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "RIFF") && ascii(8, "AVI ") -> "video/x-msvideo"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "audio/wav"
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            ascii(0, "GIF8") -> "image/gif"
            ascii(0, "%PDF") -> "application/pdf"
            at(0, 0x50, 0x4B, 0x03, 0x04) -> "application/zip"
            else -> null
        }
    }

    /** Stored type if specific, else extension, else magic bytes, else generic. */
    fun resolve(stored: String?, name: String, head: ByteArray?): String {
        if (!isGeneric(stored)) return stored!!.substringBefore(';').trim()
        fromExtension(name)?.let { return it }
        head?.let { sniff(it) }?.let { return it }
        return GENERIC
    }

    /** `videoplayback.bin` + video/mp4 -> `videoplayback.mp4`; names that already have a real extension stay. */
    fun fixName(name: String, mime: String): String {
        val ext = extFor[mime] ?: return name
        val dot = name.lastIndexOf('.')
        val cur = if (dot > 0) name.substring(dot + 1).lowercase() else ""
        if (cur.isNotEmpty() && cur != "bin" && byExt.containsKey(cur)) return name
        val base = when {
            cur == "bin" -> name.substring(0, dot)
            cur.isEmpty() -> name.trimEnd('.')
            else -> name // unknown extension (e.g. "videoplayback.php"): append
        }
        return "${base.ifEmpty { "download" }}.$ext"
    }

    fun isPlayable(mime: String) = mime.startsWith("video/") || mime.startsWith("audio/")
}
