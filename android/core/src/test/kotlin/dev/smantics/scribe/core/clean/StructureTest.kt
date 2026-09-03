package dev.smantics.scribe.core.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether Clean mode produces something shaped like writing.
 *
 * The complaint these answer: a long dictation came back as one unbroken wall, and Clean
 * looked barely different from Raw. Commas and capitals are not a visible difference over
 * two minutes of speech; paragraphs, sentences and list items are.
 */
class StructureTest {

    private fun clean(text: String) = CleanPipeline.run(text)
    private fun raw(text: String) = RawPipeline.run(text)

    // ------------------------------------------------------------------ pauses

    /** A measured silence is the best evidence there is for a paragraph break. */
    @Test fun `a pause in the audio becomes a paragraph break`() {
        val spoken = "so that is the plan for the launch${PAUSE_MARKER}now about the budget"
        val out = clean(spoken)
        assertTrue("expected a blank line, got:\n$out", out.contains("\n\n"))
        assertTrue(out.startsWith("So that is the plan"))
        assertTrue(out.contains("Now about the budget"))
    }

    /** Raw must never type the marker — it is an invisible control character. */
    @Test fun `raw strips the pause marker rather than typing it`() {
        val out = raw("first part${PAUSE_MARKER}second part")
        assertEquals("first part second part", out)
        assertFalse(out.contains(PAUSE_MARKER))
    }

    @Test fun `clean never leaves a stray marker behind`() {
        assertFalse(clean("a${PAUSE_MARKER}b${PAUSE_MARKER}c").contains(PAUSE_MARKER))
    }

    // -------------------------------------------------------------- run-ons

    @Test fun `a spoken chain becomes separate sentences`() {
        val out = clean(
            "I went down to the shop and then I picked up the parcel and then I came home",
        )
        assertTrue("expected more than one sentence, got:\n$out", out.count { it == '.' } >= 2)
    }

    /** "and then" joining two short fragments is a conjunction, not a sentence break. */
    @Test fun `a short and then is left alone`() {
        assertEquals("Wait and then go.", clean("wait and then go."))
    }

    // ---------------------------------------------------------- enumerations

    /** Numbering out loud is deliberate; commas are not. */
    @Test fun `a spoken enumeration becomes one item per line`() {
        val out = clean("one, book the room. two, send the invite. three, order lunch")
        assertEquals(
            "1. Book the room.\n2. Send the invite.\n3. Order lunch",
            out,
        )
    }

    @Test fun `an ordinary sentence with commas stays a sentence`() {
        val out = clean("we need milk, eggs and bread")
        assertFalse("a plain list of nouns must not be broken up:\n$out", out.contains("\n"))
    }

    /** Numbers that do not count upward from one are not a list. */
    @Test fun `out of sequence numbers are not an enumeration`() {
        val text = "Two of us went. Three came back."
        assertEquals(text, clean(text))
    }

    // ------------------------------------------------- the visible difference

    /**
     * The point of the whole exercise: over a real dictation, Clean and Raw must not look
     * like the same text.
     */
    @Test fun `clean is visibly different from raw on a long dictation`() {
        val spoken = "um so I wanted to go through the plan for next week and then " +
            "there are three things to sort out${PAUSE_MARKER}one, the venue. two, the " +
            "catering. three, the invitations and then we can send it round"

        val rawOut = raw(spoken)
        val cleanOut = clean(spoken)

        assertFalse("raw should be one block", rawOut.contains("\n"))
        assertTrue("clean should have line breaks:\n$cleanOut", cleanOut.contains("\n"))
        assertTrue("clean should have numbered items:\n$cleanOut", cleanOut.contains("1. "))
        assertTrue("clean should drop the filler", !cleanOut.startsWith("um"))
        assertTrue(
            "clean should be broken into sentences:\n$cleanOut",
            cleanOut.count { it == '.' } >= 3,
        )
    }

    /** Structure is off when asked, for anyone who wants the old behaviour back. */
    @Test fun `structure can be switched off`() {
        val ctx = CleanContext(
            options = CleanOptions(paragraphs = false, splitSentences = false),
        )
        val out = CleanPipeline.run("a plan${PAUSE_MARKER}another plan", ctx)
        assertFalse(out.contains("\n\n"))
        assertFalse(out.contains(PAUSE_MARKER))
    }
}
