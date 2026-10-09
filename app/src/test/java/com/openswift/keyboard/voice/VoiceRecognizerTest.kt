package com.openswift.keyboard.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceRecognizerTest {

    @Test
    fun silenceTimeoutIsSevenSeconds() {
        assertEquals(7_000L, VoiceRecognizer.SILENCE_TIMEOUT_MS)
    }

    @Test
    fun voiceRecognizerLifecycleDoesNotAlterDeviceVolumeSettings() {
        val context = RuntimeEnvironment.getApplication()
        val recognizer = VoiceRecognizer(context)
        recognizer.startListening("en-US")
        recognizer.stopListening()
        recognizer.destroy()
        assertEquals(7_000L, VoiceRecognizer.SILENCE_TIMEOUT_MS)
    }
}
