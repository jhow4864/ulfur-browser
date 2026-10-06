package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.backup.BackupManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 2.7: "Hide cookie banners" wires the app switch to uBO's stock cookie-notice lists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CookieBannersTest {
    private fun file(rel: String) = listOf("src/main", "app/src/main").map { File(it, rel) }.first { it.exists() }
    private val lists = listOf("fanboy-cookiemonster", "ublock-cookies-easylist")

    @Test fun bridgeTogglesStockCookieLists() {
        val assets = JSONObject(file("assets/extensions/ublock/assets/assets.json").readText())
        lists.forEach { k ->
            assertTrue("$k missing from uBO assets", assets.has(k))
            assertEquals("cookies", assets.getJSONObject(k).getString("group2"))
        }
        val bridge = file("assets/extensions/ublock/js/beast-bridge.js").readText()
        lists.forEach { assertTrue(it, bridge.contains("'$it'")) }
        listOf("case 'setCookieLists'", "case 'getCookieLists'", "µb.isReadyPromise", "µb.loadFilterLists()")
            .forEach { assertTrue(it, bridge.contains(it)) }
    }

    @Test fun shieldsSheetHasPerSiteSwitchOnUboCosmeticFiltering() {
        val bridge = file("assets/extensions/ublock/js/beast-bridge.js").readText()
        listOf("case 'getCosmetic'", "case 'setCosmetic'", "'no-cosmetic-filtering'", "persist: true", "µb.toggleHostnameSwitch")
            .forEach { assertTrue(it, bridge.contains(it)) }
        val sheet = file("res/layout/sheet_shields.xml").readText()
        assertTrue(sheet.contains("@+id/cookieSwitch"))
        assertTrue(sheet.contains("@+id/cookieState"))
        assertTrue(sheet.indexOf("@+id/cookieSwitch") > sheet.indexOf("@+id/shieldsSwitch"))
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("Hide cookie banners on this site", app.getString(R.string.shields_cookies_site))
        assertEquals("Cookie banners shown on bbc.co.uk", app.getString(R.string.shields_cookies_shown, "bbc.co.uk"))
    }

    @Test fun settingIsOnByDefaultUnderShieldsAndBackedUp() {
        val xml = file("res/xml/preferences.xml").readText()
        val row = Regex("""<SwitchPreferenceCompat app:key="cookie_banners"[^>]*/>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.value
        assertTrue("cookie_banners switch missing", row != null)
        assertTrue(row!!.contains("""app:defaultValue="true""""))
        assertTrue(row.contains("""app:dependency="ublock""""))
        assertTrue(xml.indexOf("cookie_banners") < xml.indexOf("Privacy &amp; security"))
        assertEquals(java.lang.Boolean::class.java, BackupManager.SETTINGS["cookie_banners"])
    }
}
