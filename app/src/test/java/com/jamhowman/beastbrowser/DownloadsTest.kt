package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.downloads.DlFormat
import com.jamhowman.beastbrowser.downloads.DlStatus
import com.jamhowman.beastbrowser.downloads.DownloadCenter
import com.jamhowman.beastbrowser.downloads.DownloadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadsTest {
    private fun item(status: DlStatus, done: Long = 0, total: Long = -1, speed: Long = 0) =
        DownloadItem(1, "https://example.com/a.pdf", "a.pdf", "application/pdf", "example.com", false, status, done, total, speed)

    @Test fun percentAndEta() {
        val d = item(DlStatus.DOWNLOADING, 25, 100, 5)
        assertEquals(25, d.percent)
        assertEquals(15, d.etaSeconds)
        assertEquals(-1, item(DlStatus.DOWNLOADING, 25).percent)
        assertEquals(-1, item(DlStatus.PAUSED, 25, 100, 5).etaSeconds)
        assertEquals(100, item(DlStatus.DONE, 10).percent)
    }

    @Test fun formatting() {
        assertEquals("512 B", DlFormat.bytes(512))
        assertEquals("1.5 KB", DlFormat.bytes(1536))
        assertEquals("48.0 MB", DlFormat.bytes(48L * 1024 * 1024))
        assertEquals("45s left", DlFormat.eta(45))
        assertEquals("2m 5s left", DlFormat.eta(125))
        val line = DlFormat.statusLine(item(DlStatus.DOWNLOADING, 1024 * 1024, 4 * 1024 * 1024, 1024 * 1024))
        assertEquals("Downloading · 1.0 MB / 4.0 MB · 1.0 MB/s · 3s left", line)
        assertEquals("Waiting…", DlFormat.detail(item(DlStatus.QUEUED)))
    }

    @Test fun jsonRoundTripDropsPrivateFlag() {
        val d = item(DlStatus.DONE, 10, 10).copy(contentUri = "content://media/1", referrer = "https://example.com/")
        val back = DownloadItem.fromJson(d.toJson())
        assertEquals(d.copy(speedBps = 0), back)
        assertFalse(back.isPrivate)
    }

    @Test fun statusGroups() {
        assertTrue(DlStatus.QUEUED.isActive && DlStatus.DOWNLOADING.isActive)
        assertFalse(DlStatus.PAUSED.isActive)
        assertTrue(DlStatus.DONE.isFinished && DlStatus.FAILED.isFinished && DlStatus.CANCELLED.isFinished)
    }

    @Test fun sanitizesFileNames() {
        assertEquals("a_b_c.txt", DownloadCenter.sanitize("a/b\\\\c.txt").replace("__", "_"))
        assertEquals("download", DownloadCenter.sanitize("..."))
        assertTrue(DownloadCenter.sanitize("x".repeat(300)).length <= 120)
        // Long names keep their extension and stay inside the file system's 255-byte limit.
        val long = DownloadCenter.sanitize("x".repeat(300) + ".mp4")
        assertTrue(long.endsWith(".mp4")); assertTrue(long.length <= 120)
        val cjk = DownloadCenter.sanitize("视".repeat(150) + ".pdf")
        assertTrue(cjk.endsWith(".pdf")); assertTrue(cjk.toByteArray(Charsets.UTF_8).size <= 200)
        val emoji = DownloadCenter.sanitize("🐺".repeat(100) + ".jpg")
        assertTrue(emoji.endsWith(".jpg")); assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= 200)
        assertFalse("no broken surrogate pair", emoji.removeSuffix(".jpg").last().isHighSurrogate())
        assertEquals("short name.txt", DownloadCenter.sanitize("short name.txt"))
        // A "dot" far from the end isn't an extension: plain truncation.
        assertEquals(120, DownloadCenter.sanitize("v1.2 " + "y".repeat(300)).length)
    }
}
