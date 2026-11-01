package com.hawkeynl.jarvis.ui

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

private val LATER = listOf(
    Triple(JvIcon.PHONE, "Channels", "Voice, desktop and mobile apart"),
    Triple(JvIcon.DOC, "Summaries", "Short recap after every thread"),
    Triple(JvIcon.TASKS, "Action items", "Todos pulled out of a thread"),
    Triple(JvIcon.MEMORY, "Linked memories", "What Jarvis learned from it"),
)

/** Conversations (D-Home list): the real thread list from Core, filtered by period and
 *  title; a row opens the thread. Channels, summaries, action items and linked memories
 *  need Core support first. */
@Composable
fun ConversationsScreen(state: JarvisUiState, actions: JarvisViewModel) {
    LaunchedEffect(Unit) { actions.refreshConversations() }
    var view by rememberSaveable { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    val now = remember(state.conversations) { System.currentTimeMillis() }
    val conversations = state.conversations
    val week = countSince(conversations.map { it.updated_at }, 7, now)
    val shown = filterByTitle(conversations, query, if (view == "week") 7 else null, now, { it.title }, { it.updated_at })
    val rows = shown.take(MAX_ROWS).map {
        ActivityItem(it.id, JvIcon.CHAT, it.title.ifEmpty { "Untitled" }, time = relativeTime(it.updated_at, now), tone = Tone.OK, toneLabel = "")
    }

    PushedPage(
        screenTitle(Screen.CONVERSATIONS),
        "${conversations.size} on your Home Node · $week active this week",
        { actions.navigate(backOf(Screen.CONVERSATIONS)) },
        Modifier.testTag("conversations"),
    ) {
        ActionButton("New conversation", actions::newConversation)
        when {
            conversations.isEmpty() -> ListSection(null) {
                EmptyState(
                    "No conversations yet",
                    body = "Start one with New conversation. Every conversation is stored on your Home Node and follows you across devices.",
                    icon = JvIcon.CHAT,
                )
            }
            else -> {
                Segmented(listOf("all" to "All", "week" to "This week"), view, { view = it })
                OutlinedTextField(
                    query, { query = it.take(200) }, Modifier.fillMaxWidth(),
                    placeholder = { Text("Search by title") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                if (shown.isEmpty() && query.isNotBlank()) {
                    ListSection(null) {
                        EmptyState(
                            "Nothing found for “${query.trim()}”",
                            body = "Search looks at conversation titles.",
                            action = "Clear search" to { query = "" },
                        )
                    }
                } else {
                    ActivityList(
                        "${shown.size} of ${conversations.size}", rows, "No conversations in this period.",
                        onRowClick = { actions.openConversation(it.id) }, rowClickLabel = "Open conversation",
                    )
                    if (shown.size > MAX_ROWS) MutedText("Showing the first $MAX_ROWS. Search to narrow the list.")
                }
            }
        }
        LaterSection(LATER, "Core does not record channels, write summaries, extract action items or keep a memory store yet.")
    }
}
