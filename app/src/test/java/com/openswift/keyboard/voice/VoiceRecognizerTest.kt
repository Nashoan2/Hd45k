package com.openswift.keyboard.voice
 
import org.junit.Assert.assertEquals
import org.junit.Test
 
class VoiceRecognizerTest {
 
    @Test
    fun silenceTimeoutIsSevenSeconds() {
        assertEquals(7_000L, VoiceRecognizer.SILENCE_TIMEOUT_MS)
    }
}
