package com.hawkeynl.jarvis.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechRateTest {
    @Test fun boundsAndInvalidNumbersAreSafe() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(1f, SpeechRate.normalize(invalid), 0f)
        }
        assertEquals(0.5f, SpeechRate.normalize(-100f), 0f)
        assertEquals(2f, SpeechRate.normalize(100f), 0f)
        assertEquals(1.25f, SpeechRate.normalize(1.25f), 0f)
    }
}
