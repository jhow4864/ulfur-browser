
package com.jamhowman.beastbrowser

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.downloads.DlStatus
import com.jamhowman.beastbrowser.downloads.DownloadCenter
import com.jamhowman.beastbrowser.downloads.DownloadItem
import com.jamhowman.beastbrowser.downloads.PrivateLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateDownloadsTest {
    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        DownloadCenter.init(app)
        DownloadCenter.replaceAllForPreview(emptyList())
        // clear vault via public API
        DownloadCenter.clearPrivateVault()
        PrivateLock.lock()
    }

    @Test fun privateDirHasNomedia() {
        val dir = File(app.filesDir, DownloadCenter.PRIVATE_DIR)
        dir.mkdirs()
        File(dir, ".nomedia").createNewFile()
        assertTrue(File(dir, ".nomedia").isFile)
        // Must NOT live under public Downloads
        assertFalse(dir.absolutePath.contains("/Download/"))
        assertTrue(dir.absolutePath.contains(app.filesDir.absolutePath))
    }

    @Test fun endPrivateSessionVaultsFinished() {
        val done = DownloadItem(
            id = 42, url = "https://example.com/a.mp4", fileName = "a.mp4",
            mime = "video/mp4", domain = "example.com", isPrivate = true,
            status = DlStatus.DONE, downloaded = 100, total = 100,
            filePath = File(app.filesDir, "private_downloads/a.mp4").also {
                it.parentFile!!.mkdirs(); it.writeText("x")
            }.absolutePath,
            finishedAt = System.currentTimeMillis(),
        )
        val active = done.copy(id = 43, status = DlStatus.DOWNLOADING, fileName = "b.mp4", finishedAt = 0)
        DownloadCenter.replaceAllForPreview(listOf(done, active))
        val cancelled = DownloadCenter.endPrivateSession()
        assertEquals(0, cancelled) // active had no live Job
        assertTrue(DownloadCenter.items.value.none { it.isPrivate })
        assertEquals(1, DownloadCenter.privateVaultCount())
        assertEquals("a.mp4", DownloadCenter.privateItems.value.first().fileName)
    }

    @Test fun lockStartsLocked() {
        PrivateLock.lock()
        assertFalse(PrivateLock.isUnlocked())
        PrivateLock.unlock()
        assertTrue(PrivateLock.isUnlocked())
        PrivateLock.lock()
        assertFalse(PrivateLock.isUnlocked())
    }
}
