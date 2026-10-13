package com.jamhowman.beastbrowser.downloads

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException

/**
 * 2.11.1: Media3 [DataSource] over one vault file. Each read decrypts only the chunk(s) it needs
 * ([PrivateCrypto.RandomAccess]), in memory; no cache, no temp file, nothing plaintext on disk. A damaged or
 * tampered chunk throws, which stops playback with an error instead of playing junk.
 */
@UnstableApi
class VaultDataSource(private val file: File) : BaseDataSource(/* isNetwork = */ false) {
    private var ra: PrivateCrypto.RandomAccess? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val r = try { PrivateCrypto.RandomAccess(file) } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        ra = r
        if (dataSpec.position > r.size) { r.close(); ra = null; throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) }
        pos = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, r.size - pos) else r.size - pos
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val r = ra ?: throw IOException("not open")
        val n = try { r.read(pos, buffer, offset, minOf(length.toLong(), remaining).toInt()) } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n; remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        ra?.close(); ra = null
        if (opened) { opened = false; transferEnded() }
    }

    class Factory(private val file: File) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(file)
    }
}
