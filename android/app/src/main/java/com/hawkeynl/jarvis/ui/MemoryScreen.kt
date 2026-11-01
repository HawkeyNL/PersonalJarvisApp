package com.hawkeynl.jarvis.ui

import androidx.compose.runtime.Composable

private val MEMORY_ITEMS = listOf(
    Triple(JvIcon.PEOPLE, "People", "Who matters and how you know them"),
    Triple(JvIcon.CONTEXT, "Projects", "Everything about what you build"),
    Triple(JvIcon.HEART, "Preferences", "How you like things done"),
    Triple(JvIcon.BOOK, "Knowledge", "Notes, clips and saved insights"),
    Triple(JvIcon.REPEAT, "Routines", "Your habits and recurring days"),
    Triple(JvIcon.BULB, "Ideas", "Sparks worth coming back to"),
)

/** Memory (D-Memory): Core keeps conversations but has no browsable memory store yet,
 *  so the page says so plainly and lists what comes later; no sample data. */
@Composable
fun MemoryScreen(onNavigate: (Screen) -> Unit) {
    PushedPage(screenTitle(Screen.MEMORY), "What Jarvis remembers about you", { onNavigate(backOf(Screen.MEMORY)) }) {
        ListSection(null) {
            EmptyState(
                "No memory store yet",
                body = "Jarvis keeps your conversations, but your Core does not have a browsable memory store yet. Nothing is shown until it does.",
                icon = JvIcon.MEMORY,
            )
        }
        LaterSection(MEMORY_ITEMS)
    }
}
