package com.jamhowman.beastbrowser.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.util.Rational
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.browser.AutoplayPolicy
import com.jamhowman.beastbrowser.browser.ForcedDark
import com.jamhowman.beastbrowser.browser.BrowserHost
import com.jamhowman.beastbrowser.browser.Engine
import com.jamhowman.beastbrowser.browser.ShieldLevel
import com.jamhowman.beastbrowser.browser.Tab
import com.jamhowman.beastbrowser.browser.TabCallbacks
import com.jamhowman.beastbrowser.browser.UrlUtils
import com.jamhowman.beastbrowser.data.Accent
import com.jamhowman.beastbrowser.data.BeastControl
import com.jamhowman.beastbrowser.data.BrowserDb
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.Realm
import com.jamhowman.beastbrowser.data.TabGroup
import com.jamhowman.beastbrowser.data.TrackerCategory
import com.jamhowman.beastbrowser.data.TrackerTally
import com.jamhowman.beastbrowser.data.TrackerTallyDb
import com.jamhowman.beastbrowser.search.SearchSuggest
import com.jamhowman.beastbrowser.search.SuggestItem
import com.jamhowman.beastbrowser.search.SuggestKind
import com.jamhowman.beastbrowser.data.Stats
import com.jamhowman.beastbrowser.passwords.PasswordVault
import com.jamhowman.beastbrowser.databinding.ActivityMainBinding
import com.jamhowman.beastbrowser.databinding.DialogAddTileBinding
import com.jamhowman.beastbrowser.databinding.ItemMenuBinding
import com.jamhowman.beastbrowser.databinding.ItemTabGroupPillBinding
import com.jamhowman.beastbrowser.databinding.SheetBeastControlBinding
import com.jamhowman.beastbrowser.databinding.SheetMenuBinding
import com.jamhowman.beastbrowser.databinding.SheetShieldsBinding
import com.jamhowman.beastbrowser.util.Contrast
import com.jamhowman.beastbrowser.util.Domains
import com.jamhowman.beastbrowser.downloads.DownloadCenter
import com.jamhowman.beastbrowser.media.MediaSniffer
import com.jamhowman.beastbrowser.media.PipPolicy
import com.jamhowman.beastbrowser.browser.HelperSessions
import com.jamhowman.beastbrowser.reader.ReaderMode
import com.jamhowman.beastbrowser.widget.SpeedDialWidget
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.util.EnumMap
import java.util.EnumSet
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : AppCompatActivity(), BrowserHost {

    private lateinit var b: ActivityMainBinding
    private lateinit var runtime: GeckoRuntime
    private lateinit var geckoView: GeckoView
    private lateinit var db: BrowserDb
    /** Roadmap 10: weekly tracker tally, on this phone only. */
    private val tally by lazy { TrackerTallyDb.get(this) }
    private lateinit var prompts: Prompts
    private lateinit var tileAdapter: SpeedDialAdapter
    private lateinit var tabAdapter: TabCardAdapter
    private lateinit var suggestAdapter: SuggestAdapter
    private var suggestJob: Job? = null

    /** 2.3.8 Realms: the active realm; every realm keeps its own tab list and last-selected tab. */
    private var realm = Realm.PLAY
    private val realmTabs = EnumMap<Realm, MutableList<Tab>>(Realm::class.java).apply {
        Realm.entries.forEach { put(it, mutableListOf()) }
    }
    private val realmCurrent = EnumMap<Realm, Tab>(Realm::class.java)
    /** Realms whose saved session was already restored (others are restored lazily on first switch). */
    private val restoredRealms: EnumSet<Realm> = EnumSet.noneOf(Realm::class.java)
    /** Tabs of the current realm (what the UI shows). */
    private val tabs: MutableList<Tab> get() = realmTabs.getValue(realm)
    /** Tabs of every realm (session lookups, uBO, shields). */
    private val allTabs: List<Tab> get() = realmTabs.values.flatten()
    private var current: Tab? = null
    private var nextId = 1L
    private var accent = Accent.RED
    private var switcherPrivate = false
    /** Tab switcher search text (2.3.5); matches title, URL or group name. */
    private var switcherQuery = ""
    /** Tab switcher group filter ([TabGroup.id]); null = All. */
    private var switcherGroupFilter: String? = null
    private var fullscreenTab: Tab? = null
    /** 2.5: the activity is shown in a picture-in-picture window (browser chrome hidden). */
    private var inPip = false
    private var pipFindBarWasVisible = false
    private val pipSupported: Boolean by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    /** Play / pause buttons in the PiP window. */
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PIP_CONTROL) return
            val t = current ?: return
            val ms = t.mediaSession ?: return
            when (intent.getStringExtra(EXTRA_PIP_CMD)) {
                PIP_PLAY -> ms.play()
                PIP_PAUSE -> ms.pause()
                PIP_BACK, PIP_FORWARD -> {
                    val elapsed = (android.os.SystemClock.elapsedRealtime() - t.mediaPositionAt) / 1000.0
                    val now = PipPolicy.estimatedPosition(t.mediaPosition, t.mediaRate, t.mediaPlaying, elapsed, t.mediaDuration)
                    val delta = if (intent.getStringExtra(EXTRA_PIP_CMD) == PIP_BACK) -PipPolicy.SEEK_SECONDS else PipPolicy.SEEK_SECONDS
                    ms.seekTo(PipPolicy.seekTarget(now, delta, t.mediaDuration), false)
                }
            }
        }
    }
    private var lastBadge = -1
    /** Badge colour role currently painted on shieldBadge (roadmap 10), so it is only rebuilt on change. */
    private var shieldBadgeKey: Pair<ShieldBadge.Tint, Boolean>? = null
    /** Tracks UI_MODE_NIGHT_* so we recolour chrome when light/dark flips without recreate. */
    private var lastUiNightMask = Configuration.UI_MODE_NIGHT_UNDEFINED
    private var imeVisible = false

    private val uboActions = HashMap<Long, WebExtension.Action>()
    private var uboDefaultAction: WebExtension.Action? = null
    private val extListener: (WebExtension) -> Unit = { onUblockReady(it) }
    private val readerListener: (GeckoSession) -> Unit = { s -> if (current?.session === s) runOnUiThread { updateReaderButton() } }
    private val readerHost = object : ReaderMode.Host {
        override fun closeReader(session: GeckoSession, originalUrl: String?) {
            val t = allTabs.firstOrNull { it.session === session } ?: return
            when {
                t.canGoBack -> session.goBack()
                originalUrl != null -> load(t, originalUrl)
                else -> showHome(t)
            }
        }
    }

    private var pendingFile: ((Array<Uri>?) -> Unit)? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        val uris: Array<Uri>? = if (res.resultCode != RESULT_OK || data == null) null else {
            val clip = data.clipData
            if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri } else data.data?.let { arrayOf(it) }
        }
        pendingFile?.invoke(uris); pendingFile = null
    }
    private val libraryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringExtra(EXTRA_URL)?.let { url -> current?.let { load(it, url) } }
    }
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringExtra(EXTRA_URL)?.let { newTab(it) }
    }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // ======================================================================= lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        realm = Prefs.realm
        accent = Prefs.accent
        theme.applyStyle(accent.overlay, true)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        db = BrowserDb.get(this)
        runtime = Engine.runtime(this)
        PasswordVault.init(this)
        Engine.attachPasswordVault({ this }, { current?.session })
        Engine.syncZoomMap(db.allZoomPrefs())
        syncForceDark()
        DownloadCenter.init(this)
        ReaderMode.init(this)
        ReaderMode.host = readerHost
        ReaderMode.addListener(readerListener)
        if (Prefs.pendingWipe) wipeData() // "clear data on exit" after the process was killed

        geckoView = GeckoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(getColor(R.color.bg))
            coverUntilFirstPaint(getColor(R.color.bg))
        }
        b.webContainer.addView(geckoView)
        prompts = Prompts(this, ::pickFiles) { snack("Pop-up blocked") }

        setupInsets()
        setupAddressBar()
        setupBottomBar()
        setupHome()
        setupSwitcher()
        setupFindBar()
        setupTranslateChip()
        setupFullscreenPipButton()
        setupSwipe()
        setupBack()
        applyAccent()
        lastUiNightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        Engine.onExtensionReady(extListener)
        Engine.uboBridge.events += uboEvents
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_CONTROL), ContextCompat.RECEIVER_NOT_EXPORTED)
        observeDownloads()
        restoreTabs(realm)
        if (!handleIntent(intent) && tabs.isEmpty()) newTab()
        if (current == null) tabs.lastOrNull()?.let { selectTab(it) }
        com.jamhowman.beastbrowser.update.Updater.onLaunch(this) // GitHub release check, at most daily
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Prefs.pendingWipe = false
    }

    override fun onResume() {
        super.onResume()
        Engine.applySettings()
        syncForceDark() // Settings may have switched forced dark or edited "Never force dark on"
        if (Prefs.accent != accent) {
            accent = Prefs.accent
            theme.applyStyle(accent.overlay, true)
            applyAccent()
        }
        // Settings → theme (or system flip while we were paused): config may have changed already.
        maybeReapplyUiModeChrome(resources.configuration)
        // Settings → Import backup may have added speed-dial tiles.
        SpeedDialStore.load().let { if (it != tileAdapter.tiles) { tileAdapter.tiles = it; tileAdapter.notifyDataSetChanged() } }
        updateHomeStats()
        refreshUi()
        updatePipParams() // Settings may have turned picture-in-picture on or off
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep uiMode in configChanges so Gecko sessions are not torn down; recolour chrome instead.
        maybeReapplyUiModeChrome(newConfig)
    }

    private fun maybeReapplyUiModeChrome(config: Configuration) {
        val night = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (night == lastUiNightMask) return
        lastUiNightMask = night
        reapplyUiModeChrome()
    }

    /**
     * Activity is not recreated on light/dark flips (uiMode in configChanges) so inflated
     * text/icon colours stay on the old night resources while [getColor] resolves the new ones
     * (white text on a light address bar). Push fresh theme colours onto chrome views.
     */
    private fun reapplyUiModeChrome() {
        theme.applyStyle(accent.overlay, true)
        val bg = getColor(R.color.bg)
        val text = getColor(R.color.text_primary)
        val hint = getColor(R.color.text_hint)
        val secondary = getColor(R.color.text_secondary)
        b.root.setBackgroundColor(bg)
        b.topBar.setBackgroundColor(bg)
        b.bottomBar.setBackgroundColor(bg)
        b.webContainer.setBackgroundColor(bg)
        b.suggestList.setBackgroundColor(bg)
        b.home.root.setBackgroundColor(bg)
        geckoView.setBackgroundColor(bg)
        geckoView.coverUntilFirstPaint(bg)

        b.urlInput.setTextColor(text)
        b.urlInput.setHintTextColor(hint)
        b.findInput.setTextColor(text)
        b.findInput.setHintTextColor(hint)
        b.home.homeSearchInput.setTextColor(text)
        b.home.homeSearchInput.setHintTextColor(hint)
        b.home.wordmark.setTextColor(text)
        b.home.statsHeader.setTextColor(text)
        b.home.speedDialHeader.setTextColor(text)
        b.home.estimateNote.setTextColor(hint)
        // BeastStatLabel siblings (no ids) under each stat value column
        listOf(b.home.statBlocked, b.home.statData, b.home.statTime).forEach { value ->
            val row = value.parent as? android.view.ViewGroup ?: return@forEach
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                if (child is android.widget.TextView && child !== value) child.setTextColor(secondary)
            }
        }
        b.tabCount.setTextColor(text)
        b.switcher.switcherClose.imageTintList = ColorStateList.valueOf(text)
        b.switcher.closeAll.setTextColor(secondary)

        val barTint = ColorStateList.valueOf(text)
        listOf(b.btnBack, b.btnForward, b.btnHome).forEach { it.imageTintList = barTint }
        listOf(b.findPrev, b.findNext, b.findClose, b.reloadButton).forEach {
            it.imageTintList = ColorStateList.valueOf(secondary)
        }

        b.swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.surface2))

        // Soft shapes that baked night colours at inflate time.
        recolorCard(b.home.statsCard, getColor(R.color.surface), getColor(R.color.stroke))
        (getDrawable(R.drawable.bg_find)!!.mutate() as GradientDrawable).let {
            it.setColor(getColor(R.color.surface2))
            it.setStroke(dp(this, 1), getColor(R.color.stroke))
            (b.findInput.parent as? android.view.View)?.background = it
        }
        (getDrawable(R.drawable.bg_chip)!!.mutate() as GradientDrawable).let {
            it.setColor(getColor(R.color.surface3))
            b.home.statsStatus.background = it
        }

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, b.root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        window.navigationBarColor = bg

        applyAccent() // also styleAddressBar / refreshUi / home accent tints
    }

    private fun recolorCard(view: android.view.View, fill: Int, stroke: Int) {
        val g = (getDrawable(R.drawable.bg_card)!!.mutate() as GradientDrawable)
        g.setColor(fill)
        g.setStroke(dp(this, 1), stroke)
        view.background = g
    }

    override fun onPause() {
        super.onPause()
        Stats.save()
        tally.flushAsync()
        saveTabs()
    }

    override fun onStop() {
        super.onStop()
        if (Prefs.clearOnExit) Prefs.pendingWipe = true
    }

    override fun onDestroy() {
        Engine.removeExtensionListener(extListener)
        ReaderMode.removeListener(readerListener)
        if (ReaderMode.host === readerHost) ReaderMode.host = null
        Engine.uboBridge.events -= uboEvents
        runCatching { unregisterReceiver(pipReceiver) }
        if (isFinishing) {
            DownloadCenter.endPrivateSession()
            if (Prefs.clearOnExit) wipeData()
            geckoView.releaseSession()
            allTabs.forEach { runCatching { it.session.close() } }
        } else {
            geckoView.releaseSession()
        }
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        if (intent.action == ReaderMode.ACTION_OPEN_SAVED) {
            val id = intent.getLongExtra(ReaderMode.EXTRA_ARTICLE_ID, -1L)
            if (id <= 0) return false
            val t = current?.takeIf { !it.isPrivate && (it.showingHome || ReaderMode.isReaderUrl(it.url)) } ?: newTab()
            openSavedArticle(t, id, 0)
            return true
        }
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(SearchManager.QUERY)?.let { UrlUtils.fromInput(it, Prefs.searchEngine) }
            else -> null
        } ?: return false
        newTab(url)
        return true
    }

    // ======================================================================= tabs

    /** Realm sessions use the realm's Gecko cookie jar ([Realm.contextId]); Ghost is always private. */
    private fun createSession(private: Boolean, realm: Realm) = GeckoSession(
        GeckoSessionSettings.Builder()
            .usePrivateMode(private || realm.alwaysPrivate)
            .apply { realm.contextId?.let { contextId(it) } }
            .useTrackingProtection(true)
            .suspendMediaWhenInactive(false)
            .build()
    )

    private fun wire(tab: Tab) {
        TabCallbacks(tab, this).attach(tab.session)
        tab.session.promptDelegate = prompts
        attachUbo(tab)
        HelperSessions.register(tab.session, tab.isPrivate)
        wireTranslations(tab)
    }

    fun newTab(
        url: String? = null, private: Boolean = false,
        parent: Tab? = null, select: Boolean = true, lazy: Boolean = false, open: Boolean = true,
        realm: Realm = parent?.realm ?: this.realm,
    ): Tab {
        val isPrivate = private || realm.alwaysPrivate
        val tab = Tab(nextId++, isPrivate, createSession(isPrivate, realm), realm)
        tab.parentId = parent?.id
        wire(tab)
        if (open) tab.session.open(runtime)
        // Tab DNA: children go after the parent's existing branch so lineages stay together.
        val list = realmTabs.getValue(realm)
        if (parent != null && parent in list) list.add(lastOfBranch(list, parent) + 1, tab) else list.add(tab)
        if (url != null && url != UrlUtils.HOME) {
            tab.showingHome = false
            tab.url = url
            if (lazy || !open) tab.pendingUrl = if (open) url else null else load(tab, url)
        }
        if (select && realm == this.realm) {
            if (open) selectTab(tab) else selectWhenOpen(tab, 0)
        }
        updateTabCount()
        if (isPrivate && realm == Realm.GHOST && !Prefs.ghostHintShown) {
            Prefs.ghostHintShown = true
            snack("Ghost realm: private, own cookie jar, nothing kept after the last Ghost tab closes")
        } else if (isPrivate && realm != Realm.GHOST && !Prefs.privateWarningShown) {
            Prefs.privateWarningShown = true
            snack("Private tab: no history, cookies or cache are kept after you close it")
        }
        return tab
    }

    /** Index of the last tab in [tab]'s branch (the tab itself or its trailing descendants) in [list]. */
    private fun lastOfBranch(list: List<Tab>, tab: Tab): Int {
        var i = list.indexOf(tab)
        if (i < 0) return list.lastIndex
        val branch = descendants(list, tab).mapTo(HashSet()) { it.id }
        while (i + 1 < list.size && list[i + 1].id in branch) i++
        return i
    }

    /** All tabs descending from [tab] (children, grandchildren, ...) in list order, excluding [tab]. */
    private fun descendants(list: List<Tab>, tab: Tab): List<Tab> {
        val ids = hashSetOf(tab.id)
        var grew = true
        while (grew) {
            grew = false
            for (t in list) if (t.id !in ids && t.parentId != null && t.parentId in ids) { ids += t.id; grew = true }
        }
        return list.filter { it.id != tab.id && it.id in ids }
    }

    /** Tab DNA: close a tab together with its whole branch. */
    private fun closeBranch(tab: Tab) {
        val branch = descendants(tabs, tab) + tab
        branch.forEach { closeTab(it, force = true) }
        snack("Closed branch · ${branch.size} tabs")
    }

    /** Sessions returned to Gecko (window.open / tabs.create) are opened by Gecko; attach once open. */
    private fun selectWhenOpen(tab: Tab, attempt: Int) {
        b.root.postDelayed({
            if (tab !in tabs) return@postDelayed
            if (tab.session.isOpen) selectTab(tab) else if (attempt < 40) selectWhenOpen(tab, attempt + 1)
        }, 50)
    }

    fun load(tab: Tab, url: String) {
        if (url == UrlUtils.HOME) { showHome(tab); return }
        tab.showingHome = false
        tab.pendingUrl = null
        tab.url = url
        applySitePrefs(tab, url, reloadIfDesktopChanged = false)
        tab.session.setActive(tab === current)
        tab.session.loadUri(url)
        if (tab === current) refreshUi()
    }

    private fun selectTab(tab: Tab) {
        val old = current
        if (old != null && old !== tab) {
            old.session.setActive(false)
            runCatching { runtime.webExtensionController.setTabActive(old.session, false) }
            if (MediaRadar.isShowing(b.radar)) MediaRadar.hide(b.radar)
        }
        current = tab
        if (geckoView.session !== tab.session) {
            if (geckoView.session != null) geckoView.releaseSession()
            geckoView.setSession(tab.session)
        }
        tab.session.setActive(!tab.showingHome)
        runCatching { runtime.webExtensionController.setTabActive(tab.session, true) }
        tab.pendingUrl?.let { load(tab, it) }
        if (tab.isPrivate) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lastBadge = -1
        refreshUi()
        updateHomeStats()
        updatePipParams()
    }

    private fun closeTab(tab: Tab, force: Boolean = false) {
        val list = realmTabs.getValue(tab.realm)
        val idx = list.indexOf(tab)
        if (idx < 0) return
        val lastPrivate = tab.isPrivate && allTabs.none { it !== tab && it.isPrivate }
        if (lastPrivate && !force && DownloadCenter.activePrivateCount() > 0) {
            val n = DownloadCenter.activePrivateCount()
            MaterialAlertDialogBuilder(this)
                .setTitle("Cancel private downloads?")
                .setMessage("Closing your last private tab ends private browsing. $n private ${if (n == 1) "download is" else "downloads are"} still running and will be cancelled.")
                .setPositiveButton("Close & cancel") { _, _ -> closeTab(tab, force = true) }
                .setNegativeButton("Keep tab", null).show()
            return
        }
        list.removeAt(idx)
        uboActions.remove(tab.id)
        // Tab DNA: orphans are adopted by the grandparent
        list.forEach { if (it.parentId == tab.id) it.parentId = tab.parentId }
        if (realmCurrent[tab.realm] === tab) realmCurrent.remove(tab.realm)
        if (tab === current) {
            if (geckoView.session === tab.session) geckoView.releaseSession()
            current = null
            val sameKind = tabs.filter { it.isPrivate == tab.isPrivate }
            val next = tabs.firstOrNull { it.id == tab.parentId } ?: sameKind.lastOrNull() ?: tabs.lastOrNull()
            if (next != null) selectTab(next)
        }
        MediaSniffer.clear(tab.session)
        ReaderMode.forget(tab.session)
        runCatching { tab.session.close() } // closing the last private session lets Gecko purge private data
        // Ghost is burned when its last tab closes: also wipe its cookie jar explicitly.
        if (tab.realm == Realm.GHOST && list.isEmpty()) burnGhostJar()
        if (lastPrivate) {
            val beforeVault = DownloadCenter.privateVaultCount()
            val cancelled = DownloadCenter.endPrivateSession()  // unfinished cancelled; DONE → private vault
            val vaulted = DownloadCenter.privateVaultCount() - beforeVault
            when {
                cancelled > 0 && vaulted > 0 -> snack("Private browsing ended · $cancelled cancelled · $vaulted saved privately")
                cancelled > 0 -> snack("Private browsing ended · $cancelled private ${if (cancelled == 1) "download" else "downloads"} cancelled")
                vaulted > 0 -> snack("Private browsing ended · $vaulted ${if (vaulted == 1) "file" else "files"} saved to Private downloads")
                else -> {}
            }
        }
        if (tabs.isEmpty()) newTab()
        else if (current == null) selectTab(tabs.last())
        updateTabCount()
        if (b.switcher.root.isVisible) refreshSwitcher()
    }

    private fun burnGhostJar() {
        Realm.GHOST.contextId?.let { id -> runCatching { runtime.storageController.clearDataForSessionContext(id) } }
    }

    private fun showHome(tab: Tab) {
        tab.showingHome = true
        tab.session.setActive(false)
        if (tab === current) { refreshUi(); updateHomeStats() }
    }

    private fun goBack(): Boolean {
        val t = current ?: return false
        if (t.showingHome) {
            val parent = tabs.firstOrNull { it.id == t.parentId }
            if (parent != null) { closeTab(t); return true }
            return false
        }
        if (t.canGoBack) { t.session.goBack(); return true }
        if (t.parentId != null && tabs.any { it.id == t.parentId }) { closeTab(t); return true }
        showHome(t)
        return true
    }

    private fun goForward() {
        val t = current ?: return
        if (t.showingHome && t.hasLoaded) {
            t.showingHome = false
            t.session.setActive(true)
            refreshUi()
        } else if (t.canGoForward) t.session.goForward()
    }

    /**
     * Saves the normal tabs of every persisting realm that was restored this run (realms never opened keep
     * their saved session untouched). Keys per realm: see [Prefs.saveTabs].
     */
    private fun saveTabs() {
        val keep = Prefs.restoreTabs && !Prefs.clearOnExit
        Realm.entries.filter { it.persistsTabs }.forEach { r ->
            if (!keep) { Prefs.saveTabs(r, "", "", "", 0); return@forEach }
            if (r !in restoredRealms) return@forEach
            val normal = realmTabs.getValue(r).filter { !it.isPrivate }
            val selected = if (r == realm) current else realmCurrent[r]
            Prefs.saveTabs(
                r,
                tabs = normal.joinToString("\n") { if (it.showingHome) UrlUtils.HOME else ReaderMode.originalUrl(it.url) ?: it.url },
                groups = normal.joinToString("\n") { it.groupId.orEmpty() },
                parents = normal.joinToString("\n") { t ->
                    normal.indexOfFirst { it.id == t.parentId }.takeIf { it >= 0 }?.toString().orEmpty()
                },
                index = normal.indexOf(selected).coerceAtLeast(0),
            )
        }
    }

    /** Restores [realm]'s saved session once per run (Ghost never persists). */
    private fun restoreTabs(realm: Realm) {
        if (!restoredRealms.add(realm) || !realm.persistsTabs) return
        if (!Prefs.restoreTabs || Prefs.clearOnExit) return
        val urls = Prefs.savedTabs(realm).split('\n').filter { it.isNotBlank() }
        if (urls.isEmpty()) return
        val groups = Prefs.savedTabGroups(realm).split('\n')
        val parents = Prefs.savedTabParents(realm).split('\n')
        val created = urls.mapIndexed { i, url ->
            newTab(url, select = false, lazy = true, realm = realm).also {
                it.groupId = groups.getOrNull(i)?.takeIf { g -> g.isNotBlank() }
            }
        }
        created.forEachIndexed { i, t ->
            val parent = parents.getOrNull(i)?.toIntOrNull()?.let { created.getOrNull(it) }
            if (parent != null && parent !== t) t.parentId = parent.id
        }
        created.getOrNull(Prefs.savedTabIndex(realm))?.let {
            if (realm == this.realm) selectTab(it) else realmCurrent[realm] = it
        }
    }

    // ======================================================================= realms (2.3.8)

    private fun showRealms() {
        val counts = Realm.entries.associateWith { r ->
            if (r in restoredRealms || !r.persistsTabs) realmTabs.getValue(r).size
            else Prefs.savedTabs(r).split('\n').count { it.isNotBlank() }
        }
        RealmSheet.show(this, realm, counts, ::switchRealm, ::wipeRealm)
    }

    private fun switchRealm(r: Realm) {
        if (r == realm) return
        if (MediaRadar.isShowing(b.radar)) MediaRadar.hide(b.radar)
        if (b.findBar.isVisible) closeFind()
        hideSuggest()
        b.urlInput.clearFocus()
        b.home.homeSearchInput.clearFocus()
        hideKeyboard()
        current?.let {
            captureThumbnail(it)
            it.session.setActive(false)
            runCatching { runtime.webExtensionController.setTabActive(it.session, false) }
            realmCurrent[realm] = it
        }
        geckoView.releaseSession()
        current = null
        realm = r
        Prefs.realm = r
        restoreTabs(r)
        if (current == null) {
            val next = realmCurrent[r]?.takeIf { it in tabs } ?: tabs.lastOrNull()
            if (next != null) selectTab(next) else newTab()
        }
        accent = Prefs.accent
        theme.applyStyle(accent.overlay, true)
        applyAccent()
        updateHomeStats()
        if (b.switcher.root.isVisible) {
            syncSwitcherMode()
            buildTabGroupPills()
            refreshSwitcher()
        }
        saveTabs()
        snack(when (r) {
            Realm.WORK -> "Work realm · own cookies & tabs"
            Realm.PLAY -> "Play realm · your original cookies & tabs"
            Realm.GHOST -> "Ghost realm · private, own cookie jar"
        })
    }

    /** Ghost: close every Ghost tab and wipe the jar. Work: wipe cookies & site data, reload its pages. */
    private fun wipeRealm(r: Realm) {
        val contextId = r.contextId ?: return
        if (r == Realm.GHOST) realmTabs.getValue(r).toList().forEach { closeTab(it, force = true) }
        runtime.storageController.clearDataForSessionContext(contextId)
        if (r == Realm.WORK) realmTabs.getValue(r).filter { !it.showingHome }.forEach { it.session.reload() }
        snack(if (r == Realm.GHOST) "Ghost burned" else "Work cookies & site data wiped")
    }

    // ======================================================================= UI wiring

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            imeVisible = ime.bottom > 0
            if (fullscreenTab == null && !inPip) {
                b.content.setPadding(sys.left, sys.top, sys.right, max(sys.bottom, ime.bottom))
            } else b.content.setPadding(0, 0, 0, 0)
            b.switcher.root.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            // Fullscreen PiP button: 16dp from the corner, clear of a display cutout / system bars.
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            (b.fullscreenPipButton.layoutParams as FrameLayout.LayoutParams).let {
                it.topMargin = dp(this, 16) + cut.top
                it.marginEnd = dp(this, 16) + cut.right
                b.fullscreenPipButton.layoutParams = it
            }
            updateBarsVisibility()
            insets
        }
    }

    private fun updateBarsVisibility() {
        val fs = fullscreenTab != null || inPip
        b.topBar.isVisible = !fs
        b.accentLineTop.isVisible = !fs
        if (fs) b.translateBar.root.isVisible = false else updateTranslateChip()
        b.accentLineBottom.isVisible = !fs && !imeVisible
        b.bottomBar.isVisible = !fs && !imeVisible
    }

    private fun setupAddressBar() {
        suggestAdapter = SuggestAdapter { item -> onSuggestPicked(item) }
        b.suggestList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        b.suggestList.adapter = suggestAdapter

        b.urlInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                submit(v.text.toString()); true
            } else false
        }
        b.urlInput.setOnFocusChangeListener { _, focused ->
            val t = current
            if (focused) {
                if (t != null && !t.showingHome) b.urlInput.setText(ReaderMode.originalUrl(t.url) ?: t.url)
                b.urlInput.post { b.urlInput.selectAll() }
                scheduleSuggest(b.urlInput.text?.toString().orEmpty())
            } else {
                hideSuggest()
                refreshUi()
            }
            styleAddressBar(focused)
        }
        b.urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (b.urlInput.hasFocus()) scheduleSuggest(s?.toString().orEmpty())
            }
        })
        b.reloadButton.setOnClickListener {
            val t = current ?: return@setOnClickListener
            if (t.loading) t.session.stop() else t.session.reload()
        }
        b.shieldButton.setOnClickListener { showShields() }
        b.readerButton.setOnClickListener { toggleReader() }
        b.mediaButton.setOnClickListener { current?.session?.let { onMediaBadgeTapped(it) } }
        b.mediaButton.setOnLongClickListener { current?.session?.let { openMediaRadar(it) }; true }
        MediaSniffer.addListener { session ->
            if (current?.session === session) runOnUiThread { updateMediaBadge() }
        }
    }

    private fun submit(text: String) {
        val t = current ?: return
        val url = UrlUtils.fromInput(text, Prefs.searchEngine)
        hideSuggest()
        hideKeyboard()
        b.urlInput.clearFocus()
        b.home.homeSearchInput.text = null
        b.home.homeSearchInput.clearFocus()
        load(t, url)
    }

    private fun onSuggestPicked(item: SuggestItem) {
        when (item.kind) {
            SuggestKind.HISTORY, SuggestKind.BOOKMARK -> submit(item.fill)
            SuggestKind.SEARCH -> submit(item.fill)
        }
    }

    private fun scheduleSuggest(query: String) {
        suggestJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            hideSuggest()
            return
        }
        val includeLocal = current?.isPrivate != true
        // Live engine suggestions: never in private tabs, and only if Settings → Search suggestions is on.
        val includeRemote = includeLocal && Prefs.searchSuggestions
        val engine = Prefs.searchEngine
        suggestJob = lifecycleScope.launch {
            delay(180)
            val items = SearchSuggest.load(q, engine, db, includeLocal, includeRemote)
            if (!b.urlInput.hasFocus() && !b.home.homeSearchInput.hasFocus()) return@launch
            if (items.isEmpty()) {
                hideSuggest()
            } else {
                suggestAdapter.submit(items)
                b.suggestList.isVisible = true
            }
        }
    }

    private fun hideSuggest() {
        suggestJob?.cancel()
        b.suggestList.isVisible = false
        suggestAdapter.submit(emptyList())
    }

    private fun setupBottomBar() {
        b.btnBack.setOnClickListener { goBack() }
        b.btnForward.setOnClickListener { goForward() }
        b.btnHome.setOnClickListener { current?.let { showHome(it) } }
        b.btnHome.setOnLongClickListener { newTab(); true }
        b.btnTabs.setOnClickListener { showSwitcher() }
        b.btnTabs.setOnLongClickListener { newTab(); toast("New tab"); true }
        b.btnMenu.setOnClickListener { showMenu() }
        b.btnMenu.setOnLongClickListener { showRealms(); true }
    }

    private fun setupHome() {
        b.home.logo.setOnLongClickListener { showRealms(); true }
        b.home.wordmark.setOnLongClickListener { showRealms(); true }
        b.home.homeSearchInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                submit(v.text.toString()); true
            } else false
        }
        b.home.homeSearchInput.setOnFocusChangeListener { _, focused ->
            if (focused) scheduleSuggest(b.home.homeSearchInput.text?.toString().orEmpty())
            else if (!b.urlInput.hasFocus()) hideSuggest()
        }
        b.home.homeSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (b.home.homeSearchInput.hasFocus()) scheduleSuggest(s?.toString().orEmpty())
            }
        })
        tileAdapter = SpeedDialAdapter(SpeedDialStore.load(), accent.color,
            onClick = { tile -> current?.let { load(it, tile.url) } },
            onLongClick = { tile ->
                MaterialAlertDialogBuilder(this).setTitle(tile.title).setMessage(tile.url)
                    .setPositiveButton("Remove") { _, _ ->
                        tileAdapter.tiles.remove(tile); SpeedDialStore.save(tileAdapter.tiles); tileAdapter.notifyDataSetChanged()
                        SpeedDialWidget.refreshAll(this)
                    }
                    .setNeutralButton("Open in new tab") { _, _ -> newTab(tile.url) }
                    .setNegativeButton("Cancel", null).show()
            },
            onAdd = { showAddTile(null, null) })
        b.home.speedDial.layoutManager = GridLayoutManager(this, 4)
        b.home.speedDial.adapter = tileAdapter
    }

    private fun showAddTile(title: String?, url: String?) {
        val d = DialogAddTileBinding.inflate(layoutInflater)
        d.tileName.setText(title); d.tileUrl.setText(url)
        MaterialAlertDialogBuilder(this).setTitle("Add to Speed Dial").setView(d.root)
            .setPositiveButton("Add") { _, _ ->
                val u = d.tileUrl.text?.toString().orEmpty().trim()
                if (u.isEmpty()) return@setPositiveButton
                val full = UrlUtils.fromInput(u, Prefs.searchEngine)
                val name = d.tileName.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: Domains.display(UrlUtils.host(full)).substringBefore('.').replaceFirstChar { it.uppercase() }
                tileAdapter.tiles.add(Tile(name, full)); SpeedDialStore.save(tileAdapter.tiles); tileAdapter.notifyDataSetChanged()
                SpeedDialWidget.refreshAll(this)
                snack("Added $name to Speed Dial")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun setupSwipe() {
        b.swipe.setOnRefreshListener { current?.session?.reload() ?: run { b.swipe.isRefreshing = false } }
        b.swipe.setOnChildScrollUpCallback { _, _ -> (current?.scrollY ?: 0) > 0 }
        b.swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.surface2))
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenTab != null -> fullscreenTab?.session?.exitFullScreen()
                    MediaRadar.isShowing(b.radar) -> MediaRadar.hide(b.radar)
                    b.switcher.root.isVisible -> hideSwitcher()
                    b.findBar.isVisible -> closeFind()
                    b.suggestList.isVisible -> {
                        hideSuggest()
                        b.urlInput.clearFocus()
                        b.home.homeSearchInput.clearFocus()
                        hideKeyboard()
                    }
                    b.urlInput.hasFocus() -> { b.urlInput.clearFocus(); hideKeyboard() }
                    !goBack() -> moveTaskToBack(true)
                }
            }
        })
    }

    // ======================================================================= rendering

    private fun refreshUi() {
        val t = current ?: return
        b.home.root.isVisible = t.showingHome
        b.swipe.isInvisible = t.showingHome
        if (!b.urlInput.hasFocus()) b.urlInput.setText(if (t.showingHome) "" else displayUrl(t))
        b.urlInput.hint = getString(when {
            realm == Realm.GHOST -> R.string.search_hint_ghost
            t.isPrivate -> R.string.search_hint_private
            else -> R.string.search_hint
        })
        b.home.homeSearchInput.hint = "Search ${Prefs.searchEngine.label}"

        val (icon, tint) = when {
            t.showingHome && t.isPrivate -> R.drawable.ic_incognito to accent.color
            t.showingHome -> R.drawable.ic_search to getColor(R.color.text_hint)
            t.url.startsWith("https://") || t.url.startsWith("moz-extension://") -> R.drawable.ic_lock to getColor(R.color.text_secondary)
            t.url.startsWith("http://") -> R.drawable.ic_warning to getColor(R.color.warn)
            else -> R.drawable.ic_search to getColor(R.color.text_hint)
        }
        b.securityIcon.setImageResource(if (t.isPrivate && !t.showingHome) R.drawable.ic_incognito else icon)
        b.securityIcon.imageTintList = ColorStateList.valueOf(if (t.isPrivate) accent.color else tint)
        styleAddressBar(b.urlInput.hasFocus())

        b.reloadButton.isVisible = !t.showingHome
        b.reloadButton.setImageResource(if (t.loading) R.drawable.ic_close else R.drawable.ic_refresh)
        b.progress.isVisible = t.loading && !t.showingHome && t.progress < 100
        if (!t.loading) b.swipe.isRefreshing = false
        b.btnForward.alpha = if (t.canGoForward || (t.showingHome && t.hasLoaded)) 1f else 0.35f
        b.btnBack.alpha = if (t.canGoBack || !t.showingHome || t.parentId != null) 1f else 0.35f
        updateShield()
        updateMediaBadge()
        updateReaderButton()
        updateTranslateChip()
        updateTabCount()
    }

    private fun displayUrl(t: Tab): String {
        val host = UrlUtils.host(t.url)
        return when {
            ReaderMode.isReaderUrl(t.url) -> "Reader · " + (ReaderMode.originalUrl(t.url)?.let { UrlUtils.host(it) }?.let { Domains.display(it) } ?: "article")
            t.url.startsWith("moz-extension://") -> t.title.ifBlank { "Extension" }
            host.isNullOrEmpty() -> t.url
            else -> Domains.display(host)
        }
    }

    private fun styleAddressBar(focused: Boolean) {
        val t = current
        val bg = (getDrawable(R.drawable.bg_address)!!.mutate() as GradientDrawable)
        val private = t?.isPrivate == true
        bg.setColor(getColor(if (private) R.color.private_bg else R.color.surface2))
        bg.setStroke(dp(this, if (focused) 2 else 1), when {
            focused -> accent.color
            private -> getColor(R.color.private_stroke)
            else -> getColor(R.color.stroke)
        })
        b.addressBar.background = bg
    }

    private fun updateShield() {
        val t = current ?: return
        val active = Prefs.blockAds && !t.siteShieldsDown
        val n = if (t.showingHome) 0 else t.blockedOnPage
        // Roadmap 10: state (ShieldLevel) and look (ShieldBadge) are separate so the visuals can be swapped.
        val style = ShieldBadge.style(ShieldLevel.of(n), active)
        b.shieldIcon.setImageResource(style.icon)
        b.shieldIcon.imageTintList = ColorStateList.valueOf(shieldColor(style.iconTint))
        b.shieldButton.contentDescription = getString(style.description, fmt(n))
        val badgeKey = style.badgeTint to style.badgeGradient
        if (badgeKey != shieldBadgeKey) {
            shieldBadgeKey = badgeKey
            val c = shieldColor(style.badgeTint)
            (getDrawable(R.drawable.bg_badge)!!.mutate() as GradientDrawable).let {
                if (style.badgeGradient) {
                    it.orientation = GradientDrawable.Orientation.TL_BR
                    it.colors = intArrayOf(c, ShieldBadge.gradientEnd(accent))
                } else it.setColor(c)
                b.shieldBadge.background = it
            }
            b.shieldBadge.setTextColor(if (style.badgeTint == ShieldBadge.Tint.ACCENT) accent.onColor else inkOn(c))
        }
        b.shieldBadge.isVisible = style.showCount
        b.shieldBadge.text = if (n > 99) "99+" else n.toString()
        if (n > lastBadge && lastBadge >= 0 && n > 0) bump(b.shieldBadge)
        lastBadge = n
    }

    private fun shieldColor(tint: ShieldBadge.Tint): Int = when (tint) {
        ShieldBadge.Tint.ACCENT -> accent.color
        ShieldBadge.Tint.MUTED -> getColor(R.color.text_hint)
        ShieldBadge.Tint.WARN -> getColor(R.color.warn)
    }

    /** Near-black or white, whichever reads better on [bg]. */
    private fun inkOn(bg: Int): Int =
        if (Contrast.ratio(bg, INK_DARK) >= Contrast.ratio(bg, Color.WHITE)) INK_DARK else Color.WHITE

    private fun bump(v: View) {
        v.animate().cancel()
        v.scaleX = 1.35f; v.scaleY = 1.35f
        v.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
    }

    private fun updateTabCount() {
        val n = tabs.count { it.isPrivate == (current?.isPrivate == true) }
        b.tabCount.text = if (n > 99) ":D" else n.toString()
    }

    private fun updateHomeStats() {
        val total = Stats.total.get()
        b.home.statBlocked.text = fmt(total)
        b.home.statData.text = Stats.dataSaved(total)
        b.home.statTime.text = Stats.timeSaved(total)
        val ubo = Engine.ublock
        b.home.statsStatus.text = when {
            !Prefs.blockAds -> "OFF"
            ubo != null && Prefs.ublockEnabled -> "ETP + uBO"
            else -> "ETP STRICT"
        }
    }

    private fun applyAccent() {
        val c = accent.color
        accentLine(b.accentLineTop, c, 0x99)
        accentLine(b.accentLineBottom, c, 0x55)
        b.progress.setIndicatorColor(c)
        b.swipe.setColorSchemeColors(c)
        b.btnMenu.imageTintList = ColorStateList.valueOf(c)
        b.menuDownloadRing.setIndicatorColor(c)
        (getDrawable(R.drawable.bg_badge)!!.mutate() as GradientDrawable).let { it.setColor(c); b.shieldBadge.background = it }
        b.shieldBadge.setTextColor(accent.onColor)
        shieldBadgeKey = ShieldBadge.Tint.ACCENT to false
        (getDrawable(R.drawable.bg_tab_count)!!.mutate() as GradientDrawable).let {
            it.setStroke(dp(this, 2), c); b.tabCount.background = it
        }
        // home
        b.home.logo.imageTintList = ColorStateList.valueOf(c)
        glow(b.home.logoGlow, c)
        b.home.wordmarkSub.setTextColor(c)
        b.home.wordmarkSub.text = "BROWSER · " + realm.label.uppercase(Locale.ROOT)
        b.home.homeSearchIcon.imageTintList = ColorStateList.valueOf(c)
        (getDrawable(R.drawable.bg_home_search)!!.mutate() as GradientDrawable).let {
            it.setColor(getColor(R.color.surface2))
            it.setStroke(dp(this, 1), accent.withAlpha(0x66))
            b.home.homeSearch.background = it
        }
        listOf(b.home.statBlocked, b.home.statData, b.home.statTime, b.home.statsStatus).forEach { it.setTextColor(c) }
        b.home.statsShield.imageTintList = ColorStateList.valueOf(c)
        b.home.speedDialMarker.setBackgroundColor(c)
        tileAdapter.accent = c
        tileAdapter.notifyDataSetChanged()
        // switcher
        b.switcher.newTabFab.backgroundTintList = ColorStateList.valueOf(c)
        b.switcher.newTabFab.setTextColor(accent.onColor)
        b.switcher.newTabFab.iconTint = ColorStateList.valueOf(accent.onColor)
        tabAdapter.accent = c
        b.switcher.realmSeal.setImageResource(realm.sealIcon)
        b.switcher.realmSeal.imageTintList = ColorStateList.valueOf(c)
        b.switcher.realmSeal.contentDescription = "Realm: ${realm.label}"
        b.btnMenu.tooltipText = "Menu · long-press for realms (${realm.label})"
        styleToggle()
        b.findCount.setTextColor(c)
        refreshUi()
    }

    private fun styleToggle() {
        val c = accent.color
        val bgStates = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent.withAlpha(0x33), 0x00000000))
        val fg = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(c, getColor(R.color.text_secondary)))
        listOf(b.switcher.modeNormal, b.switcher.modePrivate).forEach {
            it.apply {
                backgroundTintList = bgStates; setTextColor(fg); iconTint = fg
                strokeColor = ColorStateList.valueOf(accent.withAlpha(0x88))
            }
        }
    }

    // ======================================================================= tab switcher

    private fun setupSwitcher() {
        tabAdapter = TabCardAdapter(
            onSelect = { tab -> selectTab(tab); hideSwitcher() },
            onClose = { tab -> closeTab(tab) },
            onGroup = { tab -> pickTabGroup(tab) })
        val sw = b.switcher
        sw.tabGrid.layoutManager = GridLayoutManager(this, 2)
        sw.tabGrid.adapter = tabAdapter
        sw.tabGrid.addItemDecoration(TabDnaDecoration(tabAdapter))
        sw.realmSeal.setOnClickListener { showRealms() }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                tabAdapter.items.getOrNull(vh.bindingAdapterPosition)?.let { closeTab(it) }
            }
        }).attachToRecyclerView(sw.tabGrid)
        sw.switcherMode.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            switcherPrivate = id == R.id.modePrivate
            refreshSwitcher()
        }
        sw.switcherClose.setOnClickListener { hideSwitcher() }
        sw.newTabFab.setOnClickListener { newTab(private = switcherPrivate); hideSwitcher() }
        sw.closeAll.setOnClickListener {
            tabs.filter { it.isPrivate == switcherPrivate }.forEach { closeTab(it) }
            if (switcherPrivate) hideSwitcher()
        }
        sw.tabSearch.tabSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                switcherQuery = s?.toString().orEmpty().trim()
                refreshSwitcher()
            }
        })
        buildTabGroupPills()
    }

    /** "All" + one pill per [TabGroup] in its accent; the active filter is filled. */
    private fun buildTabGroupPills() {
        val strip = b.switcher.tabGroupStrip
        strip.removeAllViews()
        fun addPill(id: String?, label: String, color: Int) {
            val pill = ItemTabGroupPillBinding.inflate(layoutInflater, strip, false)
            pill.tabGroupName.text = label
            pill.tabGroupName.setTextColor(color)
            pill.tabGroupPill.strokeColor = color
            pill.tabGroupPill.setCardBackgroundColor(
                if (switcherGroupFilter == id) ColorStateList.valueOf((color and 0x00FFFFFF) or 0x33000000)
                else ColorStateList.valueOf(getColor(R.color.surface2))
            )
            pill.root.setOnClickListener { switcherGroupFilter = id; buildTabGroupPills(); refreshSwitcher() }
            strip.addView(pill.root)
        }
        addPill(null, getString(R.string.folder_all), accent.color)
        TabGroup.entries.forEach { addPill(it.id, getString(it.labelRes), it.accent.color) }
    }

    /** Long-press a tab card → "Move to group" ("All" = no group). Groups are saved with the session. */
    private fun pickTabGroup(tab: Tab) {
        val choices: List<Pair<String?, String>> =
            listOf<Pair<String?, String>>(null to getString(R.string.folder_all)) +
                TabGroup.entries.map { it.id to getString(it.labelRes) }
        val checked = choices.indexOfFirst { it.first == tab.groupId }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this).setTitle("Move to group")
            .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), checked) { d, which ->
                tab.groupId = choices[which].first
                d.dismiss()
                saveTabs()
                buildTabGroupPills()
                refreshSwitcher()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                val branch = descendants(tabs, tab)
                if (branch.isNotEmpty()) setNeutralButton("Close branch (${branch.size + 1})") { _, _ -> closeBranch(tab) }
            }
            .show()
    }

    private fun showSwitcher() {
        hideKeyboard(); b.urlInput.clearFocus()
        current?.let { captureThumbnail(it) }
        syncSwitcherMode()
        if (b.switcher.tabSearch.tabSearchInput.text?.toString() != switcherQuery) {
            b.switcher.tabSearch.tabSearchInput.setText(switcherQuery)
        }
        buildTabGroupPills()
        refreshSwitcher()
        val root = b.switcher.root
        root.alpha = 0f; root.scaleX = 0.96f; root.scaleY = 0.96f
        root.isVisible = true
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
    }

    /** Ghost has a single (private) list: hide the Normal toggle and label the other with the realm. */
    private fun syncSwitcherMode() {
        val sw = b.switcher
        switcherPrivate = realm.alwaysPrivate || current?.isPrivate == true
        sw.modeNormal.isVisible = !realm.alwaysPrivate
        sw.modeNormal.text = realm.label
        sw.modePrivate.text = if (realm.alwaysPrivate) realm.label else getString(R.string.private_tabs)
        sw.switcherMode.check(if (switcherPrivate) R.id.modePrivate else R.id.modeNormal)
    }

    private fun hideSwitcher() {
        val root = b.switcher.root
        root.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(140).withEndAction {
            root.isVisible = false
        }.start()
        refreshUi()
    }

    private fun refreshSwitcher() {
        val q = switcherQuery.lowercase(Locale.ROOT)
        val items = tabs
            .filter { it.isPrivate == switcherPrivate }
            .filter { switcherGroupFilter == null || it.groupId == switcherGroupFilter }
            .filter { t ->
                q.isEmpty() ||
                    t.displayTitle.lowercase(Locale.ROOT).contains(q) ||
                    t.url.lowercase(Locale.ROOT).contains(q) ||
                    TabGroup.from(t.groupId)?.let { getString(it.labelRes).lowercase(Locale.ROOT).contains(q) } == true
            }
        tabAdapter.items = items
        tabAdapter.byId = tabs.associateBy { it.id }
        tabAdapter.currentId = current?.id ?: -1
        tabAdapter.accent = accent.color
        tabAdapter.notifyDataSetChanged()
        val empty = b.switcher.switcherEmpty
        empty.root.isVisible = items.isEmpty()
        if (items.isEmpty()) {
            empty.emptyArt.setImageResource(R.drawable.img_empty_tabs)
            empty.emptyArt.imageTintList = ColorStateList.valueOf(accent.color)
            empty.emptyTitle.setText(R.string.empty_tabs_title)
            empty.emptyBody.setText(R.string.empty_tabs_body)
            empty.emptyCta.isVisible = true
            empty.emptyCta.text = getString(if (switcherPrivate) R.string.new_private_tab else R.string.new_tab)
            empty.emptyCta.setOnClickListener { newTab(private = switcherPrivate); hideSwitcher() }
        }
        b.switcher.newTabFab.text = when {
            realm == Realm.GHOST -> "New Ghost tab"
            else -> getString(if (switcherPrivate) R.string.new_private_tab else R.string.new_tab)
        }
        b.switcher.newTabFab.setIconResource(if (switcherPrivate) R.drawable.ic_incognito else R.drawable.ic_add)
        b.switcher.newTabFab.iconTint = ColorStateList.valueOf(accent.onColor)
    }

    private fun captureThumbnail(tab: Tab) {
        if (tab.showingHome) {
            val v = b.home.root
            if (v.width > 0 && v.height > 0) {
                val s = 0.4f
                val bmp = Bitmap.createBitmap((v.width * s).toInt(), (v.height * s).toInt(), Bitmap.Config.ARGB_8888)
                Canvas(bmp).apply { scale(s, s); v.draw(this) }
                tab.thumbnail = bmp
            }
            return
        }
        if (geckoView.session !== tab.session) return
        try {
            geckoView.capturePixels().accept({ bmp ->
                if (bmp != null) {
                    tab.thumbnail = Bitmap.createScaledBitmap(bmp, max(1, bmp.width * 2 / 5), max(1, bmp.height * 2 / 5), true)
                    if (b.switcher.root.isVisible) tabAdapter.notifyDataSetChanged()
                }
            }, { })
        } catch (_: Throwable) {}
    }

    // ======================================================================= find in page

    private fun setupFindBar() {
        b.findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val t = current ?: return
                val q = s?.toString().orEmpty()
                if (q.isEmpty()) { t.session.finder.clear(); b.findCount.text = ""; return }
                find(t.session.finder.find(q, 0))
            }
        })
        b.findNext.setOnClickListener { current?.let { find(it.session.finder.find(null, 0)) } }
        b.findPrev.setOnClickListener { current?.let { find(it.session.finder.find(null, GeckoSession.FINDER_FIND_BACKWARDS)) } }
        b.findClose.setOnClickListener { closeFind() }
        b.findInput.setOnEditorActionListener { _, _, _ -> current?.let { find(it.session.finder.find(null, 0)) }; true }
    }

    private fun find(result: GeckoResult<GeckoSession.FinderResult>) {
        result.accept({ r -> b.findCount.text = if (r == null || r.total == 0) "0/0" else "${r.current}/${r.total}" }, { })
    }

    private fun openFind() {
        val t = current ?: return
        if (t.showingHome) { toast("Open a page first"); return }
        b.addressRow.isInvisible = true
        b.findBar.isVisible = true
        updateTranslateChip()
        b.findInput.requestFocus()
        showKeyboard(b.findInput)
    }

    private fun closeFind() {
        current?.session?.finder?.clear()
        b.findInput.text = null
        b.findBar.isVisible = false
        b.addressRow.isInvisible = false
        updateTranslateChip()
        hideKeyboard()
    }

    // ======================================================================= menu & shields

    private data class MenuItem(val icon: Int, val label: String, val active: Boolean = false, val action: () -> Unit)

    private fun showMenu() {
        val t = current ?: return
        val dialog = BottomSheetDialog(this)
        val m = SheetMenuBinding.inflate(layoutInflater)
        val onPage = !t.showingHome
        m.menuShield.imageTintList = ColorStateList.valueOf(accent.color)
        m.menuTitle.text = if (onPage) "${fmt(t.blockedOnPage)} blocked on this page" else getString(R.string.shields_up)
        m.menuSubtitle.text = "${fmt(Stats.total.get())} ads & trackers blocked in total"
        m.menuHeader.setOnClickListener { dialog.dismiss(); showShields() }
        val bookmarked = onPage && db.isBookmarked(t.url)
        val pipOffered = onPage && PipPolicy.canEnterManually(Prefs.pipEnabled, pipSupported, mediaState(t))
        val darkSite = if (ForcedDark.menuTileShown(Prefs.forceDarkActive, t.url, onPage)) db.siteKey(t.url) else ""
        val darkOn = darkSite.isNotEmpty() && !forceDarkOff(t, darkSite)
        val items = listOfNotNull(
            MenuItem(R.drawable.ic_add, "New tab") { newTab() },
            MenuItem(R.drawable.ic_incognito, if (realm == Realm.GHOST) "Ghost tab" else "Private tab") { newTab(private = true) },
            MenuItem(realm.sealIcon, "Realm · ${realm.label}", realm != Realm.PLAY) { showRealms() },
            MenuItem(R.drawable.ic_bookmark, "Bookmarks") { openLibrary(0) },
            MenuItem(R.drawable.ic_history, "History") { openLibrary(1) },
            DownloadCenter.activeCount.let { n ->
                MenuItem(R.drawable.ic_download, if (n > 0) "Downloads ($n)" else "Downloads", n > 0) { openDownloads() }
            },
            (onPage && MediaSniffer.hasMedia(t.session)).let { hasMedia ->
                MenuItem(R.drawable.ic_download, getString(R.string.media_radar), hasMedia) {
                    if (hasMedia) openMediaRadar(t.session)
                    else Toast.makeText(this, R.string.media_radar_empty, Toast.LENGTH_SHORT).show()
                }
            },
            if (pipOffered) MenuItem(R.drawable.ic_pip, getString(R.string.menu_pip)) {
                if (!enterPip()) toast(getString(R.string.pip_unavailable))
            } else null,
            if (darkSite.isNotEmpty()) MenuItem(R.drawable.ic_force_dark, getString(R.string.menu_dark_page), darkOn) {
                setForceDarkOff(t, darkSite, darkOn)
            } else null,
            MenuItem(R.drawable.ic_reader, "Reading list") { startActivity(Intent(this, ReadingListActivity::class.java)) },
            MenuItem(R.drawable.ic_dashboard, getString(R.string.beast_control)) { showBeastControl() },
            MenuItem(if (bookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
                if (bookmarked) "Bookmarked" else "Bookmark", bookmarked) { toggleBookmark(t) },
            MenuItem(R.drawable.ic_share, "Share") { share(t) },
            MenuItem(R.drawable.ic_find, "Find in page") { openFind() },
            MenuItem(R.drawable.ic_desktop, "Desktop site", t.desktopMode) { toggleDesktop(t) },
            MenuItem(R.drawable.ic_up, zoomLabel(t), t.zoomPercent != 100) { showZoom(t) },
            MenuItem(R.drawable.ic_star, "Add to Speed Dial") {
                if (onPage) showAddTile(t.title, t.url) else showAddTile(null, null)
            },
            MenuItem(R.drawable.ic_settings, "Settings") { settingsLauncher.launch(Intent(this, SettingsActivity::class.java)) },
            MenuItem(R.drawable.ic_power, "Exit") { exitBrowser() },
        )
        val cell = resources.displayMetrics.widthPixels / 4
        items.forEach { item ->
            val ib = ItemMenuBinding.inflate(LayoutInflater.from(this), m.menuGrid, false)
            ib.menuIcon.setImageResource(item.icon)
            ib.menuIcon.imageTintList = ColorStateList.valueOf(if (item.active) accent.onColor else getColor(R.color.text_primary))
            (getDrawable(R.drawable.bg_menu_icon)!!.mutate() as GradientDrawable).let {
                it.setColor(if (item.active) accent.color else getColor(R.color.surface2)); ib.menuIcon.background = it
            }
            ib.menuLabel.text = item.label
            ib.root.layoutParams = GridLayout.LayoutParams().apply {
                width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            ib.root.minimumWidth = cell - dp(this, 12)
            ib.root.setOnClickListener { dialog.dismiss(); item.action() }
            m.menuGrid.addView(ib.root)
        }
        if (darkSite.isNotEmpty() && !Prefs.forceDarkTipShown) {
            // Designer SPEC: first time only, a tip under the grid explaining the Dark page tile.
            Prefs.forceDarkTipShown = true
            m.root.addView(TextView(this).apply {
                text = HtmlCompat.fromHtml(getString(R.string.menu_dark_page_tip, TextUtils.htmlEncode(darkSite)), HtmlCompat.FROM_HTML_MODE_LEGACY)
                textSize = 12.5f
                setTextColor(getColor(R.color.text_primary))
                setPadding(dp(this@MainActivity, 14), dp(this@MainActivity, 12), dp(this@MainActivity, 14), dp(this@MainActivity, 12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(this@MainActivity, 14).toFloat()
                    setColor(ColorUtils.blendARGB(getColor(R.color.surface), accent.color, 0.18f))
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(this@MainActivity, 12)
            })
        }
        dialog.setContentView(m.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }


    // ======================================================================= page translation (2.3.4)

    private fun setupTranslateChip() {
        b.translateBar.translateLabel.setOnClickListener { translateCurrentPage() }
        b.translateBar.translateClose.setOnClickListener { dismissTranslateChip() }
    }

    /** Gecko's on-device translation offers for [tab]'s session (callbacks may arrive off the main thread). */
    private fun wireTranslations(tab: Tab) {
        tab.session.translationsSessionDelegate = object : TranslationsController.SessionTranslation.Delegate {
            override fun onOfferTranslate(session: GeckoSession) = runOnUiThread {
                if (tab.session !== session) return@runOnUiThread
                tab.translateOffered = true
                if (current === tab) updateTranslateChip()
            }

            override fun onExpectedTranslate(session: GeckoSession) = runOnUiThread {
                if (tab.session !== session) return@runOnUiThread
                tab.translateOffered = true
                if (current === tab) updateTranslateChip()
            }

            override fun onTranslationStateChange(
                session: GeckoSession,
                state: TranslationsController.SessionTranslation.TranslationState?,
            ) = runOnUiThread {
                if (tab.session !== session || state == null) return@runOnUiThread
                state.detectedLanguages?.let {
                    tab.docLangTag = it.docLangTag
                    tab.userLangTag = it.userLangTag
                }
                tab.translated = state.hasVisibleChange == true || state.requestedTranslationPair != null
                if (current === tab) updateTranslateChip()
            }
        }
    }

    /**
     * "Translate page · <lang>" / "Show original" chip under the address bar. Shown when Gecko offers
     * or expects a translation, or the page language differs from the user's, unless dismissed on this tab.
     */
    private fun updateTranslateChip() {
        val chip = b.translateBar.root
        val t = current
        if (t == null || t.showingHome || b.findBar.isVisible || t.translateDismissed || fullscreenTab != null) {
            chip.isVisible = false
            return
        }
        val doc = t.docLangTag?.lowercase(Locale.ROOT)?.substringBefore('-').orEmpty()
        val userTag = t.userLangTag ?: Locale.getDefault().language
        val user = userTag.lowercase(Locale.ROOT).substringBefore('-')
        val differs = doc.isNotEmpty() && user.isNotEmpty() && doc != user
        if (!t.translateOffered && !differs && !t.translated) {
            chip.isVisible = false
            return
        }
        val target = userTag.uppercase(Locale.ROOT).substringBefore('-').ifBlank { "EN" }
        b.translateBar.translateLabel.text =
            if (t.translated) getString(R.string.translate_show_original) else getString(R.string.translate_page, target)
        chip.isVisible = true
    }

    private fun translateCurrentPage() {
        val t = current ?: return
        val st = t.session.sessionTranslation ?: return
        if (t.translated) {
            st.restoreOriginalPage().accept(
                { runOnUiThread { t.translated = false; updateTranslateChip() } },
                { runOnUiThread { snack("Couldn't restore original page") } },
            )
            return
        }
        val from = t.docLangTag ?: return
        val to = t.userLangTag ?: Locale.getDefault().language
        val options = TranslationsController.SessionTranslation.TranslationOptions.Builder()
            .downloadModel(true)    // fetch the on-device model on demand
            .build()
        st.translate(from, to, options).accept(
            { runOnUiThread { t.translated = true; updateTranslateChip() } },
            { e -> runOnUiThread { snack(e?.message?.takeIf { it.isNotBlank() } ?: "Translation unavailable") } },
        )
    }

    /** Close: restores the original first if translated; either way the chip stays hidden on this tab. */
    private fun dismissTranslateChip() {
        val t = current ?: return
        if (!t.translated) {
            t.translateDismissed = true
            updateTranslateChip()
            return
        }
        t.session.sessionTranslation?.restoreOriginalPage()?.accept(
            { runOnUiThread { t.translated = false; t.translateDismissed = true; updateTranslateChip() } },
            { runOnUiThread { t.translateDismissed = true; updateTranslateChip() } },
        )
    }

    // ======================================================================= Beast Control (2.3.5)

    /**
     * Live CPU / RAM / NET for the app, refreshed every 1.5 s, plus three limit sliders.
     * The limits are soft: going over one only turns its figure the warning colour.
     */
    private fun showBeastControl() {
        val dialog = BottomSheetDialog(this)
        val s = SheetBeastControlBinding.inflate(layoutInflater)
        s.cpuCap.value = BeastControl.cpuCap.toFloat()
        s.ramCap.value = BeastControl.ramCap.toFloat()
        s.netCap.value = BeastControl.netCap.toFloat()
        fun paint() {
            val snap = BeastControl.snapshot(this)
            BeastControl.applySoftHints(snap)
            val ok = accent.color
            val warn = getColor(R.color.warn)
            s.ctrlCpu.text = BeastControl.formatCpu(snap.cpuPercent)
            s.ctrlRam.text = BeastControl.formatRam(snap.ramBytes)
            s.ctrlNet.text = BeastControl.formatNet(snap.netBytes)
            s.ctrlCpu.setTextColor(if (snap.cpuOverSoft) warn else ok)
            s.ctrlRam.setTextColor(if (snap.ramOverSoft) warn else ok)
            s.ctrlNet.setTextColor(if (snap.netOverSoft) warn else ok)
        }
        paint()
        val ticker = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                paint()
                s.root.postDelayed(this, 1500)
            }
        }
        s.root.postDelayed(ticker, 1500)
        s.cpuCap.addOnChangeListener { _, v, fromUser -> if (fromUser) { BeastControl.cpuCap = v.toInt(); paint() } }
        s.ramCap.addOnChangeListener { _, v, fromUser -> if (fromUser) { BeastControl.ramCap = v.toInt(); paint() } }
        s.netCap.addOnChangeListener { _, v, fromUser -> if (fromUser) { BeastControl.netCap = v.toInt(); paint() } }
        dialog.setOnDismissListener { s.root.removeCallbacks(ticker) }
        dialog.setContentView(s.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    // ======================================================================= Reader view

    private fun updateReaderButton() {
        val t = current
        val inReader = t != null && !t.showingHome && ReaderMode.isReaderUrl(t.url)
        val available = t != null && !t.showingHome && ReaderMode.isReaderable(t.session)
        b.readerButton.isVisible = inReader || available
        b.readerButton.imageTintList = ColorStateList.valueOf(if (inReader) accent.color else getColor(R.color.text_secondary))
        b.readerButton.contentDescription = if (inReader) "Close Reader view" else "Reader view"
    }

    private fun toggleReader() {
        val t = current ?: return
        if (ReaderMode.isReaderUrl(t.url)) { readerHost.closeReader(t.session, ReaderMode.originalUrl(t.url)); return }
        b.readerButton.isEnabled = false
        ReaderMode.open(t.session) { err ->
            b.readerButton.isEnabled = true
            if (err != null) snack(err)
        }
    }

    /** Reading list → open a saved article (waits briefly for Beast Helper on a cold start). */
    private fun openSavedArticle(t: Tab, id: Long, attempt: Int) {
        val url = ReaderMode.savedUrl(id)
        when {
            url != null -> { if (current !== t) selectTab(t); load(t, url) }
            HelperSessions.baseUrl == null && attempt < 25 -> b.root.postDelayed({ if (t in allTabs) openSavedArticle(t, id, attempt + 1) }, 200)
            else -> snack("That article is no longer in your reading list")
        }
    }

    /** Toolbar badge for detected saveable videos. Beast Helper feeds [MediaSniffer]. */
    fun onMediaBadgeTapped(session: org.mozilla.geckoview.GeckoSession) {
        val tab = allTabs.firstOrNull { it.session === session } ?: current ?: return
        MediaSaveSheet.show(this, session, tab.isPrivate, tab.url, accent.color)
    }

    /** 2.3.7: Media Radar overlay (media badge long-press / menu). */
    private fun openMediaRadar(session: org.mozilla.geckoview.GeckoSession) {
        val tab = allTabs.firstOrNull { it.session === session } ?: current ?: return
        if (!MediaSniffer.hasMedia(session)) {
            Toast.makeText(this, R.string.media_radar_empty, Toast.LENGTH_SHORT).show()
            return
        }
        MediaRadar.show(this, b.radar, session, tab.isPrivate, tab.url, accent.color, accent.onColor)
    }

    private fun updateMediaBadge() {
        val t = current
        val n = if (t == null || t.showingHome) 0 else MediaSniffer.count(t.session)
        b.mediaButton.isVisible = n > 0
        b.mediaBadge.isVisible = n > 0
        b.mediaBadge.text = if (n > 99) "99+" else n.toString()
        b.mediaIcon.imageTintList = ColorStateList.valueOf(if (n > 0) accent.color else getColor(R.color.text_hint))
        (getDrawable(R.drawable.bg_badge)!!.mutate() as GradientDrawable).let {
            it.setColor(accent.color); b.mediaBadge.background = it
        }
        b.mediaBadge.setTextColor(accent.onColor)
    }

    private fun showShields() {
        val t = current ?: return
        val dialog = BottomSheetDialog(this)
        val s = SheetShieldsBinding.inflate(layoutInflater)
        val host = if (t.showingHome) "Speed Dial" else Domains.display(UrlUtils.host(t.url)).ifEmpty { t.url }
        s.shieldsHost.text = host
        s.shieldsIcon.imageTintList = ColorStateList.valueOf(accent.color)
        s.shieldsPageCount.setTextColor(accent.color)
        s.shieldsPageCount.text = fmt(if (t.showingHome) 0 else t.blockedOnPage)
        s.shieldsTotal.text = fmt(Stats.total.get())
        val ubo = Engine.ublock
        s.shieldsBreakdown.text = buildString {
            append("uBlock Origin: ")
            append(when {
                ubo == null && Engine.ublockError != null -> "failed to load"
                ubo == null -> "starting…"
                !Prefs.ublockEnabled -> "off"
                else -> "${t.uboCount} blocked"
            })
            append("   ·   Tracking Protection: ${t.etpBlocked.get()} blocked")
        }
        s.shieldsSwitch.text = "Shields for this site"
        fun render() {
            val uboAvailable = ubo != null && Prefs.ublockEnabled
            s.shieldsSwitch.setOnCheckedChangeListener(null)
            s.shieldsSwitch.isChecked = Prefs.blockAds && !t.siteShieldsDown
            s.shieldsSwitch.isEnabled = Prefs.blockAds && !t.showingHome && (t.trackingPermission != null || uboAvailable)
            s.shieldsState.text = when {
                !Prefs.blockAds -> "Shields are off globally (Settings)"
                t.showingHome -> "Open a site to change its shields"
                t.shieldsDown && t.uboSiteOn == false -> "Shields down on $host: uBlock Origin and Tracking Protection are paused for this site (uBO lists it as a trusted site)."
                t.uboSiteOn == false -> "uBlock Origin is paused for $host (set in its panel); Tracking Protection is still on. Turn the switch on to re-enable both."
                t.shieldsDown -> "Tracking Protection is paused for $host" + if (uboAvailable) "; uBlock Origin is still on." else "."
                uboAvailable -> "uBlock Origin + Strict ETP (ads, analytics, social, cryptominers, fingerprinters) active on this site"
                else -> "Strict ETP: ads, analytics, social, cryptominers, fingerprinters"
            }
            s.shieldsSwitch.setOnCheckedChangeListener { _, up ->
                dialog.dismiss()
                setSiteShields(t, up, host)
            }
        }
        render()
        bindAutoplaySwitch(s, t, dialog)
        // Fresh state from uBO (it may have been changed in uBO's own panel)
        if (!t.showingHome) Engine.uboSiteEnabled(t.url) { on ->
            t.uboSiteOn = on; t.uboSiteHost = UrlUtils.host(t.url).orEmpty()
            if (dialog.isShowing) render()
            updateShield()
        updateMediaBadge()
        }
        shieldsRefresh = { if (dialog.isShowing) render() }
        dialog.setOnDismissListener { shieldsRefresh = null }
        val hosts = t.blockedHosts.entries.sortedByDescending { it.value }.take(12)
        s.shieldsListTitle.isVisible = hosts.isNotEmpty()
        s.shieldsList.isVisible = hosts.isNotEmpty()
        s.shieldsList.text = hosts.joinToString("\n") { (h, n) -> if (n > 1) "$h  ×$n" else h }
        s.shieldsListInfo.text = buildString {
            append("Built-in uBlock Origin ${ubo?.metaData?.version ?: ""} (default filter lists: uBO filters, EasyList, EasyPrivacy, Peter Lowe, URLhaus…) + Firefox Enhanced Tracking Protection (Strict)")
        }
        s.uboPanelButton.isEnabled = ubo != null && Prefs.ublockEnabled
        s.uboPanelButton.setOnClickListener { dialog.dismiss(); openUboPanel() }
        s.uboDashboardButton.isEnabled = ubo?.metaData?.optionsPageUrl != null
        s.uboDashboardButton.setOnClickListener { dialog.dismiss(); openUboDashboard() }
        listOf(s.uboPanelButton, s.uboDashboardButton).forEach {
            it.setTextColor(accent.color); it.strokeColor = ColorStateList.valueOf(accent.withAlpha(0x88))
        }
        dialog.setContentView(s.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private var shieldsRefresh: (() -> Unit)? = null

    /** 2.5: "Allow autoplay on this site" (Settings > Media > Autoplay decides everywhere else). */
    private fun bindAutoplaySwitch(s: SheetShieldsBinding, t: Tab, dialog: BottomSheetDialog) {
        val mode = Prefs.autoplay
        val shown = !t.showingHome && AutoplayPolicy.siteSwitchShown(mode, t.url)
        s.autoplaySwitch.isVisible = shown
        s.autoplayState.isVisible = shown
        if (!shown) return
        val site = AutoplayPolicy.siteKey(t.url)
        val allowed = autoplayFor(t, site) == AutoplayPolicy.Mode.ALLOW_ALL
        s.autoplaySwitch.isChecked = allowed
        s.autoplayState.text = getString(when {
            allowed -> R.string.shields_autoplay_allowed
            mode == AutoplayPolicy.Mode.BLOCK_ALL -> R.string.shields_autoplay_blocked_all
            else -> R.string.shields_autoplay_blocked_audible
        })
        s.autoplaySwitch.setOnCheckedChangeListener { _, on ->
            dialog.dismiss()
            setSiteAutoplay(t, site, if (on) AutoplayPolicy.Mode.ALLOW_ALL else null)
            snack(getString(if (on) R.string.autoplay_site_allowed else R.string.autoplay_site_blocked, site))
        }
    }

    // ------------------------------------------------------------------ 2.5 forced dark (beast-siteprefs)

    private val sessionDarkOff get() = Engine.sessionDarkOff // Private/Ghost choices: session only, never in the DB

    private fun syncForceDark() = Engine.syncForceDark(Prefs.forceDarkActive, db.forceDarkOffSites())

    private fun forceDarkOff(t: Tab, site: String): Boolean =
        (t.isPrivate && site in sessionDarkOff) || db.getSitePrefs(site).forceDarkOff

    /** Menu "Dark page" tile: add/remove [site] in the exception list; the extension re-applies without a reload. */
    private fun setForceDarkOff(t: Tab, site: String, off: Boolean) {
        if (t.isPrivate && (off || site in sessionDarkOff)) {
            if (off) sessionDarkOff += site else sessionDarkOff -= site
        } else {
            db.setForceDarkOff(site, off)
        }
        syncForceDark()
        snack(getString(if (off) R.string.force_dark_site_off else R.string.force_dark_site_on, site))
    }

    /** Autoplay choices made in private / Ghost tabs: kept for this session only, never written to the DB. */
    private val sessionAutoplay = HashMap<String, AutoplayPolicy.Mode?>()
    /** Hosts that already showed the "Autoplay blocked" snackbar this session. */
    private val autoplaySnackShown = HashSet<String>()

    override fun autoplayFor(tab: Tab, site: String): AutoplayPolicy.Mode? {
        if (site.isEmpty()) return null
        if (tab.isPrivate && sessionAutoplay.containsKey(site)) return sessionAutoplay[site]
        return AutoplayPolicy.siteMode(db.getSitePrefs(site).autoplay)
    }

    /** Saves a per-site override (null = follow Settings), drops Gecko's remembered answers, reloads the site's tabs. */
    private fun setSiteAutoplay(t: Tab, site: String, mode: AutoplayPolicy.Mode?) {
        if (t.isPrivate) sessionAutoplay[site] = mode else db.setAutoplay(site, mode?.key)
        Engine.resetAutoplayPermissions(site) {
            allTabs.filter { !it.showingHome && it.isPrivate == t.isPrivate && AutoplayPolicy.siteKey(it.url) == site }
                .forEach { it.session.reload() }
        }
    }

    override fun onAutoplayBlocked(tab: Tab, site: String) {
        // Designer SPEC (d): once per host per session, never over fullscreen video or PiP, with an Allow action.
        if (tab !== current || site.isEmpty() || fullscreenTab != null || inPip) return
        if (!autoplaySnackShown.add(site)) return
        snack(getString(R.string.autoplay_blocked_on, site), getString(R.string.autoplay_allow), Snackbar.LENGTH_LONG) {
            setSiteAutoplay(tab, site, AutoplayPolicy.Mode.ALLOW_ALL)
        }
    }

    /** The single per-site switch drives both Firefox ETP (site exception) and uBO's trusted-site list. */
    private fun setSiteShields(t: Tab, up: Boolean, host: String) {
        t.trackingPermission?.let {
            runtime.storageController.setPermission(it, if (up) ContentPermission.VALUE_DENY else ContentPermission.VALUE_ALLOW)
            t.etpDownOverride = !up
        }
        val url = t.url
        Engine.setUboSiteEnabled(url, up) { res ->
            val h = UrlUtils.host(url).orEmpty()
            allTabs.filter { UrlUtils.host(it.url).orEmpty() == h }.forEach { it.uboSiteOn = res; it.uboSiteHost = h }
            if (allTabs.contains(t)) t.session.reload()
            updateShield()
        updateMediaBadge()
        }
        snack(if (up) "Shields up for $host" else "Shields down for $host (uBlock Origin + Tracking Protection)")
    }

    /** Events from the uBO bridge: the switch was flipped in uBO's own panel / dashboard. */
    private val uboEvents: (org.json.JSONObject) -> Unit = { ev ->
        when (ev.optString("type")) {
            "siteChanged" -> {
                val h = UrlUtils.host(ev.optString("url")).orEmpty()
                val on = ev.optBoolean("enabled", true)
                var etpSynced = false
                allTabs.filter { h.isNotEmpty() && UrlUtils.host(it.url).orEmpty() == h }.forEach { tab ->
                    tab.uboSiteOn = on; tab.uboSiteHost = h
                    // keep ETP in step so the one switch stays truthful
                    val perm = tab.trackingPermission
                    if (perm != null && tab.shieldsDown == on) {
                        if (!etpSynced) runtime.storageController.setPermission(perm, if (on) ContentPermission.VALUE_DENY else ContentPermission.VALUE_ALLOW)
                        etpSynced = true
                    }
                    if (perm != null) tab.etpDownOverride = !on
                }
                updateShield(); shieldsRefresh?.invoke()
            }
            "whitelistChanged", "hello" -> allTabs.forEach { refreshUboSite(it, force = true) }
        }
    }

    private fun refreshUboSite(tab: Tab, force: Boolean = false) {
        if (tab.showingHome || !tab.url.startsWith("http")) { tab.uboSiteOn = null; tab.uboSiteHost = null; return }
        val h = UrlUtils.host(tab.url).orEmpty()
        if (!force && h == tab.uboSiteHost) return
        tab.uboSiteHost = h
        Engine.uboSiteEnabled(tab.url) { on ->
            if (UrlUtils.host(tab.url).orEmpty() != h) return@uboSiteEnabled
            tab.uboSiteOn = on
            if (tab === current) { updateShield(); shieldsRefresh?.invoke() }
        }
    }

    private fun openLibrary(page: Int) =
        libraryLauncher.launch(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_PAGE, page))

    private fun openDownloads() = startActivity(Intent(this, DownloadsActivity::class.java))

    private fun toggleBookmark(t: Tab) {
        if (t.showingHome) { toast("Open a page to bookmark it"); return }
        if (db.isBookmarked(t.url)) { db.removeBookmark(t.url); snack("Bookmark removed") }
        else { db.addBookmark(t.url, t.displayTitle); snack("Bookmarked") }
    }

    private fun share(t: Tab) {
        if (t.showingHome) { toast("Nothing to share yet"); return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, t.title).putExtra(Intent.EXTRA_TEXT, t.url), "Share link"))
    }

    private fun zoomLabel(t: Tab) = if (t.zoomPercent == 100) "Zoom" else "Zoom ${t.zoomPercent}%"

    private fun applyDesktopSettings(t: Tab) {
        t.session.settings.userAgentMode =
            if (t.desktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        t.session.settings.viewportMode =
            if (t.desktopMode) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
    }

    /** Load remembered desktop + zoom for this host onto [tab]. */
    private fun applySitePrefs(t: Tab, url: String, reloadIfDesktopChanged: Boolean) {
        val host = db.siteKey(url)
        if (host.isEmpty()) return
        val prefs = db.getSitePrefs(host)
        val desktopChanged = prefs.desktop != t.desktopMode
        t.desktopMode = prefs.desktop
        t.zoomPercent = prefs.zoom
        applyDesktopSettings(t)
        Engine.setPageZoom(host, prefs.zoom)
        if (reloadIfDesktopChanged && desktopChanged && !t.showingHome) t.session.reload()
    }

    private fun toggleDesktop(t: Tab) {
        t.desktopMode = !t.desktopMode
        applyDesktopSettings(t)
        val host = db.siteKey(t.url)
        if (host.isNotEmpty()) {
            db.setDesktop(host, t.desktopMode)
            snack(if (t.desktopMode) "Desktop site on for $host" else "Mobile site for $host")
        } else {
            snack(if (t.desktopMode) "Desktop site on" else "Mobile site")
        }
        if (!t.showingHome) t.session.reload()
    }

    private fun showZoom(t: Tab) {
        if (t.showingHome || !t.url.startsWith("http")) { toast("Open a page first"); return }
        val host = db.siteKey(t.url)
        if (host.isEmpty()) { toast("Open a page first"); return }
        val steps = intArrayOf(50, 75, 90, 100, 110, 125, 150, 175, 200, 250, 300)
        fun nearestIndex(z: Int): Int {
            var best = 3
            var bestDist = Int.MAX_VALUE
            for (i in steps.indices) {
                val d = kotlin.math.abs(steps[i] - z)
                if (d < bestDist) { bestDist = d; best = i }
            }
            return best
        }
        val labels = arrayOf("Zoom out (−)", "Reset to 100%", "Zoom in (+)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Zoom · $host")
            .setMessage("Current: ${t.zoomPercent}%")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> {
                        val i = nearestIndex(t.zoomPercent)
                        setZoom(t, host, steps[(i - 1).coerceAtLeast(0)])
                    }
                    1 -> setZoom(t, host, 100)
                    2 -> {
                        val i = nearestIndex(t.zoomPercent)
                        setZoom(t, host, steps[(i + 1).coerceAtMost(steps.lastIndex)])
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun setZoom(t: Tab, host: String, zoom: Int) {
        val z = zoom.coerceIn(50, 300)
        t.zoomPercent = z
        db.setZoom(host, z)
        Engine.setPageZoom(host, z)
        snack(if (z == 100) "Zoom reset for $host" else "Zoom ${z}% for $host")
    }

    private fun exitBrowser() {
        if (Prefs.clearOnExit) wipeData()
        finishAndRemoveTask()
    }

    private fun wipeData() {
        Engine.clearSiteData(this)
        db.clearHistory()
        tally.clear() // per-site counts are browsing data too
        Prefs.pendingWipe = false
    }

    // ======================================================================= uBlock Origin

    private fun onUblockReady(ext: WebExtension) {
        ext.setActionDelegate(object : WebExtension.ActionDelegate {
            override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
                uboDefaultAction = action
            }
            override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action) = showExtensionPopup()
            override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action) = showExtensionPopup()
        })
        ext.setTabDelegate(object : WebExtension.TabDelegate {
            override fun onNewTab(source: WebExtension, details: WebExtension.CreateTabDetails): GeckoResult<GeckoSession> {
                val t = newTab(details.url, parent = current, open = false, select = details.active != false)
                return GeckoResult.fromValue(t.session)
            }
            override fun onOpenOptionsPage(source: WebExtension) { openUboDashboard() }
        })
        allTabs.forEach { attachUbo(it, ext); refreshUboSite(it, force = true) }
        updateHomeStats()
    }

    private fun attachUbo(tab: Tab, ext: WebExtension? = Engine.ublock) {
        ext ?: return
        val ctl = tab.session.webExtensionController
        ctl.setActionDelegate(ext, object : WebExtension.ActionDelegate {
            override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
                uboActions[tab.id] = action
                val n = parseBadge(action.badgeText)
                if (n > tab.uboCount) Stats.total.addAndGet((n - tab.uboCount).toLong())
                tab.uboCount = n
                tallyUbo(tab, n)
                onBlockedChanged(tab)
            }
            override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action) = showExtensionPopup()
            override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action) = showExtensionPopup()
        })
        ctl.setTabDelegate(ext, object : WebExtension.SessionTabDelegate {
            override fun onCloseTab(source: WebExtension?, session: GeckoSession): GeckoResult<AllowOrDeny> {
                closeTab(tab); return GeckoResult.allow()
            }
        })
    }

    private fun parseBadge(text: String?): Int {
        val s = text?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return 0
        return when {
            s.endsWith("k") -> ((s.dropLast(1).toDoubleOrNull() ?: 0.0) * 1000).toInt()
            else -> s.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    private fun openUboPanel() {
        val action = current?.let { uboActions[it.id] } ?: uboDefaultAction
        if (action == null) { toast("uBlock Origin is still starting…"); return }
        action.click()
    }

    private fun openUboDashboard() {
        val url = Engine.ublock?.metaData?.optionsPageUrl ?: run { toast("uBlock Origin not ready"); return }
        newTab(url)
    }

    /** Hosts an extension popup (uBO's panel) in a bottom sheet. */
    private fun showExtensionPopup(): GeckoResult<GeckoSession> {
        val session = GeckoSession(GeckoSessionSettings.Builder()
            .usePrivateMode(current?.isPrivate == true || realm.alwaysPrivate)
            .apply { realm.contextId?.let { contextId(it) } }
            .build())
        val dialog = BottomSheetDialog(this)
        val view = GeckoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(this@MainActivity, 560))
        }
        val frame = FrameLayout(this).apply { addView(view) }
        session.promptDelegate = prompts
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(s: GeckoSession) { dialog.dismiss() }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                // Links from the panel (dashboard, logger) open as tabs
                if (request.uri.startsWith("http") && request.hasUserGesture) { dialog.dismiss(); newTab(request.uri); return GeckoResult.deny() }
                return GeckoResult.allow()
            }
        }
        dialog.setContentView(frame)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.isDraggable = false
        dialog.setOnDismissListener { view.releaseSession(); runCatching { session.close() } }
        dialog.show()
        fun attach(attempt: Int) {
            frame.postDelayed({
                if (session.isOpen) view.setSession(session) else if (attempt < 60) attach(attempt + 1)
            }, 50)
        }
        attach(0)
        return GeckoResult.fromValue(session)
    }

    // ======================================================================= BrowserHost

    override fun onTabUpdated(tab: Tab) {
        refreshUboSite(tab)
        if (tab === current) refreshUi()
        if (b.switcher.root.isVisible) tabAdapter.notifyDataSetChanged()
    }

    override fun onPageStarted(tab: Tab, url: String) {
        // Link clicks / redirects: pick up remembered desktop + zoom for the new host
        if (url.startsWith("http")) applySitePrefs(tab, url, reloadIfDesktopChanged = false)
        // A new page: Gecko re-offers translation per document (the per-tab dismissal is kept).
        tab.translateOffered = false
        if (tab === current) { lastBadge = 0; refreshUi() }
    }

    override fun onPageStopped(tab: Tab, success: Boolean) {
        if (tab.restoreHttpsOnly) { tab.restoreHttpsOnly = false; Engine.allowInsecureTemporarily(false) }
        if (!tab.isPrivate && success && tab.title.isNotBlank() && tab.url.startsWith("http")) db.updateHistoryTitle(tab.url, tab.title)
        // Re-assert zoom after the page finishes (some sites reset document styles while loading)
        if (success && tab.url.startsWith("http") && tab.zoomPercent != 100) {
            val host = db.siteKey(tab.url)
            if (host.isNotEmpty()) Engine.setPageZoom(host, tab.zoomPercent)
        }
        if (tab === current) { b.swipe.isRefreshing = false; refreshUi() }
    }

    override fun onProgress(tab: Tab, progress: Int) {
        if (tab !== current) return
        b.progress.isVisible = progress < 100 && !tab.showingHome
        b.progress.setProgressCompat(progress, true)
    }

    override fun onBlockedChanged(tab: Tab) {
        if (tab === current) {
            updateShield()
        updateMediaBadge()
            if (tab.showingHome) updateHomeStats()
        }
    }

    override fun onTrackerBlocked(tab: Tab, site: String, category: TrackerCategory, count: Int) {
        tally.record(site, category, count.toLong())
    }

    /** Roadmap 10: uBlock Origin's badge increase goes into the weekly tally as its own line, never from private tabs. */
    private fun tallyUbo(tab: Tab, badge: Int) {
        val gained = TrackerTally.uboIncrease(tab.uboTallied, badge)
        tab.uboTallied = badge
        if (gained <= 0) return
        val site = TrackerTally.siteToRecord(tab.isPrivate || tab.session.settings.usePrivateMode, tab.url) ?: return
        onTrackerBlocked(tab, site, TrackerCategory.UBLOCK, gained)
    }

    override fun onVisited(tab: Tab, url: String) {
        if (url.startsWith("http")) db.addHistory(url, tab.title)
    }

    override fun onNewWindow(opener: Tab, uri: String): GeckoSession =
        newTab(null, private = opener.isPrivate, parent = opener, open = false).also { it.showingHome = false; it.url = uri }.session

    override fun closeTabRequested(tab: Tab) = closeTab(tab)

    override fun openExternal(tab: Tab, uri: String): Boolean {
        return try {
            val intent = if (uri.startsWith("intent:")) Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent); true
            } else {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (fallback != null && fallback.startsWith("http")) { load(tab, fallback); true } else { toast("No app can open this link"); true }
            }
        } catch (e: Exception) { false }
    }

    override fun onBeastUri(tab: Tab, uri: String) {
        val u = Uri.parse(uri)
        when (u.schemeSpecificPart.substringBefore('?')) {
            "continue" -> u.getQueryParameter("u")?.let { http ->
                // Exempt only this host (Gecko's per-site HTTPS-Only exception, session-scoped, private/normal kept apart)
                Engine.allowInsecureForHost(UrlUtils.host(http).orEmpty(), tab.isPrivate) { ok ->
                    if (!ok) {
                        // Fallback if the helper isn't available: allow http for this single load only
                        Engine.allowInsecureTemporarily(true)
                        tab.restoreHttpsOnly = true
                    }
                    if (allTabs.contains(tab)) load(tab, http)
                }
            }
            "back" -> goBack()
            "retry" -> u.getQueryParameter("u")?.let { load(tab, it) }
        }
    }

    override fun onFullScreen(tab: Tab, fullScreen: Boolean) {
        fullscreenTab = if (fullScreen) tab else null
        val ctl = WindowInsetsControllerCompat(window, b.root)
        if (fullScreen) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            if (fullscreenPipButtonAllowed()) {
                val count = Prefs.pipHintCount
                styleFullscreenPipButton(PipPolicy.fullscreenButtonLabelled(count))
                if (PipPolicy.fullscreenButtonLabelled(count)) Prefs.pipHintCount = count + 1
                fadePipButton(true)
            }
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            fadePipButton(false)
        }
        updateBarsVisibility()
        ViewCompat.requestApplyInsets(b.root)
        updatePipParams()
    }

    override fun onMediaStateChanged(tab: Tab) {
        if (tab === current) updatePipParams()
    }

    // ======================================================================= picture-in-picture (2.5)

    private fun mediaState(t: Tab?) = PipPolicy.MediaState(
        playing = t?.mediaPlaying == true,
        mediaFullscreen = t?.mediaFullscreen == true,
        pageFullscreen = t != null && t === fullscreenTab,
    )

    /** A fullscreen video is playing in the current tab and PiP is on: leaving the app enters PiP. */
    private fun pipAutoEligible() = PipPolicy.shouldAutoEnter(Prefs.pipEnabled, pipSupported, mediaState(current))

    private fun pipParams(): PictureInPictureParams {
        val t = current
        val ratio = PipPolicy.aspectRatio(t?.videoWidth ?: 0L, t?.videoHeight ?: 0L)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(ratio.num, ratio.den))
            .setActions(pipActions(t))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: the system enters PiP itself on swipe-home, with a smooth animation.
            builder.setAutoEnterEnabled(pipAutoEligible())
            builder.setSeamlessResizeEnabled(false) // video content: avoid cross-fading resizes
        }
        // Smooth enter animation from the fullscreen video's bounds.
        if (fullscreenTab != null && fullscreenTab === t) {
            val r = android.graphics.Rect()
            if (geckoView.getGlobalVisibleRect(r) && !r.isEmpty) builder.setSourceRectHint(r)
        }
        return builder.build()
    }

    private fun pipAction(cmd: String, requestCode: Int, icon: Int, label: Int): RemoteAction {
        val intent = Intent(ACTION_PIP_CONTROL).setPackage(packageName).putExtra(EXTRA_PIP_CMD, cmd)
        val pi = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = getString(label)
        return RemoteAction(Icon.createWithResource(this, icon), text, text, pi)
    }

    /** Play / pause, plus back / forward 10 s for media with a known length (Designer's ic_pip_rewind / _forward). */
    private fun pipActions(t: Tab?): List<RemoteAction> {
        if (t?.mediaSession == null) return emptyList()
        val playPause = if (t.mediaPlaying) pipAction(PIP_PAUSE, 1, R.drawable.ic_pip_pause, R.string.pip_pause)
        else pipAction(PIP_PLAY, 1, R.drawable.ic_pip_play, R.string.pip_play)
        val max = runCatching { maxNumPictureInPictureActions }.getOrDefault(3)
        if (!PipPolicy.seekActionsAvailable(t.mediaDuration, max)) return listOf(playPause)
        return listOf(
            pipAction(PIP_BACK, 2, R.drawable.ic_pip_rewind, R.string.pip_back_10),
            playPause,
            pipAction(PIP_FORWARD, 3, R.drawable.ic_pip_forward, R.string.pip_forward_10),
        )
    }

    /** Keeps the system's PiP params (ratio, play/pause, Android 12+ auto-enter) in step with the current tab. */
    private fun updatePipParams() {
        if (!pipSupported) return
        runCatching { setPictureInPictureParams(pipParams()) }
            .onFailure { android.util.Log.w("BeastPip", "setPictureInPictureParams failed", it) }
    }

    private fun enterPip(): Boolean {
        if (!pipSupported || !Prefs.pipEnabled) return false
        return runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
    }

    // ---- fullscreen PiP button (Designer SPEC (c)): top-right, fades in, hides after 3 s, back on any touch

    private val hidePipButton = Runnable { fadePipButton(false) }

    private fun fullscreenPipButtonAllowed() =
        PipPolicy.fullscreenButtonShown(fullscreenTab != null, pipSupported, Prefs.pipEnabled, inPip)

    private fun styleFullscreenPipButton(labelled: Boolean) {
        val btn = b.fullscreenPipButton
        val lp = btn.layoutParams
        if (labelled) {
            lp.width = FrameLayout.LayoutParams.WRAP_CONTENT; lp.height = dp(this, 44)
            btn.setBackgroundResource(R.drawable.bg_pip_overlay_pill)
            btn.setPaddingRelative(dp(this, 12), 0, dp(this, 16), 0)
            b.fullscreenPipIcon.layoutParams.let { it.width = dp(this, 22); it.height = dp(this, 22) }
        } else {
            lp.width = dp(this, 48); lp.height = dp(this, 48)
            btn.setBackgroundResource(R.drawable.bg_pip_overlay_circle)
            btn.setPadding(0, 0, 0, 0)
            b.fullscreenPipIcon.layoutParams.let { it.width = dp(this, 24); it.height = dp(this, 24) }
        }
        b.fullscreenPipLabel.isVisible = labelled
        btn.layoutParams = lp
    }

    private fun fadePipButton(show: Boolean) {
        val views = listOf(b.fullscreenPipButton, b.fullscreenPipScrim)
        b.root.removeCallbacks(hidePipButton)
        if (show && fullscreenPipButtonAllowed()) {
            views.forEach { v ->
                if (!v.isVisible) { v.alpha = 0f; v.isVisible = true }
                v.animate().alpha(1f).setDuration(150).start()
            }
            b.root.postDelayed(hidePipButton, PipPolicy.FULLSCREEN_BUTTON_HIDE_MS)
        } else {
            views.forEach { v ->
                if (!v.isVisible) return@forEach
                v.animate().alpha(0f).setDuration(150).withEndAction { if (v.alpha == 0f) v.isVisible = false }.start()
            }
        }
    }

    private fun setupFullscreenPipButton() {
        b.fullscreenPipButton.setOnClickListener {
            fadePipButton(false)
            if (!enterPip()) toast(getString(R.string.pip_unavailable))
        }
    }

    /** Any touch while fullscreen brings the button back; the event still goes to the page (never consumed). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && fullscreenTab != null && !inPip) fadePipButton(true)
        return super.dispatchTouchEvent(ev)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 8-11 only: on 12+ setAutoEnterEnabled already handles leaving the app.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && pipAutoEligible()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            b.root.removeCallbacks(hidePipButton)
            listOf(b.fullscreenPipButton, b.fullscreenPipScrim).forEach { it.animate().cancel(); it.alpha = 0f; it.isVisible = false }
            // Only the page (the video) is shown: hide every bit of browser chrome and transient UI.
            pipFindBarWasVisible = b.findBar.isVisible
            b.findBar.isVisible = false
            if (b.switcher.root.isVisible) b.switcher.root.isVisible = false
            if (b.suggestList.isVisible) hideSuggest()
            if (MediaRadar.isShowing(b.radar)) MediaRadar.hide(b.radar)
            b.urlInput.clearFocus()
            hideKeyboard()
        } else {
            if (pipFindBarWasVisible && current?.showingHome == false) b.findBar.isVisible = true
            pipFindBarWasVisible = false
            // Closed with the PiP window's X (activity already stopped) rather than expanded: stop the sound.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) current?.mediaSession?.pause()
        }
        updateBarsVisibility()
        ViewCompat.requestApplyInsets(b.root)
        if (!inPip) refreshUi()
    }

    override fun onContextMenu(tab: Tab, element: ContextElement) {
        val link = element.linkUri
        val src = element.srcUri
        val isImage = element.type == ContextElement.TYPE_IMAGE
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (link != null) {
            options += "Open in new tab" to {
                val opened = newTab(link, private = tab.isPrivate, parent = tab, select = false)
                snack("Opened in background") { if (opened in tabs) selectTab(opened) }
            }
            options += (if (realm == Realm.GHOST) "Open in Ghost tab" else "Open in private tab") to { newTab(link, private = true, parent = tab) }
            options += "Copy link" to { copy(link) }
            options += "Share link" to { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Share link")) }
            options += "Download link" to { downloadUrl(link, null) }
        }
        if (isImage && src != null) {
            options += "Open image in new tab" to { newTab(src, private = tab.isPrivate, parent = tab) }
            options += "Download image" to { downloadUrl(src, null) }
            options += "Copy image address" to { copy(src) }
        }
        if (options.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(link ?: src)
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    override fun onDownload(tab: Tab, response: WebResponse) {
        beforeDownload()
        val item = DownloadCenter.enqueue(response, tab.isPrivate, tab.url.takeIf { it.startsWith("http") })
        snack("Downloading ${item.fileName}…", "View") { openDownloads() }
    }

    /** Context-menu downloads (links/images) also go through Beast's download manager (pause/resume, private-aware). */
    private fun downloadUrl(url: String, mime: String?) {
        if (!url.startsWith("http")) { toast("Can't download this link"); return }
        beforeDownload()
        val t = current
        val item = DownloadCenter.enqueue(url, t?.isPrivate == true, t?.url?.takeIf { it.startsWith("http") }, mime)
        snack("Downloading ${item.fileName}…", "View") { openDownloads() }
    }

    private fun beforeDownload() {
        if (Build.VERSION.SDK_INT >= 33 && !Prefs.notificationsAsked &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Prefs.notificationsAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** Ring around the menu button + snackbars while downloads run. */
    private fun observeDownloads() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { DownloadCenter.items.collect { updateDownloadRing(it) } }
                launch {
                    DownloadCenter.events.collect { ev ->
                        when (ev) {
                            is DownloadCenter.Event.Finished -> snack("Downloaded ${ev.item.fileName}", "Open") {
                                DownloadCenter.get(ev.item.id)?.let { d ->
                                    try { startActivity(DownloadCenter.openIntent(d) ?: return@let) } catch (e: Exception) { toast("No app can open this file") }
                                }
                            }
                            is DownloadCenter.Event.Failed -> snack("Download failed: ${ev.item.fileName}", "View") { openDownloads() }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    private fun updateDownloadRing(list: List<com.jamhowman.beastbrowser.downloads.DownloadItem>) {
        val active = list.filter { it.status.isActive }
        val ring = b.menuDownloadRing
        if (active.isEmpty()) { ring.isVisible = false; return }
        val known = active.all { it.total > 0 }
        val total = active.sumOf { it.total.coerceAtLeast(0) }
        val pct = if (known && total > 0) ((active.sumOf { it.downloaded } * 100) / total).toInt() else -1
        val indeterminate = pct < 0
        if (ring.isIndeterminate != indeterminate) { ring.isVisible = false; ring.isIndeterminate = indeterminate }
        if (!indeterminate) ring.setProgressCompat(pct.coerceIn(2, 100), true)
        ring.isVisible = true
        b.btnMenu.contentDescription = getString(R.string.menu) + " (${active.size} downloading)"
    }

    override fun onCrashed(tab: Tab) {
        val wasCurrent = tab === current
        if (geckoView.session === tab.session) geckoView.releaseSession()
        runCatching { tab.session.close() }
        tab.resetMedia()
        tab.session = createSession(tab.isPrivate, tab.realm)
        wire(tab)
        tab.session.open(runtime)
        if (!tab.showingHome) tab.pendingUrl = tab.url
        if (wasCurrent) { current = null; selectTab(tab); snack("The page crashed and was reloaded") }
    }

    override fun errorPage(tab: Tab, uri: String?, code: Int, category: Int): GeckoResult<String> {
        val target = uri.orEmpty()
        val httpsOnly = code == WebRequestError.ERROR_HTTPS_ONLY
        val (title, msg) = when {
            httpsOnly -> "Secure connection not available" to getString(R.string.error_https_only, esc(Domains.display(UrlUtils.host(target))))
            category == WebRequestError.ERROR_CATEGORY_SECURITY -> "Connection isn't secure" to getString(R.string.error_insecure_cert)
            category == WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> "Dangerous site blocked" to "This site is reported as deceptive or harmful."
            code == WebRequestError.ERROR_UNKNOWN_HOST -> "Site not found" to "Check the address or your connection."
            category == WebRequestError.ERROR_CATEGORY_NETWORK -> "Can't connect" to "The site took too long to respond or refused the connection."
            else -> "Couldn't load page" to "Something went wrong loading this page (error $code)."
        }
        val accentHex = String.format("#%06X", accent.color and 0xFFFFFF)
        val enc = Uri.encode(target)
        val buttons = buildString {
            append("<a class='btn' href='beast:back'>Go back</a>")
            if (httpsOnly) append("<a class='btn ghost' href='beast:continue?u=${Uri.encode(target.replaceFirst("https://", "http://"))}'>Continue to HTTP site</a>")
            else if (category != WebRequestError.ERROR_CATEGORY_SAFEBROWSING) append("<a class='btn ghost' href='beast:retry?u=$enc'>Try again</a>")
        }
        val html = """<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>
            <style>body{background:#0E0E12;color:#F2F2F7;font-family:sans-serif;margin:0;padding:48px 28px}
            .bar{width:48px;height:4px;background:$accentHex;border-radius:2px;margin-bottom:24px;box-shadow:0 0 16px $accentHex}
            h1{font-size:24px;margin:0 0 12px}p{color:#A0A0AE;line-height:1.5}.u{color:#6E6E7C;font-size:13px;word-break:break-all}
            .btn{display:block;text-align:center;margin-top:14px;padding:14px;border-radius:24px;background:$accentHex;color:#fff;text-decoration:none;font-weight:bold}
            .ghost{background:transparent;border:1px solid $accentHex;color:$accentHex}</style></head>
            <body><div class='bar'></div><h1>${esc(title)}</h1><p>$msg</p><p class='u'>${esc(target)}</p>$buttons</body></html>"""
        return GeckoResult.fromValue("data:text/html;base64," + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP))
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")

    // ======================================================================= helpers

    private fun pickFiles(mimeTypes: Array<String>?, multiple: Boolean, callback: (Array<Uri>?) -> Unit) {
        pendingFile = callback
        val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        val types = mimeTypes?.filter { it.contains('/') }?.toTypedArray()
        if (!types.isNullOrEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, types)
        try { filePicker.launch(intent) } catch (e: Exception) { pendingFile = null; callback(null) }
    }

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", text))
        if (Build.VERSION.SDK_INT < 33) toast("Copied")
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(b.root.windowToken, 0)
    }

    private fun showKeyboard(v: View) {
        v.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) }
    }

    fun snack(msg: String, action: (() -> Unit)? = null) = snack(msg, "Show", action)

    fun snack(msg: String, actionLabel: String, action: (() -> Unit)?) = snack(msg, actionLabel, Snackbar.LENGTH_SHORT, action)

    fun snack(msg: String, actionLabel: String, duration: Int, action: (() -> Unit)?) {
        val s = Snackbar.make(b.root, msg, duration)
        if (b.bottomBar.isVisible) s.anchorView = b.bottomBar
        s.setBackgroundTint(getColor(R.color.surface3)).setTextColor(getColor(R.color.text_primary))
        if (action != null) s.setAction(actionLabel) { action() }.setActionTextColor(snackActionColor(this, accent.color))
        s.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_URL = "url"
        private const val ACTION_PIP_CONTROL = "com.jamhowman.beastbrowser.action.PIP_CONTROL"
        private const val EXTRA_PIP_CMD = "cmd"
        private const val PIP_PLAY = "play"
        private const val PIP_PAUSE = "pause"
        private const val PIP_BACK = "back"
        private const val PIP_FORWARD = "forward"
        /** Dark ink for badges on light colours (same as the red accent's onColor). */
        private val INK_DARK = 0xFF0E0E12.toInt()
    }
}
