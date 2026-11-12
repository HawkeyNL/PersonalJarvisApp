package com.hawkeynl.jarvis.chat

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationTest {
    @Test fun `recognition is in Dutch like desktop`() {
        assertEquals("nl-NL", DICTATION_LANGUAGE)
    }

    @Test fun `the level maps rms dB onto 0 to 1 and stays in range`() {
        assertEquals(0f, micLevel(-2f))
        assertEquals(0.5f, micLevel(4f))
        assertEquals(1f, micLevel(10f))
        assertEquals(0f, micLevel(-60f))
        assertEquals(1f, micLevel(90f))
    }

    @Test fun `silence ends quietly and other errors explain themselves`() {
        assertNull(dictationError(SpeechRecognizer.ERROR_NO_MATCH))
        assertNull(dictationError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        // A client error is ours only after our own stop.
        assertNull(dictationError(SpeechRecognizer.ERROR_CLIENT, stopping = true))
        assertEquals("Voice input stopped. Try again.", dictationError(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(MIC_DENIED, dictationError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals("Speech recognition is not reachable. Try again later.", dictationError(SpeechRecognizer.ERROR_NETWORK))
        assertEquals("Dutch speech recognition is not available on this phone.", dictationError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals("Voice input stopped. Try again.", dictationError(SpeechRecognizer.ERROR_AUDIO))
    }

    @Test fun `only installed Dutch counts as on-device`() {
        assertTrue(hasDutch(listOf("en-US", "nl-NL")))
        assertTrue(hasDutch(listOf("nl_nl")))
        assertTrue(hasDutch(listOf("nl")))
        assertFalse(hasDutch(listOf("nl-BE", "de-DE")))
        assertFalse(hasDutch(emptyList()))
    }

    @Test fun `a tap stops a running session, starts a new one, or does nothing behind the lock`() {
        assertEquals(MicTap.Stop, micTap(listening = true, locked = false, authenticated = true, disclosed = false, noticeShown = false))
        assertEquals(MicTap.Ignore, micTap(listening = false, locked = true, authenticated = true, disclosed = true, noticeShown = false))
        assertEquals(MicTap.Ignore, micTap(listening = false, locked = false, authenticated = false, disclosed = true, noticeShown = false))
        // The phone's speech service only after the notice was shown once.
        assertEquals(MicTap.Start(offDevice = false), micTap(listening = false, locked = false, authenticated = true, disclosed = false, noticeShown = false))
        assertEquals(MicTap.Start(offDevice = true), micTap(listening = false, locked = false, authenticated = true, disclosed = false, noticeShown = true))
        assertEquals(MicTap.Start(offDevice = true), micTap(listening = false, locked = false, authenticated = true, disclosed = true, noticeShown = false))
    }

    @Test fun `reports count only for their own sign-in and never behind the lock`() {
        assertTrue(dictationAllowed(asked = 3, identity = 3, locked = false))
        assertFalse(dictationAllowed(asked = 3, identity = 4, locked = false))
        assertFalse(dictationAllowed(asked = 3, identity = 3, locked = true))
    }

    @Test fun `a transcript that was not sent waits in the composer`() {
        assertNull(unsentTranscript(sent = true, text = "hallo"))
        assertEquals("hallo", unsentTranscript(sent = false, text = "hallo"))
        assertNull(unsentTranscript(sent = false, text = "  "))
    }

    @Test fun `an unsent transcript goes after the draft on its own line`() {
        assertEquals("hallo", appendDictated("", "hallo"))
        assertEquals("hallo", appendDictated("  \n", "hallo"))
        assertEquals("eerst\nhallo", appendDictated("eerst  \n", "hallo"))
    }
}
