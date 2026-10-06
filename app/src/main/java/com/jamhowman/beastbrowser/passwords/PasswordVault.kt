package com.jamhowman.beastbrowser.passwords

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.Autocomplete
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher

/**
 * Encrypted password vault. Plaintext lives in memory only while unlocked.
 * On disk: AES-GCM blob under `filesDir/passwords.vault`, keyed by Android Keystore
 * (per-use biometric via CryptoObject). Auto-locks after [IDLE_MS] of inactivity.
 *
 * Engineer wires [Autocomplete.StorageDelegate] to [fetchForOrigin] / [saveLogin] / [markUsed].
 * See `docs/password-vault.md`.
 */
object PasswordVault {
    private const val TAG = "BeastPasswords"
    private const val FILE = "passwords.vault"
    private const val META = "passwords.meta.json" // guid/origin/username only (no secrets)
    /** Idle re-lock after browser/Settings unlock (5 minutes). */
    private const val IDLE_MS = 5 * 60 * 1000L

    private lateinit var app: Context
    private var initialized = false

    @Volatile private var unlocked: List<SavedLogin> = emptyList()
    @Volatile private var isOpen = false
    @Volatile private var lastTouchMs = 0L

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleLockRunnable = Runnable { lock() }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        initialized = true
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyListeners() { listeners.forEach { runCatching { it() } } }

    fun isUnlocked(): Boolean {
        enforceIdle()
        return isOpen
    }
    fun count(): Int = if (isUnlocked()) unlocked.size else metaCount()
    fun hasPasswords(): Boolean = count() > 0

