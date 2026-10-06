package com.jamhowman.beastbrowser.browser

import com.jamhowman.beastbrowser.util.Domains
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission

/**
 * 2.5: autoplay blocker, answered through GeckoView's PermissionDelegate.onContentPermissionRequest
 * (Gecko asks for PERMISSION_AUTOPLAY_AUDIBLE / _INAUDIBLE because GeckoView sets media.geckoview.autoplay.request).
 *
 * GeckoView stores whatever the app answers as a permanent per-site permission. Blocks are therefore answered with
 * VALUE_PROMPT ("no decision": playback is refused this time and Gecko asks again next time) so a block never sticks
 * to a site; allows are stored by Gecko and cleared again ([Engine.resetAutoplayPermissions]) whenever the mode or a
 * site exception changes.
 */
object AutoplayPolicy {
    enum class Mode(val key: String) {
        BLOCK_ALL("block_all"),
        /** Default: muted autoplay (background videos, GIF-style clips) is fine, sound needs a tap. */
        BLOCK_AUDIBLE("block_audible"),
        ALLOW_ALL("allow_all");

        companion object {
            fun from(key: String?): Mode = entries.firstOrNull { it.key == key } ?: BLOCK_AUDIBLE
        }
    }

    /** What a refusal is answered with (see the class comment). */
    const val BLOCK = ContentPermission.VALUE_PROMPT
    const val ALLOW = ContentPermission.VALUE_ALLOW

    fun isAutoplay(permission: Int) =
        permission == PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE || permission == PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE

    /** Per-site override if there is one, otherwise the global setting. */
    fun effective(global: Mode, site: Mode?): Mode = site ?: global

    /** A stored per-site value (DB `site_prefs.autoplay`); unknown strings are ignored (= global). */
    fun siteMode(key: String?): Mode? = Mode.entries.firstOrNull { it.key == key }

    /**
     * Answer for an autoplay request under [mode] (already [effective]): inaudible autoplay is allowed unless
     * everything is blocked; audible autoplay only when everything is allowed.
     */
    fun decide(mode: Mode, permission: Int): Int {
        val audible = permission == PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE
        val allow = when (mode) {
            Mode.ALLOW_ALL -> true
            Mode.BLOCK_AUDIBLE -> !audible
            Mode.BLOCK_ALL -> false
        }
        return if (allow) ALLOW else BLOCK
    }

    /** Site key for exceptions: the registrable domain of the page's top-level URI ("" if none). */
    fun siteKey(uri: String?): String {
        val host = try { uri?.let { java.net.URI(it).host } } catch (_: Exception) { null }
        return Domains.registrable(host)
    }

    /** The per-site switch only matters when something is blocked globally. */
    fun siteSwitchShown(mode: Mode, pageUrl: String?): Boolean =
        mode != Mode.ALLOW_ALL && (pageUrl?.startsWith("http://") == true || pageUrl?.startsWith("https://") == true) &&
            siteKey(pageUrl).isNotEmpty()
}
