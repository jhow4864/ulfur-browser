package com.jamhowman.beastbrowser.backup

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase encryption for `.ulfur` backup files.
 *
 * File layout (UTF-8 text, two lines):
 * ```
 * {"format":"ulfur-backup","version":1,"kdf":{"name":"PBKDF2-HMAC-SHA256","iterations":600000,"salt":"<b64>"},
 *  "cipher":{"name":"AES-256-GCM","nonce":"<b64>","tagBits":128},"payload":"gzip+json"}
 * <base64 of AES-256-GCM(ciphertext || tag)>
 * ```
 * - Key = PBKDF2-HMAC-SHA256(passphrase, 16-byte random salt, [ITERATIONS]) → 256 bits.
 * - AES-256-GCM, fresh random 12-byte nonce per file, 128-bit tag.
 * - The exact header line bytes are the GCM associated data, so editing any header field (iterations, salt,
 *   version…) makes decryption fail just like a wrong passphrase.
 * - Plaintext = gzip(JSON payload, see [BackupPayload]).
 */
object BackupCrypto {
    const val FORMAT = "ulfur-backup"
    const val VERSION = 1
    /** OWASP 2023 guidance for PBKDF2-HMAC-SHA256 (the requirement floor is 310k). */
    const val ITERATIONS = 600_000
    const val MIN_ITERATIONS = 310_000
    private const val MAX_ITERATIONS = 10_000_000   // refuse absurd headers (CPU DoS)
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    const val MIN_PASSPHRASE = 8
    private const val MAX_PLAINTEXT = 256L * 1024 * 1024

    sealed class BackupException(message: String) : Exception(message) {
        /** GCM authentication failed: wrong passphrase, or the file/header was modified or damaged. */
        class WrongPassphraseOrDamaged : BackupException("wrong passphrase or damaged file")
        /** Not an Ulfur backup, or a format/version this build can't read. */
        class BadFormat(reason: String) : BackupException(reason)
    }

    private val random = SecureRandom()

    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE) { "passphrase too short" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("kdf", JSONObject().put("name", "PBKDF2-HMAC-SHA256").put("iterations", ITERATIONS).put("salt", b64(salt)))
            .put("cipher", JSONObject().put("name", "AES-256-GCM").put("nonce", b64(nonce)).put("tagBits", TAG_BITS))
            .put("payload", "gzip+json")
            .toString()
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(headerBytes)
        val ct = cipher.doFinal(gzip(plain))
        return headerBytes + '\n'.code.toByte() + b64(ct).toByteArray(Charsets.US_ASCII) + '\n'.code.toByte()
    }

    /** True if [file] starts like an Ulfur backup (cheap sniff, no crypto). */
    fun looksLikeBackup(file: ByteArray): Boolean {
        val head = String(file, 0, minOf(file.size, 64), Charsets.UTF_8)
        return head.trimStart().startsWith("{") && head.contains("\"format\"") && head.contains(FORMAT)
    }

    fun decrypt(file: ByteArray, passphrase: CharArray): ByteArray {
        val nl = file.indexOf('\n'.code.toByte())
        if (nl <= 0) throw BackupException.BadFormat("not an Ulfur backup")
        val headerBytes = file.copyOfRange(0, nl)
        val header = runCatching { JSONObject(String(headerBytes, Charsets.UTF_8)) }
            .getOrElse { throw BackupException.BadFormat("not an Ulfur backup") }
        if (header.optString("format") != FORMAT) throw BackupException.BadFormat("not an Ulfur backup")
        val version = header.optInt("version", -1)
        if (version != VERSION) throw BackupException.BadFormat("unsupported backup version $version")
        val kdf = header.optJSONObject("kdf") ?: throw BackupException.BadFormat("missing kdf")
        val c = header.optJSONObject("cipher") ?: throw BackupException.BadFormat("missing cipher")
        if (kdf.optString("name") != "PBKDF2-HMAC-SHA256" || c.optString("name") != "AES-256-GCM" ||
            c.optInt("tagBits", TAG_BITS) != TAG_BITS || header.optString("payload") != "gzip+json"
        ) throw BackupException.BadFormat("unsupported algorithms")
        val iterations = kdf.optInt("iterations", -1)
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BackupException.BadFormat("bad kdf parameters")
        val salt = unb64(kdf.optString("salt"))
        val nonce = unb64(c.optString("nonce"))
        if (salt.size != SALT_BYTES || nonce.size != NONCE_BYTES) throw BackupException.BadFormat("bad kdf parameters")
        val body = String(file, nl + 1, file.size - nl - 1, Charsets.US_ASCII).trim()
        val ct = unb64(body)
        if (ct.size < TAG_BITS / 8) throw BackupException.WrongPassphraseOrDamaged()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(headerBytes)
        val gz = try { cipher.doFinal(ct) } catch (e: AEADBadTagException) { throw BackupException.WrongPassphraseOrDamaged() }
        return runCatching { gunzip(gz) }.getOrElse { throw BackupException.WrongPassphraseOrDamaged() }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES").also { raw.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }

    private fun gzip(b: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(b) } }.toByteArray()

    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_PLAINTEXT) throw IllegalStateException("too large")
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = runCatching { Base64.decode(s, Base64.NO_WRAP) }
        .getOrElse { throw BackupException.BadFormat("damaged file") }
}
