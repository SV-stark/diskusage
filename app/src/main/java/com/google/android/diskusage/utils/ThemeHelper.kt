package com.google.android.diskusage.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeHelper {
    const val PREF_THEME = "pref_theme"
    const val THEME_SYSTEM = "System Default"
    const val THEME_LIGHT = "Light"
    const val THEME_DARK = "Dark"
    const val THEME_AMOLED = "AMOLED"

    fun applyTheme(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val theme = prefs.getString(PREF_THEME, THEME_SYSTEM)

        when (theme) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK, THEME_AMOLED -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun isAmoledTheme(context: Context): Boolean {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getString(PREF_THEME, THEME_SYSTEM) == THEME_AMOLED
    }
}
