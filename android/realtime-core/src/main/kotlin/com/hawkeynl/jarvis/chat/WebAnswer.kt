package com.hawkeynl.jarvis.chat

import java.net.URI

// Web-derived answers. Core stores a Research answer with model "web:<model>"
// and ends it with a "Sources:" (or "Bronnen:") list of https URLs.

fun isWebModel(model: String?): Boolean = model?.startsWith("web:") == true

/** An answer without its trailing Sources list, and that list's distinct https URLs. */
data class WebAnswer(val body: String, val sources: List<String>)

private val SOURCES_HEADER = Regex("sources|bronnen", RegexOption.IGNORE_CASE)

/** One https URL with a host and no user info, like Core's Sources filter. */
private fun httpsSource(line: String): String? {
    val candidate = line.trim().trimStart('-', '*').trimStart()
    if (candidate.isEmpty() || candidate.any { it.isWhitespace() }) return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    return candidate.takeIf { uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null }
}

/** Core's rule: the last line that, without `# * _ :` and spaces, reads Sources.
 *  Without one the whole text is the body. */
fun splitSources(text: String): WebAnswer {
    val lines = text.lines()
    val header = lines.indexOfLast { SOURCES_HEADER.matches(it.trim().trim('#', '*', '_', ':', ' ')) }
    if (header < 0) return WebAnswer(text, emptyList())
    return WebAnswer(lines.take(header).joinToString("\n").trimEnd(), lines.drop(header + 1).mapNotNull(::httpsSource).distinct())
}

/** A run that starts right after a finished Jarvis reply, with no new question in
 *  between, is one Core started itself: its automatic web search. */
fun startsAfterReply(lastRole: String?, lastContent: String?): Boolean =
    (lastRole == "assistant" || lastRole == "jarvis") && !lastContent.isNullOrEmpty()
