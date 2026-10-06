package com.jamhowman.beastbrowser

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.update.UpdatePrefs
import com.jamhowman.beastbrowser.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Bug 5: a failed update download must not leave the update stuck as pending or keep partial files around. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdaterFailureTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun abandonClearsPendingAndFiles() {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, "update-v9.9.9.apk").apply { writeText("half an apk") }
        val part = File(dir, "update-v9.9.9.apk.part").apply { writeText("partial") }
        val other = File(dir, "update-v9.9.8.apk").apply { writeText("another") }
        val prefs = UpdatePrefs.get(app)
        prefs.pendingTag = "v9.9.9"
        prefs.skippedTag = "v9.0.0"

        Updater.abandonDownload(app, dest)

        assertNull(prefs.pendingTag)
        assertEquals("v9.0.0", prefs.skippedTag)   // only the pending state is undone
        assertFalse(dest.exists())
        assertFalse(part.exists())
        assertTrue(other.exists())
    }

    @Test fun abandonWithoutFilesIsSafe() {
        val dest = File(File(app.cacheDir, "updates-missing"), "update-v1.apk")
        UpdatePrefs.get(app).pendingTag = "v1"
        Updater.abandonDownload(app, dest)
        assertNull(UpdatePrefs.get(app).pendingTag)
    }

    @Test fun failureMessages() {
        assertEquals("Download incomplete (1 of 2 bytes).", Updater.failureMessage(IOException("Download incomplete (1 of 2 bytes).")))
        assertEquals("Couldn't download the update.", Updater.failureMessage(IOException()))
        val generic = Updater.failureMessage(IllegalStateException("boom"))
        assertTrue(generic, generic.contains("IllegalStateException"))
        assertFalse(generic.contains("boom"))
        assertTrue(Updater.failureMessage(SecurityException()).contains("SecurityException"))
    }
}
