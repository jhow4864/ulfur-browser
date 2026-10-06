package com.jamhowman.beastbrowser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.passwords.PasswordVault
import com.jamhowman.beastbrowser.passwords.SavedLogin
import com.jamhowman.beastbrowser.passwords.UnsavedChanges
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Bug-hunt #2: a password saved or deleted while the vault was open, but not yet written (the per-use key needs a
 * fingerprint per write), must survive the vault idle-locking and be written after the next unlock.
 * An ordinary AES-GCM key stands in for the Keystore cipher, as in BackupVaultImportTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnsavedPasswordChangesTest {
    private lateinit var ctx: Context
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val vaultFile get() = File(ctx.filesDir, "passwords.vault")
    private val metaFile get() = File(ctx.filesDir, "passwords.meta.json")

    private fun encryptCipher() = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }

    private fun readVault(): List<SavedLogin> {
        val raw = vaultFile.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        val arr = JSONObject(String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)).getJSONArray("logins")
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); SavedLogin.fromMetaJson(o, o.optString("password")) }
    }

    private fun login(origin: String, user: String, pw: String, guid: String = java.util.UUID.randomUUID().toString()) =
        SavedLogin(guid = guid, origin = origin, username = user, password = pw, createdAt = 1, updatedAt = 1)

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        PasswordVault::class.java.getDeclaredField("app").apply { isAccessible = true }.set(null, ctx)
        PasswordVault::class.java.getDeclaredField("initialized").apply { isAccessible = true }.setBoolean(null, true)
        PasswordVault.resetVault()
        // A vault on disk holding a.test and b.test, open and fully written.
        PasswordVault.completeImportWithEncryptCipher(encryptCipher(), listOf(
            login("https://a.test", "ann", "pw-a"), login("https://b.test", "bob", "pw-b"),
        ))
        assertFalse(PasswordVault.needsPersistAuth())
    }

    @After fun tearDown() {
        PasswordVault.lock(); PasswordVault.resetVault()
        PasswordVault::class.java.getDeclaredField("initialized").apply { isAccessible = true }.setBoolean(null, false)
    }

    @Test fun saveAndDeleteSurviveAnIdleLockAndAreWrittenAfterTheNextUnlock() {
        val bob = PasswordVault.fetchAll().single { it.username == "bob" }
        PasswordVault.save(login("https://c.test", "cat", "pw-c"))   // fingerprint confirm cancelled: not written
        PasswordVault.delete(bob.guid)
        assertTrue(PasswordVault.needsPersistAuth())

        PasswordVault.lock()                                          // idle timer fires
        assertFalse(PasswordVault.isUnlocked())
        assertTrue(PasswordVault.hasCarriedOverChanges())
        assertFalse(PasswordVault.needsPersistAuth())
        assertEquals("file untouched while locked", setOf("ann", "bob"), readVault().map { it.username }.toSet())

        PasswordVault.openDecrypted(readVault())                      // next unlock
        assertTrue(PasswordVault.isUnlocked())
        assertFalse(PasswordVault.hasCarriedOverChanges())
        assertTrue("caller's flushPending will prompt to write", PasswordVault.needsPersistAuth())
        assertEquals(setOf("ann", "cat"), PasswordVault.fetchAll().map { it.username }.toSet())
        assertEquals("meta matches the file until the write", 2, JSONObject(metaFile.readText()).getInt("count"))

        PasswordVault.persistWithCipher(encryptCipher())              // flushPending's confirmed write
        assertFalse(PasswordVault.needsPersistAuth())
        assertEquals(setOf("ann", "cat"), readVault().map { it.username }.toSet())
        assertEquals("pw-c", readVault().single { it.username == "cat" }.password)
    }

    @Test fun changesCarryAcrossTwoLocksWithoutAWrite() {
        PasswordVault.save(login("https://c.test", "cat", "pw-c"))
        PasswordVault.lock()
        PasswordVault.openDecrypted(readVault())                      // unlocked to fill, no write
        PasswordVault.save(login("https://d.test", "dee", "pw-d"))
        PasswordVault.lock()
        PasswordVault.openDecrypted(readVault())
        assertEquals(setOf("ann", "bob", "cat", "dee"), PasswordVault.fetchAll().map { it.username }.toSet())
        assertTrue(PasswordVault.needsPersistAuth())
    }

    @Test fun lockWithNothingPendingKeepsNothing() {
        PasswordVault.lock()
        assertFalse(PasswordVault.hasCarriedOverChanges())
        PasswordVault.openDecrypted(readVault())
        assertFalse(PasswordVault.needsPersistAuth())
    }

    @Test fun resetVaultDropsCarriedOverChanges() {
        PasswordVault.save(login("https://c.test", "cat", "pw-c"))
        PasswordVault.lock()
        PasswordVault.resetVault()
        assertFalse(PasswordVault.hasCarriedOverChanges())
    }

    @Test fun unsavedChangesDiffApplyAndFold() {
        val a = login("https://a.test", "ann", "1", guid = "a")
        val b = login("https://b.test", "bob", "1", guid = "b")
        val a2 = a.copy(password = "2")
        val c = login("https://c.test", "cat", "1", guid = "c")
        val diff = UnsavedChanges.between(listOf(a, b), listOf(a2, c))
        assertEquals(listOf(a2, c), diff.upserts); assertEquals(setOf("b"), diff.deletes)
        assertEquals(listOf(a2, c), diff.applyTo(listOf(a, b)))
        assertTrue(UnsavedChanges.between(listOf(a, b), listOf(a, b)).isEmpty)
        // Re-adding a deleted guid later un-deletes it; deleting an earlier upsert drops it.
        val folded = diff.then(UnsavedChanges(listOf(b), setOf("c")))
        assertEquals(setOf("a", "b"), folded.upserts.map { it.guid }.toSet())
        assertEquals(setOf("c"), folded.deletes)
        assertEquals(listOf(a2, b), folded.applyTo(listOf(a, b)))
    }
}
