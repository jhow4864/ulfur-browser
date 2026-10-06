package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.downloads.HlsDownloader
import com.jamhowman.beastbrowser.downloads.HlsPlaylist
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * Encrypted HLS (AES-128 included) is refused: [HlsDownloader.download] throws
 * [HlsDownloader.EncryptedException] before fetching any key or segment, and nothing is written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HlsEncryptedRefusalTest {
    private val base = "https://cdn.example.org/v/"

    private class Run(val fetched: List<String>, val written: ByteArray, val error: Throwable?)

    private fun run(files: Map<String, String>, start: String): Run {
        val fetched = ArrayList<String>()
        val out = ByteArrayOutputStream()
        val failure = runCatching {
            HlsDownloader.download(
                url = base + start,
                fetch = { url, _ ->
                    synchronized(fetched) { fetched += url }
                    (files[url.removePrefix(base)] ?: throw IllegalStateException("unexpected fetch $url")).toByteArray()
                },
                stopCheck = { null },
                onPlaylist = {},
                onProgress = { _, _, _, _ -> },
                write = { out.write(it) },
            )
        }.exceptionOrNull()
        return Run(fetched, out.toByteArray(), failure)
    }

    private fun assertRefused(r: Run, allowedFetches: List<String>) {
        if (r.error !is HlsDownloader.EncryptedException) fail("expected EncryptedException, got ${r.error}")
        assertEquals(HlsPlaylist.ENCRYPTED_MESSAGE, r.error!!.message)
        assertEquals(allowedFetches.map { base + it }, r.fetched)
        assertFalse("no key may be fetched", r.fetched.any { it.endsWith(".key") || it.endsWith(".bin") })
        assertEquals(0, r.written.size)
    }

    @Test
    fun aes128MediaPlaylistIsRefusedWithoutFetchingKeyOrSegments() {
        val files = mapOf(
            "p.m3u8" to "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\"\n#EXTINF:6,\ns0.ts\n#EXTINF:6,\ns1.ts\n",
            "k.key" to "0123456789abcdef",
            "s0.ts" to "seg0", "s1.ts" to "seg1",
        )
        assertRefused(run(files, "p.m3u8"), listOf("p.m3u8"))
    }

    @Test
    fun aes128KeyPartWayThroughIsStillRefusedUpFront() {
        val files = mapOf(
            "p.m3u8" to "#EXTM3U\n#EXTINF:6,\ns0.ts\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\",IV=0x1\n#EXTINF:6,\ns1.ts\n",
            "k.key" to "0123456789abcdef",
            "s0.ts" to "seg0", "s1.ts" to "seg1",
        )
        assertRefused(run(files, "p.m3u8"), listOf("p.m3u8"))
    }

    @Test
    fun aes128SessionKeyOnMasterIsRefusedBeforeVariant() {
        val files = mapOf(
            "m.m3u8" to "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"k.key\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360\nv.m3u8\n",
            "v.m3u8" to "#EXTM3U\n#EXTINF:6,\ns0.ts\n",
            "k.key" to "0123456789abcdef",
            "s0.ts" to "seg0",
        )
        assertRefused(run(files, "m.m3u8"), listOf("m.m3u8"))
    }

    @Test
    fun aes128VariantUnderCleanMasterIsRefused() {
        val files = mapOf(
            "m.m3u8" to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360\nv.m3u8\n",
            "v.m3u8" to "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\"\n#EXTINF:6,\ns0.ts\n",
            "k.key" to "0123456789abcdef",
            "s0.ts" to "seg0",
        )
        assertRefused(run(files, "m.m3u8"), listOf("m.m3u8", "v.m3u8"))
    }

    @Test
    fun sampleAesIsRefused() {
        val files = mapOf(
            "p.m3u8" to "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:6,\ns0.ts\n",
            "s0.ts" to "seg0",
        )
        assertRefused(run(files, "p.m3u8"), listOf("p.m3u8"))
    }

    @Test
    fun aes128KeyIsUnsupportedInParser() {
        val pl = HlsPlaylist.parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\"\n#EXTINF:6,\ns0.ts\n", base + "p.m3u8")
        assertTrue(pl.encrypted)
        assertFalse(pl.drm)
        assertTrue(pl.hasUnsupportedEncryption)
    }

    @Test
    fun unencryptedStreamStillDownloads() {
        val files = mapOf(
            "p.m3u8" to "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:6,\ns0.ts\n#EXTINF:6,\ns1.ts\n",
            "s0.ts" to "seg0", "s1.ts" to "seg1",
        )
        val r = run(files, "p.m3u8")
        assertEquals(null, r.error)
        assertArrayEquals("seg0seg1".toByteArray(), r.written)
    }
}
