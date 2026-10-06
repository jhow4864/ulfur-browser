package com.jamhowman.beastbrowser

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.downloads.DownloadService
import com.jamhowman.beastbrowser.update.InstallResultReceiver
import com.jamhowman.beastbrowser.update.UpdatePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Bug 6: Android 14+ background activity-launch limits for the install confirm screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallResultReceiverTest {
    private lateinit var app: Application
    private lateinit var nm: NotificationManager

    private val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL").setPackage("com.android.packageinstaller")

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        nm = app.getSystemService(NotificationManager::class.java)
        UpdatePrefs.get(app).pendingTag = "v9.9.9"
    }

    private fun setForeground(foreground: Boolean) {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.RunningAppProcessInfo(app.packageName, android.os.Process.myPid(), arrayOf(app.packageName)).apply {
            importance = if (foreground) ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                else ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        }
        shadowOf(am).setProcesses(listOf(info))
    }

    private fun deliver(status: Int = PackageInstaller.STATUS_PENDING_USER_ACTION) {
        val intent = Intent(app, InstallResultReceiver::class.java)
            .putExtra(PackageInstaller.EXTRA_STATUS, status)
            .putExtra(Intent.EXTRA_INTENT, confirm)
        InstallResultReceiver().onReceive(app, intent)
    }

    @Test fun decision() {
        assertTrue(InstallResultReceiver.launchDirectly(Build.VERSION_CODES.TIRAMISU, inForeground = false))
        assertTrue(InstallResultReceiver.launchDirectly(Build.VERSION_CODES.Q, inForeground = true))
        assertTrue(InstallResultReceiver.launchDirectly(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, inForeground = true))
        assertFalse(InstallResultReceiver.launchDirectly(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, inForeground = false))
        assertFalse(InstallResultReceiver.launchDirectly(35, inForeground = false))
    }

    @Test fun foregroundDetection() {
        setForeground(true)
        assertTrue(InstallResultReceiver.isAppInForeground())
        setForeground(false)
        assertFalse(InstallResultReceiver.isAppInForeground())
    }

    @Test fun android14InForegroundStartsConfirmDirectly() {
        setForeground(true)
        deliver()
        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(confirm.action, started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun android14InBackgroundPostsNotificationInstead() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setForeground(false)
        deliver()
        assertNull(shadowOf(app).nextStartedActivity)
        val n = shadowOf(nm).getNotification(InstallResultReceiver.NOTIF_UPDATE)
        assertNotNull(n)
        assertEquals(DownloadService.CHANNEL_DONE, n.channelId)
        val tap = shadowOf(n.contentIntent)
        assertTrue(tap.isActivityIntent)
        assertEquals(confirm.action, tap.savedIntent.action)
        assertEquals(confirm.`package`, tap.savedIntent.`package`)
        assertEquals("v9.9.9", UpdatePrefs.get(app).pendingTag)   // still pending until installed
    }

    @Test fun android14WithoutNotificationPermissionTellsUser() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setForeground(false)
        deliver()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertTrue(ShadowToast.getTextOfLatestToast().contains("finish updating"))
        assertEquals("v9.9.9", UpdatePrefs.get(app).pendingTag)   // next launch offers the update again
    }

    @Config(sdk = [33])
    @Test fun android13KeepsDirectStartFromBackground() {
        setForeground(false)
        deliver()
        assertEquals(confirm.action, shadowOf(app).nextStartedActivity?.action)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun laterResultClearsNotification() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setForeground(false)
        deliver()
        assertNotNull(shadowOf(nm).getNotification(InstallResultReceiver.NOTIF_UPDATE))
        deliver(PackageInstaller.STATUS_FAILURE_ABORTED)
        assertNull(shadowOf(nm).getNotification(InstallResultReceiver.NOTIF_UPDATE))
    }
}
