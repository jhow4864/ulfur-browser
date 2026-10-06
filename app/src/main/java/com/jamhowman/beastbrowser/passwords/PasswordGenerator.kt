package com.jamhowman.beastbrowser.passwords

import java.security.SecureRandom

/**
 * Strong random passwords for the save/select-login prompts (2.3.4).
 * Ambiguous characters (l, I, O, 0, 1) are left out; at least one of each class is guaranteed.
 */
object PasswordGenerator {
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#$%^&*-_=+"
    private const val ALL = LOWER + UPPER + DIGITS + SYMBOLS
    private val rng = SecureRandom()

    fun generate(length: Int = 16): String {
        val n = length.coerceIn(8, 32)
        val chars = ArrayList<Char>(n)
        for (set in arrayOf(LOWER, UPPER, DIGITS, SYMBOLS)) chars += set[rng.nextInt(set.length)]
        while (chars.size < n) chars += ALL[rng.nextInt(ALL.length)]
        chars.shuffle(rng)
        return chars.joinToString("")
    }
}
