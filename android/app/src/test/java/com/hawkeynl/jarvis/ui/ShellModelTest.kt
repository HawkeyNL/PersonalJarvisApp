package com.hawkeynl.jarvis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellModelTest {
    @Test
    fun `every screen is a tab root or linked from its own tab root`() {
        val roots = Tab.entries.map { it.root }.toSet()
        for (screen in Screen.entries) {
            val tab = tabOf(screen)
            assertTrue("$screen is unreachable", screen == tab.root || screen in linksOf(tab))
        }
        // Each pushed screen is linked from exactly one tab, the one it belongs to.
        for (tab in Tab.entries) for (linked in linksOf(tab)) assertEquals(tab, tabOf(linked))
        assertEquals(Screen.entries.size, roots.size + Tab.entries.sumOf { linksOf(it).size })
    }

    @Test
    fun `the eleven original screens keep a home`() {
        val expected = mapOf(
            Screen.HUB to Tab.JARVIS, Screen.CHAT to Tab.JARVIS, Screen.CONVERSATIONS to Tab.JARVIS, Screen.MEMORY to Tab.JARVIS,
            Screen.TASKS to Tab.ACTIVITY, Screen.AGENTS to Tab.ACTIVITY, Screen.PAIRING_APPROVALS to Tab.ACTIVITY,
            Screen.SETTINGS to Tab.SETTINGS, Screen.INTEGRATIONS to Tab.SETTINGS, Screen.HEALTH to Tab.SETTINGS, Screen.CONTEXT to Tab.SETTINGS,
        )
        assertEquals(11, expected.size)
        for ((screen, tab) in expected) assertEquals(screen.name, tab, tabOf(screen))
        assertEquals(Tab.TRADING, tabOf(Screen.TRADING))
        assertEquals(Tab.ACTIVITY, tabOf(Screen.ACTIVITY))
    }

    @Test
    fun `deep links land on their tab`() {
        assertEquals(Tab.ACTIVITY, tabOf(parseDeepLink("jarvis://approvals/pairing")!!.screen))
        assertEquals(Tab.SETTINGS, tabOf(parseDeepLink("jarvis://health/usage")!!.screen))
    }

    @Test
    fun `back goes to the tab root, then to Jarvis`() {
        assertEquals(Screen.ACTIVITY, backOf(Screen.PAIRING_APPROVALS))
        assertEquals(Screen.SETTINGS, backOf(Screen.HEALTH))
        assertEquals(Screen.HUB, backOf(Screen.CHAT))
        assertEquals(Screen.HUB, backOf(Screen.TRADING))
        assertEquals(Screen.HUB, backOf(Screen.SETTINGS))
        assertEquals(Screen.HUB, backOf(Screen.HUB))
        // Two backs from anywhere reach Jarvis.
        for (screen in Screen.entries) assertEquals(Screen.HUB, backOf(backOf(screen)))
    }

    @Test
    fun `approve stays off until the content was scrolled to its end`() {
        // Not measured yet: maxValue is Int.MAX_VALUE.
        assertFalse(reviewedToEnd(false, 0, Int.MAX_VALUE))
        assertFalse(reviewedToEnd(false, Int.MAX_VALUE - 1, Int.MAX_VALUE))
        // Long content, part way.
        assertFalse(reviewedToEnd(false, 0, 900))
        assertFalse(reviewedToEnd(false, 899, 900))
        // At the end.
        assertTrue(reviewedToEnd(false, 900, 900))
        // Content that fits is seen in full at once.
        assertTrue(reviewedToEnd(false, 0, 0))
        // Once seen it stays seen, also after scrolling back up.
        assertTrue(reviewedToEnd(true, 0, 900))

        var seen = false
        for (scroll in listOf(0, 300, 600, 300)) {
            seen = reviewedToEnd(seen, scroll, 900)
            assertFalse(canApprove(approvable = true, seen = seen, busy = false, hasService = true))
        }
        seen = reviewedToEnd(seen, 900, 900)
        assertTrue(canApprove(approvable = true, seen = seen, busy = false, hasService = true))
        seen = reviewedToEnd(seen, 0, 900)
        assertTrue(canApprove(approvable = true, seen = seen, busy = false, hasService = true))
    }

    @Test
    fun `approve also needs an approvable action, a service and no running decision`() {
        assertFalse(canApprove(approvable = false, seen = true, busy = false, hasService = true))
        assertFalse(canApprove(approvable = true, seen = true, busy = true, hasService = true))
        assertFalse(canApprove(approvable = true, seen = true, busy = false, hasService = false))
        assertFalse(canApprove(approvable = true, seen = false, busy = false, hasService = true))
    }

    @Test
    fun `greeting follows the hour`() {
        assertEquals("Good morning", greeting(8))
        assertEquals("Good afternoon", greeting(12))
        assertEquals("Good evening", greeting(18))
        assertEquals("Good evening", greeting(2))
    }

    @Test
    fun `every screen has a distinct sentence-case title`() {
        val titles = Screen.entries.map(::screenTitle)
        assertEquals(titles.size, titles.toSet().size)
        for (title in titles) {
            assertTrue(title, title.isNotBlank() && title.first().isUpperCase())
            assertEquals(title, title.first() + title.drop(1).lowercase(), title)
        }
    }
}
