package com.jamhowman.beastbrowser.browser

import com.jamhowman.beastbrowser.crash.CrashReporter
import com.jamhowman.beastbrowser.media.MediaSniffer

import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.Stats
import com.jamhowman.beastbrowser.data.TrackerCategory
import com.jamhowman.beastbrowser.data.TrackerTally
import com.jamhowman.beastbrowser.util.Domains
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.HistoryDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSession.ScrollDelegate
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/** All per-session Gecko delegates for one tab. */
class TabCallbacks(private val tab: Tab, private val host: BrowserHost) :
    NavigationDelegate, ProgressDelegate, ContentDelegate, HistoryDelegate, ScrollDelegate,
    PermissionDelegate, ContentBlocking.Delegate, MediaSession.Delegate {

    fun attach(session: GeckoSession) {
        session.navigationDelegate = this
        session.progressDelegate = this
        session.contentDelegate = this
        session.historyDelegate = this
        session.scrollDelegate = this
        session.permissionDelegate = this
        session.contentBlockingDelegate = this
        session.mediaSessionDelegate = this
    }

    // ---------------------------------------------------------------- navigation

    override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<ContentPermission>, hasUserGesture: Boolean) {
        if (url == null || url == "about:blank" && !tab.hasLoaded) return
        tab.url = url
        HelperSessions.onLocationChange(session, url)
        tab.trackingPermission = perms.firstOrNull { it.permission == PermissionDelegate.PERMISSION_TRACKING }
        tab.etpDownOverride = null
        host.onTabUpdated(tab)
    }

    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canGoBack = canGoBack; host.onTabUpdated(tab) }
    override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { tab.canGoForward = canGoForward; host.onTabUpdated(tab) }

    override fun onLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
        val uri = request.uri
        val scheme = uri.substringBefore(':').lowercase()
        return when (scheme) {
            "http", "https", "about", "data", "blob", "moz-extension", "resource", "file", "view-source" -> GeckoResult.allow()
            "beast" -> { host.onBeastUri(tab, uri); GeckoResult.deny() }
            else -> if (host.openExternal(tab, uri)) GeckoResult.deny() else GeckoResult.allow()
        }
    }

    override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
        val s = host.onNewWindow(tab, uri) ?: return null
        return GeckoResult.fromValue(s)
    }

    override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? =
        host.errorPage(tab, uri, error.code, error.category)

    // ---------------------------------------------------------------- progress

    override fun onPageStart(session: GeckoSession, url: String) {
        tab.loading = true
        tab.hasLoaded = true
        tab.resetPageStats()
        tab.autoplayBlocked = 0
        MediaSniffer.clear(session)
        host.onBlockedChanged(tab)
        host.onPageStarted(tab, url)
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        tab.loading = false
        host.onPageStopped(tab, success)
    }

    override fun onProgressChange(session: GeckoSession, progress: Int) {
        tab.progress = progress
        host.onProgress(tab, progress)
    }

    override fun onSecurityChange(session: GeckoSession, securityInfo: ProgressDelegate.SecurityInformation) {
        tab.isSecure = securityInfo.isSecure
        host.onTabUpdated(tab)
    }

    // ---------------------------------------------------------------- content

    override fun onTitleChange(session: GeckoSession, title: String?) {
        tab.title = title.orEmpty()
        host.onTabUpdated(tab)
    }

    override fun onCloseRequest(session: GeckoSession) = host.closeTabRequested(tab)
    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) = host.onFullScreen(tab, fullScreen)
    override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContentDelegate.ContextElement) =
        host.onContextMenu(tab, element)
    override fun onExternalResponse(session: GeckoSession, response: WebResponse) = host.onDownload(tab, response)
    override fun onCrash(session: GeckoSession) {
        CrashReporter.recordContentCrash() // 2.5.1: opt-in, local only, nothing about the tab
        host.onCrashed(tab)
    }
    // Killed by Android (usually low memory): not a crash, so no report.
    override fun onKill(session: GeckoSession) = host.onCrashed(tab)

    // ---------------------------------------------------------------- media session (2.5: picture-in-picture)

    override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
        tab.mediaSession = mediaSession
        host.onMediaStateChanged(tab)
    }

    override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
        tab.resetMedia()
        host.onMediaStateChanged(tab)
    }

    override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
        tab.mediaSession = mediaSession
        tab.mediaPlaying = true
        host.onMediaStateChanged(tab)
    }

    override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
        tab.mediaPlaying = false
        host.onMediaStateChanged(tab)
    }

    override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
        tab.mediaPlaying = false
        host.onMediaStateChanged(tab)
    }

    override fun onPositionState(session: GeckoSession, mediaSession: MediaSession, state: MediaSession.PositionState) {
        val hadSeek = tab.mediaDuration.isFinite() && tab.mediaDuration > 0
        tab.mediaDuration = state.duration
        tab.mediaPosition = state.position
        tab.mediaRate = state.playbackRate
        tab.mediaPositionAt = android.os.SystemClock.elapsedRealtime()
        // Only the first known duration changes the PiP actions (back / forward 10 s appear).
        if (hadSeek != (state.duration.isFinite() && state.duration > 0)) host.onMediaStateChanged(tab)
    }

    override fun onFullscreen(session: GeckoSession, mediaSession: MediaSession, enabled: Boolean, meta: MediaSession.ElementMetadata?) {
        tab.mediaSession = mediaSession
        tab.mediaFullscreen = enabled
        if (enabled && meta != null && meta.width > 0 && meta.height > 0) {
            tab.videoWidth = meta.width
            tab.videoHeight = meta.height
        }
        host.onMediaStateChanged(tab)
    }

    // ---------------------------------------------------------------- history (never called for private sessions)

    override fun onVisited(session: GeckoSession, url: String, lastVisitedURL: String?, flags: Int): GeckoResult<Boolean>? {
        val topLevel = flags and HistoryDelegate.VISIT_TOP_LEVEL != 0
        val redirectSource = flags and (HistoryDelegate.VISIT_REDIRECT_SOURCE or HistoryDelegate.VISIT_REDIRECT_SOURCE_PERMANENT) != 0
        val error = flags and HistoryDelegate.VISIT_UNRECOVERABLE_ERROR != 0
        if (topLevel && !redirectSource && !error && !tab.isPrivate) host.onVisited(tab, url)
        return GeckoResult.fromValue(true)
    }

    // ---------------------------------------------------------------- scrolling (for pull-to-refresh)

    override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) { tab.scrollY = scrollY }

    // ---------------------------------------------------------------- permissions: privacy-first defaults

    override fun onContentPermissionRequest(session: GeckoSession, perm: ContentPermission): GeckoResult<Int>? {
        val value = when (perm.permission) {
            // 2.5: Settings > Media > Autoplay, plus the per-site exception from the Shields sheet
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE, PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> {
                val site = AutoplayPolicy.siteKey(perm.uri)
                val mode = AutoplayPolicy.effective(Prefs.autoplay, host.autoplayFor(tab, site))
                AutoplayPolicy.decide(mode, perm.permission).also { v ->
                    if (v == AutoplayPolicy.BLOCK) { tab.autoplayBlocked++; host.onAutoplayBlocked(tab, site) }
                }
            }
            PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> {
                HelperSessions.markDrm(session) // DRM page: never offer media downloads
                ContentPermission.VALUE_ALLOW // DRM video (Netflix etc.)
            }
            PermissionDelegate.PERMISSION_STORAGE_ACCESS -> ContentPermission.VALUE_DENY
            else -> ContentPermission.VALUE_DENY // location, notifications, XR, local network
        }
        return GeckoResult.fromValue(value)
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession, uri: String,
        video: Array<out PermissionDelegate.MediaSource>?, audio: Array<out PermissionDelegate.MediaSource>?,
        callback: PermissionDelegate.MediaCallback,
    ) = callback.reject()

    override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<out String>?, callback: PermissionDelegate.Callback) =
        callback.reject()

    // ---------------------------------------------------------------- Enhanced Tracking Protection

    override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
        if (!event.isBlocking) return
        tab.etpBlocked.incrementAndGet()
        val h = UrlUtils.host(event.uri)
        if (h != null) tab.blockedHosts.merge(Domains.display(h), 1, Int::plus)
        Stats.total.incrementAndGet()
        // Roadmap 10: weekly tally kept on the phone. Only the top-level page's host, never from private / Ghost tabs.
        val private = tab.isPrivate || session.settings.usePrivateMode
        TrackerTally.siteToRecord(private, tab.url)?.let { site ->
            host.onTrackerBlocked(tab, site, TrackerCategory.from(event.antiTrackingCategory, event.cookieBehaviorCategory))
        }
        host.onBlockedChanged(tab)
    }
}
