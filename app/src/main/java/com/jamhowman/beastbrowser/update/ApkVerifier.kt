package com.jamhowman.beastbrowser.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/**
 * Checks a downloaded APK before we hand it to the system installer:
 * same package, higher versionCode, and signed by the same certificate as the installed app.
 * (Android refuses a differently-signed update anyway; this lets us fail early with a clear message
 * and never even start an install session for a foreign APK.)
 */
object ApkVerifier {

    sealed class Result {
        data class Ok(val versionName: String?, val versionCode: Long) : Result()
        data class Rejected(val reason: String) : Result()
    }

    fun verify(context: Context, apk: File): Result {
        val pm = context.packageManager
        val ours = context.packageName
        val archive = archiveInfo(pm, apk) ?: return Result.Rejected("The downloaded file isn't a valid app package.")
        if (archive.packageName != ours) return Result.Rejected("The downloaded app is a different app (${archive.packageName}).")

        val installed = installedInfo(pm, ours)
        val newCode = PackageInfoCompat.getLongVersionCode(archive)
        val oldCode = PackageInfoCompat.getLongVersionCode(installed)
        if (newCode <= oldCode) return Result.Rejected("The downloaded app isn't newer than this one (build $newCode vs $oldCode).")

        val newCurrent = currentSigners(archive) ?: return Result.Rejected("Couldn't read the downloaded app's signature.")
        val oldCurrent = currentSigners(installed) ?: return Result.Rejected("Couldn't read this app's signature.")
        val matches = newCurrent == oldCurrent ||
            // Key rotation (APK Signature Scheme v3): the new APK proves it's allowed to replace the old key.
            (oldCurrent.size == 1 && pastSigners(archive).containsAll(oldCurrent))
        if (!matches) return Result.Rejected("The downloaded app is signed with a different key. Not installing it.")

        return Result.Ok(archive.versionName, newCode)
    }

    @Suppress("DEPRECATION")
    private fun flags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags().toLong()))
        else pm.getPackageArchiveInfo(apk.path, flags())

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags().toLong()))
        else pm.getPackageInfo(pkg, flags())

    /** SHA-256 fingerprints of the certificate(s) currently signing the package. */
    @Suppress("DEPRECATION")
    private fun currentSigners(info: PackageInfo): Set<String>? {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return null
            si.apkContentsSigners
        } else info.signatures
        return sigs?.takeIf { it.isNotEmpty() }?.map { fp(it) }?.toSet()
    }

    /** Current + past (rotated-from) certificates, API 28+. */
    private fun pastSigners(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT < 28) return emptySet()
        val si = info.signingInfo ?: return emptySet()
        if (si.hasMultipleSigners()) return emptySet()
        return si.signingCertificateHistory?.map { fp(it) }?.toSet() ?: emptySet()
    }

    private fun fp(s: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
