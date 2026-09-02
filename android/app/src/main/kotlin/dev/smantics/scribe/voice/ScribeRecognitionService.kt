package dev.smantics.scribe.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import dev.smantics.scribe.dictation.ScribeEngine

/**
 * Scribe as a system speech recogniser, so it can be plugged into a keyboard you already
 * use instead of replacing it.
 *
 * This is the integration that matters most on a phone. Scribe's own keyboard means
 * switching keyboards to dictate and switching back to type; a recognition service means
 * the microphone button on Samsung Keyboard, Gboard, FUTO or anything else can send its
 * audio here, get text back, and the user never leaves the keyboard they already like.
 * Android's own voice typing works exactly this way, and so does every offline
 * alternative to it.
 *
 * Register it under **Settings → General management → Keyboard list and default → Voice
 * input** (Samsung), or wherever the device keeps its recognition-service picker.
 *
 * Two properties of this path are worth stating plainly:
 *
 *  - the audio still never leaves the phone. The caller hands Scribe a request, not a
 *    recording; Scribe opens the microphone itself, transcribes locally, and returns text.
 *  - the caller only ever receives the finished string. It has no access to the audio.
 *
 * Whether a specific keyboard's microphone button routes here is up to that keyboard —
 * some use the system recogniser, some bundle their own and ignore this entirely. That is
 * device- and app-dependent and is marked OWNER-VERIFY.
 */
class ScribeRecognitionService : RecognitionService() {

    private lateinit var engine: ScribeEngine

    /** The caller currently being served. One at a time, as the API requires. */
    @Volatile private var active: Callback? = null

    override fun onCreate() {
        super.onCreate()
        engine = ScribeEngine.get(this)
        engine.warmUp()
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // An input method cannot grant this and neither can we; the app has to.
            Log.w(TAG, "no microphone permission")
            listener.safeError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        if (active != null) {
            listener.safeError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }

        active = listener
        runCatching { listener.readyForSpeech(Bundle()) }

        // The package that asked, so per-app tone still applies when dictating through
        // somebody else's keyboard. It is the only thing about the caller Scribe reads.
        val callerPackage = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE)

        engine.startHandsFree(
            packageName = callerPackage,
            sink = null,   // the caller inserts the text, not us
            onFinished = { text, _ -> finish(listener, text) },
            onFailed = { reason -> fail(listener, reason) },
        )
    }

    /**
     * Most callers never call this — they start listening and wait for Scribe to notice
     * the silence. When one does, it means "I have decided you are finished", so the
     * recording stops and the transcript follows.
     */
    override fun onStopListening(listener: Callback) {
        runCatching { listener.endOfSpeech() }
        engine.stopHandsFree()
    }

    override fun onCancel(listener: Callback) {
        active = null
        engine.cancelHandsFree()
    }

    private fun finish(listener: Callback, text: String) {
        active = null
        if (text.isBlank()) {
            listener.safeError(SpeechRecognizer.ERROR_NO_MATCH)
            return
        }
        val results = Bundle().apply {
            putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf(text),
            )
            // One local transcription, one hypothesis. Reporting a fabricated confidence
            // score would be inventing a number the engine does not produce.
            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1.0f))
        }
        runCatching { listener.endOfSpeech() }
        runCatching { listener.results(results) }
            .onFailure { Log.w(TAG, "caller went away before results", it) }
    }

    private fun fail(listener: Callback, reason: String) {
        active = null
        Log.i(TAG, "recognition ended without a result: $reason")
        listener.safeError(
            if (reason.contains("microphone", ignoreCase = true)) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            } else {
                SpeechRecognizer.ERROR_NO_MATCH
            },
        )
    }

    /** A caller that has already gone away must not take the service down with it. */
    private fun Callback.safeError(code: Int) {
        runCatching { error(code) }.onFailure { Log.w(TAG, "error callback failed", it) }
    }

    private companion object {
        const val TAG = "ScribeRecognition"
    }
}
