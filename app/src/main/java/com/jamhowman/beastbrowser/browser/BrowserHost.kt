package com.jamhowman.beastbrowser.browser

import com.jamhowman.beastbrowser.data.TrackerCategory
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement
import org.mozilla.geckoview.WebResponse

/** Callbacks from per-tab Gecko delegates to the activity (main thread). */
interface BrowserHost {
    fun onTabUpdated(tab: Tab)
    fun onPageStarted(tab: Tab, url: String)
    fun onPageStopped(tab: Tab, success: Boolean)
    fun onProgress(tab: Tab, progress: Int)
    fun onBlockedChanged(tab: Tab)
    /** Roadmap 10: Gecko blocked a [category] tracker on a page of [site] (host only). Never called for private tabs. */
    fun onTrackerBlocked(tab: Tab, site: String, category: TrackerCategory)
    fun onVisited(tab: Tab, url: String)
    fun onNewWindow(opener: Tab, uri: String): GeckoSession?
    fun closeTabRequested(tab: Tab)
    fun openExternal(tab: Tab, uri: String): Boolean
    fun onBeastUri(tab: Tab, uri: String)
    fun onFullScreen(tab: Tab, fullScreen: Boolean)
    /** 2.5: play / pause / fullscreen-video changes from the tab's MediaSession (picture-in-picture). */
    fun onMediaStateChanged(tab: Tab)
    /** 2.5: per-site autoplay override for [site] as seen from [tab] (private tabs also see session-only choices). */
    fun autoplayFor(tab: Tab, site: String): AutoplayPolicy.Mode?
    /** 2.5: an autoplay request on [tab] was refused. */
    fun onAutoplayBlocked(tab: Tab, site: String)
    fun onContextMenu(tab: Tab, element: ContextElement)
    fun onDownload(tab: Tab, response: WebResponse)
    fun onCrashed(tab: Tab)
    fun errorPage(tab: Tab, uri: String?, code: Int, category: Int): GeckoResult<String>?
}
