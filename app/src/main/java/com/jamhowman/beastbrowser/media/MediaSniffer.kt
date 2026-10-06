package com.jamhowman.beastbrowser.media

import android.net.Uri
import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-tab list of detected videos. Beast Helper's media sniffer pushes updates via [publish].
 * Known DRM / ToS-sensitive hosts are hard-blocked here so a Save sheet can never appear for them.
 *
 * See `docs/media-sniffer.md` for the extension ↔ app contract.
 */
object MediaSniffer {
    /** Hosts we refuse to offer a Save sheet for (exact host or suffix match). */
    private val DRM_SUFFIXES = listOf(
        "youtube.com", "youtu.be", "googlevideo.com", "youtube-nocookie.com",
        "netflix.com", "nflxvideo.net",
        "disneyplus.com", "disney.com",
        "hulu.com",
        "primevideo.com", "amazon.com", // Amazon Video CDNs vary; block primevideo + common amazon video hosts
        "max.com", "hbomax.com",
        "spotify.com",
        "twitch.tv",
        "vimeo.com", // often tokenised / progressive can work but skip for v1 safety on their main site
        "tiktok.com",
        "instagram.com",
        "facebook.com", "fbcdn.net",
        // Added (2.2, beast-helper sync — keep in step with BEAST_DRM_HOSTS in media/sniffer-core.js):
        "ytimg.com", "amazonvideo.com", "scdn.co", "tv.apple.com", "peacocktv.com",
        "paramountplus.com", "crunchyroll.com", "ttvnw.net", "itv.com", "channel4.com",
    )

    /** Host suffix + path prefix pairs blocked when a full URL is given (e.g. BBC iPlayer, not all of bbc.co.uk). */
    private val DRM_PATHS = listOf(
        "bbc.co.uk" to "/iplayer",
    )

    private val bySession = ConcurrentHashMap<GeckoSession, List<DetectedMedia>>()
    private val listeners = ConcurrentHashMap.newKeySet<(GeckoSession) -> Unit>()

    fun addListener(listener: (GeckoSession) -> Unit) { listeners += listener }
    fun removeListener(listener: (GeckoSession) -> Unit) { listeners -= listener }

    fun isDrmHost(hostOrUrl: String?): Boolean {
        if (hostOrUrl.isNullOrBlank()) return false
        val host = try {
            if (hostOrUrl.contains("://")) Uri.parse(hostOrUrl).host else hostOrUrl
        } catch (_: Exception) { hostOrUrl }
            ?.lowercase()?.removePrefix("www.") ?: return false
        if (DRM_SUFFIXES.any { host == it || host.endsWith(".$it") }) return true
        if (hostOrUrl.contains("://")) {
            val path = try { Uri.parse(hostOrUrl).path.orEmpty().lowercase() } catch (_: Exception) { "" }
            return DRM_PATHS.any { (h, p) -> (host == h || host.endsWith(".$h")) && path.startsWith(p) }
        }
        return false
    }

    /** Hosts in the hard block list (exposed for the extension/app sync test). */
    internal val drmSuffixes: List<String> get() = DRM_SUFFIXES

    /** Replace the detected list for a tab. Blocked hosts are stripped. */
    fun publish(session: GeckoSession, items: List<DetectedMedia>) {
        val cleaned = items.filterNot { isDrmHost(it.url) || isDrmHost(it.pageUrl) }
            .distinctBy { it.url }
        if (cleaned.isEmpty()) bySession.remove(session) else bySession[session] = cleaned
        listeners.forEach { runCatching { it(session) } }
    }

    fun clear(session: GeckoSession) {
        if (bySession.remove(session) != null) listeners.forEach { runCatching { it(session) } }
    }

    fun forSession(session: GeckoSession): List<DetectedMedia> = bySession[session].orEmpty()

    fun count(session: GeckoSession): Int = forSession(session).size

    /** True when the sniffer has at least one saveable (non-DRM) item. */
    fun hasMedia(session: GeckoSession): Boolean = count(session) > 0
}
