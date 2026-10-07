package com.openswift.keyboard.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.*

/**
 * High-speed, responsive, and completely silent voice input manager:
 * - Real-time streaming transcription with partial results.
 * - Auto-stops after 15 seconds if no speech is detected.
 * - Mutes start and stop beeps during recognition so input is completely quiet.
 * - Suppresses all error toasts and intrusive dialogs.
 * - Restarts recognition seamlessly across pauses in continuous mode.
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
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var isSystemMuted = false
    private var unmuteRunnable: Runnable? = null

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

    /**
     * Momentarily mutes STREAM_SYSTEM for 350ms to silence start/stop earcons without
     * touching device media (STREAM_MUSIC), ringtone, alarms, or device volume levels.
     */
    private fun transientlySilenceBeep() {
        val am = audioManager ?: return
        try {
            unmuteRunnable?.let { mainHandler.removeCallbacks(it) }
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
            isSystemMuted = true

            val runnable = Runnable {
                try {
                    if (isSystemMuted) {
                        am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                        isSystemMuted = false
                    }
                } catch (_: Exception) {}
                unmuteRunnable = null
            }
            unmuteRunnable = runnable
            mainHandler.postDelayed(runnable, 350L)
        } catch (_: Exception) {}
    }

    private fun cancelMute() {
        val am = audioManager ?: return
        unmuteRunnable?.let { mainHandler.removeCallbacks(it) }
        unmuteRunnable = null
        try {
            if (isSystemMuted) {
                am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                isSystemMuted = false
            }
        } catch (_: Exception) {}
    }

    fun prewarm() {
        initRecognizer()
    }

    private fun initRecognizer(): Boolean {
        if (recognizer == null) {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(ctx.applicationContext)
            } catch (_: Exception) {
                try {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
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
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrEmpty()) {
                        resetSilenceTimer()
                        onPartialResult?.invoke(text)
                    }
                }

                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrEmpty()) {
                        resetSilenceTimer()
                        onResult?.invoke(text)
                    }

                    // Keep listening continuously across pauses
                    if (isContinuous) {
                        mainHandler.postDelayed({
                            if (isContinuous && isListening) {
                                restartRecognitionSession()
                            }
                        }, 150L)
                    } else {
                        stopListening()
                    }
                }

                override fun onError(error: Int) {
                    if (!isListening && !isContinuous) return

                    val canRetry = (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                            error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)

                    if (isContinuous && canRetry) {
                        // Restart recognition session silently without showing error
                        mainHandler.postDelayed({
                            if (isContinuous && isListening) {
                                restartRecognitionSession()
                            }
                        }, 200L)
                        return
                    }

                    // On non-retryable error, stop silently without error message
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

    private fun restartRecognitionSession() {
        if (!isContinuous || !isListening) return
        transientlySilenceBeep()
        try {
            recognizer?.cancel()
        } catch (_: Exception) {}
        try {
            val intent = buildIntent()
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            recreateRecognizer()
            try {
                recognizer?.startListening(buildIntent())
            } catch (_: Exception) {
                isListening = false
                isContinuous = false
                mainHandler.removeCallbacks(silenceTimeoutRunnable)
                cancelMute()
                onStateChanged?.invoke(false)
            }
        }
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
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // Low-latency continuous dictation parameters
            putExtra("android.speech.extra.DICTATION_MODE", true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
    }

    fun startListening(languageCode: String? = null, continuous: Boolean = true) {
        currentLanguage = languageCode ?: Locale.getDefault().language
        isContinuous = continuous
        isListening = true

        transientlySilenceBeep()

        if (!initRecognizer()) {
            isListening = false
            isContinuous = false
            cancelMute()
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
                cancelMute()
                onStateChanged?.invoke(false)
            }
        }
    }

    fun stopListening() {
        isContinuous = false
        isListening = false
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        transientlySilenceBeep()
        try {
            recognizer?.cancel()
        } catch (_: Exception) {}
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {}
        onStateChanged?.invoke(false)
    }

    fun destroy() {
        isContinuous = false
        isListening = false
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        mainHandler.removeCallbacksAndMessages(null)
        unmuteRunnable?.let { mainHandler.removeCallbacks(it) }
        unmuteRunnable = null
        try {
            recognizer?.cancel()
        } catch (_: Exception) {}
        try {
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
        cancelMute()
        onStateChanged?.invoke(false)
    }

    companion object {
        const val SILENCE_TIMEOUT_MS = 7_000L
    }
}
