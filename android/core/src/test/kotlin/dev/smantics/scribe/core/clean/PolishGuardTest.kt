package dev.smantics.scribe.core.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard is the only thing standing between a small language model and the user's
 * outgoing message, so it is tested against the ways such a model actually fails rather
 * than against a happy path.
 */
class PolishGuardTest {

    private fun verdict(original: String, candidate: String?) =
        PolishGuard.check(original, candidate)

    private fun accepted(original: String, candidate: String?): String {
        val v = verdict(original, candidate)
        assertTrue("expected accepted, got $v", v is PolishVerdict.Accepted)
        return (v as PolishVerdict.Accepted).text
    }

    private fun rejected(original: String, candidate: String?): String {
        val v = verdict(original, candidate)
        assertTrue("expected rejected, got $v", v is PolishVerdict.Rejected)
        return (v as PolishVerdict.Rejected).reason
    }

    // ------------------------------------------------------------- accepted

    @Test fun `a genuine grammar fix is accepted`() {
        assertEquals(
            "We should ship it on Friday.",
            accepted("we should ship it on friday", "We should ship it on Friday."),
        )
    }

    @Test fun `adding grammatical glue is allowed`() {
        accepted("send report to team", "Send the report to the team.")
    }

    @Test fun `unchanged text is accepted`() {
        accepted("Already fine.", "Already fine.")
    }

    // ------------------------------------------------------------- rejected

    @Test fun `no output is rejected`() {
        assertEquals("no output", rejected("anything at all here", null))
    }

    @Test fun `empty output for real input is rejected`() {
        assertTrue(rejected("something was said", "   ").contains("empty"))
    }

    /** The worst failure mode: a number nobody said, delivered with total confidence. */
    @Test fun `an invented number is rejected`() {
        assertTrue(
            rejected("let's meet in the afternoon", "Let's meet at 3pm.")
                .contains("invented number"),
        )
    }

    @Test fun `a changed number is rejected`() {
        assertTrue(
            rejected("transfer 250 pounds", "Transfer 350 pounds.").contains("invented number"),
        )
    }

    @Test fun `preserving the number is fine`() {
        accepted("transfer 250 pounds", "Transfer 250 pounds.")
    }

    @Test fun `answering the question instead of cleaning it is rejected`() {
        rejected(
            "what is the capital of france",
            "The capital of France is Paris.",
        )
    }

    @Test fun `a preamble is rejected`() {
        assertTrue(
            rejected("fix this sentence please", "Here is the corrected text: Fix this sentence.")
                .contains("preamble"),
        )
    }

    @Test fun `truncated output is rejected`() {
        assertTrue(
            rejected(
                "we need to review the deployment plan before the release on monday",
                "We need to review",
            ).contains("too short"),
        )
    }

    @Test fun `padded output is rejected`() {
        assertTrue(
            rejected(
                "thanks",
                "Thank you very much indeed for all of your kind and generous help today.",
            ).contains("too long"),
        )
    }

    @Test fun `invented content words are rejected`() {
        rejected(
            "the meeting went well and everyone agreed on the plan",
            "The quarterly stakeholder meeting went exceptionally well and everyone agreed.",
        )
    }

    // ------------------------------------------------- the wrapper's contract

    private class FixedPolisher(private val output: String?) : Polisher {
        override val isAvailable = true
        override fun polish(text: String, instruction: String, timeoutMs: Long) = output
    }

    private object Unavailable : Polisher {
        override val isAvailable = false
        override fun polish(text: String, instruction: String, timeoutMs: Long) =
            error("must not be called when unavailable")
    }

    private class ThrowingPolisher : Polisher {
        override val isAvailable = true
        override fun polish(text: String, instruction: String, timeoutMs: Long): String =
            throw RuntimeException("native crash")
    }

    @Test fun `an unavailable model leaves the text alone`() {
        assertEquals("as it was", GuardedPolisher(Unavailable).apply("as it was"))
    }

    @Test fun `a crashing model leaves the text alone`() {
        assertEquals("as it was", GuardedPolisher(ThrowingPolisher()).apply("as it was"))
    }

    @Test fun `a rejected candidate leaves the text alone`() {
        val polisher = GuardedPolisher(FixedPolisher("Let's meet at 3pm."))
        assertEquals(
            "let's meet in the afternoon",
            polisher.apply("let's meet in the afternoon"),
        )
    }

    @Test fun `an accepted candidate replaces the text`() {
        val polisher = GuardedPolisher(FixedPolisher("We should ship it on Friday."))
        assertEquals(
            "We should ship it on Friday.",
            polisher.apply("we should ship it on friday"),
        )
    }

    /** Polish must be incapable of making blank input worse. */
    @Test fun `blank input is never sent to the model`() {
        assertEquals("", GuardedPolisher(ThrowingPolisher()).apply(""))
    }
}
