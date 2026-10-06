package com.jamhowman.beastbrowser

import android.util.Base64
import com.jamhowman.beastbrowser.backup.BackupCrypto
import com.jamhowman.beastbrowser.backup.BackupCrypto.BackupException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `.ulfur` envelope: PBKDF2-HMAC-SHA256 + AES-256-GCM, header authenticated as AAD. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupCryptoTest {
    private val pass = "correct horse battery".toCharArray()
    private val plain = """{"logins":[{"origin":"https://example.org","password":"s3cr3t-Sample!"}]}""".toByteArray()

    private fun header(file: ByteArray) = JSONObject(String(file, 0, file.indexOf('\n'.code.toByte())))

    @Test fun roundTrip() {
        val file = BackupCrypto.encrypt(plain, pass.copyOf())
        assertTrue(BackupCrypto.looksLikeBackup(file))
        assertArrayEquals(plain, BackupCrypto.decrypt(file, pass.copyOf()))
    }

    @Test fun headerHasVersionedKdfAndCipherParams() {
        val h = header(BackupCrypto.encrypt(plain, pass.copyOf()))
        assertEquals("ulfur-backup", h.getString("format"))
        assertEquals(1, h.getInt("version"))
        val kdf = h.getJSONObject("kdf")
        assertEquals("PBKDF2-HMAC-SHA256", kdf.getString("name"))
        assertTrue(kdf.getInt("iterations") >= 310_000)
        assertEquals(16, Base64.decode(kdf.getString("salt"), Base64.NO_WRAP).size)
        val c = h.getJSONObject("cipher")
        assertEquals("AES-256-GCM", c.getString("name"))
        assertEquals(12, Base64.decode(c.getString("nonce"), Base64.NO_WRAP).size)
    }

    @Test fun plaintextNotInFileAndSaltNonceAreRandom() {
        val a = BackupCrypto.encrypt(plain, pass.copyOf())
        val b = BackupCrypto.encrypt(plain, pass.copyOf())
        assertFalse(String(a).contains("s3cr3t-Sample!"))
        assertNotEquals(header(a).getJSONObject("kdf").getString("salt"), header(b).getJSONObject("kdf").getString("salt"))
        assertNotEquals(header(a).getJSONObject("cipher").getString("nonce"), header(b).getJSONObject("cipher").getString("nonce"))
        assertFalse(a.contentEquals(b))
    }

    @Test fun wrongPassphraseFails() {
        val file = BackupCrypto.encrypt(plain, pass.copyOf())
        expectWrong { BackupCrypto.decrypt(file, "correct horse battery!".toCharArray()) }
    }

    @Test fun tamperedCiphertextFails() {
        val file = BackupCrypto.encrypt(plain, pass.copyOf())
        val lines = String(file).trim().split('\n')
        val ct = Base64.decode(lines[1], Base64.NO_WRAP)
        ct[ct.size / 2] = (ct[ct.size / 2].toInt() xor 1).toByte()
        val bad = (lines[0] + "\n" + Base64.encodeToString(ct, Base64.NO_WRAP) + "\n").toByteArray()
        expectWrong { BackupCrypto.decrypt(bad, pass.copyOf()) }
    }

    @Test fun tamperedHeaderFails() {
        // Header is GCM AAD: a re-ordered / edited header (same values, different bytes) must not decrypt.
        val file = BackupCrypto.encrypt(plain, pass.copyOf())
        val lines = String(file).trim().split('\n')
        val h = JSONObject(lines[0]).put("note", "edited")
        expectWrong { BackupCrypto.decrypt((h.toString() + "\n" + lines[1] + "\n").toByteArray(), pass.copyOf()) }
    }

    @Test fun weakOrUnknownParamsRejected() {
        val file = BackupCrypto.encrypt(plain, pass.copyOf())
        val lines = String(file).trim().split('\n')
        val weak = JSONObject(lines[0]).apply { getJSONObject("kdf").put("iterations", 1000) }
        expectBadFormat { BackupCrypto.decrypt((weak.toString() + "\n" + lines[1]).toByteArray(), pass.copyOf()) }
        val future = JSONObject(lines[0]).put("version", 2)
        expectBadFormat { BackupCrypto.decrypt((future.toString() + "\n" + lines[1]).toByteArray(), pass.copyOf()) }
        expectBadFormat { BackupCrypto.decrypt("name,url,username,password\n".toByteArray(), pass.copyOf()) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortPassphraseRefused() { BackupCrypto.encrypt(plain, "short".toCharArray()) }

    private fun expectWrong(block: () -> Unit) {
        try { block(); fail("expected WrongPassphraseOrDamaged") } catch (_: BackupException.WrongPassphraseOrDamaged) {}
    }

    private fun expectBadFormat(block: () -> Unit) {
        try { block(); fail("expected BadFormat") } catch (_: BackupException.BadFormat) {}
    }
}
