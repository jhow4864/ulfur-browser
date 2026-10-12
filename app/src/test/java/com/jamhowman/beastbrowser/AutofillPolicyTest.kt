package com.jamhowman.beastbrowser

import android.text.InputType
import android.view.View
import com.jamhowman.beastbrowser.autofill.AutofillFields
import com.jamhowman.beastbrowser.autofill.FillPolicy
import com.jamhowman.beastbrowser.autofill.FillPolicy.Decision
import com.jamhowman.beastbrowser.passwords.PasswordVault
import com.jamhowman.beastbrowser.passwords.SavedLogin
import com.jamhowman.beastbrowser.passwords.VaultStorageDelegate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.xml.parsers.DocumentBuilderFactory

/** 2.10 Ulfur #2 (Android Autofill): one test group per rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutofillPolicyTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    @After fun tearDown() = PasswordVault.lock()

    // ---------------------------------------------------------------- rule 1a: websites by registrable domain

    @Test fun websiteMatchesSameRegistrableDomain() {
        assertTrue(FillPolicy.webMatch("https://example.com", "https://example.com/login"))
        assertTrue(FillPolicy.webMatch("https://www.example.com", "https://accounts.example.com"))
        assertTrue(FillPolicy.webMatch("https://bbc.co.uk", "https://account.bbc.co.uk"))
        assertTrue(FillPolicy.webMatch("http://example.com", "https://example.com")) // upgrade is fine
        assertTrue(FillPolicy.webMatch("example.com", "https://EXAMPLE.com."))
    }

    @Test fun lookalikesNeverMatch() {
        val saved = "https://example.com"
        listOf(
            "https://examp1e.com", "https://example.co", "https://example.com.evil.net", "https://evil-example.com",
            "https://exampleexample.com", "https://example.net", "https://xn--exmple-cua.com",
            "https://ex\u0430mple.com", // Cyrillic 'а'
            "https://example.com@evil.net", "https://evil.net/https://example.com", "https://evil.net?u=example.com",
            "javascript:alert(1)", "file:///example.com", "", "not a url",
        ).forEach { assertFalse(it, FillPolicy.webMatch(saved, it)) }
        assertFalse(FillPolicy.webMatch("https://bank.co.uk", "https://evil.co.uk"))
        assertFalse(FillPolicy.webMatch("https://alice.github.io", "https://mallory.github.io"))
        assertFalse(FillPolicy.webMatch("https://192.168.1.1", "https://192.168.1.2"))
    }

    @Test fun httpsLoginNeverOfferedOnHttp() {
        assertFalse(FillPolicy.webMatch("https://example.com", "http://example.com"))
    }

    @Test fun vaultFetchUsesRegistrableMatch() {
        PasswordVault.init(org.robolectric.RuntimeEnvironment.getApplication())
        PasswordVault.openDecrypted(listOf(
            SavedLogin(origin = "https://example.com", username = "a", password = "pw-a"),
            SavedLogin(origin = "https://examp1e.com", username = "b", password = "pw-b"),
        ))
        assertEquals(listOf("a"), PasswordVault.fetchForOrigin("https://login.example.com").map { it.username })
        assertTrue(PasswordVault.fetchForOrigin("https://example.com.evil.net").isEmpty())
    }

    // ---------------------------------------------------------------- rule 1b: apps by signature

    private val cert = ByteArray(32) { (it * 7 + 3).toByte() }
    private val other = ByteArray(32) { (it * 5 + 1).toByte() }
    private val b64 = Base64.getUrlEncoder().encodeToString(cert)

    @Test fun appMatchesOnlyWithRightSignature() {
        val origin = "android://$b64@com.example.app/"
        assertTrue(FillPolicy.appMatch(origin, "com.example.app", listOf(cert)))
        assertTrue(FillPolicy.appMatch("android://${Base64.getEncoder().encodeToString(cert)}@com.example.app", "com.example.app", listOf(other, cert)))
        val hex = cert.joinToString(":") { "%02X".format(it) }
        assertTrue(FillPolicy.appMatch("android://$hex@com.example.app", "com.example.app", listOf(cert)))
        assertFalse("re-signed clone", FillPolicy.appMatch(origin, "com.example.app", listOf(other)))
        assertFalse("other package", FillPolicy.appMatch(origin, "com.example.app2", listOf(cert)))
        assertFalse("unsigned", FillPolicy.appMatch(origin, "com.example.app", emptyList()))
        assertFalse("web login to app", FillPolicy.appMatch("https://example.com", "com.example.app", listOf(cert)))
        assertFalse("short hash", FillPolicy.appMatch("android://AAAA@com.example.app", "com.example.app", listOf(cert)))
        assertNull(FillPolicy.site("android://$b64@com.example.app")) // app logins never match a website
    }

    // ---------------------------------------------------------------- rule 2: biometric/PIN before every fill

    @Test fun everyFillGoesThroughFillAuth() {
        val prompts = File(main, "java/com/jamhowman/beastbrowser/ui/Prompts.kt").readText()
        val select = prompts.substringAfter("override fun onLoginSelect(").substringBefore("private fun wireGenerator")
        assertTrue(select.contains("FillAuth.authorize("))
        // The real password only enters the response inside the auth callback; no silent single-option confirm.
        assertFalse(select.contains("options.size == 1"))
        assertFalse(select.contains("prompt.confirm(options["))
        assertFalse(select.contains("prompt.confirm(opt)"))
        val auth = File(main, "java/com/jamhowman/beastbrowser/autofill/AutofillAuthActivity.kt").readText()
        assertTrue(auth.contains("FillAuth.authorize("))
        assertTrue(auth.substringAfter("FillAuth.authorize(").contains("EXTRA_AUTHENTICATION_RESULT"))
        val fa = File(main, "java/com/jamhowman/beastbrowser/autofill/FillAuth.kt").readText()
        // Unlocked vault still prompts: no early "already unlocked → ok" shortcut.
        assertFalse(Regex("""isUnlocked\(\)\)\s*\{?\s*(touch\(\);\s*)?onResult\(true\)""").containsMatchIn(fa))
        assertTrue(fa.contains("prompt.authenticate("))
        val svc = File(main, "java/com/jamhowman/beastbrowser/autofill/UlfurAutofillService.kt").readText()
        assertTrue("every dataset is locked", svc.contains("setAuthentication("))
        assertFalse("service never decrypts", svc.contains("loginFor("))
    }

    @Test fun geckoNeverGetsPasswords() {
        PasswordVault.init(org.robolectric.RuntimeEnvironment.getApplication())
        PasswordVault.openDecrypted(listOf(SavedLogin(origin = "https://example.com", username = "a", password = "secret")))
        val arr = await(VaultStorageDelegate({ null }).onLoginFetch("https://example.com"))
        assertEquals(1, arr.size)
        assertEquals("a", arr[0].username)
        assertEquals("", arr[0].password)
        assertEquals(0, await(VaultStorageDelegate({ null }).onLoginFetch()).size)
    }

    // ---------------------------------------------------------------- rule 3: never in private or Ghost tabs

    @Test fun neverInPrivateOrGhost() {
        val ok = FillPolicy.FieldContext(topFrame = true, sameOriginFrame = true, visible = true, atMs = 1000)
        assertEquals(Decision.PRIVATE_TAB, FillPolicy.browserFill(true, ok, 1000))
        assertEquals(Decision.ALLOW, FillPolicy.browserFill(false, ok, 1000))
        PasswordVault.init(org.robolectric.RuntimeEnvironment.getApplication())
        PasswordVault.openDecrypted(listOf(SavedLogin(origin = "https://example.com", username = "a", password = "x")))
        val priv = VaultStorageDelegate({ null }, { null }, { true })
        assertEquals(0, await(priv.onLoginFetch("https://example.com")).size)
        val mainAct = File(main, "java/com/jamhowman/beastbrowser/ui/MainActivity.kt").readText()
        assertTrue(mainAct.contains("current?.isPrivate == true || realm == Realm.GHOST"))
    }

    // ---------------------------------------------------------------- rule 4: Keystore-encrypted storage

    @Test fun credentialsEncryptedWithKeystoreKey() {
        val crypto = File(main, "java/com/jamhowman/beastbrowser/passwords/VaultCrypto.kt").readText()
        assertTrue(crypto.contains("\"AndroidKeyStore\"") || crypto.contains("KEYSTORE"))
        assertTrue(crypto.contains("setUserAuthenticationRequired(true)"))
        assertTrue(crypto.contains("GCM"))
        val vault = File(main, "java/com/jamhowman/beastbrowser/passwords/PasswordVault.kt").readText()
        // The only write of the vault file is the encrypted blob.
        assertFalse(Regex("""File\(app\.filesDir, FILE\)\.write(Text|Bytes)\((?!.*encrypt|iv)""").containsMatchIn(vault))
        // Meta for the autofill service carries no password.
        val meta = SavedLogin(origin = "https://e.com", username = "u", password = "hunter2").toMetaJson().toString()
        assertFalse(meta.contains("hunter2"))
    }

    // ---------------------------------------------------------------- rule 5: hidden fields / cross-origin frames

    @Test fun neverIntoHiddenOrCrossOriginFrames() {
        val now = 50_000L
        fun ctx(top: Boolean, same: Boolean, vis: Boolean, at: Long = now) = FillPolicy.FieldContext(top, same, vis, at)
        assertEquals(Decision.ALLOW, FillPolicy.browserFill(false, ctx(true, true, true), now))
        assertEquals(Decision.ALLOW, FillPolicy.browserFill(false, ctx(false, true, true), now)) // same-origin frame
        assertEquals(Decision.CROSS_ORIGIN_FRAME, FillPolicy.browserFill(false, ctx(false, false, true), now))
        assertEquals(Decision.HIDDEN_FIELD, FillPolicy.browserFill(false, ctx(true, true, false), now))
        assertEquals(Decision.CROSS_ORIGIN_FRAME, FillPolicy.browserFill(false, ctx(false, false, false), now))
        assertEquals(Decision.NO_FIELD_INFO, FillPolicy.browserFill(false, null, now))
        assertEquals(Decision.NO_FIELD_INFO, FillPolicy.browserFill(false, ctx(true, true, true, at = now - 6_000), now))
        val js = File(main, "assets/extensions/beast-helper/content.js").readText()
        assertTrue(js.contains("type: \"loginFocus\""))
    }

    @Test fun appFieldsSkipHidden() {
        fun n(id: Int, type: Int, vis: Boolean = true, entry: String? = null, hints: List<String> = emptyList()) =
            AutofillFields.Node(id, hints, type, entry, null, vis, true, true)
        val text = InputType.TYPE_CLASS_TEXT
        val pw = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val found = AutofillFields.find(listOf(n(1, text, entry = "email"), n(2, pw, vis = false), n(3, pw)))!!
        assertEquals(1, found.username); assertEquals(3, found.password)
        assertNull(AutofillFields.find(listOf(n(1, text), n(2, pw, vis = false))))
        assertEquals(5, AutofillFields.find(listOf(n(4, text, hints = listOf(View.AUTOFILL_HINT_USERNAME)), n(5, text, hints = listOf(View.AUTOFILL_HINT_PASSWORD))))!!.password)
        val svc = File(main, "java/com/jamhowman/beastbrowser/autofill/UlfurAutofillService.kt").readText()
        assertTrue("web content inside other apps is not filled", svc.contains("if (scan.hasWebDomain) return callback.onSuccess(null)"))
    }

    // ---------------------------------------------------------------- rule 6: no secrets in logs

    @Test fun autofillCodeNeverLogs() {
        val files = listOf("autofill", "passwords").flatMap { File(main, "java/com/jamhowman/beastbrowser/$it").walk().filter { f -> f.extension == "kt" }.toList() } +
            File(main, "java/com/jamhowman/beastbrowser/ui/Prompts.kt")
        val autofill = files.filter { it.path.contains("/autofill/") }
        autofill.forEach { f ->
            assertFalse("${f.name} must not log", Regex("""\bLog\.[vdiwe]\(|println\(|printStackTrace|Timber""").containsMatchIn(f.readText()))
        }
        val prompts = files.last().readText().substringAfter("override fun onLoginSelect(").substringBefore("private fun wireGenerator")
        assertFalse(Regex("""\bLog\.""").containsMatchIn(prompts))
        assertFalse(SavedLogin(origin = "https://e.com", username = "u", password = "hunter2").toString().contains("hunter2"))
        assertFalse(FillPolicy.FieldContext(true, true, true, 1).toString().contains("hunter2"))
    }

    // ---------------------------------------------------------------- manifest hardening

    @Test fun manifestLocksDownAutofillComponents() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(main, "AndroidManifest.xml"))
        val ns = "http://schemas.android.com/apk/res/android"
        fun find(tag: String, name: String) = (0 until doc.getElementsByTagName(tag).length)
            .map { doc.getElementsByTagName(tag).item(it) as org.w3c.dom.Element }.first { it.getAttributeNS(ns, "name") == name }
        val svc = find("service", ".autofill.UlfurAutofillService")
        assertEquals("android.permission.BIND_AUTOFILL_SERVICE", svc.getAttributeNS(ns, "permission"))
        assertEquals("false", find("activity", ".autofill.AutofillAuthActivity").getAttributeNS(ns, "exported"))
    }

    private fun await(r: GeckoResult<Array<Autocomplete.LoginEntry>>): Array<Autocomplete.LoginEntry> {
        val latch = CountDownLatch(1); val box = AtomicReference<Array<Autocomplete.LoginEntry>>(emptyArray())
        r.accept({ box.set(it ?: emptyArray()); latch.countDown() }, { latch.countDown() })
        val until = System.currentTimeMillis() + 2000
        while (latch.count > 0 && System.currentTimeMillis() < until) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); latch.await(10, TimeUnit.MILLISECONDS)
        }
        return box.get()
    }
}
