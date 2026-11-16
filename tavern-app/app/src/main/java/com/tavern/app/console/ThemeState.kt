package com.tavern.app.console

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemeState {
    private const val PREFS_NAME = "tavern_console_prefs"
    // Internal: a backup archive carries the theme as part of the app settings, so
    // the key name is shared with `SettingsState` rather than repeated.
    internal const val KEY_DARK_MODE = "dark_mode"

    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun init(context: Context) {
        _isDarkMode.value = isDark(context)
    }

    /**
     * The mode the app is in: the stored choice when the user has made one, light on a fresh
     * install. Following the platform put an untouched install on a black page on phones whose
     * owner runs everything dark, before the app had shown what it looks like.
     *
     * The View layer asks here too. A platform dialog is built while no
     * composition runs, and reading the setting is what keeps it in the mode the
     * console itself is drawn in.
     */
    fun isDark(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_DARK_MODE)) return prefs.getBoolean(KEY_DARK_MODE, false)
        return false
    }

    /** The platform's own night flag, which is the only "follow system" state there is. */
    internal fun isNightMode(uiMode: Int): Boolean =
        (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun toggle(context: Context) {
        val newValue = !_isDarkMode.value
        _isDarkMode.value = newValue
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK_MODE, newValue).apply()
    }
}
