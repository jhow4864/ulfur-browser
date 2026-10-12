package com.jamhowman.beastbrowser.autofill

import android.text.InputType
import android.view.View

/**
 * 2.10 Ulfur #2: finds the username + password fields in another app's screen (AssistStructure), as plain data so
 * the rules are unit-tested. Only visible, enabled, focusable text fields count; invisible ones are never filled.
 */
object AutofillFields {
    data class Node<I>(
        val id: I,
        val hints: List<String>,
        val inputType: Int,
        val idEntry: String?,
        val hint: String?,
        val visible: Boolean,
        val enabled: Boolean,
        val isTextField: Boolean,
    )

    data class Found<I>(val username: I?, val password: I)

    private val userWords = Regex("user|login|email|e-mail|account|phone|identifier", RegexOption.IGNORE_CASE)
    private val passWords = Regex("pass|pwd|pin", RegexOption.IGNORE_CASE)

    fun isPassword(n: Node<*>): Boolean {
        if (n.hints.any { it == View.AUTOFILL_HINT_PASSWORD || it.contains("password", true) }) return true
        val v = n.inputType and InputType.TYPE_MASK_VARIATION
        val cls = n.inputType and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) return true
        if (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true
        return n.hints.isEmpty() && (n.idEntry?.let(passWords::containsMatchIn) == true)
    }

    fun isUsername(n: Node<*>): Boolean {
        if (n.hints.any { it == View.AUTOFILL_HINT_USERNAME || it == View.AUTOFILL_HINT_EMAIL_ADDRESS || it.contains("username", true) }) return true
        val v = n.inputType and InputType.TYPE_MASK_VARIATION
        if ((n.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) return true
        return listOfNotNull(n.idEntry, n.hint).any(userWords::containsMatchIn)
    }

    /** Fields in screen order. Returns null when there's no visible password field. */
    fun <I> find(nodes: List<Node<I>>): Found<I>? {
        val usable = nodes.filter { it.isTextField && it.visible && it.enabled }
        val pwIndex = usable.indexOfFirst { isPassword(it) }
        if (pwIndex < 0) return null
        val user = usable.take(pwIndex).lastOrNull { !isPassword(it) && isUsername(it) }
            ?: usable.take(pwIndex).lastOrNull { !isPassword(it) }
        return Found(user?.id, usable[pwIndex].id)
    }
}
