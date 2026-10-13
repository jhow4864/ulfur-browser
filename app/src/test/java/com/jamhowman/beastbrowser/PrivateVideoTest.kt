package com.jamhowman.beastbrowser

import android.app.Application
import android.net.Uri
import android.system.ErrnoException
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.downloads.DlStatus
import com.jamhowman.beastbrowser.downloads.DownloadCenter
import com.jamhowman.beastbrowser.downloads.DownloadItem
import com.jamhowman.beastbrowser.downloads.PrivateCrypto
import com.jamhowman.beastbrowser.downloads.PrivateVaultProvider
import com.jamhowman.beastbrowser.downloads.VaultDataSource
import com.jamhowman.beastbrowser.downloads.VaultMime
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.random.Random

/** 2.11.1: private videos (type, name, seekable decryption, in-app player data source, migration). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateVideoTest {
    private lateinit var app: Application
    private lateinit var dir: File
    private val src = File("src/main/java/com/jamhowman/beastbrowser")

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PrivateCrypto.keys = MemKeys()
        dir = File(app.filesDir, "pv-${System.nanoTime()}").apply { mkdirs() }
    }

    @After fun tearDown() { PrivateCrypto.keys = PrivateCrypto.AndroidKeystore; dir.deleteRecursively() }

    private fun box(type: String, body: ByteArray): ByteArray =
        java.nio.ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()

    /** ftyp + big mdat + moov at the END (the index a player must seek to before playing). */
    private val mp4: ByteArray by lazy {
        val ftyp = box("ftyp", "isom\u0000\u0000\u0002\u0000isomiso2mp41".toByteArray())
        val mdat = box("mdat", Random(7).nextBytes(300_000))
        val moov = box("moov", "UlfurMoovIndex".toByteArray() + Random(8).nextBytes(5_000))
        ftyp + mdat + moov
    }
    private val moovOffset get() = mp4.size - (8 + 14 + 5_000)
    private val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte(), 0x42, 0x86.toByte(), 0x81.toByte(), 0x01,
        0x42, 0x82.toByte(), 0x84.toByte()) + "webm".toByteArray() + Random(3).nextBytes(4000)

    private fun vaultFile(name: String, plain: ByteArray, chunk: Int = PrivateCrypto.CHUNK, meta: PrivateCrypto.Meta? = PrivateCrypto.Meta(name, "application/octet-stream")): File =
        File(dir, name).also { f -> PrivateCrypto.writer(f, chunk, meta).use { w -> w.write(plain); w.finish() } }

    private fun item(f: File, name: String, mime: String = "application/octet-stream") = DownloadItem(
        id = Random.nextLong(1, Long.MAX_VALUE), url = "https://cdn.example/videoplayback?id=1", fileName = name, mime = mime,
        domain = "cdn.example", isPrivate = true, status = DlStatus.DONE, filePath = f.absolutePath,
    )

    private fun readAll(ds: VaultDataSource, spec: DataSpec): ByteArray {
        ds.open(spec)
        val out = ByteArrayOutputStream(); val b = ByteArray(7777)
        while (true) { val n = ds.read(b, 0, b.size); if (n == C.RESULT_END_OF_INPUT) break; out.write(b, 0, n) }
        ds.close()
        return out.toByteArray()
    }

    // ------------------------------------------------------------- random access

    @Test fun randomAccessReadsAtAnyOffsetMatchPlaintext() {
        for (chunk in listOf(1024, PrivateCrypto.CHUNK)) {
            val f = vaultFile("a$chunk.mp4", mp4, chunk)
            PrivateCrypto.RandomAccess(f).use { ra ->
                assertEquals(mp4.size.toLong(), ra.size)
                val rnd = Random(chunk)
                repeat(300) {
                    val pos = rnd.nextLong(0, mp4.size.toLong())
                    val len = rnd.nextInt(1, 200_000)
                    val buf = ByteArray(len)
                    val n = ra.read(pos, buf, 0, len)
                    val want = minOf(len.toLong(), mp4.size - pos).toInt()
                    assertEquals(want, n)
                    assertArrayEquals(mp4.copyOfRange(pos.toInt(), pos.toInt() + n), buf.copyOf(n))
                }
                assertEquals(-1, ra.read(mp4.size.toLong(), ByteArray(4), 0, 4))
            }
            assertEquals(mp4.size.toLong(), PrivateCrypto.plainSize(f))
        }
    }

    @Test fun seekAcrossChunkBoundary() {
        val chunk = 1024
        val f = vaultFile("b.mp4", mp4, chunk)
        PrivateCrypto.RandomAccess(f).use { ra ->
            for (k in listOf(1, 2, 57, mp4.size / chunk)) {
                val pos = k.toLong() * chunk - 10
                val buf = ByteArray(20)
                assertEquals(minOf(20, (mp4.size - pos).toInt()), ra.read(pos, buf, 0, 20))
                assertArrayEquals(mp4.copyOfRange(pos.toInt(), minOf(mp4.size, pos.toInt() + 20)), buf.copyOf(minOf(20, (mp4.size - pos).toInt())))
            }
            // Backwards seek after reading forwards.
            val b = ByteArray(3000); ra.read(5000, b, 0, 3000)
            assertArrayEquals(mp4.copyOfRange(5000, 8000), b)
        }
    }

    // ------------------------------------------------------------- name/type

    @Test fun nameAndMimeRoundTripInsideTheFile() {
        val f = vaultFile("x", mp4, meta = PrivateCrypto.Meta("Ålesund trip 🎥.mp4", "video/mp4"))
        assertTrue(PrivateCrypto.hasMeta(f))
        assertEquals(PrivateCrypto.Meta("Ålesund trip 🎥.mp4", "video/mp4"), PrivateCrypto.readMeta(f))
        PrivateCrypto.updateMeta(f, PrivateCrypto.Meta("renamed.webm", "video/webm"))
        assertEquals(PrivateCrypto.Meta("renamed.webm", "video/webm"), PrivateCrypto.readMeta(f))
        assertArrayEquals(mp4, PrivateCrypto.reader(f).use { it.readBytes() }) // chunks untouched
        assertFalse("name is not stored in clear", String(f.readBytes(), Charsets.ISO_8859_1).contains("renamed"))
        // Long names still fit the fixed record.
        PrivateCrypto.updateMeta(f, PrivateCrypto.Meta("n".repeat(2000) + ".mp4", "video/mp4"))
        assertTrue(PrivateCrypto.readMeta(f)!!.name.isNotEmpty())
        // A changed meta record fails.
        RandomAccessFile(f, "rw").use { it.seek(PrivateCrypto.HEADER2_LEN + 40L); val b = it.read(); it.seek(PrivateCrypto.HEADER2_LEN + 40L); it.write(b xor 1) }
        try { PrivateCrypto.readMeta(f); fail() } catch (_: IOException) {}
        try { PrivateCrypto.RandomAccess(f); fail() } catch (_: IOException) {}
    }

    @Test fun typeFromExtensionAndMagicBytes() {
        assertEquals("video/mp4", VaultMime.resolve("application/octet-stream", "videoplayback.bin", mp4.copyOf(512)))
        assertEquals("video/mp4", VaultMime.resolve(null, "videoplayback", mp4.copyOf(512)))
        assertEquals("video/webm", VaultMime.resolve("application/octet-stream", "clip", webm.copyOf(512)))
        assertEquals("video/webm", VaultMime.resolve("binary/octet-stream", "clip.webm", null))
        assertEquals("video/mp4", VaultMime.resolve("video/mp4", "whatever.bin", webm))
        assertEquals("application/octet-stream", VaultMime.resolve("", "noext", Random(1).nextBytes(64)))
        assertEquals("videoplayback.mp4", VaultMime.fixName("videoplayback.bin", "video/mp4"))
        assertEquals("clip.webm", VaultMime.fixName("clip", "video/webm"))
        assertEquals("film.mkv", VaultMime.fixName("film.mkv", "video/x-matroska"))
        // Provider: octet-stream entries get the real type from the file.
        val f = vaultFile("videoplayback.bin", mp4)
        assertEquals("video/mp4", PrivateVaultProvider.typeOf(item(f, "videoplayback.bin")))
        val w = vaultFile("noext", webm)
        assertEquals("video/webm", PrivateVaultProvider.typeOf(item(w, "noext")))
        // Response headers fix names guessed from the URL.
        assertEquals("videoplayback.mp4" to "video/mp4",
            DownloadCenter.refineFromHeaders("videoplayback.bin", "application/octet-stream", "https://x/videoplayback?a=1", "video/mp4; codecs=avc1", null))
    }

    // ------------------------------------------------------------- data source

    @Test fun dataSourceSeeksToMiddleAndEndOfMp4WithIndexAtEnd() {
        val f = vaultFile("videoplayback.mp4", mp4)
        val ds = VaultDataSource(f)
        val uri = Uri.parse("ulfur-vault://1")
        assertArrayEquals(mp4, readAll(ds, DataSpec(uri)))
        // The player reads ftyp, jumps to the moov at the end, then back into mdat.
        assertArrayEquals(mp4.copyOfRange(moovOffset, mp4.size), readAll(ds, DataSpec.Builder().setUri(uri).setPosition(moovOffset.toLong()).build()))
        assertTrue(String(mp4.copyOfRange(moovOffset, moovOffset + 12), Charsets.ISO_8859_1).contains("moov"))
        val mid = mp4.size / 2L
        assertArrayEquals(mp4.copyOfRange(mid.toInt(), mid.toInt() + 70_000),
            readAll(ds, DataSpec.Builder().setUri(uri).setPosition(mid).setLength(70_000).build()))
        assertEquals(0, readAll(ds, DataSpec.Builder().setUri(uri).setPosition(mp4.size.toLong()).build()).size)
        try { ds.open(DataSpec.Builder().setUri(uri).setPosition(mp4.size + 1L).build()); fail() } catch (_: IOException) {}
    }

    @Test fun tamperedChunkThrowsAndStopsPlayback() {
        val chunk = 1024
        val f = vaultFile("t.mp4", mp4, chunk)
        val dataStart = PrivateCrypto.HEADER2_LEN + PrivateCrypto.META_REC
        val rec = chunk + 28
        RandomAccessFile(f, "rw").use { it.seek(dataStart + 2L * rec + 100); val b = it.read(); it.seek(dataStart + 2L * rec + 100); it.write(b xor 0x40) }
        PrivateCrypto.RandomAccess(f).use { ra ->
            val ok = ByteArray(100); assertEquals(100, ra.read(0, ok, 0, 100)) // untouched chunk still reads
            try { ra.read(2L * chunk + 5, ByteArray(10), 0, 10); fail("tampered chunk must not decrypt") } catch (_: IOException) {}
        }
        val ds = VaultDataSource(f)
        try { readAll(ds, DataSpec(Uri.parse("ulfur-vault://t"))); fail("playback must stop") } catch (_: IOException) { ds.close() }
        // Provider: an I/O error, never junk.
        PrivateCrypto.RandomAccess(f).use { ra ->
            val cb = PrivateVaultProvider.callback(ra, null)
            try { cb.onRead(2L * chunk, 64, ByteArray(64)); fail() } catch (_: ErrnoException) {}
            assertEquals(mp4.size.toLong(), cb.onGetSize())
        }
        // Truncated after a full chunk: the last chunk isn't marked final, so it fails too.
        val g = vaultFile("u.mp4", mp4, chunk)
        RandomAccessFile(g, "rw").use { it.setLength(dataStart + 5L * rec) }
        PrivateCrypto.RandomAccess(g).use { ra -> try { ra.read(4L * chunk, ByteArray(8), 0, 8); fail() } catch (_: IOException) {} }
    }

    @Test fun nothingDecryptedLeftInCacheAfterPlayback() {
        val before = app.cacheDir.walk().filter { it.isFile }.map { it.absolutePath }.toSet()
        val f = vaultFile("videoplayback.mp4", mp4)
        val ds = VaultDataSource(f)
        readAll(ds, DataSpec(Uri.parse("ulfur-vault://c")))
        readAll(ds, DataSpec.Builder().setUri(Uri.parse("ulfur-vault://c")).setPosition(moovOffset.toLong()).build())
        val after = app.cacheDir.walk().filter { it.isFile }.toList()
        assertEquals(before, after.map { it.absolutePath }.toSet())
        for (d in listOf(app.cacheDir, app.filesDir)) d.walk().filter { it.isFile && it.length() < 50_000_000 }.forEach {
            assertFalse("${it.name} holds plaintext", String(it.readBytes(), Charsets.ISO_8859_1).contains("UlfurMoovIndex"))
        }
        for (p in listOf("downloads/VaultDataSource.kt", "ui/PrivateVideoActivity.kt")) {
            val t = File(src, p).readText()
            for (bad in listOf("CacheDataSource", "SimpleCache", "createTempFile", "cacheDir", "FileOutputStream"))
                assertFalse("$p: $bad", t.contains(bad))
        }
        assertTrue(File(src, "ui/PrivateVideoActivity.kt").readText().contains("VaultDataSource.Factory(f)"))
    }

    // ------------------------------------------------------------- migration

    @Test fun oldBinItemsAreFixedAndPlayAfterUpdate() {
        // 2.11.0 vault file: UPD1 (no meta), listed as videoplayback.bin / octet-stream.
        val old = vaultFile("videoplayback.bin", mp4, meta = null)
        assertFalse(PrivateCrypto.hasMeta(old))
        assertNull(PrivateCrypto.readMeta(old))
        val fixed = DownloadCenter.migrateVaultItem(item(old, "videoplayback.bin"))
        assertEquals("video/mp4", fixed.mime)
        assertEquals("videoplayback.mp4", fixed.fileName)
        assertTrue(PrivateCrypto.hasMeta(old))
        assertEquals(PrivateCrypto.Meta("videoplayback.mp4", "video/mp4"), PrivateCrypto.readMeta(old))
        assertFalse(File(dir, DownloadCenter.ENC_PREFIX + "videoplayback.bin").exists())
        assertArrayEquals(mp4, readAll(VaultDataSource(old), DataSpec(Uri.parse("ulfur-vault://m"))))
        assertTrue(VaultMime.isPlayable(fixed.mime))
        // Idempotent.
        assertEquals(fixed, DownloadCenter.migrateVaultItem(fixed))
        // WebM with no extension, and a pre-2.10 plaintext file.
        val w = vaultFile("clip", webm, meta = null)
        assertEquals("video/webm", DownloadCenter.migrateVaultItem(item(w, "clip")).mime)
        val plain = File(dir, "legacy.bin").apply { writeBytes(mp4) }
        val p = DownloadCenter.migrateVaultItem(item(plain, "legacy.bin"))
        assertEquals("video/mp4", p.mime); assertTrue(PrivateCrypto.hasMeta(plain))
        // Extension-only fix on load.
        assertEquals("video/webm", DownloadCenter.fixTypeFromName(item(w, "a.webm")).mime)
        // A damaged old file is left as it was (no crash).
        val bad = vaultFile("bad.bin", mp4, meta = null).apply { RandomAccessFile(this, "rw").use { it.seek(60); it.write(0) ; it.seek(61); it.write(1) } }
        val b = item(bad, "bad.bin")
        assertEquals(b, DownloadCenter.migrateVaultItem(b))
    }

    @Test fun tapOpensInAppPlayerAndOpenWithWarns() {
        val a = File(src, "ui/PrivateDownloadsActivity.kt").readText()
        val open = a.substringAfter("private fun open(d: DownloadItem)").substringBefore("private fun openWith")
        assertTrue(open.contains("VaultMime.isPlayable(d.mime)") && open.contains("PrivateVideoActivity.intent(this, d.id)"))
        assertTrue(a.contains("R.string.vault_open_with_warning"))
        val p = File(src, "downloads/PrivateVaultProvider.kt").readText()
        assertTrue(p.contains("openProxyFileDescriptor") && p.contains("openPipe(f)"))
        val v = File(src, "ui/PrivateVideoActivity.kt").readText()
        listOf("SEEK_MS = 10_000L", "setFullscreenButtonClickListener", "FLAG_SECURE", "PrivateLock.isUnlocked()").forEach { assertTrue(it, v.contains(it)) }
    }
}
