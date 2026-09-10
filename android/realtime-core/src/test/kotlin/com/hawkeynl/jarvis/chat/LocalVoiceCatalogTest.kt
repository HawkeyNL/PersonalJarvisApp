package com.hawkeynl.jarvis.chat

import org.junit.Assert.*
import org.junit.Test

class LocalVoiceCatalogTest {
    @Test fun catalogExcludesNetworkMissingAndMalformedVoices() {
        val voices = LocalVoiceCatalog.catalog(sequenceOf(
            VoiceCandidate("local", "Local fixture", false, true),
            VoiceCandidate("cloud", "Network fixture", true, true),
            VoiceCandidate("download", "Not installed", false, false),
            VoiceCandidate("bad\n", "Unsafe", false, true),
            VoiceCandidate("long", "x".repeat(129), false, true),
        ))
        assertEquals(listOf(LocalVoice("local", "Local fixture")), voices)
        assertNull(LocalVoiceCatalog.choose(voices, "cloud", "local"))
        assertNull(LocalVoiceCatalog.choose(voices, "removed", "local"))
        assertEquals("local", LocalVoiceCatalog.choose(voices, "", "cloud"))
    }
    @Test fun selectionAndCatalogRemainBounded() {
        val voices = LocalVoiceCatalog.catalog(generateSequence(0) { it + 1 }.map { VoiceCandidate("v$it", "Voice $it", false, true) })
        assertEquals(128, voices.size)
        assertEquals("v10", LocalVoiceCatalog.choose(voices, "v10", "v1"))
        assertNull(LocalVoiceCatalog.choose(emptyList(), "", null))
    }
}
