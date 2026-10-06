package com.jamhowman.beastbrowser.browser

import com.jamhowman.beastbrowser.media.MediaSniffer

import com.jamhowman.beastbrowser.data.Stats
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
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/** All per-session Gecko delegates for one tab. */
class TabCallbacks(private val tab: Tab, private val host: BrowserHost) :
    NavigationDelegate, ProgressDelegate, ContentDelegate, HistoryDelegate, ScrollDelegate,
    PermissionDelegate, ContentBlocking.Delegate {

    fun attach(session: GeckoSession) {
        session.navigationDelegate = this
        session.progressDelegate = this
        session.contentDelegate = this
        session.historyDelegate = this
        session.scrollDelegate = this
        session.permissionDelegate = this
        session.contentBlockingDelegate = this
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
    override fun onCrash(session: GeckoSession) = host.onCrashed(tab)
    override fun onKill(session: GeckoSession) = host.onCrashed(tab)

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
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> ContentPermission.VALUE_ALLOW
            PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> {
                HelperSessions.markDrm(session) // DRM page: never offer media downloads
                ContentPermission.VALUE_ALLOW // DRM video (Netflix etc.)
            }
            PermissionDelegate.PERMISSION_STORAGE_ACCESS -> ContentPermission.VALUE_DENY
            else -> ContentPermission.VALUE_DENY // location, notifications, XR, local network, audible autoplay
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
        host.onBlockedChanged(tab)
    }
}
