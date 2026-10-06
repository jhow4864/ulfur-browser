package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.update.ReleaseAsset
import com.jamhowman.beastbrowser.update.SemVer
import com.jamhowman.beastbrowser.update.UpdateLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    @Test fun parsesTags() {
        assertEquals(listOf(2, 4, 1), SemVer.parse("v2.4.1")!!.parts)
        assertEquals(listOf(2, 4, 1), SemVer.parse("2.4.1")!!.parts)
        assertEquals("beta.2", SemVer.parse("V2.4.1-beta.2+build5")!!.pre)
        assertNull(SemVer.parse("latest"))
        assertNull(SemVer.parse(""))
        assertNull(SemVer.parse("release-2.4"))
    }

    @Test fun comparesVersions() {
        assertTrue(UpdateLogic.isNewer("v2.4.1", "2.3.3"))
        assertTrue(UpdateLogic.isNewer("2.3.10", "2.3.9"))
        assertTrue(UpdateLogic.isNewer("v3", "2.9.9"))
        assertFalse(UpdateLogic.isNewer("v2.3.3", "2.3.3"))
        assertFalse(UpdateLogic.isNewer("2.3", "2.3.0"))
        assertFalse(UpdateLogic.isNewer("v2.3.2", "2.3.3"))
        assertFalse(UpdateLogic.isNewer("v2.4.0-beta", "2.4.0"))
        assertTrue(UpdateLogic.isNewer("v2.4.0", "2.4.0-beta"))
        assertTrue(SemVer.parse("1.0.0-beta.11")!! > SemVer.parse("1.0.0-beta.2")!!)
        assertTrue(SemVer.parse("1.0.0-rc.1")!! > SemVer.parse("1.0.0-beta")!!)
        assertFalse(UpdateLogic.isNewer("nightly", "2.3.3"))
    }

    @Test fun picksApk() {
        fun a(n: String) = ReleaseAsset(n, "https://x/$n", 1, null)
        assertNull(UpdateLogic.pickApk(listOf(a("notes.txt"), a("source.zip"))))
        assertEquals("app.apk", UpdateLogic.pickApk(listOf(a("notes.txt"), a("app.apk")))!!.name)
        val many = listOf(a("beast-x86_64.apk"), a("beast-armeabi-v7a.apk"), a("beast-arm64-v8a.APK"))
        assertEquals("beast-arm64-v8a.APK", UpdateLogic.pickApk(many)!!.name)
        assertEquals("beast-arm64-v8a.APK", UpdateLogic.pickApk(many, "arm64-v8a")!!.name)
        assertEquals("beast-x86_64.apk", UpdateLogic.pickApk(many, "x86_64")!!.name)
        assertEquals("a.apk", UpdateLogic.pickApk(listOf(a("a.apk"), a("b.apk")), "x86_64")!!.name)
    }

    @Test fun repoConfig() {
        assertFalse(UpdateLogic.isConfigured(""))
        assertFalse(UpdateLogic.isConfigured("OWNER/REPO"))
        assertFalse(UpdateLogic.isConfigured("just-a-name"))
        assertFalse(UpdateLogic.isConfigured("a/b/c"))
        assertTrue(UpdateLogic.isConfigured("jamhowman/beast-browser"))
    }

    @Test fun notes() {
        assertEquals("No release notes.", UpdateLogic.notesForDialog("  \n "))
        assertEquals("Fixes", UpdateLogic.notesForDialog("\r\nFixes\r\n"))
        assertEquals(11, UpdateLogic.notesForDialog("x".repeat(50), max = 10).length)
    }
}
