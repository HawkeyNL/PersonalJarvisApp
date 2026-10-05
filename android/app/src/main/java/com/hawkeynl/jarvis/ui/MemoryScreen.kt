package com.hawkeynl.jarvis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

private val MEMORY_ITEMS = listOf(
    planned("people", "People", "People", JvIcon.PEOPLE, "Who matters and how you know them"),
    planned("projects", "Projects", "Projects", JvIcon.CONTEXT, "Everything about what you build"),
    planned("preferences", "Preferences", "Preferences", JvIcon.HEART, "How you like things done"),
    planned("knowledge", "Knowledge", "Knowledge", JvIcon.BOOK, "Notes, clips and saved insights"),
    planned("routines", "Routines", "Routines", JvIcon.REPEAT, "Your habits and recurring days"),
    planned("ideas", "Ideas", "Ideas", JvIcon.BULB, "Sparks worth coming back to"),
)

/** Memory: Core keeps conversations but has no browsable memory store yet,
 *  so every category says so plainly; no sample data. */
@Composable
fun MemoryScreen(mood: Mood, onNavigate: (Screen) -> Unit) {
    var selected by rememberSaveable { mutableStateOf("people") }
    val current = MEMORY_ITEMS.first { it.id == selected }
    NodePage("MEMORY", "WHAT JARVIS KNOWS ABOUT YOUR WORLD", MEMORY_ITEMS, selected, { selected = it }, onNavigate, mood) {
        Panel(current.icon, current.title, Tone.IDLE, current.status, description = current.description) {
            Unavailable(
                current.title, current.icon,
                detail = "Jarvis keeps your conversations, but your Core does not have a browsable memory store yet. Nothing is shown until it does.",
            )
        }
    }
}
