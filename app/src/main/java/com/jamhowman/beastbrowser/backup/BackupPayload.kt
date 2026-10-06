package com.jamhowman.beastbrowser.backup

import com.jamhowman.beastbrowser.passwords.SavedLogin
import org.json.JSONArray
import org.json.JSONObject

/** A bookmark as stored in a backup. [folder] is a [com.jamhowman.beastbrowser.data.BookmarkFolder] id. */
data class BackupBookmark(val url: String, val title: String, val created: Long, val folder: String?)

data class BackupTile(val title: String, val url: String)

data class BackupArticle(
    val url: String, val title: String, val byline: String, val site: String, val excerpt: String, val html: String,
    val lang: String, val dir: String, val published: String, val savedAt: Long, val words: Int, val read: Boolean,
)

/** A setting value: Boolean or String (the only pref types in the whitelist). */
typealias BackupSettings = Map<String, Any>

/**
 * Decrypted backup contents. A null section was not included in the backup (distinct from an empty one).
 * JSON (inside the encrypted envelope, see [BackupCrypto]):
 * `{"app":"Ulfur","appVersion":"2.4.0","createdAt":…,"logins":[…],"bookmarks":[…],"speedDial":[…],
 *   "readingList":[…],"settings":{"key":value}}`
 */
data class BackupPayload(
    val appVersion: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val logins: List<SavedLogin>? = null,
    val bookmarks: List<BackupBookmark>? = null,
    val speedDial: List<BackupTile>? = null,
    val readingList: List<BackupArticle>? = null,
    val settings: BackupSettings? = null,
) {
    /** Redacted: never print logins. */
    override fun toString() = "BackupPayload(logins=${logins?.size}, bookmarks=${bookmarks?.size}, " +
        "speedDial=${speedDial?.size}, readingList=${readingList?.size}, settings=${settings?.size})"

    fun toJson(): String {
        val o = JSONObject().put("app", "Ulfur").put("appVersion", appVersion).put("createdAt", createdAt)
        logins?.let { list ->
            o.put("logins", JSONArray(list.map { l ->
                JSONObject().put("origin", l.origin).put("formActionOrigin", l.formActionOrigin).put("httpRealm", l.httpRealm)
                    .put("username", l.username).put("password", l.password)
                    .put("createdAt", l.createdAt).put("updatedAt", l.updatedAt).put("timesUsed", l.timesUsed)
            }))
        }
        bookmarks?.let { list ->
            o.put("bookmarks", JSONArray(list.map {
                JSONObject().put("url", it.url).put("title", it.title).put("created", it.created).put("folder", it.folder)
            }))
        }
        speedDial?.let { list -> o.put("speedDial", JSONArray(list.map { JSONObject().put("t", it.title).put("u", it.url) })) }
        readingList?.let { list ->
            o.put("readingList", JSONArray(list.map { a ->
                JSONObject().put("url", a.url).put("title", a.title).put("byline", a.byline).put("site", a.site)
                    .put("excerpt", a.excerpt).put("html", a.html).put("lang", a.lang).put("dir", a.dir)
                    .put("published", a.published).put("savedAt", a.savedAt).put("words", a.words).put("read", a.read)
            }))
        }
        settings?.let { s -> o.put("settings", JSONObject().apply { s.forEach { (k, v) -> put(k, v) } }) }
        return o.toString()
    }

    companion object {
        fun fromJson(text: String): BackupPayload {
            val o = JSONObject(text)
            fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotEmpty() && it != "null" }
            fun arr(k: String): List<JSONObject>? = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            return BackupPayload(
                appVersion = o.optString("appVersion"),
                createdAt = o.optLong("createdAt"),
                logins = arr("logins")?.mapNotNull { l ->
                    val origin = l.str("origin") ?: return@mapNotNull null
                    SavedLogin(
                        origin = origin, formActionOrigin = l.str("formActionOrigin"), httpRealm = l.str("httpRealm"),
                        username = l.optString("username"), password = l.optString("password"),
                        createdAt = l.optLong("createdAt"), updatedAt = l.optLong("updatedAt"), timesUsed = l.optInt("timesUsed"),
                    )
                },
                bookmarks = arr("bookmarks")?.mapNotNull { b ->
                    BackupBookmark(b.str("url") ?: return@mapNotNull null, b.optString("title"), b.optLong("created"), b.str("folder"))
                },
                speedDial = arr("speedDial")?.mapNotNull { t -> BackupTile(t.optString("t"), t.str("u") ?: return@mapNotNull null) },
                readingList = arr("readingList")?.mapNotNull { a ->
                    BackupArticle(
                        a.str("url") ?: return@mapNotNull null, a.optString("title"), a.optString("byline"), a.optString("site"),
                        a.optString("excerpt"), a.optString("html"), a.optString("lang"), a.optString("dir"),
                        a.optString("published"), a.optLong("savedAt"), a.optInt("words"), a.optBoolean("read"),
                    )
                },
                settings = o.optJSONObject("settings")?.let { s ->
                    s.keys().asSequence().mapNotNull { k ->
                        when (val v = s.opt(k)) { is Boolean -> k to v; is String -> k to v; else -> null }
                    }.toMap()
                },
            )
        }
    }
}
