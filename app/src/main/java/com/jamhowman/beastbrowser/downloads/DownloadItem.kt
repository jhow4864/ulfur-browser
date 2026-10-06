package com.jamhowman.beastbrowser.downloads

import org.json.JSONObject

enum class DlStatus(val label: String) {
    QUEUED("Queued"), DOWNLOADING("Downloading"), PAUSED("Paused"),
    FAILED("Failed"), CANCELLED("Cancelled"), DONE("Done");

    val isActive get() = this == QUEUED || this == DOWNLOADING
    val isFinished get() = this == DONE || this == FAILED || this == CANCELLED
}

/** Immutable snapshot of one download. [total] is -1 when the server didn't send a length. */
data class DownloadItem(
    val id: Long,
    val url: String,
    val fileName: String,
    val mime: String,
    val domain: String,
    val isPrivate: Boolean,
    val status: DlStatus,
    val downloaded: Long = 0,
    val total: Long = -1,
    val speedBps: Long = 0,
    val error: String? = null,
    /** content:// Uri (MediaStore, Android 10+) */
    val contentUri: String? = null,
    /** absolute file path (Android 8/9) */
    val filePath: String? = null,
    val referrer: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
) {
    /** 0..100, or -1 when indeterminate. */
    val percent: Int get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else if (status == DlStatus.DONE) 100 else -1

    /** Seconds remaining, or -1 if unknown. */
    val etaSeconds: Long get() = if (status == DlStatus.DOWNLOADING && total > 0 && speedBps > 0) ((total - downloaded).coerceAtLeast(0) / speedBps) else -1

    val extension: String get() = fileName.substringAfterLast('.', "").take(4).uppercase()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("url", url).put("fileName", fileName).put("mime", mime).put("domain", domain)
        .put("status", status.name).put("downloaded", downloaded).put("total", total)
        .put("error", error).put("contentUri", contentUri).put("filePath", filePath).put("referrer", referrer)
        .put("createdAt", createdAt).put("finishedAt", finishedAt)

    companion object {
        fun fromJson(o: JSONObject) = DownloadItem(
            id = o.getLong("id"), url = o.getString("url"), fileName = o.getString("fileName"),
            mime = o.optString("mime", "application/octet-stream"), domain = o.optString("domain"),
            isPrivate = false, status = runCatching { DlStatus.valueOf(o.getString("status")) }.getOrDefault(DlStatus.FAILED),
            downloaded = o.optLong("downloaded"), total = o.optLong("total", -1),
            error = o.optString("error").takeIf { it.isNotEmpty() && it != "null" },
            contentUri = o.optString("contentUri").takeIf { it.isNotEmpty() && it != "null" },
            filePath = o.optString("filePath").takeIf { it.isNotEmpty() && it != "null" },
            referrer = o.optString("referrer").takeIf { it.isNotEmpty() && it != "null" },
            createdAt = o.optLong("createdAt"), finishedAt = o.optLong("finishedAt"),
        )
    }
}

object DlFormat {
    fun bytes(b: Long): String {
        if (b < 0) return "?"
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0; var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
    }

    fun eta(sec: Long): String = when {
        sec < 0 -> ""
        sec < 60 -> "${sec}s left"
        sec < 3600 -> "${sec / 60}m ${sec % 60}s left"
        else -> "${sec / 3600}h ${(sec % 3600) / 60}m left"
    }

    /** "12.4 MB / 48.0 MB · 2.3 MB/s · 16s left" (no status label) */
    fun detail(d: DownloadItem): String {
        val parts = mutableListOf<String>()
        when (d.status) {
            DlStatus.DOWNLOADING, DlStatus.PAUSED, DlStatus.QUEUED -> {
                if (d.downloaded > 0 || d.total > 0) parts += bytes(d.downloaded) + if (d.total > 0) " / ${bytes(d.total)}" else ""
                if (d.status == DlStatus.DOWNLOADING && d.speedBps > 0) parts += "${bytes(d.speedBps)}/s"
                eta(d.etaSeconds).takeIf { it.isNotEmpty() }?.let { parts += it }
                if (d.status == DlStatus.QUEUED && parts.isEmpty()) parts += "Waiting…"
                if (d.status == DlStatus.PAUSED && d.error != null) parts += d.error
            }
            DlStatus.DONE -> parts += bytes(if (d.total > 0) d.total else d.downloaded)
            DlStatus.FAILED -> parts += d.error ?: "Something went wrong"
            DlStatus.CANCELLED -> parts += "Removed partial file"
        }
        return parts.joinToString(" · ")
    }

    /** "Downloading · 12.4 MB / 48.0 MB · 2.3 MB/s · 16s left" */
    fun statusLine(d: DownloadItem): String = listOf(d.status.label, detail(d)).filter { it.isNotEmpty() }.joinToString(" · ")
}
