package com.jamhowman.beastbrowser.downloads

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves vault files to other apps ("Open with…" / share). Not exported: apps only get access through a one-off
 * FLAG_GRANT_READ_URI_PERMISSION grant from the vault screen. URIs are `content://<pkg>.privatevault/<id>/<name>`.
 *
 * 2.11.1: a seekable descriptor ([StorageManager.openProxyFileDescriptor]) whose reads decrypt only the chunks they
 * touch ([PrivateCrypto.RandomAccess]), so video players can seek (MP4 index at the end). Nothing is ever written
 * to disk in plaintext. The pipe stays as a fallback if the proxy can't be created. Type and size come from the
 * file itself (stored meta, extension, magic bytes), never a blind application/octet-stream.
 */
class PrivateVaultProvider : ContentProvider() {
    override fun onCreate() = true

    private fun item(uri: Uri): DownloadItem? {
        DownloadCenter.init(context!!)
        val id = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: return null
        return DownloadCenter.privateItems.value.firstOrNull { it.id == id }
    }

    override fun getType(uri: Uri): String? {
        val d = item(uri) ?: return null
        return typeOf(d)
    }

    override fun query(uri: Uri, projection: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val d = item(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val size = d.filePath?.let { runCatching { PrivateCrypto.plainSize(File(it)) }.getOrNull() } ?: 0L
        val mime = typeOf(d)
        val name = VaultMime.fixName(d.fileName, mime)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { c ->
                when (c) {
                    OpenableColumns.DISPLAY_NAME -> name
                    OpenableColumns.SIZE -> size
                    "mime_type" -> mime
                    else -> null
                }
            })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val d = item(uri) ?: throw FileNotFoundException("not in the vault")
        val f = File(d.filePath ?: throw FileNotFoundException("no file"))
        return try { openSeekable(context!!.getSystemService(StorageManager::class.java), f) }
        catch (e: FileNotFoundException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "seekable open failed (${e.javaClass.simpleName}); using a pipe")
            openPipe(f)
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0

    companion object {
        private const val TAG = "PrivateVault"

        fun uriFor(pkg: String, d: DownloadItem): Uri = Uri.Builder().scheme("content").authority("$pkg.privatevault")
            .appendPath(d.id.toString()).appendPath(d.fileName).build()

        /** Stored type when specific, else the file's stored meta / extension / decrypted magic bytes. */
        fun typeOf(d: DownloadItem): String {
            if (!VaultMime.isGeneric(d.mime)) return d.mime
            val f = d.filePath?.let(::File)
            val (meta, head) = runCatching {
                PrivateCrypto.RandomAccess(f!!).use { ra ->
                    val b = ByteArray(minOf(512L, ra.size).toInt()); val n = ra.read(0, b, 0, b.size)
                    ra.meta to b.copyOf(maxOf(0, n))
                }
            }.getOrDefault(null to null)
            return VaultMime.resolve(meta?.mime, d.fileName, head)
        }

        /** Seekable read-only descriptor over [f]; chunks are decrypted on demand on a private handler thread. */
        fun openSeekable(sm: StorageManager, f: File): ParcelFileDescriptor {
            val ra = try { PrivateCrypto.RandomAccess(f) } catch (e: Exception) { throw FileNotFoundException(e.message) }
            val thread = HandlerThread("vault-proxy").apply { start() }
            return try {
                sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback(ra, thread), Handler(thread.looper))
            } catch (e: Exception) { ra.close(); thread.quitSafely(); throw e }
        }

        internal fun callback(ra: PrivateCrypto.RandomAccess, thread: HandlerThread?) = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = ra.size
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                var n = 0
                while (n < size) { val r = ra.read(offset + n, data, n, size - n); if (r <= 0) break; n += r }
                n
            } catch (e: Exception) {
                // Damaged/tampered chunk or lost key: an I/O error, never junk bytes.
                throw ErrnoException("read", OsConstants.EIO)
            }
            override fun onRelease() { ra.close(); thread?.quitSafely() }
        }

        /** Fallback: sequential decrypt into a pipe (not seekable). */
        fun openPipe(f: File): ParcelFileDescriptor {
            val src = try { PrivateCrypto.reader(f) } catch (e: Exception) { throw FileNotFoundException(e.message) }
            val (readSide, writeSide) = ParcelFileDescriptor.createReliablePipe()
            Thread({
                val out = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
                try {
                    src.use { i -> out.use { o -> i.copyTo(o, PrivateCrypto.CHUNK) } }
                } catch (e: Exception) {
                    Log.w(TAG, "decrypt pipe ended: ${e.javaClass.simpleName}")
                    runCatching { writeSide.closeWithError("Private download could not be decrypted") }
                }
            }, "vault-pipe").start()
            return readSide
        }
    }
}
