package com.jamhowman.beastbrowser

import androidx.core.content.edit
import com.jamhowman.beastbrowser.backup.BackupManager
import com.jamhowman.beastbrowser.backup.BackupPayload
import com.jamhowman.beastbrowser.data.Prefs
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Switches (Boolean prefs) travel in backups too: SETTINGS used the primitive boolean class, so they never did. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSettingsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val onlySettings = BackupManager.Sections(logins = false, bookmarks = false, speedDial = false, readingList = false, settings = true)

    @Test fun booleanAndStringSettingsAreExportedAndRestored() {
        Prefs.sp.edit(commit = true) {
            putBoolean("dark_pages", false); putBoolean("pip", false); putBoolean("force_dark", true)
            putString("autoplay", "block_all"); putString("doh_mode", "default")
            putInt("pip_hint_count", 2) // counters never travel
        }
        val out = BackupManager.collect(ctx, onlySettings).settings!!
        assertEquals(false, out["dark_pages"]); assertEquals(false, out["pip"]); assertEquals(true, out["force_dark"])
        assertEquals("block_all", out["autoplay"]); assertEquals("default", out["doh_mode"])
        assertFalse("pip_hint_count" in out)

        val parsed = BackupPayload.fromJson(BackupPayload(settings = out).toJson())
        Prefs.sp.edit(commit = true) { clear() }
        val sum = BackupManager.apply(ctx, parsed, onlySettings)
        assertEquals(out.size, sum.settings)
        assertFalse(Prefs.darkPages); assertFalse(Prefs.pipEnabled); assertTrue(Prefs.forceDark)
        assertEquals("block_all", Prefs.autoplay.key)
    }

    @Test fun wrongTypesAndUnknownKeysAreIgnoredOnImport() {
        Prefs.sp.edit(commit = true) { clear() }
        val p = BackupPayload(settings = mapOf("pip" to "yes", "realm" to "ghost", "autoplay" to true, "dnt_gpc" to true))
        val sum = BackupManager.apply(ctx, p, onlySettings)
        assertEquals(1, sum.settings)
        assertTrue(Prefs.sp.getBoolean("dnt_gpc", false))
        assertNull(Prefs.sp.getString("realm", null))
        assertTrue(Prefs.pipEnabled) // default kept
    }
}
