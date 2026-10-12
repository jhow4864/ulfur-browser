package com.jamhowman.beastbrowser.autofill

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * SHA-256 of an installed app's CURRENT signing certificate(s), for [FillPolicy.appMatch]. Past certificates from
 * key rotation (signingCertificateHistory) are never accepted: a login tied to a retired key must not follow it.
 */
object AppSigners {
    fun sha256(ctx: Context, pkg: String): List<ByteArray> = runCatching {
        val pm = ctx.packageManager
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptyList()
            currentSigners(info.apkContentsSigners, info.signingCertificateHistory)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
        }
        hashes(certs)
    }.getOrDefault(emptyList())

    /** Only the APK's current signers; [history] is deliberately ignored (kept as a parameter so the test shows it). */
    @Suppress("UNUSED_PARAMETER")
    internal fun currentSigners(apkContents: Array<Signature>?, history: Array<Signature>?): List<Signature> =
        apkContents?.toList().orEmpty()

    internal fun hashes(certs: List<Signature>): List<ByteArray> = certs.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
}
