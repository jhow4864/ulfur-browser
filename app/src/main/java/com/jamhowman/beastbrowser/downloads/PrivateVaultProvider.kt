package com.jamhowman.beastbrowser.downloads

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves vault files to other apps (open/share) by decrypting into a pipe on the fly. Nothing is ever written to
 * disk in plaintext. Not exported: apps only get access through a one-off FLAG_GRANT_READ_URI_PERMISSION grant
 * from the vault screen. URIs are `content://<pkg>.privatevault/<id>/<name>`.
 *
 * Pipes aren't seekable, so viewers that need random access (some video players) may not be able to open a file
 * this way; the vault's "Move to Downloads" is the escape hatch for those.
 */
class PrivateVaultProvider : ContentProvider() {
    override fun onCreate() = true

    private fun item(uri: Uri): DownloadItem? {
        DownloadCenter.init(context!!)
        val id = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: return null
        return DownloadCenter.privateItems.value.firstOrNull { it.id == id }
    }

    override fun getType(uri: Uri): String? = item(uri)?.mime

    override fun query(uri: Uri, projection: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val d = item(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val size = d.filePath?.let { PrivateCrypto.plainSize(File(it).length()) } ?: 0L
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { c ->
                when (c) { OpenableColumns.DISPLAY_NAME -> d.fileName; OpenableColumns.SIZE -> size; else -> null }
            })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val d = item(uri) ?: throw FileNotFoundException("not in the vault")
        val f = File(d.filePath ?: throw FileNotFoundException("no file"))
        val src = try { PrivateCrypto.reader(f) } catch (e: Exception) { throw FileNotFoundException(e.message) }
        val (readSide, writeSide) = ParcelFileDescriptor.createReliablePipe()
        Thread({
            val out = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
            try {
                src.use { i -> out.use { o -> i.copyTo(o, PrivateCrypto.CHUNK) } }
            } catch (e: Exception) {
                // Reader stopped early (it's allowed to) or the file failed to authenticate.
                Log.w("PrivateVault", "decrypt pipe ended: ${e.javaClass.simpleName}")
                runCatching { writeSide.closeWithError("Private download could not be decrypted") }
            }
        }, "vault-pipe").start()
        return readSide
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0

    companion object {
        fun uriFor(pkg: String, d: DownloadItem): Uri = Uri.Builder().scheme("content").authority("$pkg.privatevault")
            .appendPath(d.id.toString()).appendPath(d.fileName).build()
    }
}
