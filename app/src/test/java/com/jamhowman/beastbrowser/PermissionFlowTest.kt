package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.browser.PermissionFlow
import com.jamhowman.beastbrowser.browser.PermissionFlow.Decision
import com.jamhowman.beastbrowser.browser.SessionPermissions
import com.jamhowman.beastbrowser.browser.SitePermissions.Kind
import com.jamhowman.beastbrowser.browser.SitePermissions.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.12.1 camera / mic / location: Apk Tester's gate. */
class PermissionFlowTest {
    private val CAM = "android.permission.CAMERA"
    private val MIC = "android.permission.RECORD_AUDIO"
    private val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private fun src(p: String) = File(main, "java/com/jamhowman/beastbrowser/$p").readText()

    /** Fake world: stored decisions (normal / private kept apart), a scripted prompt, a scripted Android dialog. */
    private class Env(var answer: Decision = Decision.BLOCK, var androidGrants: Set<String> = emptySet()) : PermissionFlow.Env {
        val disk = HashMap<String, State>(); val session = HashMap<String, State>()
        var prompts = 0; var androidAsks = 0
        override fun stored(origin: String, kind: Kind, private: Boolean) = (if (private) session else disk)["$origin|$kind"]
        override fun remember(origin: String, kind: Kind, state: State, private: Boolean) { (if (private) session else disk)["$origin|$kind"] = state }
        override fun prompt(origin: String, kinds: List<Kind>, allowOnce: Boolean, done: (Decision) -> Unit) { prompts++; done(answer) }
        override fun ensureAndroid(perms: List<String>, done: (Set<String>) -> Unit) { androidAsks++; done(androidGrants.intersect(perms.toSet())) }
    }

    private fun run(e: Env, kinds: List<Kind>, private: Boolean = false): Boolean {
        var r: Boolean? = null; PermissionFlow.media(e, "https://meet.example", kinds, private) { r = it }; return r!!
    }

