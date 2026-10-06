package com.jamhowman.beastbrowser.data

import androidx.appcompat.app.AppCompatDelegate

object UiTheme {
    fun apply(mode: String = Prefs.uiTheme) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_YES // dark
            }
        )
    }
}
