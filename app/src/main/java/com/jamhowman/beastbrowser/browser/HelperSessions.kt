package com.jamhowman.beastbrowser.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jamhowman.beastbrowser.media.DetectedMedia
import com.jamhowman.beastbrowser.media.MediaSniffer
import com.jamhowman.beastbrowser.reader.ReaderMode
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap

/**
 * Per-session channel to Beast Helper's content script and reader page (native app "beast_tab").
 *
 * Content script → app (port messages, one port per tab/page):
 *  - `{type:"readerable", value, url}`  → [ReaderMode.setReaderable]
 *  - `{type:"media", items:[{url,mime,quality,w,h,bytes,kind,bandwidth?,codecs?,master?}], pageUrl, title}`
 *     → [MediaSniffer.publish] (full list each time; ignored after DRM was seen on the page)
 *  - `{type:"drm"}` → [markDrm]
 * App → content script: [request] `{id, type:"extract"}` → `{id, ok, article}`.
 * Reader page → app (one-off `runtime.sendNativeMessage`): handled by [ReaderMode.handlePageMessage].
 * Everything runs on the main thread.
 */
object HelperSessions : WebExtension.MessageDelegate {
    const val NATIVE_APP = "beast_tab"
    private const val TAG = "BeastHelperTabs"

    private val main = Handler(Looper.getMainLooper())
    private val sessions = WeakHashMap<GeckoSession, Boolean>()           // session -> isPrivate
    private val ports = WeakHashMap<GeckoSession, WebExtension.Port>()
    private val drm: MutableSet<GeckoSession> = Collections.newSetFromMap(WeakHashMap())
    private val lastUrl = WeakHashMap<GeckoSession, String>()
    private val pending = HashMap<Int, (JSONObject?) -> Unit>()
    private var nextId = 1

    var extension: WebExtension? = null
        private set

    /** `moz-extension://<uuid>/` of Beast Helper, once installed. */
    val baseUrl: String? get() = extension?.metaData?.baseUrl

    fun isPrivate(session: GeckoSession?): Boolean = session != null && sessions[session] == true

    /** Call for every tab session (before or after the helper is ready). */
    fun register(session: GeckoSession, isPrivate: Boolean) {
        sessions[session] = isPrivate
        extension?.let { attach(session, it) }
    }

    /** Engine calls this whenever the helper extension object is (re)created. */
    fun onHelperReady(ext: WebExtension) {
        extension = ext
        ext.setMessageDelegate(this, NATIVE_APP)        // extension pages without a session-level delegate
        sessions.keys.toList().forEach { attach(it, ext) }
    }

    private fun attach(session: GeckoSession, ext: WebExtension) {
        runCatching { session.webExtensionController.setMessageDelegate(ext, this, NATIVE_APP) }
            .onFailure { Log.w(TAG, "session delegate failed", it) }
    }

    fun isDrm(session: GeckoSession) = session in drm

    /** The page uses EME / DRM: hide and stop accepting detected media until the next navigation. */
    fun markDrm(session: GeckoSession) {
        drm += session
        MediaSniffer.clear(session)
    }

    /** From TabCallbacks.onLocationChange: per-page state resets on a real navigation (not a #hash change). */
    fun onLocationChange(session: GeckoSession, url: String) {
        val key = url.substringBefore('#')
        if (lastUrl.put(session, key) == key) return
        drm -= session
        ReaderMode.setReaderable(session, false, url)
    }

    // ------------------------------------------------------------------ ports (content scripts)

    override fun onConnect(port: WebExtension.Port) {
        val session = port.sender.session ?: return
        ports[session] = port
        port.setDelegate(object : WebExtension.PortDelegate {
            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                val o = message as? JSONObject ?: return
                handle(port.sender.session ?: return, o)
            }

            override fun onDisconnect(port: WebExtension.Port) {
                val s = port.sender.session ?: return
                if (ports[s] === port) ports.remove(s)
            }
        })
    }

    override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
        val o = message as? JSONObject ?: return null
        return ReaderMode.handlePageMessage(sender.session, o)
    }

    internal fun handle(session: GeckoSession, o: JSONObject) {
        val id = o.optInt("id", 0)
        if (id != 0) { pending.remove(id)?.invoke(o); return }
        when (o.optString("type")) {
            "readerable" -> ReaderMode.setReaderable(session, o.optBoolean("value"), o.optString("url"))
            "media" -> if (session !in drm) MediaSniffer.publish(session, parseMedia(o))
            "drm" -> markDrm(session)
        }
    }

    /** Sends [msg] to the content script of [session]'s current page; [cb] gets the reply or null (timeout / no page). */
    fun request(session: GeckoSession, msg: JSONObject, timeoutMs: Long = 10_000, cb: (JSONObject?) -> Unit) {
        val port = ports[session]
        if (port == null) { main.post { cb(null) }; return }
        val id = nextId++
        msg.put("id", id)
        pending[id] = cb
        runCatching { port.postMessage(msg) }.onFailure { pending.remove(id); main.post { cb(null) }; return }
        main.postDelayed({ pending.remove(id)?.invoke(null) }, timeoutMs)
    }

    fun hasPage(session: GeckoSession) = ports[session] != null

    // ------------------------------------------------------------------ media JSON -> DetectedMedia

    /** Maps the extension's media message to Jamh's [DetectedMedia] (unknown/non-http entries dropped). */
    internal fun parseMedia(o: JSONObject): List<DetectedMedia> {
        val arr: JSONArray = o.optJSONArray("items") ?: return emptyList()
        val pageUrl = o.optString("pageUrl")
        val title = o.optString("title")
        val out = ArrayList<DetectedMedia>(arr.length())
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val url = it.optString("url")
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue
            val hls = it.optString("kind") == "hls"
            out += DetectedMedia(
                id = stableId(url),
                url = url,
                mime = it.optString("mime").ifBlank { if (hls) "application/vnd.apple.mpegurl" else "video/mp4" },
                qualityLabel = it.optString("quality").ifBlank { if (hls) "HLS" else "Video" },
                width = it.optInt("w", 0).coerceAtLeast(0),
                height = it.optInt("h", 0).coerceAtLeast(0),
                bytes = it.optLong("bytes", -1L).let { b -> if (b > 0) b else -1L },
                kind = if (hls) DetectedMedia.Kind.HLS else DetectedMedia.Kind.PROGRESSIVE,
                pageUrl = pageUrl,
                title = title,
                bandwidth = it.optLong("bandwidth", -1L).let { b -> if (b > 0) b else -1L },
                masterUrl = it.optString("master").takeIf { m -> m.startsWith("http") },
                codecs = it.optString("codecs"),
            )
        }
        return out
    }

    internal fun stableId(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}
