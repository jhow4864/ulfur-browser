package com.jamhowman.beastbrowser.autofill

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.appcompat.app.AppCompatActivity
import com.jamhowman.beastbrowser.passwords.PasswordVault

/**
 * Shown by Android when the user picks an Ulfur suggestion in another app. Asks for biometric/PIN every time
 * ([FillAuth]), then re-reads the requesting app from the system-supplied AssistStructure, re-checks its signature
 * against the decrypted login's origin, and only then hands the filled dataset back. Anything else: cancel.
 */
class AutofillAuthActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        setResult(RESULT_CANCELED)
        val guid = intent.getStringExtra(EXTRA_GUID) ?: return finish()
        @Suppress("DEPRECATION")
        val structure: AssistStructure = (if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
            else intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE)) ?: return finish()
        val pkg = structure.activityComponent?.packageName ?: return finish()
        val scan = UlfurAutofillService.scan(structure)
        if (pkg == packageName || scan.hasWebDomain) return finish()
        val found = AutofillFields.find(scan.nodes) ?: return finish()
        PasswordVault.init(this)
        FillAuth.authorize(this, appLabel(pkg)) { ok ->
            val login = if (ok) PasswordVault.loginFor(guid) else null
            if (login == null || !FillPolicy.appMatch(login.origin, pkg, AppSigners.sha256(this, pkg))) { finish(); return@authorize }
            val pres = RemoteViews(packageName, android.R.layout.simple_list_item_1)
            @Suppress("DEPRECATION")
            val ds = Dataset.Builder(pres).apply {
                found.username?.let { setValue(it, AutofillValue.forText(login.username)) }
                setValue(found.password, AutofillValue.forText(login.password))
            }.build()
            setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, ds))
            finish()
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object { const val EXTRA_GUID = "guid" }
}
