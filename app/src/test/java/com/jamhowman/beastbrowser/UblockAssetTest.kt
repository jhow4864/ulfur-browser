package com.jamhowman.beastbrowser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Sanity checks on the bundled uBlock Origin built-in extension. */
class UblockAssetTest {
    private val dir = listOf("src/main/assets/extensions/ublock", "app/src/main/assets/extensions/ublock")
        .map(::File).first { it.isDirectory }

    @Test fun manifestIsUblockOrigin() {
        val text = File(dir, "manifest.json").readText()
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(text)!!.groupValues[1]
        assertEquals("uBlock0@raymondhill.net", id)
        assertTrue(text.contains("\"webRequestBlocking\""))
        assertTrue(File(dir, "background.html").isFile)
        assertTrue(File(dir, "popup-fenix.html").isFile)
    }

    @Test fun localesAndDefaultListsArePresent() {
        assertTrue("_locales/en/messages.json missing", File(dir, "_locales/en/messages.json").isFile)
        assertTrue(File(dir, "assets/assets.json").isFile)
        assertTrue(File(dir, "assets/thirdparties/easylist/easylist.txt").length() > 500_000)
        assertTrue(File(dir, "assets/thirdparties/easylist/easyprivacy.txt").length() > 500_000)
        assertTrue(File(dir, "assets/ublock/filters.min.txt").isFile || File(dir, "assets/ublock").isDirectory)
    }
}
