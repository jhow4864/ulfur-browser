package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.browser.AutoplayPolicy
import com.jamhowman.beastbrowser.browser.AutoplayPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import java.io.File

/** 2.5 autoplay blocker: mode mapping, per-site exceptions, and non-sticky blocks. */
class AutoplayPolicyTest {
    private val audible = PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE
    private val inaudible = PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
    private val allow = ContentPermission.VALUE_ALLOW

    @Test fun defaultIsBlockAudibleLikeBefore() {
        assertEquals(Mode.BLOCK_AUDIBLE, Mode.from(null))
        assertEquals(Mode.BLOCK_AUDIBLE, Mode.from("nonsense"))
        assertEquals(allow, AutoplayPolicy.decide(Mode.BLOCK_AUDIBLE, inaudible))
        assertEquals(AutoplayPolicy.BLOCK, AutoplayPolicy.decide(Mode.BLOCK_AUDIBLE, audible))
    }

    @Test fun allowAllAllowsBoth() {
        assertEquals(allow, AutoplayPolicy.decide(Mode.ALLOW_ALL, audible))
        assertEquals(allow, AutoplayPolicy.decide(Mode.ALLOW_ALL, inaudible))
    }

    @Test fun blockAllBlocksBoth() {
        assertEquals(AutoplayPolicy.BLOCK, AutoplayPolicy.decide(Mode.BLOCK_ALL, audible))
        assertEquals(AutoplayPolicy.BLOCK, AutoplayPolicy.decide(Mode.BLOCK_ALL, inaudible))
    }

    @Test fun perSiteValueOverridesTheGlobalOne() {
        assertEquals(Mode.ALLOW_ALL, AutoplayPolicy.effective(Mode.BLOCK_ALL, Mode.ALLOW_ALL))
        assertEquals(Mode.BLOCK_ALL, AutoplayPolicy.effective(Mode.ALLOW_ALL, Mode.BLOCK_ALL))
        for (g in Mode.entries) assertEquals(g, AutoplayPolicy.effective(g, null))
        assertEquals(Mode.ALLOW_ALL, AutoplayPolicy.siteMode("allow_all"))
        assertEquals(null, AutoplayPolicy.siteMode(null))
        assertEquals(null, AutoplayPolicy.siteMode("garbage")) // unknown stored value = follow Settings
        assertEquals(allow, AutoplayPolicy.decide(AutoplayPolicy.effective(Mode.BLOCK_ALL, Mode.ALLOW_ALL), audible))
    }

    @Test fun blocksAreNotStoredAsPermanentDenials() {
        // GeckoView stores the answer EXPIRE_NEVER; PROMPT means "ask again", DENY would stick to the site.
        assertEquals(ContentPermission.VALUE_PROMPT, AutoplayPolicy.BLOCK)
        assertTrue(AutoplayPolicy.BLOCK != ContentPermission.VALUE_DENY)
    }

    @Test fun onlyAutoplayPermissionsAreRecognised() {
        assertTrue(AutoplayPolicy.isAutoplay(audible))
        assertTrue(AutoplayPolicy.isAutoplay(inaudible))
        assertFalse(AutoplayPolicy.isAutoplay(PermissionDelegate.PERMISSION_GEOLOCATION))
        assertFalse(AutoplayPolicy.isAutoplay(PermissionDelegate.PERMISSION_TRACKING))
        assertFalse(AutoplayPolicy.isAutoplay(PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS))
    }

    @Test fun siteKeyIsTheRegistrableDomain() {
        assertEquals("youtube.com", AutoplayPolicy.siteKey("https://www.youtube.com/watch?v=x"))
        assertEquals("youtube.com", AutoplayPolicy.siteKey("https://m.youtube.com/"))
        assertEquals("bbc.co.uk", AutoplayPolicy.siteKey("https://www.bbc.co.uk/iplayer"))
        assertEquals("example.com", AutoplayPolicy.siteKey("http://example.com:8080/a"))
        assertEquals("", AutoplayPolicy.siteKey("about:blank"))
        assertEquals("", AutoplayPolicy.siteKey(null))
        assertEquals("", AutoplayPolicy.siteKey("not a url"))
    }

    @Test fun siteSwitchOnlyOnWebPagesWhenSomethingIsBlocked() {
        assertTrue(AutoplayPolicy.siteSwitchShown(Mode.BLOCK_AUDIBLE, "https://www.youtube.com/"))
        assertTrue(AutoplayPolicy.siteSwitchShown(Mode.BLOCK_ALL, "http://example.com/"))
        assertFalse(AutoplayPolicy.siteSwitchShown(Mode.ALLOW_ALL, "https://www.youtube.com/"))
        assertFalse(AutoplayPolicy.siteSwitchShown(Mode.BLOCK_ALL, "beast://home"))
        assertFalse(AutoplayPolicy.siteSwitchShown(Mode.BLOCK_ALL, "moz-extension://abc/reader.html"))
        assertFalse(AutoplayPolicy.siteSwitchShown(Mode.BLOCK_ALL, null))
    }

    @Test fun settingsKeysAndDefaultMatchTheCode() {
        val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
        val arrays = File(main, "res/values/arrays.xml").readText()
        val keys = Regex("<string-array name=\"autoplay_keys\">(.*?)</string-array>", RegexOption.DOT_MATCHES_ALL)
            .find(arrays)!!.groupValues[1].let { Regex("<item>([^<]*)</item>").findAll(it).map { m -> m.groupValues[1] }.toList() }
        assertEquals(Mode.entries.map { it.key }, keys)
        val prefs = File(main, "res/xml/preferences_site_content.xml").readText()
        assertTrue(prefs.contains("app:key=\"autoplay\" app:defaultValue=\"${Mode.BLOCK_AUDIBLE.key}\""))
    }
}
