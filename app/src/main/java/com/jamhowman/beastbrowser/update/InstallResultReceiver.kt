package com.jamhowman.beastbrowser.update

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.downloads.DownloadService

/** Receives PackageInstaller session status for the self-update. Not exported. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // System "Do you want to update this app?" screen.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                showConfirm(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Normally we're killed and replaced before this arrives; nothing to do.
                cancelNotification(context)
                UpdatePrefs.get(context).clearPending()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                cancelNotification(context)
                Toast.makeText(context, "Update cancelled.", Toast.LENGTH_SHORT).show()
            }
            else -> {
                cancelNotification(context)
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val why = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "it conflicts with the installed app (different signing key?)"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "it isn't compatible with this device"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "there isn't enough storage"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "the package is invalid"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "it was blocked by the system"
                    else -> msg ?: "unknown error"
                }
                Toast.makeText(context, "Update failed: $why.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Android 14+ blocks activity starts from the background (the system no longer lends its own
     * background-start privilege to the session callback). So when no screen of ours is in the foreground,
     * post a notification whose tap opens the confirm screen instead. Before 14 the direct start works as it always did.
     */
    private fun showConfirm(context: Context, confirm: Intent) {
        if (launchDirectly(Build.VERSION.SDK_INT, isAppInForeground())) {
            try { context.startActivity(confirm); return } catch (_: Exception) { /* fall back to the notification */ }
        }
        if (notifyConfirm(context, confirm)) return
        // Notifications are off: try anyway (may be blocked), and tell the user how to finish. pendingTag is still
        // set, so the next launch offers the update again (the verified APK is kept in the cache).
        runCatching { context.startActivity(confirm) }
        Toast.makeText(context, "Update ready. If the installer didn't open, open ${context.getString(R.string.app_name)} to finish updating.",
            Toast.LENGTH_LONG).show()
    }

    /** Posts the "tap to install" notification. Returns false if notifications aren't allowed. */
    private fun notifyConfirm(context: Context, confirm: Intent): Boolean {
        // Android 13+: respect a "no" to notifications (same rule as DownloadService).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = NotificationManagerCompat.from(context)
        DownloadService.createChannels(context)
        if (!nm.areNotificationsEnabled() ||
            nm.getNotificationChannel(DownloadService.CHANNEL_DONE)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return false
        val tap = PendingIntent.getActivity(context, NOTIF_UPDATE, confirm,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, DownloadService.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_download_done)
            .setContentTitle("Update ready to install")
            .setContentText("Tap to finish updating ${context.getString(R.string.app_name)}")
            .setColor(Prefs.accent.color)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        return runCatching { nm.notify(NOTIF_UPDATE, n) }.isSuccess
    }

    private fun cancelNotification(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_UPDATE) }
    }

    companion object {
        const val NOTIF_UPDATE = 4202

        /** Start the confirm screen right away, or go through a notification (Android 14+ in the background)? */
        internal fun launchDirectly(sdkInt: Int, inForeground: Boolean): Boolean =
            sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || inForeground

        /** True while one of our activities is the top, visible UI (a foreground service alone doesn't count). */
        internal fun isAppInForeground(): Boolean = runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }.getOrDefault(false)
    }
}
