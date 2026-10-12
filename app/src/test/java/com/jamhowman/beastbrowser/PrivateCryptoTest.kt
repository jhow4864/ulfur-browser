package com.jamhowman.beastbrowser

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.downloads.DownloadCenter
import com.jamhowman.beastbrowser.downloads.PrivateCrypto
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Random
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Software stand-in for the Android Keystore (Robolectric has no AndroidKeyStore provider). */
class MemKeys : PrivateCrypto.KeySource {
    var key: SecretKey? = null
    override fun get() = key
    override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    override fun destroy() { key = null }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateCryptoTest {
    private lateinit var app: Application
    private val keys = MemKeys()
    private val small = 1024 // small chunks so tests cover many chunk boundaries cheaply

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PrivateCrypto.keys = keys
    }

    @After fun tearDown() { PrivateCrypto.keys = PrivateCrypto.AndroidKeystore }

    private fun data(n: Int) = ByteArray(n).also { Random(n.toLong()).nextBytes(it) }

    private fun enc(plain: ByteArray, chunk: Int = small, finish: Boolean = true): ByteArray {
        val bos = ByteArrayOutputStream()
        val w = PrivateCrypto.writer(bos, chunk)
        // odd write sizes so writes straddle chunks
        var o = 0
        while (o < plain.size) { val n = minOf(333, plain.size - o); w.write(plain, o, n); o += n }
        if (finish) w.finish()
        w.close()
        return bos.toByteArray()
    }

    private fun dec(ct: ByteArray): ByteArray = PrivateCrypto.reader(ByteArrayInputStream(ct)).use { it.readBytes() }

    @Test fun roundTripAcrossSizes() {
        for (n in listOf(0, 1, small - 1, small, small + 1, 3 * small, 10_000)) {
            val p = data(n)
            val c = enc(p)
            assertArrayEquals("size $n", p, dec(c))
            assertEquals("plainSize $n", n.toLong(), PrivateCrypto.plainSize(c.size.toLong(), small))
        }
    }

    @Test fun roundTripFileWithDefaultChunk() {
        val f = File(app.cacheDir, "rt.bin")
        val p = data(200_000)
        PrivateCrypto.writer(f).use { it.write(p); it.finish() }
        assertTrue(PrivateCrypto.isEncrypted(f))
        assertArrayEquals(p, PrivateCrypto.reader(f).use { it.readBytes() })
        assertEquals(p.size.toLong(), PrivateCrypto.plainSize(f.length()))
    }

    @Test fun ciphertextHidesPlaintext() {
        val p = "SECRET-SECRET-SECRET-SECRET".repeat(200).toByteArray()
        val c = enc(p)
        assertFalse(String(c, Charsets.ISO_8859_1).contains("SECRET"))
    }

    private fun assertRejects(c: ByteArray) {
        try { dec(c); fail("expected IOException") } catch (_: IOException) {}
    }

    @Test fun tamperedByteIsDetected() {
        val c = enc(data(5000))
        for (i in listOf(PrivateCrypto.HEADER_LEN + 3, PrivateCrypto.HEADER_LEN + 20, c.size / 2, c.size - 1, 10)) {
            val t = c.copyOf(); t[i] = (t[i].toInt() xor 1).toByte()
            assertRejects(t)
        }
    }

    @Test fun truncationAndReorderAreDetected() {
        val c = enc(data(5000))
        val rec = 12 + small + 16
        assertRejects(c.copyOf(c.size - rec + 5))                     // final chunk dropped mid-way
        assertRejects(c.copyOf(PrivateCrypto.HEADER_LEN + 2 * rec))   // cut on a chunk boundary
        val swapped = c.copyOf()                                      // chunks 0 and 1 swapped
        System.arraycopy(c, PrivateCrypto.HEADER_LEN + rec, swapped, PrivateCrypto.HEADER_LEN, rec)
        System.arraycopy(c, PrivateCrypto.HEADER_LEN, swapped, PrivateCrypto.HEADER_LEN + rec, rec)
        assertRejects(swapped)
        assertRejects(c + ByteArray(40))                              // junk appended after the final chunk
    }

    @Test fun unfinishedStreamNeverDecryptsFully() {
        assertRejects(enc(data(5000), finish = false))
        assertRejects(enc(data(4 * small), finish = false))
    }

    @Test fun keyLossMakesFilesUnreadable() {
        val c = enc(data(3000))
        PrivateCrypto.destroyKey()
        try { dec(c); fail() } catch (e: IOException) { assertTrue(e is PrivateCrypto.KeyLostException) }
        keys.create() // a new key (as after "Wipe vault and key") can't read old files either
        assertRejects(c)
    }

    @Test fun killedPartialDownloadIsSweptOnNextStart() {
        val dir = File(app.cacheDir, "killed").apply { deleteRecursively(); mkdirs() }
        val kept = File(dir, "done.pdf")
        PrivateCrypto.writer(kept).use { it.write(data(3000)); it.finish() }
        // Simulates a kill: the writer is never finished or closed properly.
        val partial = File(dir, "half.mp4")
        val w = PrivateCrypto.writer(partial, small); w.write(data(5000)); w.flush()
        assertTrue(PrivateCrypto.isEncrypted(partial))
        assertRejects(partial.readBytes())
        val gone = DownloadCenter.sweepOrphanedPrivateFiles(dir, listOf(kept.absolutePath))
        assertEquals(listOf("half.mp4"), gone.map { it.name })
        assertTrue(kept.isFile)
        w.close()
    }

    @Test fun legacyPlaintextVaultFileIsEncrypted() {
        DownloadCenter.init(app)
        val f = File(app.cacheDir, "old.txt").apply { writeText("hello legacy") }
        assertTrue(DownloadCenter.encryptLegacy(f))
        assertTrue(PrivateCrypto.isEncrypted(f))
        assertEquals("hello legacy", String(PrivateCrypto.reader(f).use { it.readBytes() }))
        assertFalse(DownloadCenter.encryptLegacy(f)) // already encrypted: untouched
    }
}
