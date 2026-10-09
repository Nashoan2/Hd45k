package com.openswift.keyboard.voice

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.*

/**
 * High-speed, responsive voice input manager:
 * - Real-time streaming transcription with partial results.
 * - Auto-stops after 7 seconds if no speech is detected.
 * - Does NOT touch or modify phone audio/volume settings in any way.
 * - Suppresses all error toasts and intrusive dialogs.
 * - Works reliably across all phones and Android versions using system SpeechRecognizer.
 */
class VoiceRecognizer(private val ctx: Context) {

    var onPartialResult: ((String) -> Unit)? = null
    var onResult: ((String) -> Unit)? = null
    var onStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isContinuous = false
    private var currentLanguage: String = Locale.getDefault().language
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastPartialDispatched = ""

    private val silenceTimeoutRunnable = Runnable {
        // Automatically stop after 7 seconds if no speech is detected
        if (isListening || isContinuous) {
            stopListening()
        }
    }

    private fun resetSilenceTimer() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        if (isListening || isContinuous) {
            mainHandler.postDelayed(silenceTimeoutRunnable, SILENCE_TIMEOUT_MS)
        }
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
        mainHandler.post { initRecognizer() }
    }

    private fun initRecognizer(): Boolean {
        if (recognizer == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                return false
            }
            try {
                // Use the standard system SpeechRecognizer which works reliably across all devices
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
                    if (rmsdB > 2.0f) {
                        resetSilenceTimer()
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {}
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

                    // Session already completed naturally; update state without calling cancel()
                    // so we don't trigger a redundant close tone after completion.
                    isListening = false
                    isContinuous = false
                    mainHandler.removeCallbacks(silenceTimeoutRunnable)
                    onStateChanged?.invoke(false)
                }

                override fun onError(error: Int) {
                    if (!isListening && !isContinuous) return

                    // Flush any partial text that was already transcribed before the pause/timeout
                    if (lastPartialDispatched.isNotBlank()) {
                        val pending = lastPartialDispatched
                        lastPartialDispatched = ""
                        onResult?.invoke(pending)
                    }

                    isListening = false
                    isContinuous = false
                    mainHandler.removeCallbacks(silenceTimeoutRunnable)
                    onStateChanged?.invoke(false)
                    onError?.invoke("")
                }

                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }
        return recognizer != null
    }

    private fun recreateRecognizer() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
        initRecognizer()
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
            // Keep session open across natural pauses while live partial results write words immediately
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L)
        }
    }

    fun startListening(languageCode: String? = null, continuous: Boolean = false) {
        currentLanguage = languageCode ?: Locale.getDefault().language
        isContinuous = continuous
        isListening = true
        lastPartialDispatched = ""

        if (!initRecognizer()) {
            isListening = false
            isContinuous = false
            onStateChanged?.invoke(false)
            return
        }

        try {
            recognizer?.startListening(buildIntent())
            resetSilenceTimer()
            onStateChanged?.invoke(true)
        } catch (_: Exception) {
            recreateRecognizer()
            try {
                recognizer?.startListening(buildIntent())
                resetSilenceTimer()
                onStateChanged?.invoke(true)
            } catch (_: Exception) {
                isListening = false
                isContinuous = false
                mainHandler.removeCallbacks(silenceTimeoutRunnable)
                onStateChanged?.invoke(false)
            }
        }
    }

    fun stopListening() {
        val wasListening = isListening
        isContinuous = false
        isListening = false
        if (lastPartialDispatched.isNotBlank()) {
            val pending = lastPartialDispatched
            lastPartialDispatched = ""
            onResult?.invoke(pending)
        }
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        if (wasListening) {
            try {
                recognizer?.cancel()
            } catch (_: Exception) {}
        }
        onStateChanged?.invoke(false)
    }

    fun destroy() {
        isContinuous = false
        isListening = false
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        mainHandler.removeCallbacksAndMessages(null)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {}
        try {
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
        onStateChanged?.invoke(false)
    }

    companion object {
        const val SILENCE_TIMEOUT_MS = 7_000L
    }
}
