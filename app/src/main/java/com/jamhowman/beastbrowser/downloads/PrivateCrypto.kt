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
 *
 * 2.11.1 format "UPD2" adds an encrypted, fixed-size metadata record (original file name and MIME type) right after
 * the header, so the vault never has to guess a type from a `.bin` name:
 * ```
 * header   "UPD2" | u32 chunkSize | 16-byte random file id | u32 metaRecordLen
 * meta     12-byte IV | AES-GCM(512-byte padded JSON {n,m}) + tag      AAD = header || "META"
 * chunk*   as above (AAD = header || u64 index || u8 final)
 * ```
 * The meta record has a fixed size so [updateMeta] can rewrite it in place. [RandomAccess] decrypts single chunks
 * at any offset (seekable playback); "UPD1" files stay readable and are upgraded on load.
 */
object PrivateCrypto {
    const val KEY_ALIAS = "ulfur_private_downloads_v1"
    private val MAGIC = byteArrayOf('U'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte())
    const val CHUNK = 64 * 1024
    private const val IV = 12
    private const val TAG_BITS = 128
    private const val TAG = 16
    const val HEADER_LEN = 4 + 4 + 16
    private val MAGIC2 = byteArrayOf('U'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), '2'.code.toByte())
    const val HEADER2_LEN = HEADER_LEN + 4
    const val META_PLAIN = 512
    const val META_REC = IV + META_PLAIN + TAG
    private val META_AAD = "META".toByteArray()

    /** Original name and MIME type, kept encrypted inside the file (2.11.1). */
    data class Meta(val name: String, val mime: String) {
        fun encode(): ByteArray {
            var n = name
            while (true) {
                val j = org.json.JSONObject().put("n", n).put("m", mime.take(100)).toString().toByteArray(Charsets.UTF_8)
                if (j.size <= META_PLAIN - 2) {
                    return ByteBuffer.allocate(META_PLAIN).putShort(j.size.toShort()).put(j).array()
                }
                n = n.dropLast(maxOf(1, n.length / 8)) // keep the extension end? names are sanitised to < 128 chars anyway
            }
        }
        companion object {
            fun decode(b: ByteArray): Meta {
                val len = ByteBuffer.wrap(b, 0, 2).short.toInt() and 0xFFFF
                if (len > META_PLAIN - 2) throw IOException("Bad metadata")
                val o = org.json.JSONObject(String(b, 2, len, Charsets.UTF_8))
                return Meta(o.optString("n"), o.optString("m"))
            }
        }
    }

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
        f.inputStream().use { i -> val b = ByteArray(4); i.read(b) == 4 && (b.contentEquals(MAGIC) || b.contentEquals(MAGIC2)) }
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
    class Writer internal constructor(private val out: OutputStream, private val chunk: Int, meta: Meta? = null) : OutputStream() {
        private val key = keyForWrite()
        private val header: ByteArray = if (meta == null) ByteBuffer.allocate(HEADER_LEN).put(MAGIC).putInt(chunk)
            .put(ByteArray(16).also { SecureRandom().nextBytes(it) }).array()
            else ByteBuffer.allocate(HEADER2_LEN).put(MAGIC2).putInt(chunk)
            .put(ByteArray(16).also { SecureRandom().nextBytes(it) }).putInt(META_REC).array()
        private val metaRecord: ByteArray? = meta?.let { sealMeta(key, header, it) }
        private val buf = ByteArray(chunk)
        private var fill = 0
        private var index = 0L
        private var finished = false
        private var closed = false
        var plainBytes = 0L; private set

        init { out.write(header); metaRecord?.let { out.write(it) } }

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

    /** [meta] null writes the 2.10 "UPD1" format (no name/type); the app always passes it (2.11.1). */
    fun writer(out: OutputStream, chunk: Int = CHUNK, meta: Meta? = null): Writer = Writer(out, chunk, meta)
    fun writer(f: File, chunk: Int = CHUNK, meta: Meta? = null): Writer = Writer(FileOutputStream(f), chunk, meta)

    private fun sealMeta(key: SecretKey, header: ByteArray, meta: Meta): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        c.updateAAD(header + META_AAD)
        val ct = c.doFinal(meta.encode())
        return c.iv + ct
    }

    private fun openMeta(key: SecretKey, header: ByteArray, rec: ByteArray): Meta {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, rec, 0, IV))
        c.updateAAD(header + META_AAD)
        val plain = try { c.doFinal(rec, IV, rec.size - IV) } catch (e: java.security.GeneralSecurityException) {
            throw IOException("Private download is damaged, was changed, or its key is gone", e)
        }
        return Meta.decode(plain)
    }

    /** File layout read from the header (no decryption beyond the meta record). */
    private class Layout(val header: ByteArray, val chunk: Int, val dataStart: Long, val v2: Boolean)

    private fun layout(raf: java.io.RandomAccessFile): Layout {
        val h = ByteArray(HEADER_LEN)
        raf.seek(0)
        try { raf.readFully(h) } catch (_: EOFException) { throw IOException("Not a private download file") }
        val magic = h.copyOfRange(0, 4)
        val chunk = ByteBuffer.wrap(h, 4, 4).int
        if (chunk <= 0 || chunk > 16 * 1024 * 1024) throw IOException("Bad chunk size")
        return when {
            magic.contentEquals(MAGIC) -> Layout(h, chunk, HEADER_LEN.toLong(), false)
            magic.contentEquals(MAGIC2) -> {
                val ml = try { raf.readInt() } catch (_: EOFException) { throw IOException("Not a private download file") }
                if (ml != META_REC) throw IOException("Bad metadata size")
                Layout(h + ByteBuffer.allocate(4).putInt(ml).array(), chunk, (HEADER2_LEN + META_REC).toLong(), true)
            }
            else -> throw IOException("Not a private download file")
        }
    }

    /** True for 2.11.1+ files that carry their name and type. */
    fun hasMeta(f: File): Boolean = runCatching {
        f.inputStream().use { i -> val b = ByteArray(4); i.read(b) == 4 && b.contentEquals(MAGIC2) }
    }.getOrDefault(false)

    /** The stored name/type, or null for a 2.10 "UPD1" file. Throws if the record was changed or the key is gone. */
    fun readMeta(f: File): Meta? = java.io.RandomAccessFile(f, "r").use { raf ->
        val l = layout(raf)
        if (!l.v2) return null
        val rec = ByteArray(META_REC); raf.seek(HEADER2_LEN.toLong()); raf.readFully(rec)
        openMeta(keyForRead(), l.header, rec)
    }

    /** Rewrites the (fixed-size) meta record of a UPD2 file in place. Chunks are untouched. */
    fun updateMeta(f: File, meta: Meta) {
        java.io.RandomAccessFile(f, "rw").use { raf ->
            val l = layout(raf)
            if (!l.v2) throw IOException("Old format: re-encrypt instead")
            val key = keyForRead()
            val rec = sealMeta(key, l.header, meta)
            check(rec.size == META_REC)
            raf.seek(HEADER2_LEN.toLong()); raf.write(rec)
            raf.fd.sync()
        }
    }

    /** Plaintext size of a finished vault file of either format. */
    fun plainSize(f: File): Long = java.io.RandomAccessFile(f, "r").use { raf ->
        val l = layout(raf)
        plainSizeFrom(raf.length() - l.dataStart, l.chunk)
    }

    private fun plainSizeFrom(dataLen: Long, chunk: Int): Long {
        if (dataLen <= 0) return 0
        val full = chunk + IV + TAG
        val n = dataLen / full
        val rest = dataLen - n * full
        return n * chunk + maxOf(0, rest - IV - TAG)
    }

    /**
     * Seekable decryption (2.11.1): reads plaintext at any offset by decrypting only the chunks that cover it,
     * in memory. Each chunk is authenticated with its index and "final" flag, so tampering, truncation and
     * reordering still fail with an IOException. Keeps one decrypted chunk cached; zeroed on [close].
     */
    class RandomAccess(f: File) : java.io.Closeable {
        private val raf = java.io.RandomAccessFile(f, "r")
        private val key = keyForRead()
        private val l = try { layout(raf) } catch (e: IOException) { raf.close(); throw e }
        private val fileLen = raf.length()
        private val rec = l.chunk + IV + TAG
        private val chunks: Long = ((fileLen - l.dataStart) + rec - 1) / rec
        val size: Long = plainSizeFrom(fileLen - l.dataStart, l.chunk)
        val meta: Meta? = if (l.v2) {
            val r = ByteArray(META_REC); raf.seek(HEADER2_LEN.toLong()); raf.readFully(r); openMeta(key, l.header, r)
        } else null
        private var cachedIndex = -1L
        private var cached = ByteArray(0)
        private var closed = false

        init { if (fileLen - l.dataStart < IV + TAG) { raf.close(); throw IOException("Private download is incomplete or damaged") } }

        @Synchronized private fun chunk(i: Long): ByteArray {
            if (i == cachedIndex) return cached
            val start = l.dataStart + i * rec
            val n = minOf(rec.toLong(), fileLen - start).toInt()
            if (n < IV + TAG) throw IOException("Private download is incomplete or damaged")
            val buf = ByteArray(n)
            raf.seek(start); raf.readFully(buf)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, buf, 0, IV))
            c.updateAAD(aad(l.header, i, i == chunks - 1))
            val plain = try { c.doFinal(buf, IV, n - IV) } catch (e: AEADBadTagException) {
                throw IOException("Private download is damaged, was changed, or its key is gone", e)
            } catch (e: java.security.GeneralSecurityException) {
                throw IOException("Private download can't be decrypted", e)
            }
            java.util.Arrays.fill(cached, 0)
            cached = plain; cachedIndex = i
            return plain
        }

        /** Reads up to [len] bytes at [pos]; returns -1 at the end. */
        @Synchronized fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int {
            check(!closed) { "closed" }
            if (pos >= size) return -1
            if (len == 0) return 0
            var p = pos; var o = off; var left = minOf(len.toLong(), size - pos).toInt()
            var total = 0
            while (left > 0) {
                val i = p / l.chunk
                val within = (p - i * l.chunk).toInt()
                val c = chunk(i)
                val n = minOf(left, c.size - within)
                if (n <= 0) throw IOException("Private download is incomplete or damaged")
                System.arraycopy(c, within, b, o, n)
                p += n; o += n; left -= n; total += n
            }
            return total
        }

        @Synchronized override fun close() {
            if (closed) return
            closed = true
            java.util.Arrays.fill(cached, 0); cachedIndex = -1
            raf.close()
        }
    }

    /** Decrypting reader: throws IOException on tampering, truncation, a wrong key or a lost key. */
    class Reader internal constructor(input: InputStream) : InputStream() {
        private val inp = DataInputStream(input)
        private val key = keyForRead()
        private var header = ByteArray(HEADER_LEN)
        private val chunk: Int
        /** The stored name/type (UPD2), or null (UPD1). */
        var meta: Meta? = null; private set
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0L
        private var done = false

        init {
            try { inp.readFully(header) } catch (_: EOFException) { throw IOException("Not a private download file") }
            val magic = header.copyOfRange(0, 4)
            if (!magic.contentEquals(MAGIC) && !magic.contentEquals(MAGIC2)) throw IOException("Not a private download file")
            chunk = ByteBuffer.wrap(header, 4, 4).int
            if (chunk <= 0 || chunk > 16 * 1024 * 1024) throw IOException("Bad chunk size")
            if (magic.contentEquals(MAGIC2)) {
                val ml = ByteArray(4)
                try { inp.readFully(ml) } catch (_: EOFException) { throw IOException("Not a private download file") }
                if (ByteBuffer.wrap(ml).int != META_REC) throw IOException("Bad metadata size")
                header += ml
                val rec = ByteArray(META_REC)
                try { inp.readFully(rec) } catch (_: EOFException) { throw IOException("Private download is incomplete or damaged") }
                meta = openMeta(key, header, rec)
            }
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
