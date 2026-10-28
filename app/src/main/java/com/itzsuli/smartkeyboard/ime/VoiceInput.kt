package com.itzsuli.smartkeyboard.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Speech to text through Android's speech recogniser. Prefers the on-device recogniser
 * (works offline, audio never leaves the phone) and falls back to the regular one, which on
 * most phones is Google's or Samsung's. Results go through the same chill proofreading as
 * typed text (see [InputLogic.onVoiceText]).
 */
class VoiceInput(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onVoiceReady()
        fun onVoicePartial(text: String)
        fun onVoiceLevel(level: Float)
        fun onVoiceResult(text: String)
        fun onVoiceError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var triedOnDevice = false
    var isListening = false
        private set

    val hasPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context) ||
            (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))

    /** Starts listening in [languageTag] (e.g. "en-US", "de-DE", "ar"). */
    fun start(languageTag: String, preferOnDevice: Boolean = true) {
        stop()
        val onDevice = preferOnDevice && Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        triedOnDevice = onDevice
        val r = runCatching {
            if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull()
        if (r == null) { listener.onVoiceError("Speech recognition isn't available on this phone"); return }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listener.onVoiceReady() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { listener.onVoiceLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let { listener.onVoicePartial(it) }
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val text = best(results)
                if (text.isNullOrBlank()) listener.onVoiceError("Didn't catch that — try again") else listener.onVoiceResult(text)
            }
            override fun onError(error: Int) {
                isListening = false
                // The on-device recogniser may lack this language: try the regular one once.
                if (triedOnDevice && error in RETRY_ERRORS) {
                    start(languageTag, preferOnDevice = false)
                    return
                }
                listener.onVoiceError(message(error))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Longer pauses are fine: people think while dictating a message.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        isListening = true
        runCatching { r.startListening(intent) }.onFailure {
            isListening = false
            listener.onVoiceError("Couldn't start listening")
        }
    }

    /** Stops listening; whatever was said so far still arrives as a result. */
    fun finish() { runCatching { recognizer?.stopListening() } }

    fun stop() {
        isListening = false
        recognizer?.let { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
        recognizer = null
    }

    private fun best(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun message(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that — try again"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No connection for voice typing"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Voice typing isn't available in this language"
        else -> "Voice typing stopped (error $error)"
    }

    companion object {
        private val RETRY_ERRORS = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        )

        /** Recogniser language for a keyboard language. */
        fun languageTag(lang: String): String = when (lang) {
            "en" -> "en-US"; "de" -> "de-DE"; "ar" -> "ar-SA"; "fr" -> "fr-FR"; "es" -> "es-ES"; "it" -> "it-IT"
            "pt" -> "pt-BR"; "tr" -> "tr-TR"; "nl" -> "nl-NL"; "ru" -> "ru-RU"; "pl" -> "pl-PL"; else -> lang
        }
    }
}