    fun canAuthenticate(ctx: Context): Boolean {
        val m = BiometricManager.from(ctx)
        // Per-use CryptoObject requires BIOMETRIC_STRONG (not device credential alone).
        return m.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun lock() {
        val discardedUnsaved = pendingPersist && isOpen
        mainHandler.removeCallbacks(idleLockRunnable)
        unlocked = emptyList()
        isOpen = false
        lastTouchMs = 0L
        pendingPersist = false
        notifyListeners()
        if (discardedUnsaved && initialized) {
            mainHandler.post {
                Toast.makeText(
                    app,
                    "Unsaved password changes were discarded — confirm fingerprint next time to keep them",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Wipe Keystore key + vault files. Use after biometric enrolment change or a
     * permanently unreadable vault (v1 key / AEAD failure). Irreversible.
     */
    fun resetVault() {
        if (!initialized) return
        mainHandler.removeCallbacks(idleLockRunnable)
        unlocked = emptyList()
        isOpen = false
        lastTouchMs = 0L
        pendingPersist = false
        runCatching { VaultCrypto.deleteKey() }
        runCatching { File(app.filesDir, FILE).delete() }
        runCatching { File(app.filesDir, "$FILE.tmp").delete() }
        runCatching { File(app.filesDir, META).delete() }
        notifyListeners()
    }

    private fun touch() {
        lastTouchMs = System.currentTimeMillis()
        mainHandler.removeCallbacks(idleLockRunnable)
        if (isOpen) mainHandler.postDelayed(idleLockRunnable, IDLE_MS)
    }

    private fun enforceIdle() {
        if (!isOpen) return
        if (lastTouchMs > 0L && System.currentTimeMillis() - lastTouchMs > IDLE_MS) {
            lock()
        }
    }

    /**
     * Unlock with strong biometric and a [BiometricPrompt.CryptoObject].
     * Creates an empty vault on first use.
     */
    fun unlock(activity: FragmentActivity, onResult: (ok: Boolean, error: String?) -> Unit) {
        init(activity)
        enforceIdle()
        if (isOpen) { touch(); onResult(true, null); return }
        if (!canAuthenticate(activity)) {
            onResult(false, "Set up a fingerprint or face unlock in Settings first"); return
        }
        val file = File(app.filesDir, FILE)
        try {
            val cipher: Cipher
            val decrypting: Boolean
            if (file.isFile && file.length() > VaultCrypto.IV_SIZE) {
                val raw = file.readBytes()
                val iv = raw.copyOfRange(0, VaultCrypto.IV_SIZE)
                cipher = VaultCrypto.cipherForDecrypt(iv)
                decrypting = true
            } else {
                cipher = VaultCrypto.cipherForEncrypt()
                decrypting = false
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val c = result.cryptoObject?.cipher
                        if (c == null) { onResult(false, "Crypto unlock failed"); return }
                        try {
                            if (decrypting) {
                                val raw = file.readBytes()
                                val ct = raw.copyOfRange(VaultCrypto.IV_SIZE, raw.size)
                                val plain = VaultCrypto.decrypt(c, ct)
                                unlocked = parseVault(String(plain, Charsets.UTF_8))
                            } else {
                                unlocked = emptyList()
                                persistWithCipher(c, emptyList())
                            }
                            isOpen = true
                            touch()
                            writeMeta(unlocked)
                            notifyListeners()
                            onResult(true, null)
                        } catch (e: Exception) {
                            // Never log the throwable: org.json exceptions embed the (decrypted) input text.
                            Log.e(TAG, "unlock decrypt failed: ${e.javaClass.simpleName}")
                            onResult(false, unrecoverableMessage(e) ?: "Couldn't open vault")
                        }
                    }

                    override fun onAuthenticationError(code: Int, errString: CharSequence) {
                        if (code == BiometricPrompt.ERROR_USER_CANCELED ||
                            code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            code == BiometricPrompt.ERROR_CANCELED
                        ) onResult(false, null)
                        else onResult(false, errString.toString())
                    }
                })
            prompt.authenticate(
                cryptoPromptInfo("Unlock password vault", "Saved logins are encrypted on this phone"),
                BiometricPrompt.CryptoObject(cipher),
            )
        } catch (e: Exception) {
            Log.e(TAG, "unlock start failed: ${e.javaClass.simpleName}")
            onResult(false, unrecoverableMessage(e) ?: (e.message ?: "Unlock failed"))
        }
    }

    /** Error string when the vault/key is permanently unreadable — UI should offer Reset vault. */
    const val ERR_NEEDS_RESET = "Vault can't be opened — use Reset vault"

    private fun unrecoverableMessage(e: Throwable): String? {
        var cur: Throwable? = e
        while (cur != null) {
            when (cur) {
                is android.security.keystore.KeyPermanentlyInvalidatedException -> return ERR_NEEDS_RESET
                is javax.crypto.AEADBadTagException -> return ERR_NEEDS_RESET
            }
            val msg = cur.message.orEmpty()
            if (cur is java.security.InvalidKeyException &&
                msg.contains("permanently invalidated", ignoreCase = true)
            ) return ERR_NEEDS_RESET
            if (msg.contains("KeyPermanentlyInvalidated", ignoreCase = true)) return ERR_NEEDS_RESET
            cur = cur.cause
        }
        return null
    }

    /** BIOMETRIC_STRONG only — required for CryptoObject with per-use Keystore keys (all API levels). */
    private fun cryptoPromptInfo(title: String, subtitle: String): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .setConfirmationRequired(false)
            .build()

    // -------------------- StorageDelegate-facing API --------------------

    /** Returns matching logins for a site origin (e.g. https://example.com). Empty if locked. */
    fun fetchForOrigin(origin: String?): List<SavedLogin> {
        if (!isUnlocked()) return emptyList()
        touch()
        if (origin.isNullOrBlank()) return unlocked.toList()
        val key = normalizeOrigin(origin)
        return unlocked.filter { normalizeOrigin(it.origin) == key || originsMatch(it.origin, origin) }
    }

    fun fetchAll(): List<SavedLogin> {
        if (!isUnlocked()) return emptyList()
        touch()
        return unlocked.toList()
    }

    fun saveLogin(entry: Autocomplete.LoginEntry) {
        if (!isUnlocked()) return
        save(SavedLogin.fromGecko(entry))
    }

    fun save(login: SavedLogin) {
        if (!isUnlocked()) return
        touch()
        val now = System.currentTimeMillis()
        val key = normalizeOrigin(login.origin)
        val existing = unlocked.indexOfFirst {
            it.guid == login.guid ||
                (normalizeOrigin(it.origin) == key && it.username == login.username)
        }
        val next = if (existing >= 0) {
            val old = unlocked[existing]
            login.copy(
                guid = old.guid,
                createdAt = old.createdAt,
                updatedAt = now,
                timesUsed = old.timesUsed,
            )
        } else login.copy(updatedAt = now)
        unlocked = if (existing >= 0) unlocked.toMutableList().also { it[existing] = next }
        else unlocked + next
        persistUnlocked()
        // Meta only updated in persistWithCipher after the encrypted write succeeds.
        notifyListeners()
    }

    fun delete(guid: String) {
        if (!isUnlocked()) return
        touch()
        unlocked = unlocked.filterNot { it.guid == guid }
        persistUnlocked()
        notifyListeners()
    }

    fun markUsed(entry: Autocomplete.LoginEntry) {
        if (!isUnlocked()) return
        touch()
        val guid = entry.guid ?: return
        unlocked = unlocked.map {
            if (it.guid == guid) it.copy(timesUsed = it.timesUsed + 1, updatedAt = System.currentTimeMillis()) else it
        }
        // Meta only — never re-encrypt on autofill submit (per-use key would biometric-prompt every time).
        // timesUsed lands in the encrypted blob on the next real save/delete.
        // Skip while a write is pending, or meta would list unsaved adds/deletes (persistWithCipher writes it).
        if (!pendingPersist) writeMeta(unlocked)
    }

    fun clearAll() {
        if (!isUnlocked()) return
        touch()
        unlocked = emptyList()
        persistUnlocked()
        notifyListeners()
    }

    fun toGeckoArray(list: List<SavedLogin>): Array<Autocomplete.LoginEntry> =
        list.map { it.toGecko() }.toTypedArray()

    // -------------------- persistence --------------------

    @Volatile private var pendingPersist = false

    private fun persistUnlocked() {
        if (!isOpen) return
        // Per-use Keystore keys can't encrypt without BiometricPrompt — always queue for flushPending.
        pendingPersist = true
    }

    fun needsPersistAuth(): Boolean = pendingPersist

    /** Call after a fresh biometric CryptoObject encrypt cipher is obtained. */
    fun persistWithCipher(cipher: Cipher, list: List<SavedLogin> = unlocked) {
        val plain = vaultJson(list).toString().toByteArray(Charsets.UTF_8)
        val iv = cipher.iv ?: ByteArray(0)
        val ct = VaultCrypto.encrypt(cipher, plain)
        val out = iv + ct
        val f = File(app.filesDir, FILE)
        val tmp = File(app.filesDir, "$FILE.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(f)) {
            f.writeBytes(out)
            tmp.delete()
        }
        pendingPersist = false
        writeMeta(list)
    }

    /**
     * If a save happened while the encrypt key needed re-auth, prompt again to flush to disk.
     */
    fun flushPending(activity: FragmentActivity, onDone: (Boolean) -> Unit) {
        if (!pendingPersist || !isUnlocked()) { onDone(true); return }
        try {
            val cipher = VaultCrypto.cipherForEncrypt()
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val c = result.cryptoObject?.cipher
                        if (c == null) { onDone(false); return }
                        // Re-locked (idle timer) while the prompt was up: memory is empty, writing would wipe the vault.
                        if (!isOpen) { onDone(false); return }
                        val ok = runCatching { persistWithCipher(c) }.isSuccess
                        touch()
                        onDone(ok)
                    }
                    override fun onAuthenticationError(code: Int, errString: CharSequence) = onDone(false)
                })
            prompt.authenticate(
                cryptoPromptInfo("Save password vault", "Confirm to write encrypted passwords"),
                BiometricPrompt.CryptoObject(cipher),
            )
        } catch (e: Exception) {
            onDone(false)
        }
    }

