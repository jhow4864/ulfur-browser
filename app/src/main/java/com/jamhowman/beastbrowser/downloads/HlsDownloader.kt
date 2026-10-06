package com.jamhowman.beastbrowser.downloads

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads an HLS stream (2.3.6): resolves master → best variant, then fetches segments
 * [CONCURRENCY] at a time, decrypts AES-128 when needed, and writes them strictly in order
 * so the output is a single playable .ts (or fragmented .mp4 when there is an init segment).
 */
object HlsDownloader {
    private const val TAG = "BeastHls"
    private const val CONCURRENCY = 3
    private const val SEGMENT_TIMEOUT_SEC = 120L
    private const val MAX_RESPONSE = 64 * 1024 * 1024

    class EncryptedException : IOException(HlsPlaylist.ENCRYPTED_MESSAGE)

    class StopException(val status: DlStatus) : IOException("stopped: $status")

    /**
     * @param fetch     fetches [url] (optionally a byte range) and returns the body
     * @param stopCheck returns a status when the user paused/cancelled, else null
     * @param onPlaylist called once with the media playlist that will be downloaded
     * @param onProgress (bytesWritten, segmentsDone, segmentsTotal, speedBps)
     * @param write     appends bytes to the output, in playback order
     * @return total bytes written
     */
    fun download(
        url: String,
        fetch: (String, HlsPlaylist.ByteRange?) -> ByteArray,
        stopCheck: () -> DlStatus?,
        onPlaylist: (HlsPlaylist.MediaPlaylist) -> Unit,
        onProgress: (Long, Int, Int, Long) -> Unit,
        write: (ByteArray) -> Unit,
    ): Long {
        var playlistUrl = url
        var playlist = HlsPlaylist.parse(String(fetch(url, null), Charsets.UTF_8), url)
        if (playlist.isMaster) {
            val variant = playlist.bestVariantUrl() ?: throw IOException("HLS master has no variants")
            if (playlist.drm) throw EncryptedException()
            playlistUrl = variant
            playlist = HlsPlaylist.parse(String(fetch(variant, null), Charsets.UTF_8), variant)
        }
        if (playlist.hasUnsupportedEncryption) throw EncryptedException()
        val segments = playlist.segments
        if (segments.isEmpty()) throw IOException("HLS playlist has no segments")

        onPlaylist(playlist)
        val total = segments.size
        onProgress(0, 0, total, 0)

        var written = 0L
        var done = 0
        var speed = 0L
        var lastTick = System.currentTimeMillis()
        var bytesSinceTick = 0L

        fun checkStop() {
            stopCheck()?.let { throw StopException(it) }
        }

        fun emit(chunk: ByteArray) {
            write(chunk)
            written += chunk.size
            bytesSinceTick += chunk.size
            val now = System.currentTimeMillis()
            val dt = now - lastTick
            if (dt >= 300) {
                val instant = bytesSinceTick * 1000 / dt
                speed = if (speed == 0L) instant else (instant * 0.3 + speed * 0.7).toLong()
                lastTick = now
                bytesSinceTick = 0
            }
            onProgress(written, done, total, speed)
        }

        playlist.init?.let { init ->
            checkStop()
            emit(fetch(init.url, init.byteRange))
        }

        val keys = ConcurrentHashMap<String, ByteArray>()
        val firstError = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(CONCURRENCY) { r ->
            Thread(r, "beast-hls-seg").apply { isDaemon = true }
        }
        try {
            var index = 0
            while (index < total) {
                checkStop()
                val batch: List<Future<ByteArray>> = segments.subList(index, minOf(index + CONCURRENCY, total)).map { seg ->
                    pool.submit<ByteArray> {
                        try {
                            checkStop()
                            val body = fetch(seg.url, seg.byteRange)
                            if (seg.key.isAes128) {
                                val keyUrl = seg.key.uri?.let { resolveKey(it, playlistUrl) }
                                    ?: throw IOException("AES-128 key URI missing")
                                val key = keys.getOrPut(keyUrl) {
                                    fetch(keyUrl, null).also {
                                        if (it.size != 16) throw IOException("Bad AES-128 key (${it.size} bytes)")
                                    }
                                }
                                decryptAes128(body, key, HlsPlaylist.ivFor(seg))
                            } else {
                                body
                            }
                        } catch (t: Throwable) {
                            firstError.compareAndSet(null, t)
                            throw t
                        }
                    }
                }
                for (future in batch) {
                    val chunk = try {
                        future.get(SEGMENT_TIMEOUT_SEC, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        batch.forEach { it.cancel(true) }
                        throw wrap(firstError.get() ?: e)
                    }
                    checkStop()
                    done++
                    emit(chunk)
                }
                index += CONCURRENCY
            }
        } finally {
            pool.shutdownNow()
        }
        onProgress(written, done, total, speed)
        Log.i(TAG, "HLS done: $done segments, $written bytes")
        return written
    }

    fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val spec = SecretKeySpec(key, "AES")
        return try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv))
                doFinal(data)
            }
        } catch (e: Exception) {
            // Some packagers omit PKCS#7 padding on block-aligned segments.
            if (data.size % 16 != 0) throw IOException("AES-128 decrypt failed", e)
            Cipher.getInstance("AES/CBC/NoPadding").run {
                init(Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv))
                doFinal(data)
            }
        }
    }

    /** Reads a whole response body, refusing anything over [max] bytes. */
    fun readAll(input: InputStream, max: Int = MAX_RESPONSE): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("Response too large")
        }
        return out.toByteArray()
    }

    private fun resolveKey(uri: String, base: String): String =
        try { java.net.URI(base).resolve(uri).toString() } catch (_: Exception) { uri }

    private fun wrap(t: Throwable): Throwable {
        val cause = if (t is ExecutionException) t.cause ?: t else t
        return cause
    }
}
