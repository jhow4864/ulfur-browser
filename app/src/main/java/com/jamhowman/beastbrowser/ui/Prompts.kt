package com.jamhowman.beastbrowser.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import android.text.InputType
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.browser.HelperSessions
import com.jamhowman.beastbrowser.databinding.RowPasswordGeneratorBinding
import com.jamhowman.beastbrowser.passwords.PasswordGenerator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import java.util.Calendar
import java.util.Locale

/** Native Material dialogs for web page prompts (alert/confirm/prompt, <select>, file inputs, auth, dates). */
class Prompts(
    private val activity: AppCompatActivity,
    private val pickFiles: (Array<String>?, Boolean, (Array<Uri>?) -> Unit) -> Unit,
    private val onPopupBlocked: () -> Unit,
) : PromptDelegate {

    private class Once(val result: GeckoResult<PromptResponse> = GeckoResult()) {
        private var done = false
        fun complete(r: PromptResponse) { if (!done) { done = true; result.complete(r) } }
    }

    private fun builder(title: String?) = MaterialAlertDialogBuilder(activity).apply { if (!title.isNullOrBlank()) setTitle(title) }

    override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> {
        val o = Once()
        builder(prompt.title).setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ -> o.complete(prompt.dismiss()) }
            .setOnDismissListener { o.complete(prompt.dismiss()) }.show()
        return o.result
    }

    override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> {
        val o = Once()
        builder(prompt.title).setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ -> o.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> o.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
            .setOnDismissListener { o.complete(prompt.dismiss()) }.show()
        return o.result
    }

    override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptResponse> {
        val o = Once()
        val input = EditText(activity).apply { setText(prompt.defaultValue); setSingleLine() }
        builder(prompt.title).setMessage(prompt.message).setView(padded(input))
            .setPositiveButton(android.R.string.ok) { _, _ -> o.complete(prompt.confirm(input.text.toString())) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> o.complete(prompt.dismiss()) }
            .setOnDismissListener { o.complete(prompt.dismiss()) }.show()
        return o.result
    }

    override fun onAuthPrompt(session: GeckoSession, prompt: PromptDelegate.AuthPrompt): GeckoResult<PromptResponse> {
        val o = Once()
        val user = EditText(activity).apply { hint = "Username"; setText(prompt.authOptions.username); setSingleLine() }
        val pass = EditText(activity).apply { hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; addView(user); addView(pass) }
        builder(prompt.title ?: "Sign in").setMessage(prompt.message).setView(padded(box))
            .setPositiveButton("Sign in") { _, _ -> o.complete(prompt.confirm(user.text.toString(), pass.text.toString())) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> o.complete(prompt.dismiss()) }
            .setOnDismissListener { o.complete(prompt.dismiss()) }.show()
        return o.result
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse> {
        val o = Once()
        val flat = ArrayList<PromptDelegate.ChoicePrompt.Choice>()
        fun walk(items: Array<PromptDelegate.ChoicePrompt.Choice>?) {
            items?.forEach { c -> if (c.items != null) walk(c.items) else if (!c.separator) flat += c }
        }
        walk(prompt.choices)
        val labels = flat.map { it.label }.toTypedArray()
        val b = builder(prompt.title ?: prompt.message)
        if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
            val checked = BooleanArray(flat.size) { flat[it].selected }
            b.setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    o.complete(prompt.confirm(flat.filterIndexed { i, _ -> checked[i] }.toTypedArray()))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> o.complete(prompt.dismiss()) }
        } else {
            val sel = flat.indexOfFirst { it.selected }
            b.setSingleChoiceItems(labels, sel) { d, i ->
                if (!flat[i].disabled) { o.complete(prompt.confirm(flat[i])); d.dismiss() }
            }
        }
        b.setOnDismissListener { o.complete(prompt.dismiss()) }.show()
        return o.result
    }

    override fun onFilePrompt(session: GeckoSession, prompt: PromptDelegate.FilePrompt): GeckoResult<PromptResponse> {
        val o = Once()
        pickFiles(prompt.mimeTypes, prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE) { uris ->
            when {
                uris.isNullOrEmpty() -> o.complete(prompt.dismiss())
                prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE -> o.complete(prompt.confirm(activity, uris))
                else -> o.complete(prompt.confirm(activity, uris[0]))
            }
        }
        return o.result
    }

    override fun onDateTimePrompt(session: GeckoSession, prompt: PromptDelegate.DateTimePrompt): GeckoResult<PromptResponse> {
        val o = Once()
        val cal = Calendar.getInstance()
        when (prompt.type) {
            PromptDelegate.DateTimePrompt.Type.DATE -> {
                prompt.defaultValue?.split('-')?.takeIf { it.size == 3 }?.let {
                    cal.set(it[0].toIntOrNull() ?: cal[Calendar.YEAR], (it[1].toIntOrNull() ?: 1) - 1, it[2].toIntOrNull() ?: 1)
                }
                DatePickerDialog(activity, { _, y, m, d ->
                    o.complete(prompt.confirm(String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d)))
                }, cal[Calendar.YEAR], cal[Calendar.MONTH], cal[Calendar.DAY_OF_MONTH]).apply {
                    setOnDismissListener { o.complete(prompt.dismiss()) }
                }.show()
            }
            PromptDelegate.DateTimePrompt.Type.TIME -> {
                TimePickerDialog(activity, { _, h, m ->
                    o.complete(prompt.confirm(String.format(Locale.ROOT, "%02d:%02d", h, m)))
                }, cal[Calendar.HOUR_OF_DAY], cal[Calendar.MINUTE], true).apply {
                    setOnDismissListener { o.complete(prompt.dismiss()) }
                }.show()
            }
            else -> o.complete(prompt.dismiss())
        }
        return o.result
    }


    /**
     * Gecko asks before writing to [Autocomplete.StorageDelegate]. Confirm here so
     * [com.jamhowman.beastbrowser.passwords.VaultStorageDelegate.onLoginSave] actually runs.
     * Private tabs never save.
     */
    override fun onLoginSave(
        session: GeckoSession,
        prompt: PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>,
    ): GeckoResult<PromptResponse> {
        if (isPrivate(session)) {
            Toast.makeText(activity, "Passwords aren't saved in private tabs", Toast.LENGTH_SHORT).show()
            return GeckoResult.fromValue(prompt.dismiss())
        }
        val option = prompt.options.firstOrNull()
            ?: return GeckoResult.fromValue(prompt.dismiss())
        val login = option.value
        val user = login.username.takeIf { it.isNotBlank() } ?: "(no username)"
        val host = runCatching { Uri.parse(login.origin).host }.getOrNull()
            ?.removePrefix("www.")
            ?: login.origin
        val updating = !login.guid.isNullOrBlank()
        val o = Once()
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val pad = dp(activity, 22)
        box.addView(TextView(activity).apply {
            text = "$user\n$host\n\n" + activity.getString(R.string.vault_stored_note)
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 14f
            setPadding(pad, dp(activity, 8), pad, 0)
        })
        // 2.3.4: offer a strong generated password instead of the one typed into the page.
        val gen = RowPasswordGeneratorBinding.inflate(LayoutInflater.from(activity), box, true)
        gen.root.setPadding(pad, 0, pad, 0)
        wireGenerator(gen)
        val dialog = builder(if (updating) "Update password?" else "Save password?")
            .setView(box)
            .setPositiveButton(if (updating) "Update" else "Save") { _, _ -> o.complete(prompt.confirm(option)) }
            .setNegativeButton("Not now") { _, _ -> o.complete(prompt.dismiss()) }
            .setOnDismissListener { o.complete(prompt.dismiss()) }
            .create()
        gen.useGenerated.setOnClickListener {
            val pw = gen.generatedPassword.text?.toString().orEmpty()
            if (pw.isBlank()) return@setOnClickListener
            HelperSessions.fillPassword(session, pw)       // put it into the page's password field too
            o.complete(prompt.confirm(Autocomplete.LoginSaveOption(withPassword(login, pw))))
            dialog.dismiss()
        }
        dialog.show()
        return o.result
    }

    /** Pick among vault logins when Gecko offers a select prompt (fill). Allowed in private tabs. */
    override fun onLoginSelect(
        session: GeckoSession,
        prompt: PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>,
    ): GeckoResult<PromptResponse> {
        // Picking a GENERATED option makes Gecko call StorageDelegate.onLoginSave directly (no onLoginSave prompt),
        // so: never offer it in private tabs, and never auto-confirm it.
        val generated = Autocomplete.SelectOption.Hint.GENERATED
        val options = if (isPrivate(session)) prompt.options.filter { it.hint and generated == 0 }.toTypedArray() else prompt.options
        if (options.isEmpty()) return GeckoResult.fromValue(prompt.dismiss())
        if (options.size == 1 && options[0].hint and generated == 0) return GeckoResult.fromValue(prompt.confirm(options[0]))
        val o = Once()
        val generatedOptions = options.filter { it.hint and generated != 0 }
        val logins = options.filter { it.hint and generated == 0 }
        fun label(opt: Autocomplete.LoginSelectOption) = opt.value.username.takeIf { it.isNotBlank() } ?: "(no username)"
        if (generatedOptions.isEmpty()) {
            builder("Choose a login")
                .setItems(options.map { label(it) }.toTypedArray()) { d, i -> o.complete(prompt.confirm(options[i])); d.dismiss() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> o.complete(prompt.dismiss()) }
                .setOnDismissListener { o.complete(prompt.dismiss()) }
                .show()
            return o.result
        }
        // 2.3.4: Gecko offers a generated password → show the saved logins plus Beast's generator row.
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val pad = dp(activity, 22)
        var dialog: androidx.appcompat.app.AlertDialog? = null
        logins.forEach { opt ->
            box.addView(TextView(activity).apply {
                text = label(opt)
                textSize = 16f
                setTextColor(activity.getColor(R.color.text_primary))
                setPadding(pad, dp(activity, 14), pad, dp(activity, 14))
                setOnClickListener { o.complete(prompt.confirm(opt)); dialog?.dismiss() }
            })
        }
        val gen = RowPasswordGeneratorBinding.inflate(LayoutInflater.from(activity), box, true)
        gen.root.setPadding(pad, 0, pad, 0)
        wireGenerator(gen)
        generatedOptions.first().value.password?.takeIf { it.length in 8..32 }?.let { pw ->
            gen.lengthSlider.value = pw.length.toFloat()   // first: the slider listener regenerates
            gen.generatedPassword.text = pw
            gen.lengthLabel.text = pw.length.toString()
        }
        val d = builder("Choose a login")
            .setView(box)
            .setNegativeButton(android.R.string.cancel) { _, _ -> o.complete(prompt.dismiss()) }
            .setOnDismissListener { o.complete(prompt.dismiss()) }
            .create()
        dialog = d
        gen.useGenerated.setOnClickListener {
            val pw = gen.generatedPassword.text?.toString().orEmpty()
            if (pw.isBlank()) return@setOnClickListener
            val base = generatedOptions.first().value
            o.complete(prompt.confirm(Autocomplete.LoginSelectOption(withPassword(base, pw), generated)))
            d.dismiss()
        }
        d.show()
        return o.result
    }

    /** Length slider (8–32) regenerates; tapping the password regenerates at the same length. */
    private fun wireGenerator(row: RowPasswordGeneratorBinding) {
        fun refresh() {
            val n = row.lengthSlider.value.toInt().coerceIn(8, 32)
            row.lengthLabel.text = n.toString()
            row.generatedPassword.text = PasswordGenerator.generate(n)
        }
        row.lengthSlider.addOnChangeListener { _, value, _ ->
            row.lengthLabel.text = value.toInt().toString()
            row.generatedPassword.text = PasswordGenerator.generate(value.toInt())
        }
        refresh()
        row.generatedPassword.setOnClickListener { refresh() }
    }

    /** Copy of [login] with [password] swapped in (keeps guid / form origin / realm). */
    private fun withPassword(login: Autocomplete.LoginEntry, password: String): Autocomplete.LoginEntry =
        Autocomplete.LoginEntry.Builder()
            .origin(login.origin)
            .username(login.username)
            .password(password)
            .apply {
                login.guid?.let { guid(it) }
                login.formActionOrigin?.let { formActionOrigin(it) }
                login.httpRealm?.let { httpRealm(it) }
            }
            .build()

    override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptResponse> {
        onPopupBlocked()
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
    }

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: PromptDelegate.BeforeUnloadPrompt): GeckoResult<PromptResponse> {
        val o = Once()
        builder("Leave this page?").setMessage("Changes you made may not be saved.")
            .setPositiveButton("Leave") { _, _ -> o.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
            .setNegativeButton("Stay") { _, _ -> o.complete(prompt.confirm(AllowOrDeny.DENY)) }
            .setOnDismissListener { o.complete(prompt.confirm(AllowOrDeny.DENY)) }.show()
        return o.result
    }

    override fun onRepostConfirmPrompt(session: GeckoSession, prompt: PromptDelegate.RepostConfirmPrompt): GeckoResult<PromptResponse> {
        val o = Once()
        builder("Resend data?").setMessage("This page needs to resend the form data you sent before.")
            .setPositiveButton("Resend") { _, _ -> o.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> o.complete(prompt.confirm(AllowOrDeny.DENY)) }
            .setOnDismissListener { o.complete(prompt.confirm(AllowOrDeny.DENY)) }.show()
        return o.result
    }

    private fun isPrivate(session: GeckoSession) = HelperSessions.isPrivate(session) || session.settings.usePrivateMode

    private fun padded(v: android.view.View) = LinearLayout(activity).apply {
        val p = dp(activity, 22)
        setPadding(p, dp(activity, 8), p, 0)
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}
