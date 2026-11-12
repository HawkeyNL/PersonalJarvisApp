package com.hawkeynl.jarvis.chat

import java.net.URI

// Web-derived answers. Core stores a Research answer with model "web:<model>"
// and ends it with a "Sources:" (or "Bronnen:") list of https URLs.

fun isWebModel(model: String?): Boolean = model?.startsWith("web:") == true

/** An answer without its trailing Sources list, and that list's distinct https sources. */
data class WebAnswer(val body: String, val sources: List<Source>)

/** One Sources entry: a normalized https URL and the plain-text title Core's research gave it. */
data class Source(val url: String, val title: String? = null) {
    /** The host without "www.", for a link card. */
    val host: String get() = URI(url).host.orEmpty().removePrefix("www.")

    /** The path, shortened to 40 characters; empty for "/". */
    val shortPath: String get() {
        val path = URI(url).rawPath.orEmpty().takeUnless { it == "/" }.orEmpty()
        return if (path.length > 40) path.take(39) + "…" else path
    }
}

private val SOURCES_HEADER = Regex("sources|bronnen", RegexOption.IGNORE_CASE)

/** Bidi controls, zero-width, fillers, variation selectors, line/paragraph separators,
 *  tags and other invisible characters: exactly Core's validation::no_hidden_chars list
 *  (control characters are checked apart). A title could hide or reorder text with them. */
private val INVISIBLE_FORMAT = Regex(
    "[\u00AD\u034F\u061C\u115F\u1160\u180E\u200B-\u200F\u2028-\u202E\u2060-\u2069\u3164\uFE00-\uFE0F\uFEFF\uFFF9-\uFFFB\\x{E0000}-\\x{E007F}]",
)

/** A whole `[Title](url)` line, like Core: a plain title of 1 to 120 characters
 *  without brackets, parentheses, control or invisible format characters. The
 *  title is only ever shown as plain text. */
private fun titledLink(candidate: String): Pair<String, String>? {
    if (!candidate.startsWith("[") || !candidate.endsWith(")")) return null
    val end = candidate.indexOf("](")
    if (end < 0) return null
    val title = candidate.substring(1, end)
    val plain = title.codePointCount(0, title.length) in 1..120 &&
        title.none { it in "[]()" || Character.getType(it) == Character.CONTROL.toInt() } &&
        !INVISIBLE_FORMAT.containsMatchIn(title)
    return if (plain) title to candidate.substring(end + 2, candidate.length - 1) else null
}

/** One https URL with a host and no user info, bare or titled, like Core's Sources filter.
 *  Returned normalized: lowercase scheme (Android intent matching is case-sensitive)
 *  and ASCII only, so non-ASCII or bidi characters are shown percent-encoded. */
private fun httpsSource(line: String): Source? {
    val candidate = line.trim().trimStart('-', '*').trimStart()
    val titled = titledLink(candidate)
    val text = titled?.second ?: candidate
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
    return Source("https" + uri.toASCIIString().substring(uri.scheme.length), titled?.first)
}

/** Core's rule: the last line that, without `# * _ :` and spaces, reads Sources.
 *  Without one the whole text is the body. */
fun splitSources(text: String): WebAnswer {
    val lines = text.lines()
    val header = lines.indexOfLast { SOURCES_HEADER.matches(it.trim().trim('#', '*', '_', ':', ' ')) }
    if (header < 0) return WebAnswer(text, emptyList())
    return WebAnswer(lines.take(header).joinToString("\n").trimEnd(), lines.drop(header + 1).mapNotNull(::httpsSource).distinctBy { it.url })
}

/** A run that starts right after a finished Jarvis reply, with no new question in
 *  between, is one Core started itself: its automatic web search. */
fun startsAfterReply(lastRole: String?, lastContent: String?): Boolean =
    (lastRole == "assistant" || lastRole == "jarvis") && !lastContent.isNullOrEmpty()
