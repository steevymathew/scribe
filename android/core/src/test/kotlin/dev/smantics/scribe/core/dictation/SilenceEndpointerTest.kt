package dev.smantics.scribe.core.dictation

import dev.smantics.scribe.core.dictation.SilenceEndpointer.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When Scribe decides you have stopped talking.
 *
 * The bias under test is that it is **late rather than early**: a second of trailing
 * silence costs nothing (Whisper ignores it) while cutting someone off mid-sentence loses
 * words they said. Every case here is written from that direction.
 */
class SilenceEndpointerTest {

    /** Feed levels at a fixed cadence and return the first non-CONTINUE decision. */
    private fun run(
        endpointer: SilenceEndpointer,
        levels: List<Float>,
        stepMs: Long = 50,
    ): Pair<Decision, Long> {
        var now = 0L
        for (level in levels) {
            val decision = endpointer.onLevel(level, now)
            if (decision != Decision.CONTINUE) return decision to now
            now += stepMs
        }
        return Decision.CONTINUE to now
    }

    private fun speech(ms: Long, stepMs: Long = 50) = List((ms / stepMs).toInt()) { 0.5f }
    private fun quiet(ms: Long, stepMs: Long = 50) = List((ms / stepMs).toInt()) { 0.02f }

    @Test fun `a sentence followed by a pause ends`() {
        val (decision, at) = run(SilenceEndpointer(), speech(2_000) + quiet(3_000))
        assertEquals(Decision.ENDED, decision)
        // Ends after the trailing silence, not before: ~2000ms speech + ~1500ms quiet.
        assertTrue("ended at ${at}ms, too early", at >= 3_400)
    }

    @Test fun `a thinking pause does not end the utterance`() {
        // "Send it to the team on Monday … [1s] … and copy me in."
        val levels = speech(1_500) + quiet(1_000) + speech(1_500) + quiet(400)
        assertEquals(Decision.CONTINUE, run(SilenceEndpointer(), levels).first)
    }

    @Test fun `a tap with nothing said is discarded rather than transcribed`() {
        val (decision, _) = run(SilenceEndpointer(), quiet(8_000))
        assertEquals(Decision.NOTHING_HEARD, decision)
    }

    /** A cough or a door is not an utterance, and must not become one. */
    @Test fun `a single blip is not enough speech to end on`() {
        val levels = quiet(500) + speech(100) + quiet(3_000)
        val (decision, _) = run(SilenceEndpointer(), levels)
        // Too little speech for ENDED; falls through to the leading-silence timeout.
        assertEquals(Decision.NOTHING_HEARD, decision)
    }

    @Test fun `someone who keeps talking is cut off at the ceiling, not dropped`() {
        val (decision, _) = run(SilenceEndpointer(maxDurationMs = 3_000), speech(10_000))
        assertEquals(Decision.TOO_LONG, decision)
    }

    @Test fun `heardSpeech reports what the caller can show`() {
        val endpointer = SilenceEndpointer()
        endpointer.onLevel(0.01f, 0)
        assertFalse(endpointer.heardSpeech)
        endpointer.onLevel(0.6f, 50)
        assertTrue(endpointer.heardSpeech)
    }

    @Test fun `reset makes it reusable for the next utterance`() {
        val endpointer = SilenceEndpointer()
        assertEquals(Decision.ENDED, run(endpointer, speech(1_000) + quiet(2_500)).first)
        endpointer.reset()
        assertEquals(Decision.CONTINUE, run(endpointer, speech(500)).first)
    }

    /** Quiet speech must not be mistaken for silence — the threshold is a floor, not a gate. */
    @Test fun `a quiet voice above the threshold still counts as speech`() {
        val endpointer = SilenceEndpointer(speechThreshold = 0.12f)
        val levels = List(40) { 0.15f } + quiet(400)
        assertEquals(Decision.CONTINUE, run(endpointer, levels).first)
        assertTrue(endpointer.heardSpeech)
    }
}
