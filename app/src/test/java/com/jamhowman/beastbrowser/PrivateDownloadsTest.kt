
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

    @Test fun sweepRemovesOnlyUnlistedDownloads() {
        val dir = File(app.cacheDir, "sweep-test").apply { deleteRecursively(); mkdirs() }
        val vaulted = File(dir, "kept.mp4").apply { writeText("done") }
        val orphan = File(dir, "half.mp4").apply { writeText("partial") }
        val orphan2 = File(dir, "half (1).zip").apply { writeText("partial") }
        val nomedia = File(dir, ".nomedia").apply { writeText("") }
        val hidden = File(dir, ".vault-state").apply { writeText("x") }
        val json = File(dir, "private_downloads.json").apply { writeText("[]") }
        val jsonTmp = File(dir, "private_downloads.json.tmp").apply { writeText("[]") }
        val sub = File(dir, "sub").apply { mkdirs() }
        val inSub = File(sub, "nested.bin").apply { writeText("x") }

        val deleted = DownloadCenter.sweepOrphanedPrivateFiles(dir, listOf(vaulted.absolutePath))

        assertEquals(setOf("half.mp4", "half (1).zip"), deleted.map { it.name }.toSet())
        assertFalse(orphan.exists())
        assertFalse(orphan2.exists())
        listOf(vaulted, nomedia, hidden, json, jsonTmp, inSub).forEach { assertTrue(it.name, it.isFile) }
        assertTrue(sub.isDirectory)
    }

    @Test fun sweepMatchesNonCanonicalPaths() {
        val dir = File(app.cacheDir, "sweep-test2").apply { deleteRecursively(); mkdirs() }
        val kept = File(dir, "a.pdf").apply { writeText("x") }
        // Same file, spelled with a redundant "./" segment: must still count as listed.
        val deleted = DownloadCenter.sweepOrphanedPrivateFiles(dir, listOf(dir.absolutePath + "/./a.pdf"))
        assertTrue(deleted.isEmpty())
        assertTrue(kept.isFile)
    }

    @Test fun sweepSkippedWhenListMissingOrUnreadable() {
        // A missing list (e.g. the app died before the first save) loads as empty, so sweeping would wipe the vault.
        assertFalse(DownloadCenter.shouldSweepPrivate(listExists = false, listReadOk = true))
        assertFalse(DownloadCenter.shouldSweepPrivate(listExists = true, listReadOk = false))
        assertFalse(DownloadCenter.shouldSweepPrivate(listExists = false, listReadOk = false))
        assertTrue(DownloadCenter.shouldSweepPrivate(listExists = true, listReadOk = true))
    }

    @Test fun sweepOnMissingDirIsNoOp() {
        val dir = File(app.cacheDir, "does-not-exist").apply { deleteRecursively() }
        assertTrue(DownloadCenter.sweepOrphanedPrivateFiles(dir, emptyList()).isEmpty())
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
