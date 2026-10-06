package com.jamhowman.beastbrowser.reader

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.jamhowman.beastbrowser.browser.HelperSessions
import com.jamhowman.beastbrowser.data.Prefs
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.WeakHashMap
import kotlin.concurrent.thread

/**
 * Reader view: Beast Helper's content script reports whether a page is "probably readerable" (Readability's
 * isProbablyReaderable). [open] then extracts the article in the page and shows it in Beast Helper's own
 * `reader/reader.html` extension page, which pulls the article from here via native messaging.
 *
 * Nothing is stored unless the user taps Save (also in private tabs). Live articles are in memory only.
 */
object ReaderMode {
    const val ACTION_OPEN_SAVED = "com.jamhowman.beastbrowser.action.OPEN_SAVED_ARTICLE"
    const val EXTRA_ARTICLE_ID = "article_id"
    private const val READER_PATH = "reader/reader.html"
    private const val MAX_LIVE = 8

    interface Host {
        /** The reader page's close button: go back to the article (or load [originalUrl]). */
        fun closeReader(session: GeckoSession, originalUrl: String?)
        fun onReadingListChanged() {}
    }

    var host: Host? = null

    private class Live(val article: JSONObject, val isPrivate: Boolean, val session: WeakReference<GeckoSession>, var savedId: Long? = null)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private val prefs: SharedPreferences by lazy { app.getSharedPreferences("beast_reader", Context.MODE_PRIVATE) }
    private val readerable = WeakHashMap<GeckoSession, String>()          // session -> url that is readerable
    private val listeners = mutableListOf<(GeckoSession) -> Unit>()
    private val live = object : LinkedHashMap<String, Live>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Live>?) = size > MAX_LIVE
    }

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }

    fun db(): ReadingListDb = ReadingListDb.get(app)

    // ------------------------------------------------------------------ readerable state

    fun addListener(l: (GeckoSession) -> Unit) { listeners += l }
    fun removeListener(l: (GeckoSession) -> Unit) { listeners -= l }

    fun setReaderable(session: GeckoSession, value: Boolean, url: String) {
        val changed = if (value) readerable.put(session, url) != url else readerable.remove(session) != null
        if (changed) listeners.toList().forEach { runCatching { it(session) } }
    }

    fun isReaderable(session: GeckoSession) = readerable.containsKey(session)

    /** Drop everything held for a closed tab. */
    fun forget(session: GeckoSession) {
        readerable.remove(session)
        live.entries.removeAll { it.value.session.get().let { s -> s == null || s === session } }
    }

    // ------------------------------------------------------------------ URLs

    fun isReaderUrl(url: String?): Boolean =
        url != null && url.startsWith("moz-extension://") && url.substringBefore('#').endsWith("/$READER_PATH")

    /** The article's original page for a reader URL, else null. */
    fun originalUrl(url: String?): String? {
        if (!isReaderUrl(url)) return null
        return fragmentParam(url!!, "url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    internal fun fragmentParam(url: String, name: String): String? =
        url.substringAfter('#', "").split('&').firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }

    internal fun readerUrl(base: String, key: String, value: String, original: String) =
        "$base$READER_PATH#$key=${URLEncoder.encode(value, "UTF-8")}&url=${URLEncoder.encode(original, "UTF-8")}"

    // ------------------------------------------------------------------ open

    /** Extracts the current page of [session] and shows it in Reader view. [cb] gets an error message or null. */
    fun open(session: GeckoSession, cb: (String?) -> Unit) {
        val base = HelperSessions.baseUrl
        if (base == null || !HelperSessions.hasPage(session)) { cb("Reader view isn't ready on this page yet"); return }
        HelperSessions.request(session, JSONObject().put("type", "extract"), 15_000) { r ->
            val a = r?.optJSONObject("article")
            if (r?.optBoolean("ok") != true || a == null) { cb(r?.optString("error")?.ifBlank { null } ?: "Couldn't create Reader view"); return@request }
            val id = UUID.randomUUID().toString().substring(0, 13)
            live[id] = Live(a, HelperSessions.isPrivate(session), WeakReference(session))
            session.loadUri(readerUrl(base, "id", id, a.optString("url")))
            cb(null)
        }
    }

    /** Reader URL for a saved article (marks it read), or null if the helper isn't ready / the article is gone. */
    fun savedUrl(savedId: Long): String? {
        val base = HelperSessions.baseUrl ?: return null
        val a = db().get(savedId) ?: return null
        thread { runCatching { db().setRead(savedId, true) } }
        return readerUrl(base, "saved", savedId.toString(), a.url)
    }

    // ------------------------------------------------------------------ reader page messages

    /**
     * A reader page request, from either route: the page's own `runtime.sendNativeMessage("beast_tab")`
     * ([session] is the tab) or the background relay ([handleRelayed], [session] null). Without a session the
     * tab is found through the live article id, when there is one.
     */
    fun handlePageMessage(session: GeckoSession?, o: JSONObject): GeckoResult<Any>? {
        val l = live[o.optString("id")]
        val s = session ?: l?.session?.get()
        return when (o.optString("type")) {
            "getArticle" -> getArticle(s, l, o)
            "readerPrefs" -> {
                o.optJSONObject("prefs")?.let { p ->
                    prefs.edit().putInt("size", p.optInt("size", 100).coerceIn(70, 200))
                        .putString("font", if (p.optString("font") == "serif") "serif" else "sans")
                        .putString("theme", p.optString("theme").takeIf { it in THEMES } ?: "dark").apply()
                }
                GeckoResult.fromValue(JSONObject().put("ok", true))
            }
            "saveArticle" -> saveArticle(o)
            "readerClose" -> {
                val url = o.optString("url").takeIf { it.startsWith("http") }
                if (s == null) GeckoResult.fromValue(JSONObject().put("ok", false))   // page falls back to history.back()
                else {
                    main.post { host?.closeReader(s, url) }
                    GeckoResult.fromValue(JSONObject().put("ok", true))
                }
            }
            else -> null
        }
    }

    /**
     * Reader page request relayed by Beast Helper's background script over the `beast_helper` port:
     * `{type:"readerRelay", rid, msg, private}`. [send] gets `{type:"readerReply", rid, reply}` or
     * `{type:"readerReply", rid, error}` exactly once, on the main thread.
     */
    fun handleRelayed(o: JSONObject, send: (JSONObject) -> Unit) {
        val rid = o.optInt("rid", 0)
        if (rid == 0) return
        val msg = o.optJSONObject("msg") ?: JSONObject()
        val out = { k: String, v: Any -> main.post { send(JSONObject().put("type", "readerReply").put("rid", rid).put(k, v)) } }
        val result = try {
            handlePageMessage(null, msg)
        } catch (e: Exception) {
            out("error", e.message ?: "Reader request failed"); return
        }
        if (result == null) { out("error", "unknown type ${msg.optString("type")}"); return }
        result.accept({ v -> out("reply", v ?: JSONObject.NULL) }, { e -> out("error", e?.message ?: "Reader request failed") })
    }

    private val THEMES = setOf("dark", "sepia", "light")

    private fun baseReply(session: GeckoSession?, live: Live? = null) = JSONObject()
        .put("prefs", JSONObject()
            .put("size", prefs.getInt("size", 100))
            .put("font", prefs.getString("font", "sans"))
            .put("theme", prefs.getString("theme", "dark")))
        .put("accent", "#%06X".format(Prefs.accent.color and 0xFFFFFF))
        .put("onAccent", "#%06X".format(Prefs.accent.onColor and 0xFFFFFF))
        .put("private", HelperSessions.isPrivate(session) || live?.isPrivate == true)

    private fun getArticle(session: GeckoSession?, l: Live?, o: JSONObject): GeckoResult<Any> {
        val reply = baseReply(session, l)
        val savedId = o.optString("saved").toLongOrNull()
        if (savedId != null) {
            val result = GeckoResult<Any>()
            thread {
                val a = runCatching { db().get(savedId) }.getOrNull()
                if (a == null) result.complete(reply.put("ok", false))
                else result.complete(reply.put("ok", true).put("saved", true).put("article", toJson(a)))
            }
            return result
        }
        if (l == null) return GeckoResult.fromValue(reply.put("ok", false))
        val alreadySaved = l.savedId != null || (!l.isPrivate && runCatching { db().idForUrl(l.article.optString("url")) }.getOrNull() != null)
        return GeckoResult.fromValue(reply.put("ok", true).put("saved", alreadySaved).put("article", l.article))
    }

    /** Explicit user tap only. Private tabs too, but the article then outlives the private session (we say so). */
    private fun saveArticle(o: JSONObject): GeckoResult<Any> {
        if (o.optString("saved").isNotBlank()) return GeckoResult.fromValue(JSONObject().put("ok", true).put("saved", true))
        val l = live[o.optString("id")] ?: return GeckoResult.fromValue(JSONObject().put("ok", false).put("error", "This article is no longer available"))
        val result = GeckoResult<Any>()
        val article = fromJson(l.article)
        thread {
            val id = runCatching { db().save(article) }.getOrNull()
            if (id == null) { result.complete(JSONObject().put("ok", false).put("error", "Couldn't save")); return@thread }
            main.post { l.savedId = id; host?.onReadingListChanged() }
            result.complete(JSONObject().put("ok", true).put("saved", true).put("savedId", id)
                .put("note", if (l.isPrivate) "Saved. Reading list items stay after private browsing ends" else ""))
        }
        return result
    }

    internal fun fromJson(a: JSONObject, now: Long = System.currentTimeMillis()) = SavedArticle(
        id = 0,
        url = a.optString("url"),
        title = a.optString("title").ifBlank { a.optString("url") },
        byline = a.optString("byline"),
        site = a.optString("siteName"),
        excerpt = a.optString("excerpt"),
        html = a.optString("content"),
        lang = a.optString("lang"),
        dir = a.optString("dir"),
        published = a.optString("publishedTime"),
        savedAt = now,
        words = (a.optInt("length", 0) / 6).coerceAtLeast(0),
        read = false,
    )

    internal fun toJson(a: SavedArticle) = JSONObject()
        .put("url", a.url).put("title", a.title).put("byline", a.byline).put("siteName", a.site)
        .put("excerpt", a.excerpt).put("content", a.html).put("lang", a.lang).put("dir", a.dir)
        .put("publishedTime", a.published).put("words", a.words)
}
