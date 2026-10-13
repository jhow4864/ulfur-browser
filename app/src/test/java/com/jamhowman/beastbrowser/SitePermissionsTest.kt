package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.browser.GeckoPermissionWriter
import com.jamhowman.beastbrowser.browser.KeyValue
import com.jamhowman.beastbrowser.browser.SessionPermissions
import com.jamhowman.beastbrowser.browser.SiteMediaStore
import com.jamhowman.beastbrowser.browser.SitePermissionController
import com.jamhowman.beastbrowser.browser.SitePermissions
import com.jamhowman.beastbrowser.browser.SitePermissions.Chip
import com.jamhowman.beastbrowser.browser.SitePermissions.Entry
import com.jamhowman.beastbrowser.browser.SitePermissions.Kind
import com.jamhowman.beastbrowser.browser.SitePermissions.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 2.12 site permissions dashboard + Apk Tester's gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SitePermissionsTest {
    private class MemKV : KeyValue {
        var v: String? = null; var writes = 0
        override fun get() = v
        override fun put(value: String?) { v = value; writes++ }
    }

    /** Stands in for StorageController: the stored value per token (= Gecko's own stored permission). */
    private class FakeGecko : GeckoPermissionWriter {
        val stored = HashMap<Any, State>()
        override fun write(entry: Entry, state: State) { stored[entry.token!!] = state }
    }

    private val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private fun src(p: String) = File(main, "java/com/jamhowman/beastbrowser/$p").readText()

    @Test fun groupsByOriginHttpAndHttpsSeparate() {
        val sites = SitePermissions.group(listOf(
            Entry("https://example.com", Kind.LOCATION, State.BLOCK, 10),
            Entry("http://example.com", Kind.NOTIFICATIONS, State.BLOCK, 5),
            Entry("https://example.com", Kind.CAMERA, State.BLOCK, 20),
        ))
        assertEquals(listOf("https://example.com", "http://example.com"), sites.map { it.origin })
        assertEquals(listOf(Kind.CAMERA, Kind.LOCATION), sites[0].entries.map { it.kind })
        assertEquals("https://example.com", SitePermissions.origin("https://example.com/a?b"))
        assertEquals("http://example.com", SitePermissions.origin("http://example.com/"))
        assertEquals("https://example.com:8443", SitePermissions.origin("https://example.com:8443/x"))
    }

    @Test fun sortedMostRecentlyUsedThenByName() {
        val s = SitePermissions.group(listOf(
            Entry("https://b.com", Kind.LOCATION, State.BLOCK, 0), Entry("https://a.com", Kind.LOCATION, State.BLOCK, 0),
            Entry("https://z.com", Kind.LOCATION, State.BLOCK, 99)))
        assertEquals(listOf("https://z.com", "https://a.com", "https://b.com"), s.map { it.origin })
    }

    @Test fun chipsAndSearchFilter() {
        val s = SitePermissions.group(listOf(Entry("https://cam.com", Kind.CAMERA, State.BLOCK), Entry("https://geo.org", Kind.LOCATION, State.BLOCK)))
        assertEquals(listOf("https://cam.com"), SitePermissions.filter(s, Chip.CAMERA, "").map { it.origin })
        assertEquals(listOf("https://geo.org"), SitePermissions.filter(s, Chip.ALL, "GEO").map { it.origin })
        assertEquals(listOf("ALL", "CAMERA", "MIC", "LOCATION", "NOTIFICATIONS"), Chip.entries.map { it.name }) // Designer: no Pop-ups/Downloads/Clipboard yet
        assertEquals(2, SitePermissions.filter(s, Chip.ALL, " ").size)
    }

    @Test fun glyphsMaxFiveThenPlusN() {
        val e = listOf(Kind.CAMERA, Kind.MIC, Kind.LOCATION, Kind.NOTIFICATIONS, Kind.DRM, Kind.XR, Kind.LOCAL_NETWORK)
            .map { Entry("https://x.com", it, State.BLOCK) } + Entry("https://x.com", Kind.STORAGE_ACCESS, State.ASK)
        val (g, more) = SitePermissions.Site("https://x.com", e).glyphs()
        assertEquals(5, g.size); assertEquals(2, more)
    }

    @Test fun geckoKindsAndValuesMap() {
        assertEquals(Kind.LOCATION, Kind.fromGecko(PermissionDelegate.PERMISSION_GEOLOCATION))
        assertEquals(Kind.NOTIFICATIONS, Kind.fromGecko(PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION))
        assertEquals(Kind.OTHER, Kind.fromGecko(999))
        assertFalse(SitePermissions.shownInDashboard(PermissionDelegate.PERMISSION_TRACKING))
        for (s in State.entries) assertEquals(s, State.fromGecko(s.gecko))
        assertEquals(ContentPermission.VALUE_PROMPT, State.ASK.gecko)
    }

    @Test fun changeWritesGeckosStoredPermissionNotJustUlfursCopy() {
        val gecko = FakeGecko(); val kv = MemKV()
        val token = Any() // the ContentPermission Gecko handed us
        val c = SitePermissionController(gecko, SiteMediaStore(kv))
        c.apply(Entry("https://a.com", Kind.LOCATION, State.ALLOW, token = token), State.BLOCK)
        assertEquals(State.BLOCK, gecko.stored[token])
        assertEquals(0, kv.writes) // Gecko kinds aren't copied into Ulfur's store
        // Production writer really calls Gecko's StorageController.setPermission with that ContentPermission. Gecko
        // permissions are per principal, shared by every open tab, so open tabs get the new value on their next request.
        val eng = src("browser/SitePermissionsEngine.kt")
        assertTrue(eng.contains("sc.setPermission(p, state.gecko)"))
    }

    @Test fun cameraBlockAppliesToNextRequestWithoutReload() {
        val store = SiteMediaStore(MemKV())
        val c = SitePermissionController(FakeGecko(), store)
        store.touch("https://meet.example", Kind.CAMERA, 1)
        assertEquals(State.ASK, store.state("https://meet.example", Kind.CAMERA))
        c.apply(Entry("https://meet.example", Kind.CAMERA, State.ASK), State.BLOCK)
        assertEquals(State.BLOCK, store.state("https://meet.example", Kind.CAMERA))
        assertFalse(SitePermissions.mediaAllowed(store.state("https://meet.example", Kind.CAMERA), androidGranted = true))
        // The media delegate reads the store fresh inside each request (no per-tab cache to invalidate).
        val cb = src("browser/TabCallbacks.kt")
        val body = cb.substringAfter("override fun onMediaPermissionRequest").substringBefore("override fun onAndroidPermissionsRequest")
        assertTrue(body.contains("PermissionFlow.media(flowEnv, origin, kinds, private)"))
        assertTrue(cb.contains("SitePermissionsEngine.store(c).state(origin, kind)"))
        assertTrue(body.contains("callback.reject()"))
    }

    @Test fun privateAndGhostPermissionsNeverWrittenToDiskOrListed() {
        val kv = MemKV()
        SiteMediaStore(kv) // production store
        SessionPermissions.set("https://secret.example", Kind.CAMERA, State.BLOCK)
        assertEquals(State.BLOCK, SessionPermissions.get("https://secret.example", Kind.CAMERA))
        assertNull(kv.v); assertEquals(0, kv.writes)
        SessionPermissions.clear(); assertNull(SessionPermissions.get("https://secret.example", Kind.CAMERA))
        assertFalse(SitePermissions.listable(privateMode = true, contextId = null, ghostContextId = "beast-realm-ghost"))
        assertFalse(SitePermissions.listable(false, "beast-realm-ghost", "beast-realm-ghost"))
        assertTrue(SitePermissions.listable(false, "beast-realm-work", "beast-realm-ghost"))
        val cb = src("browser/TabCallbacks.kt")
        // last-used writes are guarded by the private checks in both delegates
        assertTrue(cb.contains("if (origin != null && !private && ctx != null)"))
        assertTrue(cb.contains("if (!perm.privateMode && !tab.isPrivate && SitePermissions.shownInDashboard(perm.permission))"))
        assertTrue(src("browser/SitePermissionsEngine.kt").contains("SitePermissions.listable(it.privateMode, it.contextId, Realm.GHOST.contextId)"))
        assertTrue(src("ui/MainActivity.kt").contains("SessionPermissions.clear()"))
    }

    @Test fun undoRestoresExactPriorState() {
        val gecko = FakeGecko(); val store = SiteMediaStore(MemKV())
        val c = SitePermissionController(gecko, store)
        val t1 = Any(); val t2 = Any()
        store.set("https://a.com", Kind.MIC, State.BLOCK)
        gecko.stored[t1] = State.ALLOW; gecko.stored[t2] = State.BLOCK
        val before = listOf(
            Entry("https://a.com", Kind.NOTIFICATIONS, State.ALLOW, token = t1),
            Entry("https://a.com", Kind.LOCATION, State.BLOCK, token = t2),
            Entry("https://a.com", Kind.MIC, State.BLOCK))
        val undo = c.apply(before, State.ASK)
        assertEquals(State.ASK, gecko.stored[t1]); assertEquals(State.ASK, gecko.stored[t2])
        assertEquals(State.ASK, store.state("https://a.com", Kind.MIC))
        c.undo(undo)
        assertEquals(State.ALLOW, gecko.stored[t1]); assertEquals(State.BLOCK, gecko.stored[t2])
        assertEquals(State.BLOCK, store.state("https://a.com", Kind.MIC))
    }

    @Test fun mediaStoreRoundTripAndClear() {
        val kv = MemKV(); val s = SiteMediaStore(kv)
        s.set("https://a.com", Kind.CAMERA, State.BLOCK); s.touch("https://a.com", Kind.CAMERA, 42)
        val again = SiteMediaStore(kv) // as after a restart
        assertEquals(listOf(Entry("https://a.com", Kind.CAMERA, State.BLOCK, 42)), again.mediaEntries())
        again.clearSite("https://a.com"); assertTrue(again.mediaEntries().isEmpty())
        s.set("https://b.com", Kind.MIC, State.BLOCK); s.clearAll(); assertNull(kv.v)
    }

    @Test fun allowEnabledForCameraMicLocation() {
        assertTrue(Kind.CAMERA.allowSupported); assertTrue(Kind.MIC.allowSupported); assertTrue(Kind.LOCATION.allowSupported)
        assertFalse(SitePermissions.mediaAllowed(State.ALLOW, androidGranted = false))
        assertTrue(SitePermissions.mediaAllowed(State.ALLOW, androidGranted = true))
    }

    @Test fun wiredIntoSettingsAndShieldLongPress() {
        assertTrue(File(main, "res/xml/preferences.xml").readText().contains("site_permissions"))
        assertTrue(File(main, "AndroidManifest.xml").readText().contains(".ui.SitePermissionsActivity"))
        assertTrue(src("ui/MainActivity.kt").contains("b.shieldButton.setOnLongClickListener { openSitePermissions(); true }"))
    }
}
