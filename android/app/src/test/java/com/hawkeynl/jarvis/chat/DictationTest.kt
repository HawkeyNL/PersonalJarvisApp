package com.hawkeynl.jarvis.chat

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertNull(dictationError(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(MIC_DENIED, dictationError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals("Speech recognition is not reachable. Try again later.", dictationError(SpeechRecognizer.ERROR_NETWORK))
        assertEquals("Dutch speech recognition is not available on this phone.", dictationError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals("Voice input stopped. Try again.", dictationError(SpeechRecognizer.ERROR_AUDIO))
    }

    @Test fun `an unsent transcript goes after the draft on its own line`() {
        assertEquals("hallo", appendDictated("", "hallo"))
        assertEquals("hallo", appendDictated("  \n", "hallo"))
        assertEquals("eerst\nhallo", appendDictated("eerst  \n", "hallo"))
    }
}
