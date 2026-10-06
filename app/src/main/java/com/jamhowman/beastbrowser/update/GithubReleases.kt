package com.jamhowman.beastbrowser.update

import com.jamhowman.beastbrowser.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Blocking GitHub calls; always call from a background thread. */
object GithubReleases {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private val userAgent get() = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME} (Android updater)"

    sealed class Result {
        data class Ok(val release: ReleaseInfo) : Result()
        /** 404: the repo has no published (non-draft, non-prerelease) release yet, or doesn't exist / is private. */
        data object NoRelease : Result()
        data class Error(val message: String) : Result()
    }

    fun fetchLatest(repo: String): Result {
        val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", userAgent)
        }
        return try {
            when (val code = conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    Result.Ok(parse(JSONObject(body)))
                }
                404 -> Result.NoRelease
                403, 429 -> Result.Error("GitHub rate limit reached. Try again later.")
                else -> Result.Error("GitHub returned HTTP $code.")
            }
        } catch (e: IOException) {
            Result.Error("Couldn't reach GitHub (${e.javaClass.simpleName}).")
        } catch (e: org.json.JSONException) {
            Result.Error("Unexpected response from GitHub.")
        } finally {
            conn.disconnect()
        }
    }

    fun parse(o: JSONObject): ReleaseInfo {
        val arr = o.optJSONArray("assets")
        val assets = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val a = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val url = a.optString("browser_download_url")
            if (url.isEmpty()) null else ReleaseAsset(
                name = a.optString("name"),
                url = url,
                size = a.optLong("size", -1),
                digest = a.optString("digest").takeIf { it.startsWith("sha256:") },
            )
        }
        return ReleaseInfo(
            tag = o.optString("tag_name"),
            name = o.optString("name"),
            notes = if (o.isNull("body")) "" else o.optString("body"),
            htmlUrl = o.optString("html_url"),
            draft = o.optBoolean("draft"),
            prerelease = o.optBoolean("prerelease"),
            assets = assets,
        )
    }

    /**
     * Downloads [asset] to [dest] (via a .part file), reporting progress 0..1 (or -1 if size unknown).
     * Verifies the size and, when GitHub published one, the SHA-256 digest.
     * [isCancelled] is polled between chunks.
     */
    fun download(asset: ReleaseAsset, dest: File, isCancelled: () -> Boolean, onProgress: (Float) -> Unit) {
        val part = File(dest.parentFile, dest.name + ".part")
        val sha = MessageDigest.getInstance("SHA-256")
        val conn = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true   // github.com → release-assets CDN (https → https)
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("Download failed (HTTP $code).")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size
            var done = 0L
            var lastReport = -1
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastReport) { lastReport = pct; onProgress(done.toFloat() / total) }
                        } else if (lastReport != -2) { lastReport = -2; onProgress(-1f) }
                    }
                }
            }
            if (asset.size > 0 && done != asset.size) throw IOException("Download incomplete ($done of ${asset.size} bytes).")
            asset.digest?.let { d ->
                val hex = sha.digest().joinToString("") { "%02x".format(it) }
                if (!d.removePrefix("sha256:").equals(hex, ignoreCase = true)) throw IOException("Downloaded file is corrupt (checksum mismatch).")
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("Couldn't save the update.")
        } catch (e: Throwable) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    class CancelledException : IOException("Cancelled")
}
