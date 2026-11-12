package com.hawkeynl.jarvis.chat

import org.junit.Assert.*
import org.junit.Test

class WebAnswerTest {
    private fun urls(text: String) = splitSources(text).sources.map { it.url }

    @Test fun onlyAnswersStoredAsWebModelAreWebAnswers() {
        assertTrue(isWebModel("web:claude-opus-5"))
        for (model in listOf(null, "", "claude-opus-5", "research", "Web:x", "x web:y")) assertFalse(model, isWebModel(model))
    }

    @Test fun trailingSourcesBecomeHttpsLinks() {
        val text = "Rust 1.99 is out.\n\n**Sources:**\n- https://blog.rust-lang.org/a.html\n* https://example.org\nhttps://news.example\n"
        assertEquals(WebAnswer("Rust 1.99 is out.", listOf(Source("https://blog.rust-lang.org/a.html"), Source("https://example.org"), Source("https://news.example"))), splitSources(text))
        assertEquals(listOf("https://a.example"), urls("A\nSources:\n- https://a.example\n- https://a.example"))
        assertEquals(WebAnswer("Het weer.", listOf(Source("https://knmi.nl/a"))), splitSources("Het weer.\r\n## Bronnen\r\n- https://knmi.nl/a"))
    }

    @Test fun onlyPlainHttpsUrlsWithAHostSurvive() {
        val bad = listOf("http://plain.example", "javascript:alert(1)", "https://user:pw@example.org", "https://a.example b",
            "[x](http://a.example)", "ftp://a.example", "https://", "see https://a.example")
        assertEquals(listOf("https://ok.example/p?q=1"), urls("Answer\nSources:\n${bad.joinToString("\n")}\n- https://ok.example/p?q=1"))
    }

    @Test fun sourcesAreNormalizedForDisplayAndOpening() {
        // Core accepts any scheme case; Android intents only match lowercase.
        assertEquals(listOf("https://Example.org/A"), urls("A\nSources:\n- HTTPS://Example.org/A"))
        // Non-ASCII and bidi controls are shown percent-encoded, never rendered.
        assertEquals(listOf("https://a.example/caf%C3%A9/%E2%80%AEtxt.exe"), urls("A\nSources:\nhttps://a.example/café/\u202Etxt.exe"))
    }

    @Test fun theLastHeaderCountsAndTextWithoutOneIsUntouched() {
        assertEquals(WebAnswer("No list here.", emptyList()), splitSources("No list here."))
        assertEquals(listOf("https://b.example"), urls("Sources:\n- https://a.example\nMore\nSources\n- https://b.example"))
        assertTrue(splitSources("Our sources: many\nhttps://a.example").sources.isEmpty())
    }

    @Test fun titledSourcesKeepAPlainTitleUnderCoresUrlRule() {
        // Core's own fixture (chat.rs https_sources_only test).
        val lines = listOf(
            "- [Rust 1.97 released](https://blog.rust-lang.org/2026/rust-1.97)",
            "[Foo (bar) - Wiki](https://en.example.org/wiki/Foo)",
            "* [A](https://en.example.org/wiki/Foo_(bar))",
            "- [Plain title](http://plain.example/)",
            "- [Who](https://user:pw@evil.example/)",
            "- [](https://empty.example/)",
            "- [Spaced](https://example.org/a b)",
            "- [Click \u202Eevil](https://example.org/)",
            "- [Two](https://example.org/a) [Three](https://example.org/b)",
            "- [Nested [x]](https://example.org/)",
            "- [Tab\there](https://example.org/)",
            "- [Zero\u200Bwidth](https://example.org/)",
            "- [Line\u2028sep](https://example.org/)",
            "- [Soft\u00ADhyphen](https://example.org/)",
            "- [Unclosed](https://example.org/",
            "- [<b>hi</b> *x*](https://x.example/)",
            "- https://bare.example/p",
        )
        assertEquals(
            WebAnswer("Answer", listOf(
                Source("https://blog.rust-lang.org/2026/rust-1.97", "Rust 1.97 released"),
                Source("https://en.example.org/wiki/Foo_(bar)", "A"),
                Source("https://x.example/", "<b>hi</b> *x*"),
                Source("https://bare.example/p"),
            )),
            splitSources("Answer\nSources:\n" + lines.joinToString("\n")),
        )
        assertTrue(splitSources("A\nSources:\n- [${"t".repeat(121)}](https://example.org/)").sources.isEmpty())
        assertEquals("é".repeat(120), splitSources("A\nSources:\n- [${"é".repeat(120)}](https://example.org/)").sources.single().title)
    }

    @Test fun aCardShowsTheHostWithoutWwwAndAShortPath() {
        assertEquals("example.org", Source("https://www.example.org/").host)
        assertEquals("", Source("https://www.example.org/").shortPath)
        assertEquals("/2026/rust", Source("https://blog.example/2026/rust?x=1").shortPath)
        assertEquals("www2.example", Source("https://www2.example/x").host)
        val long = Source("https://a.example/" + "p".repeat(60)).shortPath
        assertEquals(40, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test fun aRunRightAfterAFinishedReplyIsCoresOwnWebSearch() {
        assertTrue(startsAfterReply("assistant", "Probably dry."))
        // Our own, another device's or a tapped research run follows a question.
        assertFalse(startsAfterReply("user", "Weer morgen?"))
        assertFalse(startsAfterReply("assistant", ""))
        assertFalse(startsAfterReply(null, null))
    }
}
