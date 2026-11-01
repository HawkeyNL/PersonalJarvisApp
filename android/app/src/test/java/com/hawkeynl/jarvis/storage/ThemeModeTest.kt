package com.hawkeynl.jarvis.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test
    fun storedNamesRoundTripAndAnythingElseFollowsTheSystem() {
        for (mode in ThemeMode.entries) assertEquals(mode, ThemeMode.parse(mode.name))
        for (raw in listOf(null, "", "dark", "NIGHT", " LIGHT")) assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(raw))
    }

    @Test
    fun overrideWinsOverTheSystemTheme() {
        for (systemDark in listOf(false, true)) {
            assertEquals(systemDark, ThemeMode.SYSTEM.isDark(systemDark))
            assertEquals(false, ThemeMode.LIGHT.isDark(systemDark))
            assertEquals(true, ThemeMode.DARK.isDark(systemDark))
        }
    }
}
