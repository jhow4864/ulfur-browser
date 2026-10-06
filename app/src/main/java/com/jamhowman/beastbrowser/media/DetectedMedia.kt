package com.jamhowman.beastbrowser.media

/**
 * One video/audio stream the sniffer found on a page.
 * Progressive MP4/WebM and clear (non-DRM) HLS only — never encrypted/DASH-DRM.
 */
data class DetectedMedia(
    val id: String,
    val url: String,
    val mime: String = "video/mp4",
    /** Human label like "1080p", "720p", "Audio". */
    val qualityLabel: String = "Video",
    val width: Int = 0,
    val height: Int = 0,
    /** -1 when unknown. */
    val bytes: Long = -1,
    val kind: Kind = Kind.PROGRESSIVE,
    val pageUrl: String = "",
    val title: String = "",
    // --- Added for HLS variants (2.2, additive; defaults keep existing call sites compiling) ---
    /** HLS variant BANDWIDTH in bits/s, -1 when unknown. */
    val bandwidth: Long = -1,
    /** For an HLS variant: the master playlist it came from (null for progressive / media-only playlists). */
    val masterUrl: String? = null,
    /** HLS CODECS attribute, e.g. "avc1.64001f,mp4a.40.2"; empty when unknown. */
    val codecs: String = "",
) {
    enum class Kind { PROGRESSIVE, HLS }

    val shortHost: String
        get() = try {
            java.net.URI(url).host?.removePrefix("www.") ?: ""
        } catch (_: Exception) { "" }
}
