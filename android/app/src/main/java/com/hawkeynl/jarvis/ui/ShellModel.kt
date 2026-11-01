package com.hawkeynl.jarvis.ui

// Pure navigation and review helpers for the tab shell (no Android or Compose imports).

/** The four tabs of the signed-in shell, each with the screen it opens on. */
enum class Tab(val root: Screen) {
    JARVIS(Screen.HUB),
    TRADING(Screen.TRADING),
    ACTIVITY(Screen.ACTIVITY),
    SETTINGS(Screen.SETTINGS),
}

/** The tab a screen belongs to. Exhaustive, so a new screen cannot compile without a tab. */
fun tabOf(screen: Screen): Tab = when (screen) {
    Screen.HUB, Screen.CHAT, Screen.CONVERSATIONS, Screen.MEMORY -> Tab.JARVIS
    Screen.TRADING -> Tab.TRADING
    Screen.ACTIVITY, Screen.PAIRING_APPROVALS, Screen.TASKS, Screen.AGENTS -> Tab.ACTIVITY
    Screen.SETTINGS, Screen.INTEGRATIONS, Screen.HEALTH, Screen.CONTEXT -> Tab.SETTINGS
}

/** Pushed screens each tab root links to; the root pages render exactly these links. */
fun linksOf(tab: Tab): List<Screen> = when (tab) {
    // CHAT opens from the "Ask Jarvis" bar on the Jarvis tab.
    Tab.JARVIS -> listOf(Screen.CHAT, Screen.CONVERSATIONS, Screen.MEMORY)
    Tab.TRADING -> emptyList()
    Tab.ACTIVITY -> listOf(Screen.PAIRING_APPROVALS, Screen.TASKS, Screen.AGENTS)
    Tab.SETTINGS -> listOf(Screen.INTEGRATIONS, Screen.HEALTH, Screen.CONTEXT)
}

/** Sentence-case name of a screen: the page title, and the fallback row for a link a
 *  tab root has no row for. */
fun screenTitle(screen: Screen): String = when (screen) {
    Screen.HUB -> "Jarvis"
    Screen.TRADING -> "Trading"
    Screen.ACTIVITY -> "Activity"
    Screen.SETTINGS -> "Settings"
    Screen.CHAT -> "Chat"
    Screen.CONVERSATIONS -> "Conversations"
    Screen.MEMORY -> "Memory"
    Screen.PAIRING_APPROVALS -> "Pairing requests"
    Screen.TASKS -> "Tasks"
    Screen.AGENTS -> "Agents"
    Screen.INTEGRATIONS -> "Integrations"
    Screen.HEALTH -> "System health"
    Screen.CONTEXT -> "Context and devices"
}

/** Back from a pushed screen goes to its tab's root, from a tab root to Jarvis. */
fun backOf(screen: Screen): Screen {
    val root = tabOf(screen).root
    return if (screen == root) Screen.HUB else root
}

/**
 * The scroll-to-end gate in front of Approve: true once the reviewer has scrolled the
 * whole action into view, and it stays true for that action. [scroll] and [maxScroll]
 * are the content's ScrollState value and maxValue; maxValue is Int.MAX_VALUE until the
 * content is measured, which never counts as seen. Content that fits has maxValue 0.
 */
fun reviewedToEnd(seen: Boolean, scroll: Int, maxScroll: Int): Boolean =
    seen || (maxScroll != Int.MAX_VALUE && scroll >= maxScroll)

/** Approve is offered only for an action this phone can show in full, after the gate,
 *  with a service to sign with and no decision running. */
fun canApprove(approvable: Boolean, seen: Boolean, busy: Boolean, hasService: Boolean): Boolean =
    approvable && seen && !busy && hasService
