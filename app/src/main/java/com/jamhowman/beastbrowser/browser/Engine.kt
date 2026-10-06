package com.jamhowman.beastbrowser.browser

import android.content.Context
import android.util.Log
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.SecureDns
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.json.JSONArray
import org.json.JSONObject
import com.jamhowman.beastbrowser.BuildConfig
import com.jamhowman.beastbrowser.passwords.VaultStorageDelegate
import com.jamhowman.beastbrowser.reader.ReaderMode
import androidx.fragment.app.FragmentActivity

/** Process-wide GeckoRuntime + the built-in uBlock Origin extension. */
object Engine {
    private const val TAG = "BeastEngine"
    const val UBO_ID = "uBlock0@raymondhill.net"
    const val UBO_LOCATION = "resource://android/assets/extensions/ublock/"
    const val HELPER_ID = "beast-helper@jamhowman.com"
    const val HELPER_LOCATION = "resource://android/assets/extensions/beast-helper/"
    const val SITEPREFS_ID = "beast-siteprefs@jamhowman.com"
    const val SITEPREFS_LOCATION = "resource://android/assets/extensions/beast-siteprefs/"

    /** Port to the Beast bridge module inside uBO (per-site trusted switch). */
    val uboBridge = NativeBridge("beast_ubo")
    /**
     * Port to the Beast Helper extension (per-host HTTPS-Only exceptions). Also carries Reader view requests the
     * background script relays for the reader page when its own native message gets no answer.
     */
    val helperBridge = NativeBridge("beast_helper").also { b ->
        b.events += { o -> if (o.optString("type") == "readerRelay") ReaderMode.handleRelayed(o, b::post) }
    }
    /** Port to the site-prefs extension (per-host page zoom). */
    val sitePrefsBridge = NativeBridge("beast_siteprefs")
    private var helper: WebExtension? = null
    private var sitePrefs: WebExtension? = null

    private var runtime: GeckoRuntime? = null
    /** Last Secure DNS config handed to Gecko (TRR prefs are only rewritten when it changes). */
    private var appliedDns: SecureDns.Config? = null
    var ublock: WebExtension? = null
        private set
    var ublockError: String? = null
        private set
    private val extensionListeners = mutableListOf<(WebExtension) -> Unit>()

    fun runtime(context: Context): GeckoRuntime {
        runtime?.let { return it }
        val settings = GeckoRuntimeSettings.Builder()
            .contentBlocking(contentBlocking())
            .allowInsecureConnections(httpsMode())
            .globalPrivacyControlEnabled(Prefs.sendDntGpc)
            .preferredColorScheme(colorScheme())
            .remoteDebuggingEnabled(false)
            .consoleOutput(false)
            .aboutConfigEnabled(false)
            .loginAutofillEnabled(true)
            .extensionsWebAPIEnabled(false)
            // No crashHandler(): GeckoView's crash reporter uploads to Mozilla. Ulfur's own reporter
            // (crash/CrashReporter.kt) is opt-in and keeps reports on the device.
            .apply {
                // 2.5: Secure DNS (Gecko TRR). URI first so the mode never starts against a stale resolver.
                val dns = Prefs.secureDns
                dns.uri?.let { trustedRecursiveResolverUri(it) }
                trustedRecursiveResolverMode(dns.trrMode)
                appliedDns = dns
            }
            .build()
        val r = GeckoRuntime.create(context.applicationContext, settings)
        r.settings.setFingerprintingProtection(Prefs.fingerprinting)
        r.settings.setFingerprintingProtectionPrivateBrowsing(true)
        runtime = r
        // Built-in extensions are only refreshed by ensureBuiltIn() when their version changes, so force a
        // reinstall (an in-place update that keeps uBO's settings) once after each app update.
        val upgrade = Prefs.extensionsInstalledFor != BuildConfig.VERSION_CODE
        installUblock(r, upgrade)
        installHelper(r, upgrade)
        installSitePrefs(r, upgrade)
        if (upgrade) Prefs.extensionsInstalledFor = BuildConfig.VERSION_CODE
        return r
    }

    fun runtimeOrNull(): GeckoRuntime? = runtime

    /**
     * Attach the vault-backed autocomplete store. Call from MainActivity once the activity exists.
     * [loginAutofillEnabled] is on; this delegate is the only credential store.
     */
    fun attachPasswordVault(
        activityProvider: () -> FragmentActivity?,
        sessionProvider: () -> GeckoSession? = { null },
    ) {
        val r = runtime ?: return
        r.setAutocompleteStorageDelegate(VaultStorageDelegate(activityProvider, sessionProvider))
    }


    // ------------------------------------------------------------------ privacy settings

