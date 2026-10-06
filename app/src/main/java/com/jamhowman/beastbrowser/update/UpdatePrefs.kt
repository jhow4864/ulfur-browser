package com.jamhowman.beastbrowser.update

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Updater state in its own prefs file (kept separate from Prefs/beast_prefs). */
class UpdatePrefs private constructor(private val sp: SharedPreferences) {

    var lastCheck: Long
        get() = sp.getLong("last_check", 0)
        set(v) = sp.edit { putLong("last_check", v) }

    /** Tag the user chose "Skip this version" for. */
    var skippedTag: String?
        get() = sp.getString("skipped_tag", null)
        set(v) = sp.edit { putString("skipped_tag", v) }

    /** Set while an update is mid-flight (e.g. user sent to the "install unknown apps" screen),
     *  so the next launch re-checks even inside the 24h window. */
    var pendingTag: String?
        get() = sp.getString("pending_tag", null)
        set(v) = sp.edit { putString("pending_tag", v) }

    fun clearPending() = sp.edit { remove("pending_tag") }

    companion object {
        private const val FILE = "beast_updater"
        fun get(context: Context) = UpdatePrefs(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
