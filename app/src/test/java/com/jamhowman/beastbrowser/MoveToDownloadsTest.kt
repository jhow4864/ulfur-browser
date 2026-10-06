package com.jamhowman.beastbrowser

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.downloads.DlStatus
import com.jamhowman.beastbrowser.downloads.DownloadCenter
import com.jamhowman.beastbrowser.downloads.DownloadItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** 2.5.1 roadmap item 1: copy, verify, then delete. A file never leaves the vault unless its copy checks out. */
@RunWith(RobolectricTestRunner::class)
class MoveToDownloadsTest {
    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        DownloadCenter.init(app)
        DownloadCenter.replaceAllForPreview(emptyList())
        DownloadCenter.clearPrivateVault()
        drain()
    }

    private fun vault(id: Long, name: String, bytes: ByteArray): File {
        val f = File(app.filesDir, "private_downloads/$name").also { it.parentFile!!.mkdirs(); it.writeBytes(bytes) }
        val item = DownloadItem(
            id = id, url = "https://example.com/$name", fileName = name, mime = "application/octet-stream",
            domain = "example.com", isPrivate = true, status = DlStatus.DONE,
            downloaded = bytes.size.toLong(), total = bytes.size.toLong(), filePath = f.absolutePath,
            finishedAt = System.currentTimeMillis(),
        )
        DownloadCenter.replaceAllForPreview(DownloadCenter.items.value + item)
        DownloadCenter.endPrivateSession()
        return f
    }

    /** Waits for the io thread and the main-thread callback. */
    private fun <T> await(block: ((T) -> Unit) -> Unit): T {
        var out: T? = null
        block { out = it }
        val until = System.currentTimeMillis() + 10_000
        while (out == null && System.currentTimeMillis() < until) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }
        return out ?: error("timed out")
    }

    private fun drain() { Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle() }

    @Config(sdk = [28])
    @Test fun movesVerifiedCopyAndRemovesFromVault() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val data = ByteArray(200_000) { (it * 31).toByte() }
        val src = vault(7, "report.pdf", data)
        val (moved, failed) = await<Pair<List<DownloadItem>, List<DownloadItem>>> { done ->
            DownloadCenter.moveToDownloads(listOf(7L)) { m, f -> done(m to f) }
        }
        assertEquals(1, moved.size); assertTrue(failed.isEmpty())
        val out = File(moved[0].filePath!!)
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        assertTrue(out.absolutePath.startsWith(downloads.absolutePath))
        assertArrayEquals(data, out.readBytes())
        assertFalse(moved[0].isPrivate)
        assertEquals(DlStatus.DONE, moved[0].status)
        assertFalse(src.exists())
        assertEquals(0, DownloadCenter.privateVaultCount())
        assertTrue(DownloadCenter.items.value.any { it.id == moved[0].id && !it.isPrivate })
    }

    @Config(sdk = [28])
    @Test fun missingSourceStaysInVault() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val ok = vault(8, "a.bin", byteArrayOf(1, 2, 3))
        val gone = vault(9, "b.bin", byteArrayOf(4, 5, 6))
        gone.delete()
        val (moved, failed) = await<Pair<List<DownloadItem>, List<DownloadItem>>> { done ->
            DownloadCenter.moveToDownloads(listOf(8L, 9L)) { m, f -> done(m to f) }
        }
        assertEquals(listOf("a.bin"), moved.map { it.fileName })
        assertEquals(listOf("b.bin"), failed.map { it.fileName })
        assertFalse(ok.exists())
        assertEquals(listOf(9L), DownloadCenter.privateItems.value.map { it.id })
    }
}
