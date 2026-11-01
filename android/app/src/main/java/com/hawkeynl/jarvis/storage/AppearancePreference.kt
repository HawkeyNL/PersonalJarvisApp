package com.hawkeynl.jarvis.storage

import android.app.UiModeManager
import android.content.Context
import android.os.Build

/** Light, dark or follow the system; stored on this device only. */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** Anything unknown, from an older or tampered file, falls back to [SYSTEM]. */
        fun parse(raw: String?): ThemeMode = entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

class AppearancePreference(private val context: Context) {
    private val preferences = context.getSharedPreferences("jarvis-appearance", Context.MODE_PRIVATE)

    val mode: ThemeMode get() = ThemeMode.parse(preferences.getString(KEY, null))

    fun save(mode: ThemeMode) {
        preferences.edit().putString(KEY, mode.name).apply()
        applyToSystem(mode)
    }

    /**
     * On Android 12+ the system keeps the app's night mode itself, so even the starting
     * window, drawn before any app code runs, uses the chosen colours. MainActivity
     * re-applies it at start so the system copy cannot drift from the stored choice.
     * Older versions only get the in-app colours; see MainActivity.applySystemBars.
     */
    fun applyToSystem(mode: ThemeMode = this.mode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
            when (mode) {
                ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }

    private companion object {
        const val KEY = "theme"
    }
}
