package dev.smantics.scribe.core.dictation

import dev.smantics.scribe.core.clean.CleanContext
import dev.smantics.scribe.core.clean.Mode
import dev.smantics.scribe.core.clean.Transcript
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The dictation state machine, ported from the desktop build's `src/scribe/engine.py`.
 *
 * `idle → recording → transcribing → injected → idle`, with `loading` at startup and
 * `error` as a transient state. The desktop's nine `event_sink` events are reproduced
 * exactly ([DictationEvent]) because they are already a clean, thread-safe boundary
 * between engine and UI, and keeping them identical means a bug found in one product is
 * findable in the other.
 *
 * Three behaviours are carried over as behaviour, not as accident:
 *
 *  - [MIN_AUDIO_SEC] and [MAX_AUDIO_SEC] are the desktop's, unchanged;
 *  - **VAD does not gate the hot path.** The desktop tried that and it dropped real speech
 *    on quiet microphones, so it was reverted. Silence is handled by Whisper returning
 *    `[BLANK_AUDIO]` and the pipeline stripping it to "";
 *  - a transcription failure emits `Error` and **no** `Injected`, while an *injection*
 *    failure emits `Injected` anyway — the transcript still happened and the user should
 *    still see it in history even if it could not be typed.
 *
 * Everything here is platform-free so the whole transition table, including every error
 * path, is unit-testable on a machine with no Android and no microphone.
 */
