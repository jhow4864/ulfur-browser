package com.jamhowman.beastbrowser.browser

import android.graphics.Bitmap
import com.jamhowman.beastbrowser.data.Realm
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.MediaSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** @param realm 2.3.8: the [Realm] (cookie jar / tab list) this tab belongs to. */
class Tab(val id: Long, val isPrivate: Boolean, var session: GeckoSession, val realm: Realm = Realm.PLAY) {
    var url: String = UrlUtils.HOME
    var title: String = ""
    var thumbnail: Bitmap? = null
    var progress: Int = 100
    var loading = false
    var showingHome = true
    var desktopMode = false
    /** Page zoom percent for this tab (100 = default). Remembered per host in BrowserDb. */
    var zoomPercent = 100
    var parentId: Long? = null
    /** 2.3.5: [com.jamhowman.beastbrowser.data.TabGroup] id, or null. */
    var groupId: String? = null
    var pendingUrl: String? = null
    var canGoBack = false
    var canGoForward = false
    var isSecure = false
    var scrollY = 0
    var hasLoaded = false
    /** Restore HTTPS-only mode once a user-approved http:// page finishes. */
    var restoreHttpsOnly = false

    // 2.3.4: page translation (GeckoView TranslationsController)
    /** Gecko offered / expects a translation for the current page. */
    var translateOffered = false
    /** User closed the translate chip on this tab (remembered for the tab's lifetime). */
    var translateDismissed = false
    /** The page is currently shown translated. */
    var translated = false
    var docLangTag: String? = null
    var userLangTag: String? = null

    /** Gecko's tracking-protection permission for the current page; VALUE_ALLOW == shields down (ETP exception). */
    var trackingPermission: ContentPermission? = null

    /** Enhanced Tracking Protection blocks on the current page. */
    val etpBlocked = AtomicInteger(0)
    val blockedHosts: MutableMap<String, Int> = ConcurrentHashMap()
    /** uBlock Origin's per-tab badge count for the current page. */
    var uboCount = 0

    val blockedOnPage: Int get() = etpBlocked.get() + uboCount

    /** Set right after we change the ETP exception (the cached ContentPermission is stale until the next load). */
    var etpDownOverride: Boolean? = null
    /** ETP exception for this site (tracking protection allowed). */
    val shieldsDown: Boolean
        get() = etpDownOverride ?: (trackingPermission?.value == ContentPermission.VALUE_ALLOW)

    /** uBO's per-site switch for the current host (null = unknown / uBO not available). */
    var uboSiteOn: Boolean? = null
    var uboSiteHost: String? = null
    /** Shields counted as down if either ETP or uBO is paused for this site. */
    val siteShieldsDown: Boolean
        get() = shieldsDown || uboSiteOn == false

    /** 2.5: autoplay requests refused on the current page. */
    var autoplayBlocked = 0

    // 2.5: media state for picture-in-picture (GeckoView MediaSession delegate)
    /** Gecko's controllable media session for this tab, while one is active. */
    var mediaSession: MediaSession? = null
    var mediaPlaying = false
    /** A video element is fullscreen (MediaSession.Delegate.onFullscreen). */
    var mediaFullscreen = false
    /** Size of the fullscreen video, 0 if unknown. */
    var videoWidth = 0L
    var videoHeight = 0L
    /** Last MediaSession.PositionState (seconds) and when it arrived (SystemClock.elapsedRealtime). */
    var mediaDuration = 0.0
    var mediaPosition = 0.0
    var mediaRate = 1.0
    var mediaPositionAt = 0L

    fun resetMedia() {
        mediaDuration = 0.0
        mediaPosition = 0.0
        mediaRate = 1.0
        mediaPositionAt = 0L
        mediaSession = null
        mediaPlaying = false
        mediaFullscreen = false
        videoWidth = 0L
        videoHeight = 0L
    }

    fun resetPageStats() {
        etpBlocked.set(0)
        blockedHosts.clear()
        uboCount = 0
    }

    val displayTitle: String
        get() = when {
            showingHome -> "Speed Dial"
            title.isNotBlank() -> title
            else -> UrlUtils.host(url) ?: url
        }
}
