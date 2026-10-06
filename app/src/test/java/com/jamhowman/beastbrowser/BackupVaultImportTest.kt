package com.jamhowman.beastbrowser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.passwords.PasswordVault
import com.jamhowman.beastbrowser.passwords.PasswordVault.ImportAuth
import com.jamhowman.beastbrowser.passwords.SavedLogin
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * 2.4.1: password import with one biometric prompt where the per-use vault key allows it. The Android Keystore
 * isn't available under Robolectric, so an ordinary AES-GCM key stands in for the authenticated Keystore cipher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupVaultImportTest {
    private lateinit var ctx: Context
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private val vaultFile get() = File(ctx.filesDir, "passwords.vault")
    private val metaFile get() = File(ctx.filesDir, "passwords.meta.json")

    private fun encryptCipher() = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }

    private fun readVault(): List<JSONObject> {
        val raw = vaultFile.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        val arr = JSONObject(String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)).getJSONArray("logins")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun login(origin: String, user: String, pw: String) = SavedLogin(origin = origin, username = user, password = pw)

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // PasswordVault is a process-wide object: point it at this test's app context.
        PasswordVault::class.java.getDeclaredField("app").apply { isAccessible = true }.set(null, ctx)
        PasswordVault::class.java.getDeclaredField("initialized").apply { isAccessible = true }.setBoolean(null, true)
        PasswordVault.resetVault()
    }

    @After fun tearDown() {
        PasswordVault.lock(); PasswordVault.resetVault()
        // Let the next test class's PasswordVault.init(ctx) take its own context again.
        PasswordVault::class.java.getDeclaredField("initialized").apply { isAccessible = true }.setBoolean(null, false)
    }

    @Test fun promptPlan() {
        assertEquals(ImportAuth.SAVE_ONLY, PasswordVault.importAuthPlan(open = true, vaultFileExists = true))
        assertEquals(ImportAuth.SAVE_ONLY, PasswordVault.importAuthPlan(open = true, vaultFileExists = false))
        assertEquals(ImportAuth.CREATE_WITH_IMPORT, PasswordVault.importAuthPlan(open = false, vaultFileExists = false))
        assertEquals(ImportAuth.UNLOCK_THEN_SAVE, PasswordVault.importAuthPlan(open = false, vaultFileExists = true))
        assertEquals(1, ImportAuth.SAVE_ONLY.prompts)
        assertEquals(1, ImportAuth.CREATE_WITH_IMPORT.prompts)
        assertEquals("existing locked vault: decrypt + encrypt need separate per-use auths", 2, ImportAuth.UNLOCK_THEN_SAVE.prompts)
    }

    @Test fun newVaultIsCreatedWithImportedLoginsInOneAuthorisedWrite() {
        assertFalse(vaultFile.exists())
        val out = PasswordVault.completeImportWithEncryptCipher(encryptCipher(), listOf(
            login("https://a.test", "ann", "pw-a"), login("https://b.test", "bob", "pw-b"), login("https://c.test", "x", ""),
        ))
        assertTrue(out.saved); assertNull(out.error)
        assertEquals(2, out.result!!.added); assertEquals(1, out.result!!.skipped)
        assertEquals(setOf("pw-a", "pw-b"), readVault().map { it.getString("password") }.toSet())
        assertTrue(PasswordVault.isUnlocked())
        assertFalse("nothing left to write", PasswordVault.needsPersistAuth())
        assertEquals(2, PasswordVault.fetchAll().size)
        val meta = metaFile.readText()
        assertEquals(2, JSONObject(meta).getInt("count"))
        assertFalse("meta never holds passwords", meta.contains("pw-a") || meta.contains("password"))
    }

    @Test fun vaultUnlockedWhilePromptWasUpIsMergedNotReplaced() {
        PasswordVault.completeImportWithEncryptCipher(encryptCipher(), listOf(login("https://a.test", "ann", "old")))
        val out = PasswordVault.completeImportWithEncryptCipher(encryptCipher(), listOf(
            login("https://a.test", "ann", "new"), login("https://d.test", "dee", "pw-d"),
        ))
        assertEquals(1, out.result!!.added); assertEquals(1, out.result!!.updated)
        val rows = readVault()
        assertEquals(2, rows.size)
        assertEquals("new", rows.single { it.getString("username") == "ann" }.getString("password"))
    }

    @Test fun refusesToOverwriteAVaultItCannotRead() {
        val existing = ByteArray(64) { it.toByte() }
        vaultFile.writeBytes(existing) // e.g. created by another unlock, then idle-locked, while the prompt was up
        assertFalse(PasswordVault.isUnlocked())
        val out = PasswordVault.completeImportWithEncryptCipher(encryptCipher(), listOf(login("https://a.test", "ann", "pw")))
        assertNull(out.result); assertFalse(out.saved)
        assertArrayEquals(existing, vaultFile.readBytes())
        assertFalse(PasswordVault.isUnlocked())
    }

    @Test fun securityGuardsForTheImportFlow() {
        val vault = File(main, "java/com/jamhowman/beastbrowser/passwords/PasswordVault.kt").readText()
        val crypto = File(main, "java/com/jamhowman/beastbrowser/passwords/VaultCrypto.kt").readText()
        // Key design unchanged: same aliases, per-use strong-biometric auth, nothing stored outside the Keystore.
        assertTrue(crypto.contains("ALIAS = \"beast_password_vault_v2\"") && crypto.contains("LEGACY_ALIAS = \"beast_password_vault_v1\""))
        assertTrue(crypto.contains("setUserAuthenticationRequired(true)"))
        assertTrue(crypto.contains("setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)"))
        val create = vault.substringAfter("private fun createWithImport(").substringBefore("\n    internal fun ")
        assertTrue("single prompt uses a Keystore CryptoObject", create.contains("VaultCrypto.cipherForEncrypt()") && create.contains("CryptoObject(cipher)"))
        val complete = vault.substringAfter("internal fun completeImportWithEncryptCipher(").substringBefore("\n    fun ")
        assertTrue("must not replace a locked vault it can't read", complete.contains("if (!isOpen && vaultFileExists()) return"))
        // The backup screen goes through importWithAuth only (no extra unlock / save prompts of its own on import).
        val ui = File(main, "java/com/jamhowman/beastbrowser/ui/BackupUi.kt").readText()
        val importPart = ui.substringAfter("// ------------------------------------------------------------------ import")
        assertTrue(importPart.contains("PasswordVault.importWithAuth("))
        assertFalse(importPart.contains("PasswordVault.unlock(") || importPart.contains("PasswordVault.flushPending("))
    }
}