    private val strictCategories = ContentBlocking.AntiTracking.STRICT or
        ContentBlocking.AntiTracking.AD or ContentBlocking.AntiTracking.ANALYTIC or
        ContentBlocking.AntiTracking.SOCIAL or ContentBlocking.AntiTracking.CRYPTOMINING or
        ContentBlocking.AntiTracking.FINGERPRINTING or ContentBlocking.AntiTracking.EMAIL

    private fun cookieBehavior() = when (Prefs.cookieMode) {
        "block3p" -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
        "trackers" -> ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
        else -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS // Total Cookie Protection
    }

    private fun contentBlocking(): ContentBlocking.Settings = ContentBlocking.Settings.Builder()
        .antiTracking(if (Prefs.blockAds) strictCategories else ContentBlocking.AntiTracking.NONE)
        .enhancedTrackingProtectionLevel(if (Prefs.blockAds) ContentBlocking.EtpLevel.STRICT else ContentBlocking.EtpLevel.NONE)
        .strictSocialTrackingProtection(Prefs.blockAds)
        .cookieBehavior(cookieBehavior())
        .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
        .cookiePurging(true)
        .queryParameterStrippingEnabled(true)
        .queryParameterStrippingPrivateBrowsingEnabled(true)
        .safeBrowsing(if (Prefs.safeBrowsing) ContentBlocking.SafeBrowsing.DEFAULT else ContentBlocking.SafeBrowsing.NONE)
        .build()

    fun httpsMode() = when (Prefs.httpsMode) {
        "off" -> GeckoRuntimeSettings.ALLOW_ALL
        "private" -> GeckoRuntimeSettings.HTTPS_ONLY_PRIVATE
        else -> GeckoRuntimeSettings.HTTPS_ONLY
    }

    private fun colorScheme() =
        if (Prefs.darkPages) GeckoRuntimeSettings.COLOR_SCHEME_DARK else GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM

    /** Re-applies preferences to a running runtime (after the settings screen). */
    fun applySettings() {
        val r = runtime ?: return
        val cb = r.settings.contentBlocking
        cb.setAntiTracking(if (Prefs.blockAds) strictCategories else ContentBlocking.AntiTracking.NONE)
        cb.setEnhancedTrackingProtectionLevel(if (Prefs.blockAds) ContentBlocking.EtpLevel.STRICT else ContentBlocking.EtpLevel.NONE)
        cb.setStrictSocialTrackingProtection(Prefs.blockAds)
        cb.setCookieBehavior(cookieBehavior())
        cb.setSafeBrowsing(if (Prefs.safeBrowsing) ContentBlocking.SafeBrowsing.DEFAULT else ContentBlocking.SafeBrowsing.NONE)
        r.settings.setAllowInsecureConnections(httpsMode())
        r.settings.setGlobalPrivacyControl(Prefs.sendDntGpc)
        r.settings.setPreferredColorScheme(colorScheme())
        r.settings.setFingerprintingProtection(Prefs.fingerprinting)
        applySecureDns(r.settings)
        // 2.5: autoplay mode changed (or first 2.5 start): forget Gecko's stored per-site autoplay answers.
        val autoplay = Prefs.autoplay.key
        if (Prefs.autoplayApplied != autoplay) {
            resetAutoplayPermissions()
            Prefs.autoplayApplied = autoplay
        }
        ublock?.let { ext ->
            val ctl = r.webExtensionController
            val enabled = ext.metaData.enabled
            if (Prefs.ublockEnabled && !enabled) ctl.enable(ext, WebExtensionController.EnableSource.USER).accept({ it?.let { e -> uboBridge.attach(e); ublock = e } }, {})
            if (!Prefs.ublockEnabled && enabled) ctl.disable(ext, WebExtensionController.EnableSource.USER).accept({ it?.let { e -> uboBridge.attach(e); ublock = e } }, {})
        }
    }

    /** 2.5: DNS over HTTPS via GeckoRuntimeSettings.setTrustedRecursiveResolverUri / setTrustedRecursiveResolverMode. */
    private fun applySecureDns(s: GeckoRuntimeSettings) {
        val dns = Prefs.secureDns
        if (dns == appliedDns) return
        dns.uri?.let { if (it != appliedDns?.uri) s.setTrustedRecursiveResolverUri(it) }
        if (dns.trrMode != appliedDns?.trrMode) s.setTrustedRecursiveResolverMode(dns.trrMode)
        appliedDns = dns
        Log.i(TAG, "Secure DNS: mode ${dns.trrMode}" + (dns.uri?.let { " via ${UrlUtils.host(it)}" } ?: ""))
    }

