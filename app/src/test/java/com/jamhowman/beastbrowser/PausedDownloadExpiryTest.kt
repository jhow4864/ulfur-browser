package com.jamhowman.beastbrowser

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.downloads.DlStatus
import com.jamhowman.beastbrowser.downloads.DownloadCenter
import com.jamhowman.beastbrowser.downloads.DownloadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

/** Bug 4: paused downloads whose pending MediaStore entry expired (or was deleted) must restart cleanly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PausedDownloadExpiryTest {
    private lateinit var app: Application

    /** Stand-in for MediaProvider: row 1 is gone, row 2 is temporarily unreadable, row 3 exists. */
    class FakeMediaProvider : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = when (uri.lastPathSegment) {
            "1" -> throw FileNotFoundException("No item at $uri")
            "2" -> throw IllegalStateException("Volume not mounted")
            else -> ParcelFileDescriptor.open(File.createTempFile("pending", ".bin"), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private fun paused(contentUri: String? = null, filePath: String? = null) = DownloadItem(
        id = 7, url = "https://example.com/big.zip", fileName = "big.zip", mime = "application/zip",
        domain = "example.com", isPrivate = false, status = DlStatus.PAUSED, downloaded = 5_000, total = 10_000,
        contentUri = contentUri, filePath = filePath, segmentsDone = 3, error = "Interrupted",
    )

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        DownloadCenter.init(app)
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
    }

    @Test fun refreshWindow() {
        val now = 1_800_000_000L
        val day = 24L * 60 * 60
        assertFalse(DownloadCenter.pendingNeedsRefresh(null, now))            // not pending / Android 10: never expires
        assertFalse(DownloadCenter.pendingNeedsRefresh(now + 7 * day, now))   // freshly paused
        assertFalse(DownloadCenter.pendingNeedsRefresh(now + 4 * day, now))
        assertTrue(DownloadCenter.pendingNeedsRefresh(now + 2 * day, now))
        assertTrue(DownloadCenter.pendingNeedsRefresh(now - 60, now))         // overdue but not yet swept
    }

    @Test fun missingMediaStoreRowIsGone() {
        assertTrue(DownloadCenter.targetGone(paused(contentUri = "content://media/external/downloads/1")))
    }

    @Test fun otherErrorsAreNotTreatedAsGone() {
        assertFalse(DownloadCenter.targetGone(paused(contentUri = "content://media/external/downloads/2")))
    }

    @Test fun existingMediaStoreRowIsKept() {
        assertFalse(DownloadCenter.targetGone(paused(contentUri = "content://media/external/downloads/3")))
    }

    @Test fun legacyFilePaths() {
        val f = File.createTempFile("partial", ".zip")
        assertFalse(DownloadCenter.targetGone(paused(filePath = f.absolutePath)))
        f.delete()
        assertTrue(DownloadCenter.targetGone(paused(filePath = f.absolutePath)))
        assertFalse(DownloadCenter.targetGone(paused()))   // not started yet: nothing to lose
    }

    @Test fun startupCheckFlagsOnlyVanishedPartials() {
        val gone = paused(contentUri = "content://media/external/downloads/1").copy(id = 1)
        val unreadable = paused(contentUri = "content://media/external/downloads/2").copy(id = 2)
        val fine = paused(contentUri = "content://media/external/downloads/3").copy(id = 3)
        val failedGone = paused(contentUri = "content://media/external/downloads/1").copy(id = 4, status = DlStatus.FAILED, error = "HTTP 500")
        val done = paused(contentUri = "content://media/external/downloads/1").copy(id = 5, status = DlStatus.DONE)
        val private = paused(filePath = "/nonexistent/private.zip").copy(id = 6, isPrivate = true)
        DownloadCenter.replaceAllForPreview(listOf(gone, unreadable, fine, failedGone, done, private))

        DownloadCenter.checkUnfinishedTargets()

        val after = DownloadCenter.items.value.associateBy { it.id }
        assertEquals(DownloadCenter.restartFromScratch(gone, DownloadCenter.TARGET_GONE_MESSAGE), after[1])
        assertEquals(DlStatus.PAUSED, after[1]!!.status)
        assertEquals(unreadable, after[2])
        assertEquals(fine, after[3])
        assertEquals(DownloadCenter.restartFromScratch(failedGone, DownloadCenter.TARGET_GONE_MESSAGE), after[4])
        assertEquals(done, after[5])        // finished downloads are left alone
        assertEquals(private, after[6])     // private ones are handled by the private-session logic
        DownloadCenter.replaceAllForPreview(emptyList())
    }

    @Test fun restartForgetsPartialFile() {
        val d = paused(contentUri = "content://media/external/downloads/1").copy(isHls = true, segmentsTotal = 9)
        val r = DownloadCenter.restartFromScratch(d, DownloadCenter.TARGET_GONE_MESSAGE)
        assertNull(r.contentUri)
        assertNull(r.filePath)
        assertEquals(0L, r.downloaded)
        assertEquals(-1L, r.total)
        assertEquals(0, r.segmentsDone)
        assertEquals(DownloadCenter.TARGET_GONE_MESSAGE, r.error)
        // Same download: id, url, name, status and HLS-ness survive.
        assertEquals(d.copy(contentUri = null, downloaded = 0, total = -1, segmentsDone = 0, error = r.error), r)
        assertFalse(DownloadCenter.targetGone(r))
    }
}
