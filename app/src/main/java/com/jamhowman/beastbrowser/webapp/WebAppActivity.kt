package com.jamhowman.beastbrowser.webapp

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.browser.Engine
import com.jamhowman.beastbrowser.ui.MainActivity
import com.jamhowman.beastbrowser.ui.Prompts
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * Issue #34: a home-screen web app. One task per app (document mode keyed on a per-app data URI, empty
 * taskAffinity), separate from the browser's tabs. Honours the manifest's display, theme/background colour,
 * orientation, start_url and scope; anything outside the scope opens in a normal Ulfur tab.
 */
class WebAppActivity : AppCompatActivity() {

    private lateinit var spec: WebAppSpec
    private var session: GeckoSession? = null
    private var canGoBack = false
    private var currentUrl: String? = null
    private var titleView: TextView? = null
    private var pendingFiles: ((Array<Uri>?) -> Unit)? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pendingFiles?.invoke(uris.takeIf { it.isNotEmpty() }?.toTypedArray()); pendingFiles = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = WebAppJson.fromJson(intent.getStringExtra(EXTRA_SPEC))
        if (s == null) { finish(); return }
        spec = s
        val theme = spec.themeColor
        val bg = spec.backgroundColor ?: theme ?: Color.WHITE

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(theme ?: bg) }
        if (spec.display == Display.MINIMAL_UI) root.addView(toolbar(theme ?: bg))
        val gecko = GeckoView(this).apply { setBackgroundColor(bg) }
        root.addView(gecko, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val bars = WindowInsetsControllerCompat(window, root)
        if (spec.display == Display.FULLSCREEN) {
            bars.hide(WindowInsetsCompat.Type.systemBars())
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            // Edge-to-edge is enforced at targetSdk 35+, so the theme colour is painted behind the status bar.
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom); insets
            }
            bars.isAppearanceLightStatusBars = WebApps.isLight(theme ?: bg)
            bars.isAppearanceLightNavigationBars = WebApps.isLight(theme ?: bg)
        }
        orientation(spec.orientation)?.let { requestedOrientation = it }
        applyTaskDescription()

        val gs = GeckoSession(GeckoSessionSettings.Builder().useTrackingProtection(true).build())
        gs.promptDelegate = Prompts(this, { mimes, multiple, cb ->
            pendingFiles = cb
            runCatching { filePicker.launch(mimes?.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")) }.onFailure { cb(null); pendingFiles = null }
        }) {}
        gs.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { this@WebAppActivity.canGoBack = canGoBack }
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url != null && url != "about:blank") currentUrl = url
            }
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                val uri = request.uri
                val scheme = uri.substringBefore(':').lowercase()
                if (scheme == "http" || scheme == "https") {
                    if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT && !spec.inScope(uri)) {
                        openInBrowser(uri); return GeckoResult.deny()
                    }
                    return GeckoResult.allow()
                }
                return when (scheme) {
                    "about", "data", "blob", "resource", "moz-extension" -> GeckoResult.allow()
                    else -> { openExternal(uri); GeckoResult.deny() }
                }
            }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (uri.startsWith("http")) { if (spec.inScope(uri)) session.loadUri(uri) else openInBrowser(uri) }
                return null
            }
        }
        gs.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { titleView?.text = title?.ifBlank { null } ?: label() }
            override fun onCloseRequest(session: GeckoSession) = finishAndRemoveTask()
        }
        gs.open(Engine.runtime(this))
        gecko.setSession(gs)
        gs.loadUri(spec.startUrl)
        session = gs

        onBackPressedDispatcher.addCallback(this) {
            if (canGoBack) session?.goBack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
        }
    }

    override fun onDestroy() {
        session?.let { runCatching { it.close() } }
        session = null
        super.onDestroy()
    }

    private fun label() = WebApps.label(spec, null, spec.startUrl)

    /** minimal-ui: a slim bar with back, the page title and "open in Ulfur". */
    private fun toolbar(color: Int): LinearLayout {
        val light = WebApps.isLight(color)
        val ink = if (light) Color.BLACK else Color.WHITE
        val h = (48 * resources.displayMetrics.density).toInt()
        fun btn(icon: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
            setImageResource(icon); setColorFilter(ink); contentDescription = desc
            setBackgroundColor(Color.TRANSPARENT); setOnClickListener { onClick() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(color)
            addView(btn(R.drawable.ic_back, getString(R.string.webapp_back)) { onBackPressedDispatcher.onBackPressed() }, LinearLayout.LayoutParams(h, h))
            titleView = TextView(this@WebAppActivity).apply {
                setTextColor(ink); textSize = 16f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; text = label()
            }
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn(R.drawable.ic_open, getString(R.string.webapp_open_in_ulfur)) {
                openInBrowser(currentUrl ?: spec.startUrl)
            }, LinearLayout.LayoutParams(h, h))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
        }
    }

    private fun openInBrowser(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url), this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openExternal(uri: String) {
        runCatching {
            val i = if (uri.startsWith("intent:")) Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
                component = null; selector = null; addCategory(Intent.CATEGORY_BROWSABLE)
            } else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            startActivity(i)
        }.onFailure { if (it !is ActivityNotFoundException) return }
    }

    @Suppress("DEPRECATION")
    private fun applyTaskDescription() {
        val color = (spec.themeColor ?: 0) or 0xFF000000.toInt()
        val td = if (Build.VERSION.SDK_INT >= 33) ActivityManager.TaskDescription.Builder().setLabel(label()).setPrimaryColor(color).build()
        else ActivityManager.TaskDescription(label(), null, color)
        setTaskDescription(td)
    }

    private fun orientation(o: String?) = when (o?.lowercase()) {
        "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        "natural" -> ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        else -> null
    }

    companion object {
        const val ACTION = "com.jamhowman.beastbrowser.action.WEB_APP"
        const val EXTRA_SPEC = "com.jamhowman.beastbrowser.extra.WEB_APP_SPEC"

        /** Explicit, with a per-app data URI so document mode gives every app its own task (and recents card). */
        fun intent(ctx: Context, spec: WebAppSpec): Intent =
            Intent(ACTION, Uri.parse("ulfur-webapp://" + WebApps.shortcutId(spec, spec.startUrl)), ctx, WebAppActivity::class.java)
                .putExtra(EXTRA_SPEC, WebAppJson.toJson(spec))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
    }
}
