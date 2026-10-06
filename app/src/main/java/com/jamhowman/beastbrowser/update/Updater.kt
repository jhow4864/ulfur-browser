package com.jamhowman.beastbrowser.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.jamhowman.beastbrowser.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app updater for GitHub releases.
 *
 * - The repo comes from the GITHUB_REPO Gradle property (BuildConfig.UPDATE_REPO). Blank or the
 *   OWNER/REPO placeholder disables everything.
 * - [onLaunch]: checks at most once every 24 h (call from MainActivity.onCreate).
 * - [bindPreference]: wires the "Check for updates" item in Settings (manual check, always reports a result).
 * - Update: asks for "install unknown apps" if needed → downloads the APK to cacheDir/updates with a
 *   progress dialog → verifies package/versionCode/signing cert ([ApkVerifier]) → installs with a
 *   PackageInstaller session (result in [InstallResultReceiver]).
 */
object Updater {

    private const val TAG = "BeastUpdater"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val LAUNCH_DELAY_MS = 4_000L
    private const val UPDATES_DIR = "updates"

    val repo: String get() = BuildConfig.UPDATE_REPO.trim()
    val isEnabled: Boolean get() = UpdateLogic.isConfigured(repo)

    /** Guards against overlapping checks / stacked dialogs (launch check + manual check). */
    private var busy = false

    // ---- entry points -------------------------------------------------------------------------

