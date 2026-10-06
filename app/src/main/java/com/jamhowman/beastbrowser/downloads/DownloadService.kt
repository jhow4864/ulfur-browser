package com.jamhowman.beastbrowser.downloads

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.ui.DownloadsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive while downloads run and shows a progress notification
 * (tap -> Downloads screen). Stops itself as soon as nothing is active.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadCenter.init(this)
        createChannels(this)
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            DownloadCenter.items.sample(1000).collect { update(it) }
        }
        scope.launch {
            DownloadCenter.events.collect { ev ->
                when (ev) {
                    is DownloadCenter.Event.Finished -> notifyDone(ev.item, true)
                    is DownloadCenter.Event.Failed -> notifyDone(ev.item, false)
                    else -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        update(DownloadCenter.items.value)
        return START_NOT_STICKY
    }

    private fun update(list: List<DownloadItem>) {
        val active = list.filter { it.status.isActive }
        // startForegroundService() obliges us to call startForeground() promptly, even if the download already
        // finished in the meantime - so always enter the foreground state first, then stop if idle.
        if (!foreground) {
            try {
                ServiceCompat.startForeground(this, NOTIF_PROGRESS, progressNotification(this, active),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
                foreground = true
            } catch (e: Exception) {
                // e.g. not allowed from background / FGS time limit: downloads keep running while the app is alive
                Log.w("BeastDownloads", "foreground service not allowed", e)
                stopSelf(); return
            }
        }
        if (active.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
            return
        }
        // Android 13+: the foreground notification shows regardless, but later updates need POST_NOTIFICATIONS.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_PROGRESS, progressNotification(this, active)) }
    }

    private fun notifyDone(item: DownloadItem, ok: Boolean) {
        // Android 13+: skip quietly if the user said no to notifications (the download itself is unaffected).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val title = when {
            item.isPrivate && ok -> "Private download complete"
            item.isPrivate -> "Private download failed"
            ok -> "Downloaded ${item.fileName}"
            else -> "Download failed: ${item.fileName}"
        }
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(if (ok) R.drawable.ic_download_done else R.drawable.ic_warning)
            .setContentTitle(title)
            .setContentText(if (ok) item.domain else (item.error ?: "Tap to retry"))
            .setColor(Prefs.accent.color)
            .setContentIntent(downloadsIntent(this))
            .setAutoCancel(true)
            .setVisibility(if (item.isPrivate) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify((item.id % Int.MAX_VALUE).toInt(), n) }
    }

    @Deprecated("Deprecated in Java")
    override fun onTimeout(startId: Int) { stopSelf() }  // Android 15 dataSync time limit
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_PROGRESS = "downloads"
        const val CHANNEL_DONE = "downloads_done"
        const val NOTIF_PROGRESS = 4201

        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: Exception) {
                Log.w("BeastDownloads", "could not start download service", e)
            }
        }

        fun downloadsIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 7, Intent(context, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun progressNotification(context: Context, active: List<DownloadItem>): Notification {
            val totalKnown = active.all { it.total > 0 }
            val done = active.sumOf { it.downloaded }
            val total = active.sumOf { it.total.coerceAtLeast(0) }
            val pct = if (totalKnown && total > 0) ((done * 100) / total).toInt() else 0
            val single = active.singleOrNull()
            val title = when {
                active.isEmpty() -> "Preparing download…"
                single == null -> "Downloading ${active.size} files"
                single.isPrivate -> "Private download"
                else -> single.fileName
            }
            val text = if (single != null) DlFormat.statusLine(single) else "${DlFormat.bytes(done)}" + if (totalKnown) " / ${DlFormat.bytes(total)}" else ""
            return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(text)
                .setColor(Prefs.accent.color)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .setProgress(100, pct, !totalKnown)
                .setContentIntent(downloadsIntent(context))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
