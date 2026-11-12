package com.hawkeynl.jarvis.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** What the recogniser reports while it listens. */
sealed interface DictationEvent {
    /** Voice level, 0..1. */
    data class Level(val value: Float) : DictationEvent
    /** The recognised text; the session has ended. */
    data class Heard(val text: String) : DictationEvent
    /** The session ended without text; [message] is null when simply nothing was said. */
    data class Ended(val message: String?) : DictationEvent
}

/** The chat mic (desktop useMic): the platform recogniser, on the device when it can,
 *  in Dutch like desktop. One utterance per tap. Main thread only. */
class Dictation(context: Context) {
    private val context = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** False when no recogniser could be made. [event] gets this session's reports until it
     *  ends or [cancel] is called; nothing arrives after that. */
    fun start(event: (DictationEvent) -> Unit): Boolean {
        cancel()
        val made = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (_: RuntimeException) {
            return false
        }
        recognizer = made
        made.setRecognitionListener(object : RecognitionListener {
            // A cancelled session stays silent, also for a callback already queued.
            fun finish(last: DictationEvent) {
                if (recognizer !== made) return
                cancel()
                event(last)
            }
            override fun onRmsChanged(rmsdB: Float) { if (recognizer === made) event(DictationEvent.Level(micLevel(rmsdB))) }
            override fun onResults(results: Bundle?) {
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                finish(if (said.isEmpty()) DictationEvent.Ended(null) else DictationEvent.Heard(said))
            }
            override fun onError(error: Int) = finish(DictationEvent.Ended(dictationError(error)))
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        made.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, DICTATION_LANGUAGE)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
        )
        return true
    }

    /** Stops listening; what was said is still recognised and reported. */
    fun stop() { recognizer?.stopListening() }

    /** Drops the session and what it has not recognised yet. */
    fun cancel() {
        val current = recognizer ?: return
        recognizer = null
        current.cancel()
        current.destroy()
    }
}

/** Recognition language, as desktop (nl-NL). */
const val DICTATION_LANGUAGE = "nl-NL"

/** onRmsChanged reports roughly -2..10 dB; the mic ring wants 0..1. */
fun micLevel(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

/** What to tell the owner when a session ends without text; null for silence. */
fun dictationError(error: Int): String? = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> null
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> MIC_DENIED
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice input is busy. Try again."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
        "Speech recognition is not reachable. Try again later."
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
        "Dutch speech recognition is not available on this phone."
    else -> "Voice input stopped. Try again."
}

const val MIC_DENIED = "Microphone access is off. Allow it in Android settings to use voice input."

/** A transcript that could not be sent goes after the draft, on its own line. */
fun appendDictated(draft: String, said: String): String =
    if (draft.isBlank()) said else draft.trimEnd() + "\n" + said