    fun onLaunch(activity: AppCompatActivity) {
        if (!isEnabled) return
        val prefs = UpdatePrefs.get(activity)
        val now = System.currentTimeMillis()
        val due = prefs.pendingTag != null || now - prefs.lastCheck >= CHECK_INTERVAL_MS || prefs.lastCheck > now
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { cleanupCache(activity) }
            if (!due) return@launch
            delay(LAUNCH_DELAY_MS)   // let startup (Gecko, tabs) settle first
            check(activity, manual = false)
        }
    }

    fun bindPreference(fragment: PreferenceFragmentCompat, pref: Preference) {
        if (!isEnabled) {
            pref.isEnabled = false
            pref.summary = "Not available in this build"
            return
        }
        pref.summary = "You're on version ${BuildConfig.VERSION_NAME}. Also checks automatically once a day."
        pref.setOnPreferenceClickListener {
            (fragment.activity as? AppCompatActivity)?.let { checkNow(it) }
            true
        }
    }

    fun checkNow(activity: AppCompatActivity) {
        if (!isEnabled) return
        activity.lifecycleScope.launch { check(activity, manual = true) }
    }

    // ---- check ----------------------------------------------------------------------------------

    private suspend fun check(activity: AppCompatActivity, manual: Boolean) {
        if (busy) {
            if (manual) toast(activity, "Already checking for updates…")
            return
        }
        busy = true
        try {
            if (manual) toast(activity, "Checking for updates…")
            val result = withContext(Dispatchers.IO) { GithubReleases.fetchLatest(repo) }
            val prefs = UpdatePrefs.get(activity)
            // Background launch check while the app isn't visible: don't record, retry next launch.
            if (!manual && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            when (result) {
                is GithubReleases.Result.Ok -> {
                    prefs.lastCheck = System.currentTimeMillis()
                    handleRelease(activity, result.release, manual)
                }
                GithubReleases.Result.NoRelease -> {
                    prefs.lastCheck = System.currentTimeMillis()
                    prefs.clearPending()
                    // Launch check stays silent: a private repo (or one with no releases) also returns 404.
                    if (manual) info(activity, "No update information", "No published release was found on GitHub. The repository may still be private, or has no releases yet.")
                }
                is GithubReleases.Result.Error ->
                    if (manual) info(activity, "Couldn't check for updates", result.message)
            }
        } finally {
            busy = false
        }
    }

    private fun handleRelease(activity: AppCompatActivity, release: ReleaseInfo, manual: Boolean) {
        val prefs = UpdatePrefs.get(activity)
        val current = BuildConfig.VERSION_NAME
        if (release.draft || release.prerelease || !UpdateLogic.isNewer(release.tag, current)) {
            prefs.clearPending()
            if (manual) {
                if (SemVer.parse(release.tag) == null && !release.draft && !release.prerelease)
                    info(activity, "Couldn't check for updates", "The latest release's tag (\"${release.tag}\") isn't a version number.")
                else info(activity, "You're up to date", "Version $current is the latest version.")
            }
            return
        }
        if (!manual && release.tag == prefs.skippedTag && prefs.pendingTag != release.tag) return
        val version = displayVersion(release.tag)
        val asset = UpdateLogic.pickApk(release.assets, Build.SUPPORTED_ABIS.firstOrNull())
        if (asset == null) {
            prefs.clearPending()
            if (manual) info(activity, "Version $version is out", "But the release has no APK attached yet. Try again later.")
            return
        }
        showUpdateDialog(activity, release, asset, version)
    }

    private fun showUpdateDialog(activity: AppCompatActivity, release: ReleaseInfo, asset: ReleaseAsset, version: String) {
        if (!alive(activity)) return
        val size = if (asset.size > 0) "\n\nDownload: ${Formatter.formatShortFileSize(activity, asset.size)}" else ""
        MaterialAlertDialogBuilder(activity)
            .setTitle("Update available: $version")
            .setMessage(UpdateLogic.notesForDialog(release.notes) + size)
            .setPositiveButton("Update") { _, _ -> startUpdate(activity, release, asset, version) }
            .setNegativeButton("Later") { _, _ -> UpdatePrefs.get(activity).clearPending() }
            .setNeutralButton("Skip this version") { _, _ ->
                UpdatePrefs.get(activity).apply { skippedTag = release.tag; clearPending() }
            }
            .show()
    }

    // ---- update ---------------------------------------------------------------------------------

    private fun startUpdate(activity: AppCompatActivity, release: ReleaseInfo, asset: ReleaseAsset, version: String) {
        val prefs = UpdatePrefs.get(activity)
        prefs.pendingTag = release.tag
        if (activity.packageManager.canRequestPackageInstalls()) {
            download(activity, release, asset, version)
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Allow updates")
            .setMessage("To install the update, Android needs you to allow this app to install apps. " +
                "Turn on \"Allow from this source\", then come back.")
            .setPositiveButton("Open settings") { _, _ ->
                requestInstallPermission(activity) { granted ->
                    if (granted) download(activity, release, asset, version)
                    else {
                        prefs.clearPending()
                        toast(activity, "Update needs permission to install apps.")
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> prefs.clearPending() }
            .show()
    }

    private fun requestInstallPermission(activity: AppCompatActivity, onResult: (Boolean) -> Unit) {
        // Registered ad hoc (not in onCreate) so callers don't need any setup; unregistered on result.
        // If the activity is recreated meanwhile the callback is lost; pendingTag makes the next launch re-check.
        lateinit var launcher: ActivityResultLauncher<Intent>
        launcher = activity.activityResultRegistry.register(
            "beast_updater_install_perm_${System.nanoTime()}",
            ActivityResultContracts.StartActivityForResult(),
        ) {
            launcher.unregister()
            if (alive(activity)) onResult(activity.packageManager.canRequestPackageInstalls())
        }
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            launcher.unregister()
            info(activity, "Allow updates", "Open Android Settings → Apps → Special app access → Install unknown apps, and allow this app.")
        }
    }

    private fun download(activity: AppCompatActivity, release: ReleaseInfo, asset: ReleaseAsset, version: String) {
        if (!alive(activity)) return
        val dir = File(activity.cacheDir, UPDATES_DIR).apply { mkdirs() }
        val dest = File(dir, fileNameFor(release.tag))
        // Room for the APK plus the installer's staged copy.
        val needed = if (asset.size > 0) asset.size * 2 + 50L * 1024 * 1024 else 0L
        if (!(dest.isFile && dest.length() == asset.size) && dir.usableSpace < needed) {
            info(activity, "Not enough storage", "The update needs about ${Formatter.formatShortFileSize(activity, needed)} free.")
            UpdatePrefs.get(activity).clearPending()
            return
        }

        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val bar = LinearProgressIndicator(activity).apply { max = 100 }
        val label = TextView(activity).apply { text = "Starting…"; setPadding(0, pad / 2, 0, 0) }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + pad / 4, pad, pad + pad / 4, 0)
            addView(bar); addView(label)
        }
        val cancelled = AtomicBoolean(false)
        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Downloading $version")
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled.set(true) }
            .show()

        activity.lifecycleScope.launch {
            try {
                if (!(dest.isFile && dest.length() == asset.size)) {
                    withContext(Dispatchers.IO) {
                        GithubReleases.download(asset, dest, isCancelled = { cancelled.get() || !isActive }) { f ->
                            bar.post {
                                if (f >= 0) {
                                    val pct = (f * 100).toInt().coerceIn(0, 100)
                                    bar.setProgressCompat(pct, true)
                                    label.text = "$pct% of ${Formatter.formatShortFileSize(activity, asset.size)}"
                                } else label.text = "Downloading…"
                            }
                        }
                    }
                }
                label.text = "Checking the download…"
                when (val v = withContext(Dispatchers.IO) { ApkVerifier.verify(activity, dest) }) {
                    is ApkVerifier.Result.Rejected -> {
                        dialog.dismiss()
                        dest.delete()
                        UpdatePrefs.get(activity).clearPending()
                        info(activity, "Update not installed", v.reason)
                    }
                    is ApkVerifier.Result.Ok -> {
                        label.text = "Preparing to install…"
                        val err = withContext(Dispatchers.IO) { install(activity, dest) }
                        dialog.dismiss()
                        if (err != null) {
                            UpdatePrefs.get(activity).clearPending()
                            info(activity, "Update not installed", err)
                        }
                        // Success path: the system confirm screen opens via InstallResultReceiver.
                    }
                }
            } catch (_: GithubReleases.CancelledException) {
                dialog.dismiss()
                UpdatePrefs.get(activity).clearPending()
            } catch (e: CancellationException) {
                // Activity gone mid-update: keep pendingTag so the next launch re-checks.
                if (dialog.isShowing) dialog.dismiss()
                throw e
            } catch (e: Exception) {
                // Any failure (network, verifier, installer, UI): never crash, never leave the update stuck as pending.
                Log.w(TAG, "update download failed", e)
                runCatching { if (dialog.isShowing) dialog.dismiss() }
                abandonDownload(activity, dest)
                info(activity, "Download failed", failureMessage(e))
            }
        }
    }

    /** Undoes a failed update: forgets the pending tag and deletes the (partial or unverified) APK. */
    internal fun abandonDownload(context: Context, dest: File) {
        UpdatePrefs.get(context).clearPending()
        runCatching { File(dest.parentFile, dest.name + ".part").delete() }
        runCatching { dest.delete() }
    }

    /** Message for the "Download failed" dialog. IOExceptions carry user-facing text; anything else gets a generic line. */
    internal fun failureMessage(e: Exception): String = when (e) {
        is IOException -> e.message ?: "Couldn't download the update."
        else -> "Something went wrong while updating (${e.javaClass.simpleName}). Please try again."
    }

    /** Streams [apk] into a PackageInstaller session and commits it. Returns an error message or null. */
    private fun install(context: Context, apk: File): String? {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        var sessionId = -1
        return try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val callback = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)   // system adds status extras
                val pi = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                session.commit(pi.intentSender)
            }
            null
        } catch (e: Exception) {
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            "Couldn't start the installer (${e.message ?: e.javaClass.simpleName})."
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Deletes partial downloads and any cached APK that isn't newer than the running version. */
    private fun cleanupCache(context: Context) {
        val dir = File(context.cacheDir, UPDATES_DIR)
        dir.listFiles()?.forEach { f ->
            val tag = f.name.removePrefix("update-").removeSuffix(".apk")
            if (f.name.endsWith(".part") || !f.name.endsWith(".apk") || !UpdateLogic.isNewer(tag, BuildConfig.VERSION_NAME)) f.delete()
        }
    }

    private fun fileNameFor(tag: String) = "update-" + tag.replace(Regex("[^0-9A-Za-z._+-]"), "_") + ".apk"

    private fun displayVersion(tag: String) = tag.trim().removePrefix("v").removePrefix("V")

    private fun alive(activity: AppCompatActivity) = !activity.isFinishing && !activity.isDestroyed

    private fun info(activity: AppCompatActivity, title: String, message: String) {
        if (!alive(activity)) return
        MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
