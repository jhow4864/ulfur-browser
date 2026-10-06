package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.passwords.SavedLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Static guards from the 2.3.0 password-vault review (backup exclusion, FLAG_SECURE, no secrets in logs/toString). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasswordSecurityTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    @Test fun vaultExcludedFromBackupAndDeviceTransfer() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(main, "res/xml/data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val sec = doc.getElementsByTagName(section).item(0) as Element
            val ex = sec.getElementsByTagName("exclude").let { l -> (0 until l.length).map { l.item(it) as Element } }
            val wholeDomains = ex.filter { !it.hasAttribute("path") }.map { it.getAttribute("domain") }.toSet()
            assertTrue("$section must exclude filesDir", "file" in wholeDomains)
            assertTrue("$section must exclude databases", "database" in wholeDomains)
            val paths = ex.filter { it.getAttribute("domain") == "file" }.map { it.getAttribute("path") }.toSet()
            listOf("passwords.vault", "passwords.vault.tmp", "passwords.meta.json").forEach { assertTrue("$section: $it", it in paths) }
        }
    }

    @Test fun passwordsScreenIsSecure() {
        val src = File(main, "java/com/jamhowman/beastbrowser/ui/PasswordsActivity.kt").readText()
        assertTrue(src.contains("FLAG_SECURE"))
    }

    @Test fun savedLoginToStringRedactsPassword() {
        val s = SavedLogin(origin = "https://example.org", username = "sample-user", password = "s3cr3t-Sample!").toString()
        assertFalse(s.contains("s3cr3t-Sample!"))
        assertFalse(s.contains("sample-user"))
        assertTrue(s.contains("example.org"))
    }

    @Test fun reReviewGuards() {
        val crypto = File(main, "java/com/jamhowman/beastbrowser/passwords/VaultCrypto.kt").readText()
        assertTrue("per-use auth on API 30+", crypto.contains("setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)"))
        assertTrue("API 24-29 per-use auth must be -1", crypto.contains("setUserAuthenticationValidityDurationSeconds(-1)"))
        assertFalse(crypto.contains("setUserAuthenticationValidityDurationSeconds(0)"))
        assertTrue(crypto.contains("setInvalidatedByBiometricEnrollment(true)"))
        val vault = File(main, "java/com/jamhowman/beastbrowser/passwords/PasswordVault.kt").readText()
        assertTrue("flush must not write after an idle re-lock", vault.contains("if (!isOpen) { onDone(false); return }"))
        assertFalse("CryptoObject prompts must not allow DEVICE_CREDENTIAL (API 26-29)", vault.contains("DEVICE_CREDENTIAL"))
        val prompts = File(main, "java/com/jamhowman/beastbrowser/ui/Prompts.kt").readText()
        assertTrue(prompts.contains("session.settings.usePrivateMode"))
        assertTrue("generated passwords filtered in private tabs", prompts.contains("filter { it.hint and generated == 0 }"))
        assertTrue("generated option never auto-confirmed", prompts.contains("options.size == 1 && options[0].hint and generated == 0"))
    }

    @Test fun resetVaultDeletesEveryVaultFile() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.jamhowman.beastbrowser.passwords.PasswordVault.init(ctx)
        val names = listOf("passwords.vault", "passwords.vault.tmp", "passwords.meta.json")
        names.forEach { File(ctx.filesDir, it).writeText("sample") }
        com.jamhowman.beastbrowser.passwords.PasswordVault.resetVault() // Keystore is absent under Robolectric; key delete is runCatching
        names.forEach { assertFalse("$it not deleted", File(ctx.filesDir, it).exists()) }
        assertFalse(com.jamhowman.beastbrowser.passwords.PasswordVault.isUnlocked())
        assertEquals(0, com.jamhowman.beastbrowser.passwords.PasswordVault.count())
        val crypto = File(main, "java/com/jamhowman/beastbrowser/passwords/VaultCrypto.kt").readText()
        val deleteKey = crypto.substringAfter("fun deleteKey()")
        assertTrue(deleteKey.contains("deleteEntry(ALIAS)") && deleteKey.contains("deleteEntry(LEGACY_ALIAS)"))
        // Reset must sit behind an explicit confirmation dialog.
        val ui = File(main, "java/com/jamhowman/beastbrowser/ui/PasswordsActivity.kt").readText()
        assertEquals(1, Regex("""PasswordVault\.resetVault\(\)""").findAll(ui).count())
        assertTrue(ui.substringBefore("PasswordVault.resetVault()").substringAfterLast("private fun ").startsWith("confirmReset"))
    }

    @Test fun metaOnlyWrittenAfterEncryptedWriteAndLockWarns() {
        val vault = File(main, "java/com/jamhowman/beastbrowser/passwords/PasswordVault.kt").readText()
        fun body(name: String) = vault.substringAfter("fun $name(").substringBefore("\n    fun ")
        listOf("save", "delete", "clearAll").forEach {
            assertFalse("$it must not write meta before the encrypted write", body(it).contains("writeMeta("))
        }
        assertTrue(body("markUsed").contains("if (!pendingPersist) writeMeta("))
        assertTrue(body("persistWithCipher").contains("writeMeta(list)"))
        assertTrue("flush must report write failures", body("flushPending").contains("onDone(ok)"))
        val lock = body("lock")
        assertTrue(lock.contains("pendingPersist && isOpen") && lock.contains("Toast.makeText"))
    }

    @Test fun metaJsonAndMarkUsedNeverCarryPasswords() {
        val meta = SavedLogin(origin = "https://example.org", username = "sample-user", password = "s3cr3t-Sample!").toMetaJson()
        assertFalse(meta.has("password"))
        assertFalse(meta.toString().contains("s3cr3t-Sample!"))
        val vault = File(main, "java/com/jamhowman/beastbrowser/passwords/PasswordVault.kt").readText()
        val markUsed = vault.substringAfter("fun markUsed(").substringBefore("\n    fun ")
        assertFalse("markUsed must not encrypt/prompt", markUsed.contains("persistUnlocked(") || markUsed.contains("persistWithCipher(") || markUsed.contains("flushPending"))
        val delegate = File(main, "java/com/jamhowman/beastbrowser/passwords/VaultStorageDelegate.kt").readText()
        assertFalse("onLoginUsed must not prompt", delegate.substringAfter("fun onLoginUsed(").substringBefore("\n    private fun ").contains("flushPending"))
    }

    @Test fun passwordCodeNeverLogsLoginsOrThrowablesFromDecrypt() {
        val dir = File(main, "java/com/jamhowman/beastbrowser/passwords")
        val logs = dir.walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (Regex("""\bLog\.[vdiwe]\(|println\(|printStackTrace""").containsMatchIn(l)) "${f.name}:${i + 1}: ${l.trim()}" else null }
        }.toList()
        logs.forEach { l ->
            assertFalse("login object in log: $l", Regex("""login|entry|password|plain|unlocked""", RegexOption.IGNORE_CASE).containsMatchIn(l.substringAfter("Log.").substringAfter(",")))
        }
        assertEquals("println/printStackTrace not allowed", 0, logs.count { it.contains("println(") || it.contains("printStackTrace") })
        val vault = File(dir, "PasswordVault.kt").readText()
        assertFalse("decrypt failure must not log the throwable (org.json embeds input text)",
            vault.contains("Log.e(TAG, \"unlock decrypt failed\", e)"))
    }
}
