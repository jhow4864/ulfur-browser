package com.jamhowman.beastbrowser.downloads

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ulfur #1 (2.10): encryption for the private downloads vault.
 *
 * Every vault file is a stream of AES-256-GCM chunks under one Android Keystore key (non-exportable, so the bytes
 * never exist outside the secure hardware/keystore daemon). Plaintext only ever lives in memory, one chunk at a time.
 *
 * File format:
 * ```
 * header   "UPD1" | u32 chunkSize | 16-byte random file id
 * chunk*   12-byte IV | ciphertext+16-byte tag      (IV picked by the cipher; never reused)
 * ```
 * Each chunk's AAD is header || u64 index || u8 final, so chunks can't be reordered, moved between files, dropped,
 * or appended after the final one; a file missing its final chunk (killed mid-download) never decrypts fully.
 * Deleting the key ([destroyKey]) makes every vault file permanently unreadable.
 */
object PrivateCrypto {
    const val KEY_ALIAS = "ulfur_private_downloads_v1"
    private val MAGIC = byteArrayOf('U'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte())
    const val CHUNK = 64 * 1024
    private const val IV = 12
    private const val TAG_BITS = 128
    private const val TAG = 16
    const val HEADER_LEN = 4 + 4 + 16

    /** Thrown when the vault key is gone, so files can't be decrypted. */
    class KeyLostException : IOException("The private downloads key is gone, so this file can't be opened")

    /** Where the key comes from. Tests swap in a software key; the app uses the Android Keystore. */
    interface KeySource {
        /** The key, or null if none exists. */
        fun get(): SecretKey?
        fun create(): SecretKey
        fun destroy()
    }

    object AndroidKeystore : KeySource {
        private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        override fun get(): SecretKey? = (ks().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        override fun create(): SecretKey {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return gen.generateKey()
        }
        override fun destroy() { runCatching { ks().deleteEntry(KEY_ALIAS) } }
    }

    @Volatile var keys: KeySource = AndroidKeystore

    @Synchronized private fun keyForWrite(): SecretKey = keys.get() ?: keys.create()
    private fun keyForRead(): SecretKey = keys.get() ?: throw KeyLostException()

    /** Deletes the vault key. Every existing vault file becomes unreadable; a new key is made on the next write. */
    @Synchronized fun destroyKey() = keys.destroy()

    fun isEncrypted(f: File): Boolean = runCatching {
        f.inputStream().use { i -> val b = ByteArray(4); i.read(b) == 4 && b.contentEquals(MAGIC) }
    }.getOrDefault(false)

    /** Plaintext size of a finished file, from its length alone. */
    fun plainSize(cipherLen: Long, chunk: Int = CHUNK): Long {
        var rest = cipherLen - HEADER_LEN
        if (rest <= 0) return 0
        val full = chunk + IV + TAG
        val n = rest / full
        rest -= n * full
        return n * chunk + maxOf(0, rest - IV - TAG)
    }

    /**
     * Encrypting writer. [finish] writes the final chunk; [close] without [finish] leaves a file that will
     * never decrypt in full (the state a killed download leaves behind).
     */
    class Writer internal constructor(private val out: OutputStream, private val chunk: Int) : OutputStream() {
        private val key = keyForWrite()
        private val header: ByteArray = ByteBuffer.allocate(HEADER_LEN).put(MAGIC).putInt(chunk)
            .put(ByteArray(16).also { SecureRandom().nextBytes(it) }).array()
        private val buf = ByteArray(chunk)
        private var fill = 0
        private var index = 0L
        private var finished = false
        private var closed = false
        var plainBytes = 0L; private set

        init { out.write(header) }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!finished && !closed) { "writer closed" }
            var o = off; var l = len
            while (l > 0) {
                // A full buffer is only sealed once more data arrives, so the last chunk can always be marked final.
                if (fill == chunk) emit(false)
                val n = minOf(l, chunk - fill)
                System.arraycopy(b, o, buf, fill, n)
                fill += n; o += n; l -= n; plainBytes += n
            }
        }

        private fun emit(last: Boolean) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            c.updateAAD(aad(header, index, last))
            val ct = c.doFinal(buf, 0, fill)
            val iv = c.iv
            if (iv.size != IV) throw IOException("unexpected IV size")
            out.write(iv); out.write(ct)
            index++; fill = 0
            java.util.Arrays.fill(buf, 0)
        }

        fun finish() {
            if (finished) return
            emit(true); finished = true
            out.flush()
        }

        override fun flush() = out.flush()
        override fun close() {
            if (closed) return
            closed = true
            java.util.Arrays.fill(buf, 0)
            out.close()
        }
    }

    fun writer(out: OutputStream, chunk: Int = CHUNK): Writer = Writer(out, chunk)
    fun writer(f: File, chunk: Int = CHUNK): Writer = Writer(FileOutputStream(f), chunk)

    /** Decrypting reader: throws IOException on tampering, truncation, a wrong key or a lost key. */
    class Reader internal constructor(input: InputStream) : InputStream() {
        private val inp = DataInputStream(input)
        private val key = keyForRead()
        private val header = ByteArray(HEADER_LEN)
        private val chunk: Int
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0L
        private var done = false

        init {
            try { inp.readFully(header) } catch (_: EOFException) { throw IOException("Not a private download file") }
            if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) throw IOException("Not a private download file")
            chunk = ByteBuffer.wrap(header, 4, 4).int
            if (chunk <= 0 || chunk > 16 * 1024 * 1024) throw IOException("Bad chunk size")
        }

        private fun next(): Boolean {
            if (done) return false
            val rec = ByteArray(IV + chunk + TAG)
            var n = 0
            while (n < rec.size) { val r = inp.read(rec, n, rec.size - n); if (r < 0) break; n += r }
            if (n < IV + TAG) throw IOException("Private download is incomplete or damaged")
            // A short record must be the final chunk; a full one is final only if nothing follows it.
            val last = n < rec.size || inp.peekEof()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, rec, 0, IV))
            c.updateAAD(aad(header, index, last))
            plain = try { c.doFinal(rec, IV, n - IV) } catch (e: AEADBadTagException) {
                throw IOException("Private download is damaged, was changed, or its key is gone", e)
            } catch (e: java.security.GeneralSecurityException) {
                throw IOException("Private download can't be decrypted", e)
            }
            pos = 0; index++
            if (last) done = true
            return true
        }

        private fun DataInputStream.peekEof(): Boolean {
            mark(1); val r = read(); if (r < 0) return true; reset(); return false
        }

        override fun read(): Int {
            val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) { if (!next()) return -1 }
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n); pos += n
            return n
        }

        override fun close() { java.util.Arrays.fill(plain, 0); inp.close() }
    }

    fun reader(input: InputStream): Reader = Reader(java.io.BufferedInputStream(input, CHUNK + IV + TAG + 1))
    fun reader(f: File): Reader = reader(f.inputStream())

    private fun aad(header: ByteArray, index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 9).put(header).putLong(index).put(if (last) 1 else 0).array()
}
