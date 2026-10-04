package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction

private const val MAX_ROWS = 100

private val CONVERSATION_DETAILS = mapOf(
    "all" to "Every conversation is stored on your Home Node and follows you across devices.",
    "week" to "Conversations you or Jarvis added to in the last seven days.",
    "channels" to "Core does not record where a conversation took place yet, so threads cannot be split by voice, desktop or mobile.",
    "summaries" to "Core does not write summaries of conversations yet.",
    "actions" to "Core does not extract action items from conversations yet.",
    "memories" to "Core has no browsable memory store yet, so nothing can be linked to a conversation.",
)

/** Conversations: the real thread list from Core (search, open). Channels,
 *  summaries, action items and linked memories need Core support first. */
@Composable
fun ConversationsScreen(state: JarvisUiState, actions: JarvisViewModel, mood: Mood) {
    LaunchedEffect(Unit) { actions.refreshConversations() }
    var selected by rememberSaveable { mutableStateOf("all") }
    var sub by rememberSaveable { mutableStateOf("overview") }
    var query by remember { mutableStateOf("") }
    val now = remember(state.conversations) { System.currentTimeMillis() }
    val conversations = state.conversations
    val stamps = conversations.map { it.updated_at }
    val week = countSince(stamps, 7, now)
    val items = listOf(
        NodeItem("all", "All", "All conversations", JvIcon.CHAT, if (conversations.isEmpty()) Tone.IDLE else Tone.OK, "${conversations.size} total", "Every thread you and Jarvis had"),
        NodeItem("week", "This week", "This week", JvIcon.CALENDAR, if (week == 0) Tone.IDLE else Tone.OK, "$week active", "Threads active in the last 7 days"),
        planned("channels", "Channels", "Channels", JvIcon.PHONE, "Voice, desktop and mobile apart"),
        planned("summaries", "Summaries", "Summaries", JvIcon.DOC, "Short recap after every thread"),
        planned("actions", "Action items", "Action items", JvIcon.TASKS, "Todos pulled out of a thread"),
        planned("memories", "Memories", "Linked memories", JvIcon.MEMORY, "What Jarvis learned from it"),
    )
    val current = items.first { it.id == selected }
    val real = selected == "all" || selected == "week"
    val shown = filterByTitle(conversations, query, if (selected == "week") 7 else null, now, { it.title }, { it.updated_at })
    val rows = shown.map {
        ActivityItem(it.id, JvIcon.CHAT, it.title.ifEmpty { "Untitled" }, time = relativeTime(it.updated_at, now), tone = Tone.OK, toneLabel = "Stored on Core")
    }
    val open: @Composable RowScope.(ActivityItem) -> Unit = { item ->
        GhostButton("Open", { actions.openConversation(item.id) })
    }

    NodePage(
        "CONVERSATIONS", "EVERYTHING YOU AND JARVIS DISCUSSED", items, selected,
        { selected = it; sub = "overview" }, actions::navigate, mood,
    ) {
        Panel(
            current.icon, current.title, current.tone, current.status,
            Modifier.testTag("conversations"),
            description = CONVERSATION_DETAILS[current.id],
            quote = if (real) "Pick up any thread where you left it." else null,
            action = if (real) ({ ActionButton("New conversation", actions::newConversation) }) else null,
        ) {
            if (!real) {
                Unavailable(current.title, current.icon, detail = CONVERSATION_DETAILS[current.id])
                return@Panel
            }
            ChipRow(
                listOf(ChipItem("overview", "Overview", JvIcon.DOC), ChipItem("threads", "Threads", JvIcon.LINES)),
                sub, { sub = it }, small = true,
            )
            if (sub == "overview") {
                TileGrid(
                    "At a glance",
                    listOf(
                        TileData(JvIcon.CHAT, "Conversations", conversations.size.toString()),
                        TileData(JvIcon.CALENDAR, "Active this week", week.toString()),
                        TileData(JvIcon.SUN, "Active today", countSince(stamps, 1, now).toString()),
                        TileData(JvIcon.CLOCK, "Last activity", relativeTime(stamps.maxByOrNull { parseInstant(it) ?: Long.MIN_VALUE }, now).ifEmpty { "—" }),
                        TileData(JvIcon.HOME, "Stored", "On your Home Node"),
                        TileData(JvIcon.SEARCH, "Search", "By title, under Threads"),
                    ),
                )
                ActivityList(
                    "Recent conversations", rows.take(5), "No conversations yet.",
                    action = if (rows.size > 5) ({ GhostButton("View all", { sub = "threads" }) }) else null,
                    rowActions = open,
                )
            } else {
                OutlinedTextField(
                    query, { query = it.take(200) }, Modifier.fillMaxWidth(),
                    placeholder = { Text("Search by title") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                ActivityList(
                    "${shown.size} of ${conversations.size}", rows.take(MAX_ROWS),
                    if (query.isBlank()) "No conversations in this period." else "No conversation matches this search.",
                    rowActions = open,
                )
                if (rows.size > MAX_ROWS) MutedText("Showing the first $MAX_ROWS. Search to narrow the list.")
            }
        }
    }
}