    private fun vaultJson(list: List<SavedLogin>): JSONObject {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                s.toMetaJson()
                    .put("password", s.password) // only inside the encrypted blob
            )
        }
        return JSONObject().put("v", 1).put("logins", arr)
    }

    private fun parseVault(text: String): List<SavedLogin> {
        val root = JSONObject(text)
        val arr = root.optJSONArray("logins") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedLogin.fromMetaJson(o, o.optString("password"))
        }
    }

    private fun writeMeta(list: List<SavedLogin>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(it.toMetaJson()) } // no passwords
            File(app.filesDir, META).writeText(JSONObject().put("count", list.size).put("logins", arr).toString())
        }
    }

    private fun metaCount(): Int = runCatching {
        val f = File(app.filesDir, META)
        if (!f.isFile) {
            if (File(app.filesDir, FILE).isFile) return 1 // unknown but present
            return 0
        }
        JSONObject(f.readText()).optInt("count", 0)
    }.getOrDefault(0)

    fun metaUsernames(): List<Pair<String, String>> = runCatching {
        val f = File(app.filesDir, META)
        if (!f.isFile) return emptyList()
        val arr = JSONObject(f.readText()).optJSONArray("logins") ?: return emptyList()
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.optString("origin") to o.optString("username")
        }
    }.getOrDefault(emptyList())

    /**
     * Locked-safe meta for a site origin (guid / origin / username only).
     * Used to offer "Unlock to fill" without decrypting the vault.
     */
    fun metaForOrigin(origin: String?): List<MetaLogin> {
        if (!initialized || origin.isNullOrBlank()) return emptyList()
        return readMetaLogins().filter {
            normalizeOrigin(it.origin) == normalizeOrigin(origin) || originsMatch(it.origin, origin)
        }
    }

    private fun readMetaLogins(): List<MetaLogin> = runCatching {
        val f = File(app.filesDir, META)
        if (!f.isFile) return emptyList()
        val arr = JSONObject(f.readText()).optJSONArray("logins") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val guid = o.optString("guid")
            val org = o.optString("origin")
            if (guid.isBlank() || org.isBlank()) return@mapNotNull null
            MetaLogin(guid = guid, origin = org, username = o.optString("username"))
        }
    }.getOrDefault(emptyList())

    fun normalizeOrigin(origin: String): String {
        val t = origin.trim().lowercase().trimEnd('/')
        return try {
            val u = android.net.Uri.parse(if ("://" in t) t else "https://$t")
            val scheme = u.scheme ?: "https"
            val host = u.host?.removePrefix("www.") ?: t
            "$scheme://$host"
        } catch (_: Exception) { t }
    }

    private fun originsMatch(a: String, b: String): Boolean {
        val ha = android.net.Uri.parse(normalizeOrigin(a)).host ?: return false
        val hb = android.net.Uri.parse(normalizeOrigin(b)).host ?: return false
        return ha == hb || ha.endsWith(".$hb") || hb.endsWith(".$ha")
    }
}
