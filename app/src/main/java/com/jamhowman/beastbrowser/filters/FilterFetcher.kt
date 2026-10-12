package com.jamhowman.beastbrowser.filters

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads one filter list: https only (redirects too), at most [FilterRules.MAX_LIST_BYTES]. */
object FilterFetcher {
    private const val MAX_REDIRECTS = 5

    class TooLargeException : IOException("List is larger than ${FilterRules.MAX_LIST_BYTES / (1024 * 1024)} MB")

    fun fetch(url: String): String {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            FilterRules.checkListUrl(current)?.let { throw IOException(it) }
            val c = URL(current).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                c.setRequestProperty("Accept", "text/plain,*/*;q=0.5")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                val len = c.contentLengthLong
                if (len > FilterRules.MAX_LIST_BYTES) throw TooLargeException()
                return c.inputStream.use { readCapped(it, FilterRules.MAX_LIST_BYTES) }
            } finally { c.disconnect() }
        }
        throw IOException("Too many redirects")
    }

    /** Reads at most [cap] bytes as UTF-8; throws [TooLargeException] beyond that (whatever Content-Length said). */
    fun readCapped(input: InputStream, cap: Long): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n
            if (total > cap) throw TooLargeException()
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }
}
