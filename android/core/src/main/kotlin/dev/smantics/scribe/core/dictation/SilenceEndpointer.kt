package dev.smantics.scribe.core.dictation

/**
 * Decides when someone has finished speaking.
 *
 * The keyboard does not need this — you hold a button and let go, which is a far more
 * reliable signal than any algorithm. But the two surfaces added for the phone do:
 *
 *  - a keyboard's own microphone button routed to Scribe through the system voice-input
 *    service starts listening and then waits; there is no second press to end it;
 *  - the floating bubble is a single tap, not a hold.
 *
 * So in those cases Scribe has to notice the silence itself. This is deliberately not the
 * Silero VAD: that model was tried on the desktop as a *gate* and dropped real speech on
 * quiet microphones, which is why the engine still transcribes whole clips. This decides
 * only when to *stop recording*, never what to transcribe, so being slightly late costs a
 * second of silence at the end of a clip — which Whisper ignores — while being early would
 * cut someone off mid-sentence. It is tuned to be late.
 *
 * Pure and time-driven rather than clock-driven, so the whole thing is unit-testable.
 */
class SilenceEndpointer(
    /** Level above which we consider the microphone to be hearing a voice. */
    private val speechThreshold: Float = 0.12f,

    /** Speech must be heard for this long before silence can end the utterance. */
    private val minSpeechMs: Long = 300,

    /** Silence after speech that ends the utterance. Generous: people pause to think. */
    private val trailingSilenceMs: Long = 1_500,

    /** Silence before any speech at all — someone tapped by accident, or the mic is dead. */
    private val leadingSilenceMs: Long = 6_000,

    /** Nothing runs forever, however much someone has to say. */
    private val maxDurationMs: Long = 60_000,
) {
    enum class Decision {
        /** Keep recording. */
        CONTINUE,

        /** They finished a sentence; transcribe what we have. */
        ENDED,

        /** Nothing was ever said; discard rather than transcribing a room. */
        NOTHING_HEARD,

        /** The ceiling. Transcribe what we have rather than dropping it. */
        TOO_LONG,
    }

    private var startedAtMs = -1L
    private var firstSpeechAtMs = -1L
    private var lastSpeechAtMs = -1L
    private var speechDurationMs = 0L
    private var previousLevelAtMs = -1L

    fun reset() {
        startedAtMs = -1L
        firstSpeechAtMs = -1L
        lastSpeechAtMs = -1L
        speechDurationMs = 0L
        previousLevelAtMs = -1L
    }

    /** True once any speech has been heard, for the caller's own UI. */
    val heardSpeech: Boolean get() = firstSpeechAtMs >= 0

    /**
     * Feed one level reading, with the time it was taken. Returns what to do next.
     *
     * [nowMs] is any monotonic millisecond source; the endpointer never reads a clock
     * itself, which is what makes its behaviour reproducible in a test.
     */
    fun onLevel(level: Float, nowMs: Long): Decision {
        if (startedAtMs < 0) startedAtMs = nowMs

        if (level >= speechThreshold) {
            if (firstSpeechAtMs < 0) firstSpeechAtMs = nowMs
            if (previousLevelAtMs >= 0) {
                speechDurationMs += (nowMs - previousLevelAtMs).coerceAtLeast(0)
            }
            lastSpeechAtMs = nowMs
        }
        previousLevelAtMs = nowMs

        if (nowMs - startedAtMs >= maxDurationMs) return Decision.TOO_LONG

        // Not enough sound to be an utterance yet.
        if (speechDurationMs < minSpeechMs) {
            val neverSpoke = firstSpeechAtMs < 0
            val silentLongEnough = if (neverSpoke) {
                nowMs - startedAtMs >= leadingSilenceMs
            } else {
                // A cough, a door, a knock against the phone. Waiting for the full
                // leading timeout would hold the microphone open for another five
                // seconds after a noise nobody meant as speech.
                nowMs - lastSpeechAtMs >= trailingSilenceMs
            }
            return if (silentLongEnough) Decision.NOTHING_HEARD else Decision.CONTINUE
        }

        // Enough speech to be an utterance, and quiet for long enough to be finished.
        if (nowMs - lastSpeechAtMs >= trailingSilenceMs) return Decision.ENDED
        return Decision.CONTINUE
    }
}
