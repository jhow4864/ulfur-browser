package com.jamhowman.beastbrowser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Beast additions to the built-in extensions are wired correctly. (Robolectric only for a real org.json.) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExtensionAssetsTest {
    private val ext = listOf("src/main/assets/extensions", "app/src/main/assets/extensions").map(::File).first { it.isDirectory }

    @Test fun uboBridgeIsLoadedAndPermitted() {
        val ubo = File(ext, "ublock")
        assertTrue(File(ubo, "background.html").readText().contains("js/beast-bridge.js"))
        val bridge = File(ubo, "js/beast-bridge.js").readText()
        assertTrue(bridge.contains("connectNative('beast_ubo')") || bridge.contains("connectNative(NATIVE_APP)"))
        assertTrue(bridge.contains("toggleNetFilteringSwitch"))
        val perms = JSONObject(File(ubo, "manifest.json").readText()).getJSONArray("permissions").toString()
        assertTrue(perms.contains("nativeMessaging"))
    }

    @Test fun helperDeclaresExperimentApi() {
        val dir = File(ext, "beast-helper")
        val m = JSONObject(File(dir, "manifest.json").readText())
        assertEquals("beast-helper@jamhowman.com", m.getJSONObject("browser_specific_settings").getJSONObject("gecko").getString("id"))
        val api = m.getJSONObject("experiment_apis").getJSONObject("beastHttps")
        assertTrue(File(dir, api.getString("schema")).isFile)
        assertTrue(File(dir, api.getJSONObject("parent").getString("script")).isFile)
        val schema = JSONArray(File(dir, "schema.json").readText()).getJSONObject(0)
        assertEquals("beastHttps", schema.getString("namespace"))
        assertTrue(File(dir, "api.js").readText().contains("https-only-load-insecure"))
    }

    @Test fun sitePrefsExtensionWired() {
        val dir = File(ext, "beast-siteprefs")
        val m = JSONObject(File(dir, "manifest.json").readText())
        assertEquals("beast-siteprefs@jamhowman.com", m.getJSONObject("browser_specific_settings").getJSONObject("gecko").getString("id"))
        assertTrue(File(dir, "background.js").readText().contains("beast_siteprefs"))
        assertTrue(File(dir, "content.js").readText().contains("style.zoom"))
        val perms = m.getJSONArray("permissions").toString()
        assertTrue(perms.contains("nativeMessaging"))
    }
}
