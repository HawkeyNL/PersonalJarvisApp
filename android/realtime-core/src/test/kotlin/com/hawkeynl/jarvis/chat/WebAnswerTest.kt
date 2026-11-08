package com.hawkeynl.jarvis.chat

import org.junit.Assert.*
import org.junit.Test

class WebAnswerTest {
    @Test fun onlyAnswersStoredAsWebModelAreWebAnswers() {
        assertTrue(isWebModel("web:claude-opus-5"))
        for (model in listOf(null, "", "claude-opus-5", "research", "Web:x", "x web:y")) assertFalse(model, isWebModel(model))
    }

    @Test fun trailingSourcesBecomeHttpsLinks() {
        val text = "Rust 1.99 is out.\n\n**Sources:**\n- https://blog.rust-lang.org/a.html\n* https://example.org\nhttps://news.example\n"
        assertEquals(WebAnswer("Rust 1.99 is out.", listOf("https://blog.rust-lang.org/a.html", "https://example.org", "https://news.example")), splitSources(text))
        assertEquals(listOf("https://a.example"), splitSources("A\nSources:\n- https://a.example\n- https://a.example").sources)
        assertEquals(WebAnswer("Het weer.", listOf("https://knmi.nl/a")), splitSources("Het weer.\r\n## Bronnen\r\n- https://knmi.nl/a"))
    }

    @Test fun onlyPlainHttpsUrlsWithAHostSurvive() {
        val bad = listOf("http://plain.example", "javascript:alert(1)", "https://user:pw@example.org", "https://a.example b",
            "[x](https://a.example)", "ftp://a.example", "https://", "see https://a.example")
        assertEquals(listOf("https://ok.example/p?q=1"), splitSources("Answer\nSources:\n${bad.joinToString("\n")}\n- https://ok.example/p?q=1").sources)
    }

    @Test fun sourcesAreNormalizedForDisplayAndOpening() {
        // Core accepts any scheme case; Android intents only match lowercase.
        assertEquals(listOf("https://Example.org/A"), splitSources("A\nSources:\n- HTTPS://Example.org/A").sources)
        // Non-ASCII and bidi controls are shown percent-encoded, never rendered.
        assertEquals(listOf("https://a.example/caf%C3%A9/%E2%80%AEtxt.exe"), splitSources("A\nSources:\nhttps://a.example/café/\u202Etxt.exe").sources)
    }

    @Test fun theLastHeaderCountsAndTextWithoutOneIsUntouched() {
        assertEquals(WebAnswer("No list here.", emptyList()), splitSources("No list here."))
        assertEquals(listOf("https://b.example"), splitSources("Sources:\n- https://a.example\nMore\nSources\n- https://b.example").sources)
        assertTrue(splitSources("Our sources: many\nhttps://a.example").sources.isEmpty())
    }

    @Test fun aRunRightAfterAFinishedReplyIsCoresOwnWebSearch() {
        assertTrue(startsAfterReply("assistant", "Probably dry."))
        // Our own, another device's or a tapped research run follows a question.
        assertFalse(startsAfterReply("user", "Weer morgen?"))
        assertFalse(startsAfterReply("assistant", ""))
        assertFalse(startsAfterReply(null, null))
    }
}
