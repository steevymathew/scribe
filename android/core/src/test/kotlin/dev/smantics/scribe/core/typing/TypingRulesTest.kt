package dev.smantics.scribe.core.typing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The conventions everybody expects, and the cases where following them would be wrong. */
class TypingRulesTest {

    // ------------------------------------------------------------ capitalisation

    @Test fun `the first letter of an empty field is a capital`() {
        assertTrue(TypingRules.shouldCapitalise(""))
    }

    @Test fun `the letter after a full stop and a space is a capital`() {
        assertTrue(TypingRules.shouldCapitalise("Yes. "))
        assertTrue(TypingRules.shouldCapitalise("Really? "))
        assertTrue(TypingRules.shouldCapitalise("Stop! "))
    }

    /** The capital arrives with the next word, not the instant a full stop is typed. */
    @Test fun `a full stop with no space yet does not capitalise`() {
        assertFalse(TypingRules.shouldCapitalise("Yes."))
    }

    @Test fun `mid-sentence is not capitalised`() {
        assertFalse(TypingRules.shouldCapitalise("hello "))
        assertFalse(TypingRules.shouldCapitalise("hello, "))
    }

    // -------------------------------------------------------------- double space

    @Test fun `two spaces after a word become a full stop`() {
        assertEquals(
            TypingRules.Outcome.Replace(deleteBefore = 1, insert = ". "),
            TypingRules.doubleSpace("hello "),
        )
    }

    @Test fun `two spaces after punctuation are left alone`() {
        assertEquals(TypingRules.Outcome.AsTyped, TypingRules.doubleSpace("hello. "))
    }

    @Test fun `a space at the start of a field is left alone`() {
        assertEquals(TypingRules.Outcome.AsTyped, TypingRules.doubleSpace(" "))
    }

    // --------------------------------------------------------------- punctuation

    @Test fun `a comma after a space moves back against the word`() {
        assertEquals(
            TypingRules.Outcome.Replace(deleteBefore = 1, insert = ","),
            TypingRules.tidyPunctuation("hello ", ","),
        )
    }

    @Test fun `a letter after a space is not punctuation and is left alone`() {
        assertEquals(TypingRules.Outcome.AsTyped, TypingRules.tidyPunctuation("hello ", "a"))
    }

    /** Two spaces is a deliberate gap, not a stray one to tidy up. */
    @Test fun `punctuation after two spaces is left alone`() {
        assertEquals(TypingRules.Outcome.AsTyped, TypingRules.tidyPunctuation("hello  ", ","))
    }

    @Test fun `punctuation at the start of a field is left alone`() {
        assertEquals(TypingRules.Outcome.AsTyped, TypingRules.tidyPunctuation(" ", ","))
    }

    // -------------------------------------------------------------- word before

    @Test fun `the word before the cursor is the letters that touch it`() {
        assertEquals("hello", TypingRules.wordBefore("well hello"))
    }

    @Test fun `there is no word before a space`() {
        assertEquals("", TypingRules.wordBefore("hello "))
    }

    @Test fun `an empty field has no word before it`() {
        assertEquals("", TypingRules.wordBefore(""))
    }

    /** Stopping at the apostrophe is deliberate — it is how `don't` survives autocorrect. */
    @Test fun `the word stops at an apostrophe`() {
        assertEquals("t", TypingRules.wordBefore("don't"))
    }
}
