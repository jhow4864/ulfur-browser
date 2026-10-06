package com.jamhowman.beastbrowser.downloads

import com.jamhowman.beastbrowser.R
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.jamhowman.beastbrowser.browser.Engine
import com.jamhowman.beastbrowser.util.Domains
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Beast's own download manager (replaces Android's DownloadManager so downloads can be paused/resumed,
 * use Gecko's network stack/cookies, and respect private browsing).
 *
 * - State is a [StateFlow] of immutable [DownloadItem]s; workers publish progress ~2-3x per second.
 * - Pause closes the connection and keeps the partial file; resume re-requests with `Range: bytes=N-`
 *   through GeckoWebExecutor (falls back to a restart when the server doesn't support ranges).
 * - Normal files go to Downloads/<[R.string.downloads_folder]> ("Ulfur"; "Beast" before the rebrand) via MediaStore on
 *   Android 10+, public folder / app folder on 8-9. Records store the absolute content URI / file path, so files
 *   downloaded into the old Downloads/Beast folder keep opening, sharing and deleting normally.
 * - Private-tab downloads go to app-private `filesDir/private_downloads/` with a `.nomedia` file
 *   so Gallery/Files never see them. Finished private downloads are kept in a separate vault list
 *   behind biometric unlock; unfinished ones are cancelled when private browsing ends.
 */
object DownloadCenter {
    private const val TAG = "BeastDownloads"
    private const val MAX_PARALLEL = 3
    private const val STORE = "downloads.json"
    private const val PRIVATE_STORE = "private_downloads.json"
    /** Public Downloads subfolder for NEW downloads only (existing records keep their stored URI / path). */
    private val subdir: String get() = app.getString(R.string.downloads_folder)
    const val PRIVATE_DIR = "private_downloads"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(MAX_PARALLEL) { r -> Thread(r, "beast-download").apply { isDaemon = true } }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "beast-download-io").apply { isDaemon = true } }
    private val idGen = AtomicLong(System.currentTimeMillis())
    private val jobs = ConcurrentHashMap<Long, Job>()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val _privateItems = MutableStateFlow<List<DownloadItem>>(emptyList())
    /** Finished private downloads kept in the biometric vault. */
    val privateItems: StateFlow<List<DownloadItem>> = _privateItems.asStateFlow()

    sealed class Event(val item: DownloadItem) {
        class Started(item: DownloadItem) : Event(item)
        class Finished(item: DownloadItem) : Event(item)
        class Failed(item: DownloadItem) : Event(item)
    }
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private class Job(
        val id: Long,
        @Volatile var initial: WebResponse? = null,
        val isHls: Boolean = false,
        /** HLS: page title used to name the file once the playlist container is known. */
        val nameHint: String? = null,
    ) {
        @Volatile var stop: DlStatus? = null   // PAUSED or CANCELLED
        @Volatile var input: InputStream? = null
        @Volatile var target: DownloadItem? = null
    }

    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        val file = File(app.filesDir, STORE)
        val loaded = runCatching {
            if (!file.isFile) emptyList() else {
                val arr = JSONArray(file.readText())
                (0 until arr.length()).map { DownloadItem.fromJson(arr.getJSONObject(it)) }
            }
        }.getOrElse { Log.w(TAG, "could not read $STORE", it); emptyList() }
        // Anything that was running when the process died can be resumed.
        _items.value = loaded.map {
            if (it.status.isActive) it.copy(status = DlStatus.PAUSED, speedBps = 0, error = "Interrupted") else it
        }.sortedByDescending { it.createdAt }

        val privFile = File(app.filesDir, PRIVATE_STORE)
        var privReadOk = true
        val privLoaded = runCatching {
            if (!privFile.isFile) emptyList() else {
                val arr = JSONArray(privFile.readText())
                (0 until arr.length()).map {
                    DownloadItem.fromJson(arr.getJSONObject(it)).copy(isPrivate = true)
                }
            }
        }.getOrElse { Log.w(TAG, "could not read $PRIVATE_STORE", it); privReadOk = false; emptyList() }
        // Drop vault entries whose file vanished (uninstall-partial / clear-data edge cases).
        _privateItems.value = privLoaded.filter { fileExists(it) }
            .sortedByDescending { it.finishedAt.takeIf { t -> t > 0 } ?: it.createdAt }
        val privDir = ensurePrivateDir()
        // Private downloads that were running when the process died are never saved anywhere, so their partial
        // files would sit hidden in the folder forever. Runs before any download can start (this is the first
        // init in this process), so nothing in the folder can belong to a running download. If the vault list
        // couldn't be read, keep everything: an unreadable list must never cost the user their vault files.
        if (privReadOk) {
            val keep = _privateItems.value.mapNotNull { it.filePath } +
                _items.value.filter { it.isPrivate }.mapNotNull { it.filePath }
            sweepOrphanedPrivateFiles(privDir, keep)
        }
    }

    /**
     * Deletes files in the private downloads folder that no vault entry (or [keepPaths]) refers to.
     * Never touches dot-files (`.nomedia`, any future bookkeeping), the vault list or its temp file, or
     * sub-directories. Download names can't start with a dot ([sanitize] trims them). Returns what was deleted.
     */
    internal fun sweepOrphanedPrivateFiles(dir: File, keepPaths: Collection<String>): List<File> {
        val keep = keepPaths.mapTo(HashSet()) { canonical(File(it)) }
        val protectedNames = setOf(PRIVATE_STORE, "$PRIVATE_STORE.tmp")
        val deleted = ArrayList<File>()
        dir.listFiles()?.forEach { f ->
            if (!f.isFile || f.name.startsWith(".") || f.name in protectedNames) return@forEach
            if (canonical(f) in keep) return@forEach
            if (f.delete()) deleted += f else Log.w(TAG, "could not delete orphaned private file")
        }
        if (deleted.isNotEmpty()) Log.i(TAG, "removed ${deleted.size} unfinished private download(s) left by an earlier run")
        return deleted
    }

    private fun canonical(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    /** For previews/tests only. */
    fun replaceAllForPreview(list: List<DownloadItem>) { _items.value = list }

    fun get(id: Long) = _items.value.firstOrNull { it.id == id }
    val activeCount: Int get() = _items.value.count { it.status.isActive }
    fun activePrivateCount() = _items.value.count { it.isPrivate && it.status.isActive }

    // ------------------------------------------------------------------ start

    /** A download Gecko handed us (onExternalResponse): stream its body directly. */
    fun enqueue(response: WebResponse, isPrivate: Boolean, referrer: String?): DownloadItem {
        val headers = response.headers.mapKeys { it.key.lowercase() }
        val mime = headers["content-type"]?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
        val name = URLUtil.guessFileName(response.uri, headers["content-disposition"], mime)
        val total = headers["content-length"]?.toLongOrNull() ?: -1
        return add(response.uri, name, mime, isPrivate, referrer, total, response)
    }

    /** A link/image from the context menu: fetched through Gecko's network stack. */
    fun enqueue(url: String, isPrivate: Boolean, referrer: String?, mime: String? = null): DownloadItem =
        add(url, URLUtil.guessFileName(url, null, mime), mime, isPrivate, referrer, -1, null)

    /**
     * An HLS stream (2.3.6): the .m3u8 is resolved to its best variant and the segments are joined into
     * one file. The name/extension is provisional until the playlist says whether it's MPEG-TS or fMP4.
     */
    fun enqueueHls(url: String, isPrivate: Boolean, referrer: String?, title: String? = null): DownloadItem {
        val name = if (!title.isNullOrBlank()) sanitize("$title.ts")
            else URLUtil.guessFileName(url, null, "video/mp2t").replace(Regex("(?i)\\.m3u8$"), ".ts")
        return add(url, name, "video/mp2t", isPrivate, referrer, -1, null, isHls = true, nameHint = title?.takeIf { it.isNotBlank() })
    }

    private fun add(
        url: String, name: String, mime: String?, isPrivate: Boolean, referrer: String?, total: Long, initial: WebResponse?,
        isHls: Boolean = false, nameHint: String? = null,
    ): DownloadItem {
        val item = DownloadItem(
            id = idGen.incrementAndGet(), url = url, fileName = sanitize(name),
            mime = mime ?: guessMime(name), domain = Domains.display(Uri.parse(url).host).ifEmpty { Uri.parse(url).scheme ?: "" },
            isPrivate = isPrivate, status = DlStatus.QUEUED, total = total, referrer = referrer, isHls = isHls,
        )
        _items.update { listOf(item) + it }
        save()
        submit(Job(item.id, initial, isHls, nameHint))
        _events.tryEmit(Event.Started(item))
        DownloadService.ensureRunning(app)
        return item
    }

    private fun submit(job: Job) {
        jobs[job.id] = job
        workers.execute { run(job) }
    }

    // ------------------------------------------------------------------ actions

    fun pause(id: Long) {
        val job = jobs[id] ?: return
        job.stop = DlStatus.PAUSED
        interrupt(job)
        set(id) { if (it.status == DlStatus.QUEUED) it.copy(status = DlStatus.PAUSED, speedBps = 0) else it }
    }

    fun resume(id: Long) {
        val d = get(id) ?: return
        if (d.status != DlStatus.PAUSED && d.status != DlStatus.FAILED) return
        if (jobs.containsKey(id)) return
        set(id) { it.copy(status = DlStatus.QUEUED, error = null, speedBps = 0) }
        submit(Job(id, null, d.isHls))
        DownloadService.ensureRunning(app)
    }

    /** Retry a failed or cancelled download (resumes from the partial file when possible). */
    fun retry(id: Long) {
        val d = get(id) ?: return
        if (d.status == DlStatus.CANCELLED) set(id) { it.copy(status = DlStatus.PAUSED, downloaded = 0, total = -1, contentUri = null, filePath = null, segmentsDone = 0) }
        resume(id)
    }

    fun cancel(id: Long) {
        val job = jobs[id]
        if (job != null) {
            job.stop = DlStatus.CANCELLED
            interrupt(job)
            set(id) { if (it.status == DlStatus.QUEUED) it.copy(status = DlStatus.CANCELLED, speedBps = 0) else it }
        } else {
            val d = get(id) ?: return
            if (d.status == DlStatus.PAUSED || d.status == DlStatus.FAILED) {
                io.execute { deleteTarget(d) }
                set(id) { it.copy(status = DlStatus.CANCELLED, downloaded = 0, speedBps = 0, contentUri = null, filePath = null) }
                save()
            }
        }
    }

    /** Deletes the file (complete or partial) and the list entry. */
    fun delete(id: Long) {
        val d = get(id) ?: return
        jobs[id]?.let { it.stop = DlStatus.CANCELLED; interrupt(it) }
        _items.update { list -> list.filterNot { it.id == id } }
        io.execute { deleteTarget(d) }
        save()
    }

    /** Removes the entry but keeps a completed file on disk. */
    fun remove(id: Long) {
        val d = get(id) ?: return
        if (d.status.isActive) return
        if (d.status != DlStatus.DONE) io.execute { deleteTarget(d) } // partial files are useless
        _items.update { list -> list.filterNot { it.id == id } }
        save()
    }

    /** "Clear completed": drops finished entries (done/failed/cancelled); completed files stay in Downloads. */
    fun clearFinished(): Int {
        val gone = _items.value.filter { it.status.isFinished }
        val vault = gone.filter { it.isPrivate && it.status == DlStatus.DONE }
        gone.filter { it.status != DlStatus.DONE }.forEach { d -> io.execute { deleteTarget(d) } }
        // Non-private DONE: keep file in Downloads/<downloads_folder>. Private DONE: move into the vault.
        _items.update { list -> list.filterNot { it.status.isFinished } }
        if (vault.isNotEmpty()) {
            _privateItems.update { cur ->
                val ids = cur.map { it.id }.toSet()
                (vault.filter { it.id !in ids } + cur).sortedByDescending { it.finishedAt.takeIf { t -> t > 0 } ?: it.createdAt }
            }
            savePrivate()
        }
        save()
        return gone.size
    }

    /** Private browsing ended: cancel unfinished private downloads; move completed ones into the vault. Returns #cancelled. */
    fun endPrivateSession(): Int {
        val priv = _items.value.filter { it.isPrivate }
        var cancelled = 0
        val finished = mutableListOf<DownloadItem>()
        priv.forEach { d ->
            val job = jobs[d.id]
            if (job != null) { job.stop = DlStatus.CANCELLED; interrupt(job); cancelled++ }
            when {
                d.status == DlStatus.DONE -> finished += d
                else -> io.execute { deleteTarget(d) }
            }
        }
        _items.update { list -> list.filterNot { it.isPrivate } }
        if (finished.isNotEmpty()) {
            _privateItems.update { cur ->
                val ids = cur.map { it.id }.toSet()
                (finished.filter { it.id !in ids } + cur).sortedByDescending { it.finishedAt.takeIf { t -> t > 0 } ?: it.createdAt }
            }
            savePrivate()
        }
        return cancelled
    }

    fun privateVaultCount(): Int = _privateItems.value.size

    fun privateVaultBytes(): Long = _privateItems.value.sumOf { maxOf(it.total, it.downloaded, 0L) }

    /** Deletes a vault entry and its file. */
    fun deletePrivate(id: Long) {
        val d = _privateItems.value.firstOrNull { it.id == id } ?: return
        _privateItems.update { list -> list.filterNot { it.id == id } }
        io.execute { deleteTarget(d) }
        savePrivate()
    }

    fun clearPrivateVault(): Int {
        val gone = _privateItems.value
        gone.forEach { d -> io.execute { deleteTarget(d) } }
        _privateItems.value = emptyList()
        savePrivate()
        return gone.size
    }

    private fun interrupt(job: Job) {
        val input = job.input ?: return
        io.execute { runCatching { input.close() } }
    }

    // ------------------------------------------------------------------ worker

    private fun run(job: Job) {
        val id = job.id
        try {
            if (job.stop != null) { job.initial?.body?.let { runCatching { it.close() } }; settleStop(job); return }
            var item = get(id) ?: return
            set(id) { it.copy(status = DlStatus.DOWNLOADING, error = null, speedBps = 0) }
            if (job.isHls || item.isHls) { runHls(job, item); return }

            var offset = if (item.contentUri != null || item.filePath != null) item.downloaded else 0L
            val initial = job.initial.also { job.initial = null }
            val resp = if (initial != null && offset == 0L) initial else {
                initial?.body?.let { runCatching { it.close() } }
                fetch(item, offset)
            }
            val code = resp.statusCode
            if (code != 0 && code !in 200..299) { runCatching { resp.body?.close() }; throw IOException("HTTP $code") }
            val headers = resp.headers.mapKeys { it.key.lowercase() }
            val length = headers["content-length"]?.toLongOrNull() ?: -1
            val total: Long
            if (offset > 0 && code == 206) {
                total = headers["content-range"]?.substringAfterLast('/')?.toLongOrNull() ?: if (length >= 0) offset + length else -1
            } else {
                offset = 0 // server ignored the Range header (or fresh start): start over
                total = length
            }
            if (item.contentUri == null && item.filePath == null) {
                item = createTarget(item)
                job.target = item
                val fresh = item
                set(id) { it.copy(fileName = fresh.fileName, contentUri = fresh.contentUri, filePath = fresh.filePath) }
                save()
            }
            set(id) { it.copy(downloaded = offset, total = total) }
            val body = resp.body ?: throw IOException("Empty response")
            runCatching { resp.setReadTimeoutMillis(60_000) }
            job.input = body
            var done = offset
            openOutput(item, offset).use { out ->
                body.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var lastTick = SystemClock.elapsedRealtime()
                    var lastBytes = done
                    var speed = 0.0
                    while (job.stop == null) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, n)
                        done += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTick >= 400) {
                            val inst = (done - lastBytes) * 1000.0 / (now - lastTick)
                            speed = if (speed == 0.0) inst else speed * 0.7 + inst * 0.3
                            lastTick = now; lastBytes = done
                            val d = done; val s = speed.toLong()
                            set(id) { it.copy(downloaded = d, speedBps = s) }
                        }
                    }
                }
            }
            val got = done
            set(id) { it.copy(downloaded = got) }
            if (job.stop != null) { settleStop(job); return }
            if (total > 0 && got < total) throw IOException("Connection closed early")
            finalizeTarget(get(id) ?: item)
            set(id) { it.copy(status = DlStatus.DONE, total = if (total > 0) total else got, speedBps = 0, finishedAt = System.currentTimeMillis(), error = null) }
            val finished = get(id)
            if (finished != null) {
                _events.tryEmit(Event.Finished(finished))
                if (finished.isPrivate) vaultFinished(finished)
            }
        } catch (e: Throwable) {
            if (job.stop != null) settleStop(job)
            else {
                Log.w(TAG, "download $id failed", e)
                set(id) { it.copy(status = DlStatus.FAILED, speedBps = 0, error = friendly(e)) }
                get(id)?.let { _events.tryEmit(Event.Failed(it)) }
            }
        } finally {
            jobs.remove(id)
            save()
        }
    }

    /**
     * HLS worker. Segments are re-fetched from the start on resume (the joined file can't be
     * resumed mid-segment safely), so the output is always opened at offset 0.
     */
    private fun runHls(job: Job, start: DownloadItem) {
        val id = job.id
        var item = start
        var out: Output? = null
        try {
            val bytes = HlsDownloader.download(
                url = item.url,
                fetch = { url, range -> fetchBytes(item, url, range) },
                stopCheck = { job.stop },
                onPlaylist = { playlist ->
                    val mime = HlsPlaylist.mimeFor(playlist.container)
                    val segs = playlist.segments.size
                    if (item.contentUri == null && item.filePath == null) {
                        val name = sanitize(HlsPlaylist.suggestFileName(job.nameHint, item.url, playlist.container))
                        item = createTarget(item.copy(fileName = name, mime = mime, isHls = true))
                        job.target = item
                        val fresh = item
                        set(id) {
                            it.copy(fileName = fresh.fileName, mime = fresh.mime, contentUri = fresh.contentUri,
                                filePath = fresh.filePath, segmentsTotal = segs, isHls = true)
                        }
                        save()
                    } else {
                        set(id) { it.copy(mime = mime, segmentsTotal = segs, isHls = true) }
                    }
                    out = openOutput(get(id) ?: item, 0L)
                },
                onProgress = { written, done, total, speed ->
                    set(id) { it.copy(downloaded = written, total = -1, speedBps = speed, segmentsDone = done, segmentsTotal = total) }
                },
                write = { chunk -> (out ?: throw IOException("HLS output not ready")).write(chunk, chunk.size) },
            )
            runCatching { out?.close() }
            out = null
            if (job.stop != null) { settleStop(job); return }
            finalizeTarget(get(id) ?: item)
            set(id) {
                it.copy(status = DlStatus.DONE, downloaded = bytes, total = bytes, speedBps = 0, error = null,
                    finishedAt = System.currentTimeMillis(), segmentsDone = it.segmentsTotal)
            }
            get(id)?.let { finished ->
                _events.tryEmit(Event.Finished(finished))
                if (finished.isPrivate) vaultFinished(finished)
            }
        } catch (e: HlsDownloader.StopException) {
            runCatching { out?.close() }
            job.stop = e.status
            settleStop(job)
        } catch (e: HlsDownloader.EncryptedException) {
            runCatching { out?.close() }
            Log.w(TAG, "HLS encrypted $id")
            main.post { android.widget.Toast.makeText(app, HlsPlaylist.ENCRYPTED_MESSAGE, android.widget.Toast.LENGTH_LONG).show() }
            get(id)?.let { deleteTarget(it) }
            set(id) { it.copy(status = DlStatus.FAILED, speedBps = 0, error = HlsPlaylist.ENCRYPTED_MESSAGE, contentUri = null, filePath = null) }
            get(id)?.let { _events.tryEmit(Event.Failed(it)) }
        } catch (e: Throwable) {
            runCatching { out?.close() }
            if (job.stop != null) { settleStop(job); return }
            Log.w(TAG, "HLS download $id failed", e)
            set(id) { it.copy(status = DlStatus.FAILED, speedBps = 0, error = friendly(e)) }
            get(id)?.let { _events.tryEmit(Event.Failed(it)) }
        }
    }

    private fun settleStop(job: Job) {
        val d = get(job.id) ?: run {
            // entry already removed (deleted / private session ended): just drop the partial file
            if (job.stop == DlStatus.CANCELLED) job.target?.let { deleteTarget(it) }
            return
        }
        if (job.stop == DlStatus.CANCELLED) {
            deleteTarget(d)
            set(job.id) { it.copy(status = DlStatus.CANCELLED, downloaded = 0, speedBps = 0, contentUri = null, filePath = null, segmentsDone = 0) }
        } else {
            set(job.id) { it.copy(status = DlStatus.PAUSED, speedBps = 0) }
        }
    }

    private fun friendly(e: Throwable): String = when {
        e is java.net.SocketTimeoutException -> "Connection timed out"
        e.message?.startsWith("HTTP ") == true -> "Server error (${e.message})"
        e.message != null -> e.message!!.take(80)
        else -> e.javaClass.simpleName
    }

    /** GET through Gecko (same cookies/TLS/proxy as the browser; private flag for private downloads). */
    private fun fetch(item: DownloadItem, from: Long): WebResponse {
        val req = WebRequest.Builder(item.url).method("GET").apply {
            if (from > 0) header("Range", "bytes=$from-")
            item.referrer?.takeIf { it.startsWith("http") }?.let { referrer(it) }
        }.build()
        val flags = if (item.isPrivate) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE
        val latch = CountDownLatch(1)
        var response: WebResponse? = null
        var error: Throwable? = null
        main.post {
            try {
                val rt = Engine.runtime(app)   // starts Gecko if we were opened from the notification after process death
                GeckoWebExecutor(rt).fetch(req, flags).accept(
                    { r -> response = r; latch.countDown() },
                    { e -> error = e; latch.countDown() })
            } catch (e: Throwable) { error = e; latch.countDown() }
        }
        if (!latch.await(90, TimeUnit.SECONDS)) throw IOException("Connection timed out")
        error?.let { throw IOException(it.message ?: "Network error", it) }
        return response ?: throw IOException("No response")
    }

    /** Whole-body GET for HLS playlists/segments/keys, optionally a byte range. */
    private fun fetchBytes(item: DownloadItem, url: String, range: HlsPlaylist.ByteRange?): ByteArray {
        val req = WebRequest.Builder(url).method("GET").apply {
            range?.let { header("Range", "bytes=${it.offset}-${it.offset + it.length - 1}") }
            item.referrer?.takeIf { it.startsWith("http") }?.let { referrer(it) }
        }.build()
        val flags = if (item.isPrivate) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE
        val latch = CountDownLatch(1)
        var response: WebResponse? = null
        var error: Throwable? = null
        main.post {
            try {
                GeckoWebExecutor(Engine.runtime(app)).fetch(req, flags).accept(
                    { r -> response = r; latch.countDown() },
                    { e -> error = e; latch.countDown() })
            } catch (e: Throwable) { error = e; latch.countDown() }
        }
        if (!latch.await(90, TimeUnit.SECONDS)) throw IOException("Connection timed out")
        error?.let { throw IOException(it.message ?: "Network error", it) }
        val resp = response ?: throw IOException("No response")
        val code = resp.statusCode
        if (code != 0 && code !in 200..299) { runCatching { resp.body?.close() }; throw IOException("HTTP $code") }
        val body = resp.body ?: throw IOException("Empty response")
        runCatching { resp.setReadTimeoutMillis(60_000) }
        jobInput(item.id, body)
        try {
            return body.use { HlsDownloader.readAll(it) }
        } finally {
            jobInput(item.id, null)
        }
    }

    /** Lets pause/cancel close the stream currently being read (segments are fetched in parallel; last one wins). */
    private fun jobInput(id: Long, input: InputStream?) {
        jobs[id]?.input = input
    }

    // ------------------------------------------------------------------ files

    private interface Output : Closeable { fun write(b: ByteArray, n: Int) }

    private fun createTarget(item: DownloadItem): DownloadItem {
        if (item.isPrivate) {
            val dir = ensurePrivateDir()
            val f = unique(dir, item.fileName)
            f.createNewFile()
            return item.copy(filePath = f.absolutePath, fileName = f.name, contentUri = null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, item.fileName)
                put(MediaStore.Downloads.MIME_TYPE, item.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + subdir)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Can't create file in Downloads")
            var name = item.fileName
            runCatching {
                cr.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
            }
            return item.copy(contentUri = uri.toString(), fileName = name)
        }
        val publicOk = ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val dir = if (publicOk) File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subdir)
            else app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(app.filesDir, "downloads")
        dir.mkdirs()
        val f = unique(dir, item.fileName)
        f.createNewFile()
        return item.copy(filePath = f.absolutePath, fileName = f.name)
    }

    private fun openOutput(item: DownloadItem, offset: Long): Output {
        item.contentUri?.let { s ->
            val pfd = app.contentResolver.openFileDescriptor(Uri.parse(s), "rw") ?: throw IOException("Can't open file")
            val fos = FileOutputStream(pfd.fileDescriptor)
            fos.channel.truncate(offset); fos.channel.position(offset)
            return object : Output {
                override fun write(b: ByteArray, n: Int) = fos.write(b, 0, n)
                override fun close() { runCatching { fos.close() }; runCatching { pfd.close() } }
            }
        }
        val raf = RandomAccessFile(File(item.filePath ?: throw IOException("No target")), "rw")
        raf.setLength(offset); raf.seek(offset)
        return object : Output {
            override fun write(b: ByteArray, n: Int) = raf.write(b, 0, n)
            override fun close() = raf.close()
        }
    }

    private fun finalizeTarget(item: DownloadItem) {
        if (item.isPrivate) {
            // Stay in app-private storage; never scan into Gallery/MediaStore.
            return
        }
        item.contentUri?.let { s ->
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            app.contentResolver.update(Uri.parse(s), values, null, null)
            return
        }
        item.filePath?.let { MediaScannerConnection.scanFile(app, arrayOf(it), arrayOf(item.mime), null) }
    }

    private fun ensurePrivateDir(): File {
        val dir = File(app.filesDir, PRIVATE_DIR)
        if (!dir.isDirectory) dir.mkdirs()
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
        return dir
    }

    private fun deleteTarget(item: DownloadItem) {
        runCatching { item.contentUri?.let { app.contentResolver.delete(Uri.parse(it), null, null) } }
        runCatching { item.filePath?.let { File(it).delete() } }
    }

    fun fileExists(item: DownloadItem): Boolean = runCatching {
        item.contentUri?.let { s ->
            app.contentResolver.openFileDescriptor(Uri.parse(s), "r")?.use { true } ?: false
        } ?: item.filePath?.let { File(it).isFile } ?: false
    }.getOrDefault(false)

    fun uriFor(item: DownloadItem): Uri? = item.contentUri?.let(Uri::parse)
        ?: item.filePath?.let { runCatching { FileProvider.getUriForFile(app, app.packageName + ".files", File(it)) }.getOrNull() }

    fun openIntent(item: DownloadItem): Intent? {
        val uri = uriFor(item) ?: return null
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareIntent(item: DownloadItem): Intent? {
        val uri = uriFor(item) ?: return null
        val send = Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(item.fileName, uri)
        return Intent.createChooser(send, "Share ${item.fileName}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ------------------------------------------------------------------ helpers

    private fun set(id: Long, f: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    private fun save() {
        if (!initialized) return
        val snapshot = _items.value.filterNot { it.isPrivate }
        io.execute {
            runCatching {
                val arr = JSONArray(); snapshot.forEach { arr.put(it.toJson()) }
                val f = File(app.filesDir, STORE); val tmp = File(app.filesDir, "$STORE.tmp")
                tmp.writeText(arr.toString()); tmp.renameTo(f)
            }.onFailure { Log.w(TAG, "save failed", it) }
        }
    }


    private fun vaultFinished(item: DownloadItem) {
        _items.update { list -> list.filterNot { it.id == item.id } }
        _privateItems.update { cur ->
            if (cur.any { it.id == item.id }) cur
            else (listOf(item) + cur).sortedByDescending { it.finishedAt.takeIf { t -> t > 0 } ?: it.createdAt }
        }
        savePrivate()
    }

    private fun savePrivate() {
        if (!initialized) return
        val snapshot = _privateItems.value
        io.execute {
            runCatching {
                val arr = JSONArray()
                snapshot.forEach { arr.put(it.toJson().put("isPrivate", true)) }
                val f = File(app.filesDir, PRIVATE_STORE); val tmp = File(app.filesDir, "$PRIVATE_STORE.tmp")
                tmp.writeText(arr.toString()); tmp.renameTo(f)
            }.onFailure { Log.w(TAG, "private save failed", it) }
        }
    }

    private fun guessMime(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    internal fun sanitize(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().trim('.')
        return clean.ifEmpty { "download" }.take(120)
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 1
        while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
        return f
    }

}
