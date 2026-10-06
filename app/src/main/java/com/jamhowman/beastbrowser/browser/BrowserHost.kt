package com.jamhowman.beastbrowser.browser

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
    fun onVisited(tab: Tab, url: String)
    fun onNewWindow(opener: Tab, uri: String): GeckoSession?
    fun closeTabRequested(tab: Tab)
    fun openExternal(tab: Tab, uri: String): Boolean
    fun onBeastUri(tab: Tab, uri: String)
    fun onFullScreen(tab: Tab, fullScreen: Boolean)
    /** 2.5: play / pause / fullscreen-video changes from the tab's MediaSession (picture-in-picture). */
    fun onMediaStateChanged(tab: Tab)
    fun onContextMenu(tab: Tab, element: ContextElement)
    fun onDownload(tab: Tab, response: WebResponse)
    fun onCrashed(tab: Tab)
    fun errorPage(tab: Tab, uri: String?, code: Int, category: Int): GeckoResult<String>?
}