    /**
     * Resets Gecko's stored autoplay permissions (GeckoView keeps every answer of onContentPermissionRequest as a
     * permanent per-site permission) to "ask", for every site or just [siteKey], so [AutoplayPolicy] decides again.
     * Uses StorageController.getAllPermissions / setPermission(…, VALUE_PROMPT). [done] runs on the main thread.
     */
    fun resetAutoplayPermissions(siteKey: String? = null, done: () -> Unit = {}) {
        val r = runtime ?: run { done(); return }
        val sc = r.storageController
        sc.allPermissions.accept({ list ->
            var n = 0
            list.orEmpty()
                .filter { AutoplayPolicy.isAutoplay(it.permission) && it.value != ContentPermission.VALUE_PROMPT }
                .filter { siteKey == null || AutoplayPolicy.siteKey(it.uri) == siteKey }
                .forEach { runCatching { sc.setPermission(it, ContentPermission.VALUE_PROMPT); n++ } }
            Log.i(TAG, "autoplay: reset $n stored permission(s)" + (siteKey?.let { " for $it" } ?: ""))
            done()
        }, { e ->
            Log.w(TAG, "autoplay: couldn't read stored permissions", e)
            done()
        })
    }

    /**
     * Fallback only: globally allow plain http for a single page load (used if the helper extension's per-host
     * exception is unavailable). Restored as soon as that load finishes.
     */
    fun allowInsecureTemporarily(allow: Boolean) {
        runtime?.settings?.setAllowInsecureConnections(if (allow) GeckoRuntimeSettings.ALLOW_ALL else httpsMode())
    }

    /**
     * "Continue to HTTP site": exempts exactly [host] from HTTPS-Only for the rest of the session (private and
     * normal browsing are separate), via Gecko's own "https-only-load-insecure" permission set by the helper.
     * [cb] gets true when the per-host exception is in place.
     */
    fun allowInsecureForHost(host: String, private: Boolean, cb: (Boolean) -> Unit) {
        helperBridge.request(JSONObject().put("type", "allowInsecure").put("host", host).put("private", private), 3000) {
            if (it?.optBoolean("ok") != true) Log.w(TAG, "per-host HTTPS exception failed: ${it?.optString("error") ?: "timeout"}")
            cb(it?.optBoolean("ok") == true)
        }
    }

    // ------------------------------------------------------------------ uBO per-site switch

    /** uBO's per-site switch for [url] (true = filtering on). null if uBO/bridge isn't available. */
    fun uboSiteEnabled(url: String, cb: (Boolean?) -> Unit) {
        if (ublock == null || !Prefs.ublockEnabled) { cb(null); return }
        uboBridge.request(JSONObject().put("type", "getSite").put("url", url), 2500) {
            cb(if (it?.optBoolean("ok") == true) it.optBoolean("enabled", true) else null)
        }
    }

    /** Sets uBO's per-site switch (adds/removes [url]'s host in uBO's trusted-site list). */
    fun setUboSiteEnabled(url: String, enabled: Boolean, cb: (Boolean?) -> Unit = {}) {
        if (ublock == null || !Prefs.ublockEnabled) { cb(null); return }
        uboBridge.request(JSONObject().put("type", "setSite").put("url", url).put("enabled", enabled), 2500) {
            cb(if (it?.optBoolean("ok") == true) it.optBoolean("enabled", enabled) else null)
        }
    }

    // ------------------------------------------------------------------ uBlock Origin

    private fun installUblock(r: GeckoRuntime, reinstall: Boolean) {
        val ctl = r.webExtensionController
        val install = if (reinstall) ctl.installBuiltIn(UBO_LOCATION) else ctl.ensureBuiltIn(UBO_LOCATION, UBO_ID)
        install.accept({ ext ->
            if (ext == null) return@accept
            uboBridge.attach(ext)
            Log.i(TAG, "uBlock Origin ${ext.metaData.version} ready (enabled=${ext.metaData.enabled})")
            ublock = ext
            if (!ext.metaData.allowedInPrivateBrowsing) {
                ctl.setAllowedInPrivateBrowsing(ext, true).accept({ e -> e?.let { uboBridge.attach(it); ublock = it; notifyExtension(it) } }, {})
            }
            if (!Prefs.ublockEnabled) applySettings()
            notifyExtension(ext)
        }, { e ->
            ublockError = e?.message ?: e.toString()
            Log.e(TAG, "uBlock Origin install failed", e)
        })
    }

