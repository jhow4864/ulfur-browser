package com.jamhowman.beastbrowser.passwords

import org.json.JSONObject
import org.mozilla.geckoview.Autocomplete
import java.util.UUID

/** One saved login. [password] is only non-blank while the vault is unlocked in memory. */
data class SavedLogin(
    val guid: String = UUID.randomUUID().toString(),
    val origin: String,
    val formActionOrigin: String? = null,
    val httpRealm: String? = null,
    val username: String,
    val password: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val timesUsed: Int = 0,
) {
    /** Redacted: the generated data-class toString() would print the password. */
    override fun toString(): String =
        "SavedLogin(guid=$guid, origin=$origin, username=${if (username.isEmpty()) "" else "<redacted>"}, password=<redacted>)"

    fun toGecko(): Autocomplete.LoginEntry = Autocomplete.LoginEntry.Builder()
        .guid(guid)
        .origin(origin)
        .formActionOrigin(formActionOrigin)
        .httpRealm(httpRealm)
        .username(username)
        .password(password)
        .build()

    fun toMetaJson(): JSONObject = JSONObject()
        .put("guid", guid)
        .put("origin", origin)
        .put("formActionOrigin", formActionOrigin)
        .put("httpRealm", httpRealm)
        .put("username", username)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("timesUsed", timesUsed)

    companion object {
        fun fromGecko(e: Autocomplete.LoginEntry) = SavedLogin(
            guid = e.guid?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            origin = e.origin.orEmpty(),
            formActionOrigin = e.formActionOrigin,
            httpRealm = e.httpRealm,
            username = e.username.orEmpty(),
            password = e.password.orEmpty(),
        )

        fun fromMetaJson(o: JSONObject, password: String) = SavedLogin(
            guid = o.getString("guid"),
            origin = o.getString("origin"),
            formActionOrigin = o.optString("formActionOrigin").takeIf { it.isNotEmpty() && it != "null" },
            httpRealm = o.optString("httpRealm").takeIf { it.isNotEmpty() && it != "null" },
            username = o.optString("username"),
            password = password,
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
            timesUsed = o.optInt("timesUsed"),
        )
    }
}
