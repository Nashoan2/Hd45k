package com.openswift.keyboard.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognizerTest {

    @Test
    fun silenceTimeoutIsFiveSeconds() {
        assertEquals(5_000L, VoiceRecognizer.SILENCE_TIMEOUT_MS)
    }

    @Test
    fun stopPhrasesAreAccuratelyDetected() {
        assertTrue(VoiceRecognizer.containsStopCommand("قف عندك"))
        assertTrue(VoiceRecognizer.containsStopCommand("قف عندك."))
        assertTrue(VoiceRecognizer.containsStopCommand("قِف عِندك"))
        assertTrue(VoiceRecognizer.containsStopCommand("أرسل هذا النص قف عندك"))
        assertTrue(VoiceRecognizer.containsStopCommand("توقف"))
        assertTrue(VoiceRecognizer.containsStopCommand("توقف عندك"))
        assertTrue(VoiceRecognizer.containsStopCommand("قف هنا"))

        assertFalse(VoiceRecognizer.containsStopCommand("كتابة بالصوت سريعة"))
        assertFalse(VoiceRecognizer.containsStopCommand("السلام عليكم"))
        assertFalse(VoiceRecognizer.containsStopCommand("مرحبا بكم"))
    }

    @Test
    fun stopPhrasesAreCleanlyStrippedFromCommittedText() {
        assertEquals("", VoiceRecognizer.stripStopCommand("قف عندك"))
        assertEquals("", VoiceRecognizer.stripStopCommand("قف عندك."))
        assertEquals("", VoiceRecognizer.stripStopCommand("توقف"))
        assertEquals("أرسل هذا النص", VoiceRecognizer.stripStopCommand("أرسل هذا النص قف عندك"))
        assertEquals("سأذهب إلى المنزل", VoiceRecognizer.stripStopCommand("سأذهب إلى المنزل قف عندك."))
        assertEquals("كتابة بالصوت", VoiceRecognizer.stripStopCommand("كتابة بالصوت"))
    }
}
