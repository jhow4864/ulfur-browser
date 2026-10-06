package com.jamhowman.beastbrowser.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat

/** Receives PackageInstaller session status for the self-update. Not exported. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // System "Do you want to update this app?" screen.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(confirm) } catch (_: Exception) {
                    Toast.makeText(context, "Couldn't open the installer.", Toast.LENGTH_LONG).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Normally we're killed and replaced before this arrives; nothing to do.
                UpdatePrefs.get(context).clearPending()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Toast.makeText(context, "Update cancelled.", Toast.LENGTH_SHORT).show()
            }
            else -> {
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
}
