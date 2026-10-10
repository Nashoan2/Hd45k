package com.openswift.keyboard.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.*

/**
 * High-speed, responsive voice input manager:
 * - Real-time streaming transcription with live partial results for instant typing.
 * - Single continuous session with generous silence timeouts so it waits for the speaker.
 * - Plays ONLY a single audio tone upon user activation (mic click), zero stop or completion tones.
 * - Suppresses all system earcons (end sounds, completion chimes, error beeps).
 * - Immediately frees microphone and audio focus on finish or cancellation, ensuring
 *   the device volume is never muted and battery is conserved when not listening.
 * - Strictly does NOT alter or touch device volume or mute stream settings.
 */
class VoiceRecognizer(private val ctx: Context) {

    var onPartialResult: ((String) -> Unit)? = null
    var onResult: ((String) -> Unit)? = null
    var onStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var currentLanguage: String = Locale.getDefault().language
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastPartialDispatched = ""

    private val silenceTimeoutRunnable = Runnable {
        if (isListening) {
            stopListening()
        }
    }

    private fun resetSilenceTimer() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        if (isListening) {
            mainHandler.postDelayed(silenceTimeoutRunnable, SILENCE_TIMEOUT_MS)
        }
    }

    private fun playStartCue() {
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 65)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            mainHandler.postDelayed({
                try {
                    toneGenerator.release()
                } catch (_: Exception) {}
            }, 250)
        } catch (_: Exception) {}
    }

    private fun extractLivePartialText(results: android.os.Bundle?): String {
        if (results == null) return ""
        val stable = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        val unstable = results.getStringArrayList("android.speech.extra.UNSTABLE_TEXT")
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        return when {
            stable.isNotEmpty() && unstable.isNotEmpty() -> "$stable $unstable"
            stable.isNotEmpty() -> stable
            else -> unstable
        }
    }

    fun prewarm() {
        // No-op: Do not bind background speech services prematurely to avoid battery drain.
    }

    private fun initRecognizer(): Boolean {
        if (recognizer == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                return false
            }
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
            } catch (_: Exception) {
                try {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(ctx.applicationContext)
                } catch (_: Exception) {
                    return false
                }
            }

            recognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    resetSilenceTimer()
                }

                override fun onBeginningOfSpeech() {
                    resetSilenceTimer()
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (rmsdB > 1.2f) {
                        resetSilenceTimer()
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    resetSilenceTimer()
                }

                override fun onEndOfSpeech() {}

                override fun onPartialResults(results: android.os.Bundle?) {
                    val liveText = extractLivePartialText(results)
                    if (liveText.isNotEmpty() && liveText != lastPartialDispatched) {
                        lastPartialDispatched = liveText
                        resetSilenceTimer()
                        onPartialResult?.invoke(liveText)
                    }
                }

                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: lastPartialDispatched.takeIf { it.isNotBlank() }
                    lastPartialDispatched = ""
                    if (!text.isNullOrEmpty()) {
                        onResult?.invoke(text)
                    }

                    // Natural completion: Clean up recognizer immediately to release audio focus
                    // and stop microphone/battery consumption. No completion sound played.
                    isListening = false
                    cleanupRecognizer()
                    onStateChanged?.invoke(false)
                }

                override fun onError(error: Int) {
                    if (!isListening) return

                    // Flush any pending partial text before cleanup
                    if (lastPartialDispatched.isNotBlank()) {
                        val pending = lastPartialDispatched
                        lastPartialDispatched = ""
                        onResult?.invoke(pending)
                    }

                    isListening = false
                    cleanupRecognizer()
                    onStateChanged?.invoke(false)
                    onError?.invoke("")
                }

                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }
        return recognizer != null
    }

    private fun cleanupRecognizer() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        val r = recognizer
        recognizer = null
        if (r != null) {
            try {
                r.cancel()
            } catch (_: Exception) {}
            try {
                r.destroy()
            } catch (_: Exception) {}
        }
    }

    private fun buildIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra("android.speech.extra.DICTATION_MODE", true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // Suppress system earcons so no completion, end, or error tones are emitted by the engine
            putExtra("android.speech.extra.SUPPRESS_EARCONS", true)
            putExtra("suppress_earcons", true)
            // Generous silence thresholds: allows natural thinking/pausing without premature cutoff
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L)
        }
    }

    fun startListening(languageCode: String? = null) {
        currentLanguage = languageCode ?: Locale.getDefault().language
        isListening = true
        lastPartialDispatched = ""

        // Play the single start cue requested by the user exclusively on activation
        playStartCue()

        cleanupRecognizer()
        if (!initRecognizer()) {
            isListening = false
            onStateChanged?.invoke(false)
            return
        }

        try {
            recognizer?.startListening(buildIntent())
            resetSilenceTimer()
            onStateChanged?.invoke(true)
        } catch (_: Exception) {
            cleanupRecognizer()
            if (initRecognizer()) {
                try {
                    recognizer?.startListening(buildIntent())
                    resetSilenceTimer()
                    onStateChanged?.invoke(true)
                    return
                } catch (_: Exception) {}
            }
            isListening = false
            cleanupRecognizer()
            onStateChanged?.invoke(false)
        }
    }

    fun stopListening() {
        isListening = false
        if (lastPartialDispatched.isNotBlank()) {
            val pending = lastPartialDispatched
            lastPartialDispatched = ""
            onResult?.invoke(pending)
        }
        cleanupRecognizer()
        onStateChanged?.invoke(false)
    }

    fun destroy() {
        isListening = false
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        mainHandler.removeCallbacksAndMessages(null)
        cleanupRecognizer()
        onStateChanged?.invoke(false)
    }

    companion object {
        const val SILENCE_TIMEOUT_MS = 7_000L
    }
}
