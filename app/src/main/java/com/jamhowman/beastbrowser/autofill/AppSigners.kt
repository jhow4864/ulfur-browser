package com.jamhowman.beastbrowser.autofill

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** SHA-256 of an installed app's current signing certificate(s), for [FillPolicy.appMatch]. */
object AppSigners {
    @Suppress("DEPRECATION")
    fun sha256(ctx: Context, pkg: String): List<ByteArray> = runCatching {
        val pm = ctx.packageManager
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptyList()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        certs.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
    }.getOrDefault(emptyList())
}
