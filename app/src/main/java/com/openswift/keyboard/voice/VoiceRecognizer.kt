package com.openswift.keyboard.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Ultra-fast, low-latency, battery-efficient voice input manager:
 * - Immediate parallel activation and on-device / local-first speech recognition prioritization.
 * - Real-time streaming transcription with instant partial results for blazing-fast voice typing.
 * - Instantaneous UI feedback and minimal silence wait (1.5s after speech finishes vs 5s lag)
 *   so completed phrases commit quickly and seamlessly.
 * - Plays ONLY a single, quick audio cue upon user activation (mic click) in a non-blocking background thread.
 * - Zero stop or completion tones; all system earcons (end sounds, completion chimes, error beeps) suppressed.
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
    private val audioExecutor = Executors.newSingleThreadExecutor()

    private val silenceTimeoutRunnable = Runnable {
        if (isListening) {
            stopListening()
        }
    }

    private fun resetSilenceTimer(customTimeoutMs: Long = SILENCE_TIMEOUT_MS) {
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        if (isListening) {
            mainHandler.postDelayed(silenceTimeoutRunnable, customTimeoutMs)
        }
    }

    private fun playStartCue() {
        audioExecutor.execute {
            try {
                val toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 65)
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                try {
                    Thread.sleep(100)
                } catch (_: Exception) {}
                try {
                    toneGenerator.release()
                } catch (_: Exception) {}
            } catch (_: Exception) {}
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
        // Kept no-op to conserve battery.
    }

    private fun initRecognizer(): Boolean {
        if (recognizer == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                return false
            }

            // 1. Prioritize low-latency on-device recognition if available (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                    }
                } catch (_: Exception) {
                    recognizer = null
                }
            }

            // 2. Fall back to standard recognizer if on-device is not available or threw an error
            if (recognizer == null) {
                try {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
                } catch (_: Exception) {
                    try {
                        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx.applicationContext)
                    } catch (_: Exception) {
                        return false
                    }
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

                override fun onEndOfSpeech() {
                    // Speech stopped: accelerate wrap-up with a responsive 1.5s silence timeout
                    // instead of waiting many seconds if system finalization is slow.
                    resetSilenceTimer(SPEECH_COMPLETION_TIMEOUT_MS)
                }

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

                    // Flush any pending partial text before cleanup so user speech is not lost
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
            // Prefer offline on-device processing where possible for instant low-latency transcription
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Low-latency silence thresholds for prompt, snappy voice typing
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
    }

    fun startListening(languageCode: String? = null) {
        currentLanguage = languageCode ?: Locale.getDefault().language
        isListening = true
        lastPartialDispatched = ""

        // Play the single start cue requested by the user exclusively on activation in parallel
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
        audioExecutor.shutdownNow()
        onStateChanged?.invoke(false)
    }

    companion object {
        const val SILENCE_TIMEOUT_MS = 7_000L
        const val SPEECH_COMPLETION_TIMEOUT_MS = 1_500L
    }
}
