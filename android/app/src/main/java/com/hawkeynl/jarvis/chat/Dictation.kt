package com.hawkeynl.jarvis.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi

/** What the recogniser reports while it listens. */
sealed interface DictationEvent {
    /** Voice level, 0..1. */
    data class Level(val value: Float) : DictationEvent
    /** The recognised text; the session has ended. */
    data class Heard(val text: String) : DictationEvent
    /** The session ended without text; [message] is null when simply nothing was said. */
    data class Ended(val message: String?) : DictationEvent
    /** Only the phone's speech service could listen, which may process speech off the phone;
     *  nothing was recorded. The owner is told once before it is used. */
    data object NeedsConsent : DictationEvent
}

/** The chat mic (desktop useMic): the platform recogniser in Dutch like desktop, on the device
 *  when it has Dutch installed. One utterance per tap. Main thread only. */
class Dictation(context: Context) {
    private val context = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    /** The owner stopped this session: an ERROR_CLIENT then is ours, not a failure. */
    private var stopping = false

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Starts a session; every outcome arrives through [event], until it ends or [cancel] is
     *  called. Without on-device Dutch, the phone's speech service is used only when
     *  [offDevice] allows it; otherwise the session ends with [DictationEvent.NeedsConsent]. */
    fun start(offDevice: Boolean, event: (DictationEvent) -> Unit) {
        cancel()
        stopping = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            checkOnDevice(offDevice, event)
        } else {
            fallBack(offDevice, event)
        }
    }

    /** Listens on the device when Dutch is installed there; a Dutch model it offers is
     *  downloaded for next time, and this session falls back. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkOnDevice(offDevice: Boolean, event: (DictationEvent) -> Unit) {
        val made = create(onDevice = true) ?: return fallBack(offDevice, event)
        recognizer = made
        made.checkRecognitionSupport(intent(), context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                if (recognizer !== made) return
                when {
                    stopping -> finish(made, event, DictationEvent.Ended(null))
                    hasDutch(support.installedOnDeviceLanguages) -> listen(made, onDevice = true, offDevice, event)
                    else -> {
                        if (hasDutch(support.supportedOnDeviceLanguages)) made.triggerModelDownload(intent())
                        fallBack(offDevice, event)
                    }
                }
            }
            override fun onError(error: Int) { if (recognizer === made) fallBack(offDevice, event) }
        })
    }

    private fun fallBack(allowed: Boolean, event: (DictationEvent) -> Unit) {
        cancel()
        if (!allowed) return event(DictationEvent.NeedsConsent)
        val made = create(onDevice = false) ?: return event(DictationEvent.Ended(UNAVAILABLE))
        recognizer = made
        listen(made, onDevice = false, allowed, event)
    }

    private fun create(onDevice: Boolean): SpeechRecognizer? = try {
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
    } catch (_: RuntimeException) {
        null
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, DICTATION_LANGUAGE)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

    /** Ends [made]'s session with [last]; a cancelled session stays silent, also for a
     *  callback already queued. */
    private fun finish(made: SpeechRecognizer, event: (DictationEvent) -> Unit, last: DictationEvent) {
        if (recognizer !== made) return
        cancel()
        event(last)
    }

    private fun listen(made: SpeechRecognizer, onDevice: Boolean, offDevice: Boolean, event: (DictationEvent) -> Unit) {
        made.setRecognitionListener(object : RecognitionListener {
            override fun onRmsChanged(rmsdB: Float) { if (recognizer === made) event(DictationEvent.Level(micLevel(rmsdB))) }
            override fun onResults(results: Bundle?) {
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                finish(made, event, if (said.isEmpty()) DictationEvent.Ended(null) else DictationEvent.Heard(said))
            }
            override fun onError(error: Int) {
                if (recognizer !== made) return
                // The device lacks Dutch after all: the phone's speech service takes over.
                if (onDevice && !stopping && languageError(error)) fallBack(offDevice, event)
                else finish(made, event, DictationEvent.Ended(dictationError(error, stopping)))
            }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        made.startListening(intent())
    }

    /** Stops listening; what was said is still recognised and reported. */
    fun stop() {
        stopping = true
        recognizer?.stopListening()
    }

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

const val UNAVAILABLE = "Voice input unavailable"
const val MIC_DENIED = "Microphone access is off. Allow it in Android settings to use voice input."
/** Shown once before the phone's speech service is first used (like PHOTO_DISCLOSURE). */
const val VOICE_DISCLOSURE = "Voice input uses your phone's speech service. Without on-device recognition your speech may be " +
    "processed off this phone. Tap the mic again to continue."

/** Whether a recogniser's language list has Dutch (nl-NL or plain nl). */
fun hasDutch(languages: List<String>): Boolean =
    languages.any { it.replace('_', '-').equals(DICTATION_LANGUAGE, ignoreCase = true) || it.equals("nl", ignoreCase = true) }

/** onRmsChanged reports roughly -2..10 dB; the mic ring wants 0..1. */
fun micLevel(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

private fun languageError(error: Int) =
    error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

/** What to tell the owner when a session ends without text; null for silence, and for the
 *  client error our own stop causes ([stopping]). */
fun dictationError(error: Int, stopping: Boolean = false): String? = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
    SpeechRecognizer.ERROR_CLIENT -> if (stopping) null else "Voice input stopped. Try again."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> MIC_DENIED
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice input is busy. Try again."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
        "Speech recognition is not reachable. Try again later."
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
        "Dutch speech recognition is not available on this phone."
    else -> "Voice input stopped. Try again."
}

/** What a tap on the mic does. */
sealed interface MicTap {
    data object Stop : MicTap
    data object Ignore : MicTap
    /** [offDevice]: the phone's speech service may be used (disclosed before, or the notice
     *  is showing and this tap continues). */
    data class Start(val offDevice: Boolean) : MicTap
}

fun micTap(listening: Boolean, locked: Boolean, authenticated: Boolean, disclosed: Boolean, noticeShown: Boolean): MicTap = when {
    listening -> MicTap.Stop
    locked || !authenticated -> MicTap.Ignore
    else -> MicTap.Start(disclosed || noticeShown)
}

/** A session's report counts only for the sign-in it started in, and never behind the lock. */
fun dictationAllowed(asked: Int, identity: Int, locked: Boolean): Boolean = asked == identity && !locked

/** A transcript Jarvis could not send waits in the composer instead; null when it was sent. */
fun unsentTranscript(sent: Boolean, text: String): String? = text.takeIf { !sent && it.isNotBlank() }

/** A transcript that could not be sent goes after the draft, on its own line. */
fun appendDictated(draft: String, said: String): String =
    if (draft.isBlank()) said else draft.trimEnd() + "\n" + said
