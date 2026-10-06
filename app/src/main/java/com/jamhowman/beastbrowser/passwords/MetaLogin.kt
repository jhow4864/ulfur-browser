package com.jamhowman.beastbrowser.passwords

/** Public meta about a saved login (no password). Safe to read while the vault is locked. */
data class MetaLogin(
    val guid: String,
    val origin: String,
    val username: String,
)