class DictationMachine(
    private val recorder: AudioRecorder,
    private val transcribers: TranscriberProvider,
    private val sink: TextSink,
    private val worker: Executor,
    private val settings: () -> MachineSettings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val listener: (DictationEvent) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16_000

        /** Shorter than this is a mis-tap, not an utterance. Desktop value, unchanged. */
        const val MIN_AUDIO_SEC = 0.3

        /** Longer than this is truncated rather than refused. Desktop value, unchanged. */
        const val MAX_AUDIO_SEC = 120.0

        /** How many finished clips may queue behind a slow decode before we drop. */
        private const val QUEUE_DEPTH = 4
    }

    private data class Job(val audio: FloatArray, val heavy: Boolean, val startedAt: Long)

    private val queue = ArrayBlockingQueue<Job>(QUEUE_DEPTH)
    private val recording = AtomicBoolean(false)
    private val boostHeld = AtomicBoolean(false)
    private val useHeavy = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    /** The last utterance, kept so the mode toggle can re-render it after insertion. */
    @Volatile
    var lastTranscript: Transcript? = null
        private set

    @Volatile
    var status: Status = Status.LOADING
        private set

    enum class Status { LOADING, READY, RECORDING, TRANSCRIBING, ERROR }

    // ------------------------------------------------------------- lifecycle

    /** Load the everyday model. Blocking; call off the main thread. */
    fun warmUp() {
        val s = settings()
        emit(DictationEvent.ModelLoading(s.model))
        val loaded = runCatching { transcribers.everyday() }.getOrNull()
        if (loaded == null) {
            status = Status.ERROR
            emit(DictationEvent.Error("Speech model could not be loaded — check Settings → Models"))
            return
        }
        status = Status.READY
        emit(DictationEvent.ModelLoaded(s.model, loaded.backendLabel))
    }

    fun setPaused(value: Boolean) {
        paused.set(value)
        if (value) stopRecording()
    }

    val isPaused: Boolean get() = paused.get()

    // -------------------------------------------------------------- recording

    /**
     * Begin capturing. A failure to open the microphone never leaves the machine believing
     * it is recording — the desktop's most valuable error-path guarantee.
     */
    fun startRecording() {
        if (paused.get()) return
        if (!recording.compareAndSet(false, true)) return
        useHeavy.set(false)
        val opened = runCatching { recorder.start() }
        if (opened.isFailure) {
            recording.set(false)
            status = Status.ERROR
            emit(DictationEvent.Error(micErrorFor(opened.exceptionOrNull())))
            return
        }
        status = Status.RECORDING
        emit(DictationEvent.RecordingStarted)
    }

    /** Report the live input level. Drives the waveform; gates nothing. */
    fun onLevel(rms: Float) {
        if (recording.get()) emit(DictationEvent.Level(rms))
    }

    fun stopRecording() {
        if (!recording.compareAndSet(true, false)) return
        val heavy = useHeavy.get() || boostHeld.get()
        val audio = runCatching { recorder.stop() }.getOrElse { FloatArray(0) }

        val seconds = audio.size.toDouble() / SAMPLE_RATE
        emit(DictationEvent.RecordingStopped(seconds))

        if (audio.isEmpty() || seconds < MIN_AUDIO_SEC) {
            status = Status.READY
            return
        }
        val clipped = if (seconds > MAX_AUDIO_SEC) {
            audio.copyOf((MAX_AUDIO_SEC * SAMPLE_RATE).toInt())
        } else {
            audio
        }

        val job = Job(clipped, heavy, clock())
        if (!queue.offer(job)) {
            status = Status.READY
            emit(DictationEvent.Error("Still catching up on the last one — that clip was dropped"))
            return
        }
        status = Status.TRANSCRIBING
        emit(DictationEvent.Transcribing)
        worker.execute { drain() }
    }

    /**
     * Arm or disarm the high-accuracy model.
     *
     * As on the desktop, this may be pressed *during* a recording and still upgrades the
     * clip in flight; and it debounces, because a held key repeats.
     */
    fun setBoost(held: Boolean) {
        if (!boostHeld.compareAndSet(!held, held)) return
        if (held && recording.get()) useHeavy.set(true)
        emit(DictationEvent.Boost(held))
    }

    val isBoostActive: Boolean get() = boostHeld.get() || useHeavy.get()

    /** Abandon an in-flight decode — the user dismissed the keyboard. */
    fun cancel() {
        recording.set(false)
        runCatching { recorder.stop() }
        transcribers.cancelAll()
        queue.clear()
        status = Status.READY
    }

    // ------------------------------------------------------------ the worker

    private fun drain() {
        while (true) {
            val job = queue.poll() ?: break
            runCatching { transcribeAndInject(job) }.onFailure {
                status = Status.ERROR
                emit(DictationEvent.Error("Transcription failed — trying the next one fresh"))
            }
        }
        if (status != Status.ERROR) status = Status.READY
    }

    private fun transcribeAndInject(job: Job) {
        val s = settings()
        val transcriber = if (job.heavy) {
            emit(DictationEvent.ModelLoading(s.heavyModel))
            val heavy = runCatching { transcribers.heavy() }.getOrNull()
            if (heavy == null) {
                emit(DictationEvent.Error("High-accuracy model unavailable — using the everyday one"))
                transcribers.everyday()
            } else {
                emit(DictationEvent.ModelLoaded(s.heavyModel, heavy.backendLabel))
                heavy
            }
        } else {
            transcribers.everyday()
        }

        val rawResult = runCatching {
            transcriber.transcribe(job.audio, s.language, s.beamSize, s.initialPrompt)
        }
        if (rawResult.isFailure) {
            // No Injected event on a transcription failure — the desktop's contract.
            status = Status.ERROR
            emit(DictationEvent.Error("Transcription failed (see the log)"))
            return
        }

        val transcript = Transcript(rawResult.getOrDefault(""), s.cleanContext)
        lastTranscript = transcript
        val text = transcript.render(s.mode)
        val elapsed = (clock() - job.startedAt) / 1000.0

        if (text.isNotEmpty()) {
            // Injection can fail without invalidating the transcript, so its failure is
            // reported but does not suppress the Injected event.
            runCatching { sink.commit(text) }.onFailure {
                emit(DictationEvent.Error("Could not type into this field — text copied to history"))
            }
        }
        emit(
            DictationEvent.Injected(
                text = text,
                raw = transcript.raw,
                elapsedSec = elapsed,
                backend = transcriber.backendLabel,
                heavy = job.heavy,
            ),
        )
    }

    // ------------------------------------------------------------ re-render

    /**
     * Re-render the last utterance in the other mode and replace what was inserted.
     *
     * This is the toggle's second job and the thing Wispr Flow cannot do: because the raw
     * string is kept and every pipeline stage is pure, switching Raw↔Clean after the text
     * has already landed is a string operation, not another dictation.
     *
     * Returns the new text, or null when there is nothing to re-render.
     */
    fun rerenderLast(mode: Mode): String? {
        val transcript = lastTranscript ?: return null
        val previous = transcript.render(if (mode == Mode.CLEAN) Mode.RAW else Mode.CLEAN)
        val next = transcript.render(mode)
        if (next == previous) return next
        return if (runCatching { sink.replace(previous, next) }.getOrDefault(false)) {
            next
        } else {
            null
        }
    }

    // --------------------------------------------------------------- helpers

    private fun micErrorFor(cause: Throwable?): String {
        val message = cause?.message.orEmpty()
        return when {
            message.contains("permission", ignoreCase = true) ->
                "Scribe needs microphone access — open Scribe to grant it"
            message.contains("busy", ignoreCase = true) ->
                "The microphone is in use by another app"
            else -> "Microphone unavailable — check Settings → Audio"
        }
    }

    private fun emit(event: DictationEvent) {
        // A broken listener must never take the engine down with it. Same guarantee as
        // the desktop's `_emit`, for the same reason.
        runCatching { listener(event) }
    }
}
