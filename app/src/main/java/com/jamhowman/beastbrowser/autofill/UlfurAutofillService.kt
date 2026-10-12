package com.jamhowman.beastbrowser.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import com.jamhowman.beastbrowser.passwords.PasswordVault

/**
 * 2.10 Ulfur #2: offers Ulfur's saved logins to other apps (Settings › Passwords & autofill › Autofill service).
 *
 * - Apps only, matched by signing certificate: a login must be saved as `android://<sha256>@<package>` (the format
 *   Chrome / Google Password Manager exports use) and the app must currently be signed with that certificate.
 * - Web pages inside other apps (WebView/Custom Tabs `webDomain`) are not filled: the hosting app reports that domain
 *   itself, so it can't be trusted. Ulfur's own screens are skipped (Gecko + Prompts handle those).
 * - Suggestions carry only the username (from the no-secrets meta file). Each one is locked behind
 *   [AutofillAuthActivity], which asks for biometric/PIN, re-checks the app's signature, and only then decrypts.
 */
class UlfurAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancel: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val pkg = structure.activityComponent?.packageName ?: return callback.onSuccess(null)
        if (pkg == packageName) return callback.onSuccess(null)
        val scan = scan(structure)
        if (scan.hasWebDomain) return callback.onSuccess(null)
        val found = AutofillFields.find(scan.nodes) ?: return callback.onSuccess(null)
        PasswordVault.init(this)
        val signers = AppSigners.sha256(this, pkg)
        val matches = PasswordVault.allMeta().filter { FillPolicy.appMatch(it.origin, pkg, signers) }
        if (matches.isEmpty()) return callback.onSuccess(null)

        val response = FillResponse.Builder()
        matches.take(MAX_SUGGESTIONS).forEachIndexed { i, m ->
            val pres = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "${m.username.ifBlank { "(no username)" }} · Ulfur")
            }
            val intent = Intent(this, AutofillAuthActivity::class.java).putExtra(AutofillAuthActivity.EXTRA_GUID, m.guid)
            val pi = PendingIntent.getActivity(this, REQUEST_BASE + i, intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
            @Suppress("DEPRECATION")
            val ds = Dataset.Builder(pres).apply {
                found.username?.let { setValue(it, null, pres) }
                setValue(found.password, null, pres)
                setAuthentication(pi.intentSender)
            }.build()
            response.addDataset(ds)
        }
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()

    class Scan(val nodes: List<AutofillFields.Node<AutofillId>>, val hasWebDomain: Boolean)

    companion object {
        private const val MAX_SUGGESTIONS = 5
        private const val REQUEST_BASE = 0x5100

        fun scan(structure: AssistStructure): Scan {
            val out = ArrayList<AutofillFields.Node<AutofillId>>()
            var web = false
            fun walk(n: AssistStructure.ViewNode, parentVisible: Boolean) {
                val visible = parentVisible && n.visibility == View.VISIBLE && n.width > 1 && n.height > 1 && n.alpha > 0.1f
                if (!n.webDomain.isNullOrEmpty()) web = true
                val id = n.autofillId
                if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT) {
                    out += AutofillFields.Node(
                        id = id, hints = n.autofillHints?.toList().orEmpty(), inputType = n.inputType,
                        idEntry = n.idEntry, hint = n.hint, visible = visible, enabled = n.isEnabled,
                        isTextField = n.inputType and InputType.TYPE_MASK_CLASS != 0 || n.className?.endsWith("EditText") == true,
                    )
                }
                for (i in 0 until n.childCount) walk(n.getChildAt(i), visible)
            }
            for (w in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(w).rootViewNode, true)
            return Scan(out, web)
        }
    }
}
