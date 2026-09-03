package dev.smantics.scribe.core.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bubble writes a field's *whole* contents back, so every one of these is a case where
 * getting it wrong destroys text the user typed themselves.
 */
class TextSpliceTest {

    @Test fun `an empty field takes the transcript as it is`() {
        val result = TextSplice.into("", 0, 0, "hello there")
        assertEquals("hello there", result.text)
        assertEquals(11, result.caret)
    }

    @Test fun `text already in the field survives an insertion in the middle`() {
        val result = TextSplice.into("see you on and thanks", 11, 11, "Tuesday")
        assertEquals("see you on Tuesday and thanks", result.text)
    }

    @Test fun `the word after the cursor is not welded onto the transcript`() {
        val result = TextSplice.into("hello world", 6, 6, "there")
        assertEquals("hello there world", result.text)
    }

    @Test fun `no space is added before punctuation that follows the cursor`() {
        val result = TextSplice.into("hello .", 6, 6, "there")
        assertEquals("hello there.", result.text)
    }

    @Test fun `the cursor lands after the words just inserted, not at the end`() {
        val result = TextSplice.into("see you on and thanks", 11, 11, "Tuesday")
        assertEquals("see you on Tuesday".length, result.caret)
    }

    @Test fun `a selection is replaced rather than pushed aside`() {
        val result = TextSplice.into("see you on Monday", 11, 17, "Tuesday")
        assertEquals("see you on Tuesday", result.text)
    }

    @Test fun `dictating twice does not run the sentences together`() {
        val result = TextSplice.into("First one.", 10, 10, "Second one.")
        assertEquals("First one. Second one.", result.text)
    }

    @Test fun `no space is added where there is already one`() {
        val result = TextSplice.into("First one. ", 11, 11, "Second one.")
        assertEquals("First one. Second one.", result.text)
    }

    @Test fun `no space is added at the start of a line`() {
        val result = TextSplice.into("First one.\n", 11, 11, "Second one.")
        assertEquals("First one.\nSecond one.", result.text)
    }

    /**
     * Fields report -1 for "no selection", and some report a stale position from before
     * their last edit. Both used to be able to throw out of the middle of an insertion.
     */
    @Test fun `an unreported selection lands at the start rather than throwing`() {
        val result = TextSplice.into("half a message", -1, -1, "more")
        assertEquals("more half a message", result.text)
    }

    @Test fun `a selection past the end of the field is clamped`() {
        val result = TextSplice.into("short", 99, 120, "more")
        assertEquals("short more", result.text)
    }

    @Test fun `a reversed selection is read the right way round`() {
        val result = TextSplice.into("see you on Monday", 17, 11, "Tuesday")
        assertEquals("see you on Tuesday", result.text)
    }
}
