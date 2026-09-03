package dev.smantics.scribe.core.clean

import dev.smantics.scribe.core.clean.TextDiff.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDiffTest {

    private fun kinds(raw: String, cleaned: String) =
        TextDiff.diff(raw, cleaned).map { it.kind }

    private fun removed(raw: String, cleaned: String) =
        TextDiff.diff(raw, cleaned).filter { it.kind == Kind.REMOVED }
            .joinToString("|") { it.text.trim() }

    private fun added(raw: String, cleaned: String) =
        TextDiff.diff(raw, cleaned).filter { it.kind == Kind.ADDED }
            .joinToString("|") { it.text.trim() }

    @Test fun `identical text has nothing to show`() {
        assertEquals(listOf(Kind.UNCHANGED), kinds("hello there", "hello there"))
    }

    @Test fun `a dropped filler is marked removed`() {
        assertEquals("um", removed("hey um Sydney", "hey Sydney"))
    }

    @Test fun `a spoken correction shows the whole retracted phrase`() {
        val raw = "still good for Friday I mean Thursday"
        val cleaned = "still good for Thursday"
        assertEquals("Friday I mean", removed(raw, cleaned))
    }

    /** The case from the reference: a time corrected mid-sentence. */
    @Test fun `a corrected time removes the earlier one`() {
        val raw = "around 8:00 actually make that 8:30"
        val cleaned = "around 8:30"
        assertEquals("8:00 actually make that", removed(raw, cleaned))
    }

    @Test fun `punctuation the cleaner added is marked added`() {
        val segments = TextDiff.diff("are you there", "Are you there?")
        assertTrue(segments.any { it.kind == Kind.ADDED || it.text.contains("?") })
        assertEquals("Are you there?", TextDiff.after(segments))
    }

    /** Recasing is not an edit worth striking through — it rides on the unchanged word. */
    @Test fun `a recased word stays unchanged and shows its finished form`() {
        val segments = TextDiff.diff("hey sydney", "Hey Sydney")
        assertTrue(segments.all { it.kind == Kind.UNCHANGED })
        assertEquals("Hey Sydney", TextDiff.after(segments))
    }

    @Test fun `runs are merged so a phrase gets one strikethrough`() {
        val segments = TextDiff.diff("a b c d e", "a e")
        assertEquals(1, segments.count { it.kind == Kind.REMOVED })
        assertEquals("b c d", segments.first { it.kind == Kind.REMOVED }.text.trim())
    }

    @Test fun `before and after reconstruct both readings`() {
        val raw = "hey um Sydney just wanted to check"
        val cleaned = "Hey Sydney, just wanted to check."
        val segments = TextDiff.diff(raw, cleaned)
        assertEquals(cleaned, TextDiff.after(segments))
        assertTrue(TextDiff.before(segments).contains("um"))
    }

    @Test fun `empty sides degrade sensibly`() {
        assertEquals(emptyList<TextDiff.Segment>(), TextDiff.diff("", ""))
        assertEquals(listOf(Kind.REMOVED), kinds("something", ""))
        assertEquals(listOf(Kind.ADDED), kinds("", "something"))
    }

    /** The whole reference utterance, end to end. */
    @Test fun `the reference sentence diffs the way the design shows it`() {
        val raw = "hey um Sydney just wanted to check if you're still good for Friday " +
            "I mean Thursday around 8:00 actually make that 8:30"
        val cleaned = "Hey Sydney, just wanted to check if you're still good for Thursday " +
            "around 8:30?"
        val segments = TextDiff.diff(raw, cleaned)
        val struck = segments.filter { it.kind == Kind.REMOVED }.joinToString(" ") { it.text.trim() }
        assertTrue("expected 'um' struck, got: $struck", struck.contains("um"))
        assertTrue("expected the Friday correction struck", struck.contains("Friday"))
        assertTrue("expected the 8:00 correction struck", struck.contains("8:00"))
        assertEquals(cleaned, TextDiff.after(segments))
    }
}
