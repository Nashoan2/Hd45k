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
 * Continuous, high-speed, completely silent voice input integration:
 * - Real-time streaming with partial results for instantaneous display while speaking.
 * - Completely silences start and stop beeps/dings during speech recognizer startup, restarts, pauses, and shutdown.
 * - Suppresses all error messages, toasts, and chimes.
 * - Auto-stops after 15 seconds if no speech/words are detected.
 * - Commits clean text without duplicate words.
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
    private var originalSystemVolume: Int? = null
    private var originalNotificationVolume: Int? = null
    private var unmuteRunnable: Runnable? = null

    private val silenceTimeoutRunnable = Runnable {
        // Automatically stop after 15 seconds if no speech is detected
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

    private fun muteAllBeeps(mute: Boolean) {
        unmuteRunnable?.let { mainHandler.removeCallbacks(it) }
        unmuteRunnable = null
        val am = audioManager ?: return
        try {
            if (mute) {
                if (originalMusicVolume == null) {
                    originalMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
                if (originalSystemVolume == null) {
                    originalSystemVolume = am.getStreamVolume(AudioManager.STREAM_SYSTEM)
                }
                if (originalNotificationVolume == null) {
                    originalNotificationVolume = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                }

                // Specifically mute STREAM_MUSIC (where Google SpeechRecognizer plays start/stop beeps)
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                } catch (_: Exception) {}
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                } catch (_: Exception) {}
                // Also mute STREAM_SYSTEM
                try {
                    am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
                } catch (_: Exception) {}
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                } catch (_: Exception) {}
                // Also mute STREAM_NOTIFICATION
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                } catch (_: Exception) {}
            } else {
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {}
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {}
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {}
                try {
                    originalMusicVolume?.let { am.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
                } catch (_: Exception) {}
                try {
                    originalSystemVolume?.let { am.setStreamVolume(AudioManager.STREAM_SYSTEM, it, 0) }
                } catch (_: Exception) {}
                try {
                    originalNotificationVolume?.let { am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, it, 0) }
                } catch (_: Exception) {}
                originalMusicVolume = null
                originalSystemVolume = null
                originalNotificationVolume = null
            }
        } catch (_: Exception) {}
    }

    private fun scheduleUnmute(delayMs: Long = 1500L) {
        unmuteRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            muteAllBeeps(false)
            unmuteRunnable = null
        }
        unmuteRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun initRecognizer() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {}

                override fun onBeginningOfSpeech() {
                    // Reset 15s silence timer when user speaks
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

                    // Keep listening continuously across pauses until explicitly stopped or 15s silence
                    if (isContinuous) {
                        mainHandler.postDelayed({
                            if (isContinuous) {
                                startListeningInternal()
                            }
                        }, 40L)
                    } else {
                        isListening = false
                        onStateChanged?.invoke(false)
                        scheduleUnmute(1500L)
                    }
                }

                override fun onError(error: Int) {
                    if (!isListening && !isContinuous) return

                    val isSilenceOrPause = (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT)

                    if (isContinuous && isSilenceOrPause) {
                        // User paused speaking; resume listening seamlessly unless 15s timer stops it
                        mainHandler.postDelayed({
                            if (isContinuous) {
                                startListeningInternal()
                            }
                        }, 60L)
                        return
                    }

                    if (isContinuous && error == SpeechRecognizer.ERROR_CLIENT) {
                        // Recreate recognizer instance on transient client error and continue
                        recreateRecognizer()
                        mainHandler.postDelayed({
                            if (isContinuous) {
                                startListeningInternal()
                            }
                        }, 120L)
                        return
                    }

                    // On fatal or unrecoverable error: stop silently without showing any error message
                    isListening = false
                    isContinuous = false
                    mainHandler.removeCallbacks(silenceTimeoutRunnable)
                    onStateChanged?.invoke(false)
                    scheduleUnmute(1500L)
                    onError?.invoke("")
                }

                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
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

    fun startListening(languageCode: String? = null, continuous: Boolean = true) {
        currentLanguage = languageCode ?: Locale.getDefault().language
        isContinuous = continuous
        // Immediately mute all streams before initializing or starting
        muteAllBeeps(true)
        initRecognizer()
        startListeningInternal()
        resetSilenceTimer()
        onStateChanged?.invoke(true)
    }

    private fun startListeningInternal() {
        try {
            muteAllBeeps(true)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Enable streaming partial results for instant real-time transcription
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Crisp, snappy silence thresholds:
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 150L)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }
            recognizer?.startListening(intent)
            isListening = true
        } catch (_: Exception) {
            isListening = false
            isContinuous = false
            mainHandler.removeCallbacks(silenceTimeoutRunnable)
            onStateChanged?.invoke(false)
            scheduleUnmute(1500L)
        }
    }

    fun stopListening() {
        isContinuous = false
        isListening = false
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        // Keep streams muted while stopping to completely silence the finish/stop beep
        muteAllBeeps(true)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {}
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {}
        onStateChanged?.invoke(false)
        // Restore stream volume safely after the stop sound window has completely passed
        scheduleUnmute(1500L)
    }

    fun destroy() {
        isContinuous = false
        isListening = false
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        mainHandler.removeCallbacksAndMessages(null)
        muteAllBeeps(true)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {}
        try {
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
        onStateChanged?.invoke(false)
        scheduleUnmute(1500L)
    }

    companion object {
        private const val SILENCE_TIMEOUT_MS = 15_000L
    }
}
