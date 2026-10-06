package com.jamhowman.beastbrowser.data

import androidx.annotation.DrawableRes
import com.jamhowman.beastbrowser.R

/**
 * Browsing identities (2.3.8). Each realm has its own Gecko cookie jar ([contextId]; null = Gecko's
 * default jar, so Play keeps the logins from before Realms existed), its own tab list and its own accent.
 * Shared across realms: uBlock filters, bookmarks, history (not Ghost), passwords, downloads.
 *
 * [key] is persisted (pref `realm` and the `_work` / `_ghost` session-key suffixes); don't change it.
 */
enum class Realm(
    val key: String,
    val label: String,
    val contextId: String?,
    /** Ghost: every tab is a private session and nothing is persisted. */
    val alwaysPrivate: Boolean,
    @DrawableRes val sealIcon: Int,
) {
    WORK("work", "Work", "beast-realm-work", false, R.drawable.ic_dashboard),
    PLAY("play", "Play", null, false, R.drawable.ic_play),
    GHOST("ghost", "Ghost", "beast-realm-ghost", true, R.drawable.ic_incognito);

    val persistsTabs: Boolean get() = !alwaysPrivate

    companion object {
        fun from(key: String?): Realm = entries.firstOrNull { it.key == key } ?: PLAY
    }
}