    @Test fun manifestAddsOnlyTheFourPermissions() {
        val m = File(main, "AndroidManifest.xml").readText()
        val perms = Regex("""uses-permission android:name="([^"]+)"""").findAll(m).map { it.groupValues[1] }.toSet()
        val before = setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.REQUEST_INSTALL_PACKAGES")
        assertEquals(PermissionFlow.ADDED_ANDROID_PERMISSIONS.toSet(), perms - before)
        assertFalse(m.contains("ACCESS_BACKGROUND_LOCATION")); assertFalse(m.contains("MODIFY_AUDIO_SETTINGS"))
        for (f in listOf("android.hardware.camera", "android.hardware.microphone", "android.hardware.location"))
            assertTrue(f, Regex("""<uses-feature android:name="$f" android:required="false" />""").containsMatchIn(m))
        assertFalse(Regex("""required="true"""").containsMatchIn(m))
    }

    @Test fun androidDialogOnlyWhenAnAllowedSiteRequests_neverAtLaunch() {
        val blocked = Env().apply { disk["https://meet.example|CAMERA"] = State.BLOCK }
        assertFalse(run(blocked, listOf(Kind.CAMERA))); assertEquals(0, blocked.androidAsks); assertEquals(0, blocked.prompts)
        val refusedPrompt = Env(Decision.BLOCK)
        assertFalse(run(refusedPrompt, listOf(Kind.CAMERA))); assertEquals(0, refusedPrompt.androidAsks)
        val allowed = Env(androidGrants = setOf(CAM)).apply { disk["https://meet.example|CAMERA"] = State.ALLOW }
        assertTrue(run(allowed, listOf(Kind.CAMERA))); assertEquals(1, allowed.androidAsks); assertEquals(0, allowed.prompts)
        // Nothing in startup paths asks Android for runtime permissions.
        for (f in listOf("BeastApp.kt", "ui/MainActivity.kt")) {
            val s = src(f); val onCreate = s.substringAfter("override fun onCreate").substringBefore("\n    override fun ")
            assertFalse(f, onCreate.contains("permLauncher.launch") || onCreate.contains("requestPermissions("))
        }
        // Camera / mic: Gecko's early Android-permission callback is passed through without an OS dialog.
        val cb = src("browser/TabCallbacks.kt").substringAfter("override fun onAndroidPermissionsRequest").substringBefore("override fun onRecordingStatusChanged")
        assertTrue(cb.contains("""if (perms.none { it.endsWith("_LOCATION") }) { callback.grant(); return }"""))
    }

    @Test fun promptAnswers() {
        val always = Env(Decision.ALLOW_ALWAYS, setOf(CAM, MIC))
        assertTrue(run(always, listOf(Kind.CAMERA, Kind.MIC))); assertEquals(State.ALLOW, always.disk["https://meet.example|MIC"])
        assertTrue(run(always, listOf(Kind.CAMERA, Kind.MIC))); assertEquals(1, always.prompts) // remembered
        val once = Env(Decision.ALLOW_ONCE, setOf(CAM))
        assertTrue(run(once, listOf(Kind.CAMERA))); assertTrue(once.disk.isEmpty()) // stays "Ask"
        val dismissed = Env(Decision.DISMISSED, setOf(CAM))
        assertFalse(run(dismissed, listOf(Kind.CAMERA))); assertTrue(dismissed.disk.isEmpty())
        val block = Env(Decision.BLOCK)
        assertFalse(run(block, listOf(Kind.CAMERA))); assertEquals(State.BLOCK, block.disk["https://meet.example|CAMERA"])
        assertFalse(run(block, listOf(Kind.CAMERA))); assertEquals(1, block.prompts)
    }

    @Test fun refusedAndroidPermissionDeniesSiteCleanlyWithoutLoops() {
        val e = Env(Decision.ALLOW_ALWAYS, androidGrants = emptySet())
        assertFalse(run(e, listOf(Kind.CAMERA)))
        assertEquals(listOf<String>(), PermissionFlow.toRequest(listOf(CAM), held = emptySet(), refusedThisSession = setOf(CAM)))
        assertEquals(listOf(MIC), PermissionFlow.toRequest(listOf(CAM, MIC), held = setOf(CAM), refusedThisSession = emptySet()))
        assertFalse(PermissionFlow.androidSatisfied(listOf(CAM, MIC), setOf(CAM)))
        assertTrue(PermissionFlow.androidSatisfied(listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
            setOf("android.permission.ACCESS_COARSE_LOCATION")))
        val ma = src("ui/MainActivity.kt")
        assertTrue(ma.contains("res.forEach { (p, ok) -> if (!ok) androidRefused += p }"))
        assertTrue(ma.contains("if (ask.isEmpty() || isFinishing) { done(heldOf(perms)); return }"))
        // Gecko callbacks guarded so a late answer (tab gone) can't crash.
        val cb = src("browser/TabCallbacks.kt")
        assertTrue(cb.contains("runCatching { if (ok) callback.grant(cam, mic) else callback.reject() }"))
    }

    @Test fun privateAndGhostAllowIsSessionOnly() {
        val e = Env(Decision.ALLOW_ALWAYS, setOf(CAM))
        assertTrue(run(e, listOf(Kind.CAMERA), private = true))
        assertTrue(e.disk.isEmpty()); assertEquals(State.ALLOW, e.session["https://meet.example|CAMERA"])
        val cb = src("browser/TabCallbacks.kt")
        assertTrue(cb.contains("if (private) SessionPermissions.set(origin, kind, state)"))
        assertTrue(cb.contains("val private = tab.isPrivate || session.settings.usePrivateMode"))
        SessionPermissions.clear()
    }

    @Test fun captureStopsWhenHiddenBackgroundedOrClosed() {
        assertTrue(PermissionFlow.shouldStopCapture(true, tabVisible = false, appForeground = true))
        assertTrue(PermissionFlow.shouldStopCapture(true, tabVisible = true, appForeground = false))
        assertFalse(PermissionFlow.shouldStopCapture(true, tabVisible = true, appForeground = true))
        assertFalse(PermissionFlow.shouldStopCapture(false, false, false))
        val ma = src("ui/MainActivity.kt")
        assertTrue(ma.contains("allTabs.forEach { stopCaptureIfNeeded(it, it === current, appForeground = false) }"))
        assertTrue(ma.contains("stopCaptureIfNeeded(old, tabVisible = false, appForeground = true)"))
        val close = ma.substringAfter("private fun closeTab(").substringBefore("\n    private fun ")
        assertTrue(close.contains("tab.capturing = false") && close.contains("tab.session.close()"))
        assertTrue(src("browser/TabCallbacks.kt").contains("session.mediaDelegate = this"))
    }

    @Test fun locationGetsRealPromptAndDismissDenies() {
        val cb = src("browser/TabCallbacks.kt")
        val geo = cb.substringAfter("PermissionDelegate.PERMISSION_GEOLOCATION -> {").substringBefore("else -> ContentPermission.VALUE_DENY")
        assertTrue(geo.contains("host.promptSitePermission(tab, origin, listOf(SitePermissions.Kind.LOCATION), false)"))
        assertTrue(geo.contains("if (PermissionFlow.allows(d)) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY"))
        assertFalse(PermissionFlow.allows(Decision.DISMISSED))
    }
}