    private fun installHelper(r: GeckoRuntime, reinstall: Boolean) {
        val ctl = r.webExtensionController
        val install = if (reinstall) ctl.installBuiltIn(HELPER_LOCATION) else ctl.ensureBuiltIn(HELPER_LOCATION, HELPER_ID)
        install.accept({ ext ->
            ext ?: return@accept
            helper = ext
            helperBridge.attach(ext)
            HelperSessions.onHelperReady(ext) // Reader view + media sniffer (per-session "beast_tab")
            if (!ext.metaData.allowedInPrivateBrowsing) {
                ctl.setAllowedInPrivateBrowsing(ext, true).accept({ e -> e?.let { helper = it; helperBridge.attach(it); HelperSessions.onHelperReady(it) } }, {})
            }
        }, { e -> Log.e(TAG, "Beast helper install failed", e) })
    }

    private fun installSitePrefs(r: GeckoRuntime, reinstall: Boolean) {
        val ctl = r.webExtensionController
        val install = if (reinstall) ctl.installBuiltIn(SITEPREFS_LOCATION) else ctl.ensureBuiltIn(SITEPREFS_LOCATION, SITEPREFS_ID)
        install.accept({ ext ->
            ext ?: return@accept
            sitePrefs = ext
            sitePrefsBridge.attach(ext)
            if (!ext.metaData.allowedInPrivateBrowsing) {
                ctl.setAllowedInPrivateBrowsing(ext, true).accept({ e -> e?.let { sitePrefs = it; sitePrefsBridge.attach(it) } }, {})
            }
            Log.i(TAG, "Beast site prefs ${ext.metaData.version} ready")
        }, { e -> Log.e(TAG, "Beast site prefs install failed", e) })
    }

    private var lastZoomMap: Map<String, Int>? = null
    private var lastDark: JSONObject? = null
    /** Forced dark exceptions chosen in Private/Ghost tabs: this process only, never written to disk. */
    val sessionDarkOff = HashSet<String>()

    init {
        // The extension says hello whenever its native port (re)connects, e.g. when Gecko started it after our
        // queued syncs had timed out: push the latest state again.
        sitePrefsBridge.events += { ev ->
            if (ev.optString("type") == "hello") {
                lastZoomMap?.let { syncZoomMap(it) }
                lastDark?.let { sitePrefsBridge.request(JSONObject(it.toString()), 3000) {} }
            }
        }
    }

    /** Push the full per-host zoom map into the extension (call after DB load / edits). */
    fun syncZoomMap(map: Map<String, Int>) {
        lastZoomMap = map
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        sitePrefsBridge.request(JSONObject().put("type", "sync").put("map", o), 3000) {}
    }

    /**
     * 2.5 forced dark: tell beast-siteprefs whether to darken light pages, and where not to. [off] are the saved
     * exceptions (site_prefs.force_dark_off, kept by the extension across restarts); [sessionDarkOff] holds choices
     * made in Private/Ghost tabs, which the extension only keeps in memory. Open tabs update without a reload.
     */
    fun syncForceDark(enabled: Boolean, off: Collection<String>) {
        val msg = JSONObject().put("type", "darkSync").put("enabled", enabled)
            .put("off", JSONArray(off.toList())).put("sessionOff", JSONArray(sessionDarkOff.sorted()))
        if (msg.toString() == lastDark?.toString()) return
        lastDark = msg
        sitePrefsBridge.request(JSONObject(msg.toString()), 3000) {}
    }

    /** Tell the extension to apply [zoom] percent on tabs for [host] (registrable domain). */
    fun setPageZoom(host: String, zoom: Int, cb: (Boolean) -> Unit = {}) {
        if (host.isBlank()) { cb(false); return }
        sitePrefsBridge.request(
            JSONObject().put("type", "setZoom").put("host", host).put("zoom", zoom.coerceIn(50, 300)),
            2500,
        ) { cb(it?.optBoolean("ok") == true) }
    }

    private fun notifyExtension(ext: WebExtension) = extensionListeners.toList().forEach { it(ext) }

    fun onExtensionReady(listener: (WebExtension) -> Unit) {
        extensionListeners += listener
        ublock?.let(listener)
    }

    fun removeExtensionListener(listener: (WebExtension) -> Unit) { extensionListeners -= listener }

    // ------------------------------------------------------------------ data

    val SITE_DATA_FLAGS = StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.ALL_CACHES or
        StorageController.ClearFlags.DOM_STORAGES or StorageController.ClearFlags.AUTH_SESSIONS or
        StorageController.ClearFlags.SITE_DATA

    fun clearSiteData(context: Context, cookies: Boolean = true, cache: Boolean = true) {
        val r = runtime(context)
        var flags = 0L
        if (cookies) flags = flags or StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES or
            StorageController.ClearFlags.AUTH_SESSIONS or StorageController.ClearFlags.SITE_DATA
        if (cache) flags = flags or StorageController.ClearFlags.ALL_CACHES
        if (flags != 0L) r.storageController.clearData(flags)
    }
}
