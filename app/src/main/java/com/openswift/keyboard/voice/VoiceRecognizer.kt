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
 * High-speed, responsive voice input manager:
 * - Real-time streaming transcription with partial results.
 * - Auto-stops after 7 seconds if no speech is detected.
 * - Suppresses start/stop beep sounds using transient STREAM_MUSIC mute without breaking the microphone.
 * - Suppresses all error toasts and intrusive dialogs.
 * - Works reliably across all phones and Android versions using system SpeechRecognizer.
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

    private var originalMusicVolume: Int? = null
    private var isMuted = false

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
     * Mutes STREAM_MUSIC temporarily before startListening to silence the start beep.
     * The volume is quickly restored in onReadyForSpeech or onBeginningOfSpeech.
     */
    private fun muteBeepSound() {
        val am = audioManager ?: return
        try {
            if (!isMuted) {
                originalMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                isMuted = true
            }
        } catch (_: Exception) {}
    }

    /**
     * Restores STREAM_MUSIC volume back to original level.
     */
    private fun restoreBeepVolume() {
        val am = audioManager ?: return
        try {
            if (isMuted) {
                originalMusicVolume?.let { vol ->
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                }
                isMuted = false
                originalMusicVolume = null
            }
        } catch (_: Exception) {
            isMuted = false
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
                    // Restore music volume smoothly once recognizer is ready and beep has been silenced
                    mainHandler.postDelayed({ restoreBeepVolume() }, 100L)
                }

                override fun onBeginningOfSpeech() {
                    resetSilenceTimer()
                    restoreBeepVolume()
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (rmsdB > 2.0f) {
                        resetSilenceTimer()
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    // Mute before stop beep triggers
                    muteBeepSound()
                }

                override fun onPartialResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrEmpty()) {
                        resetSilenceTimer()
                        onPartialResult?.invoke(text)
                    }
                }

                override fun onResults(results: android.os.Bundle?) {
                    restoreBeepVolume()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrEmpty()) {
                        resetSilenceTimer()
                        onResult?.invoke(text)
                    }

                    // Keep listening continuously across pauses
                    if (isContinuous && isListening) {
                        mainHandler.post {
                            if (isContinuous && isListening) {
                                restartRecognitionSession()
                            }
                        }
                    } else {
                        stopListening()
                    }
                }

                override fun onError(error: Int) {
                    restoreBeepVolume()
                    if (!isListening && !isContinuous) return

                    val canRetry = (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                            error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)

                    if (isContinuous && canRetry) {
                        mainHandler.postDelayed({
                            if (isContinuous && isListening) {
                                restartRecognitionSession()
                            }
                        }, 100L)
                        return
                    }

                    // On fatal or final error, stop cleanly
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
        muteBeepSound()
        try {
            recognizer?.startListening(buildIntent())
        } catch (_: Exception) {
            try {
                recognizer?.cancel()
                recognizer?.startListening(buildIntent())
            } catch (_: Exception) {
                recreateRecognizer()
                try {
                    recognizer?.startListening(buildIntent())
                } catch (_: Exception) {
                    isListening = false
                    isContinuous = false
                    mainHandler.removeCallbacks(silenceTimeoutRunnable)
                    restoreBeepVolume()
                    onStateChanged?.invoke(false)
                }
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
            // Extra speech parameters for high responsiveness and fast real-time typing
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 50L)
        }
    }

    fun startListening(languageCode: String? = null, continuous: Boolean = true) {
        currentLanguage = languageCode ?: Locale.getDefault().language
        isContinuous = continuous
        isListening = true

        muteBeepSound()

        if (!initRecognizer()) {
            isListening = false
            isContinuous = false
            restoreBeepVolume()
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
                restoreBeepVolume()
                onStateChanged?.invoke(false)
            }
        }
    }

    fun stopListening() {
        isContinuous = false
        isListening = false
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        muteBeepSound()
        try {
            recognizer?.cancel()
        } catch (_: Exception) {}
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {}
        mainHandler.postDelayed({ restoreBeepVolume() }, 300L)
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
        restoreBeepVolume()
        onStateChanged?.invoke(false)
    }

    companion object {
        const val SILENCE_TIMEOUT_MS = 7_000L
    }
}
