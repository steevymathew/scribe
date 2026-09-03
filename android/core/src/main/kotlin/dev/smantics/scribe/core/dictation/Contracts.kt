package dev.smantics.scribe.core.dictation

import dev.smantics.scribe.core.clean.CleanContext
import dev.smantics.scribe.core.clean.Mode

/**
 * The interfaces [DictationMachine] talks to.
 *
 * All four are deliberately narrow so the Android implementations can be swapped for fakes
 * in a JVM test. That is the same discipline as the desktop package — where the engine is
 * importable and runnable with no Qt — and here it is not a nicety: this build machine has
 * no Android emulator, so anything that can only be tested on a device cannot be tested.
 */

/** Captures 16 kHz mono float PCM. `stop` returns everything captured since `start`. */
interface AudioRecorder {
    /** @throws Exception when the microphone cannot be opened; the machine handles it. */
    fun start()

    /** Returns the captured samples, or an empty array if nothing was captured. */
    fun stop(): FloatArray

    /**
     * Everything captured so far, without interrupting the recording.
     *
     * This is what makes live transcription possible: the partial decoder takes a copy of
     * the audio to date, transcribes it, and the recording carries on underneath. The
     * default returns nothing, so a recorder that cannot do this simply produces no
     * partials rather than failing.
     */
    fun snapshot(): FloatArray = FloatArray(0)
}

/** One loaded speech model. */
interface Transcriber {
    /** Shown in the UI beside a transcript, e.g. "base.en · CPU". */
    val backendLabel: String

    /**
     * Decode one utterance. Blocking, and called only from the machine's worker.
     *
     * [initialPrompt] carries the user's custom dictionary into recognition itself, so a
     * name Scribe has been told about is more likely to be heard correctly rather than
     * only corrected afterwards.
     */
    fun transcribe(
        pcm16k: FloatArray,
        language: String,
        beamSize: Int,
        initialPrompt: String?,
    ): String
}

/**
 * Supplies the everyday and high-accuracy models, loading them lazily.
 *
 * The heavy model is loaded on first use rather than at startup — it is several hundred
 * megabytes and most dictation never needs it.
 */
interface TranscriberProvider {
    fun everyday(): Transcriber
    fun heavy(): Transcriber
    fun cancelAll()
}

/** Where finished text goes: an `InputConnection` in the keyboard, a fake in tests. */
interface TextSink {
    fun commit(text: String)

    /**
     * Replace text this sink previously committed. Returns false when it cannot be done —
     * the field moved on, the app rejected it — and the caller then leaves things alone
     * rather than guessing.
     */
    fun replace(previous: String, next: String): Boolean
}

/**
 * Everything the machine reads per utterance. Passed as a snapshot rather than held, so a
 * settings change mid-recording cannot half-apply.
 */
data class MachineSettings(
    val model: String = "base.en",
    val heavyModel: String = "small.en",
    val language: String = "en",
    val beamSize: Int = 1,
    val mode: Mode = Mode.CLEAN,
    val cleanContext: CleanContext = CleanContext(),
    val initialPrompt: String? = null,
)

/**
 * The nine events, matching the desktop `event_sink` contract name for name.
 *
 * They may be delivered from the capture thread, the worker thread or the caller's thread;
 * a consumer marshals to its own thread, exactly as `bridge.py` does.
 */
sealed interface DictationEvent {
    data class ModelLoading(val model: String) : DictationEvent
    data class ModelLoaded(val model: String, val backend: String) : DictationEvent
    data object RecordingStarted : DictationEvent
    data class RecordingStopped(val durationSec: Double) : DictationEvent
    data object Transcribing : DictationEvent

    /**
     * [text] is what was inserted, in the current mode; [raw] is what was said. Both are
     * carried so history can show either and the mode toggle can re-render later.
     */
    data class Injected(
        val text: String,
        val raw: String,
        val elapsedSec: Double,
        val backend: String,
        val heavy: Boolean,
    ) : DictationEvent

    data class Boost(val active: Boolean) : DictationEvent
    data class Level(val rms: Float) : DictationEvent
    data class Error(val message: String) : DictationEvent
}
