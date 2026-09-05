package dev.smantics.scribe.core.typing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Autocorrect, checked on the cases that decide whether it is help or vandalism.
 *
 * The bar is deliberately asymmetric: failing to fix a typo costs the user a backspace, and
 * "fixing" a word they meant costs them a wrong word they may not notice. So most of these
 * assert that it leaves things alone.
 */
class CorrectorTest {

    private val words = WordList.parse(
        // Ranked, commonest first…
        """
        the
        and
        you
        that
        have
        hello
        with
        this
        from
        they
        know
        just
        like
        time
        will
        about
        there
        their
        would
        keyboard

        """.trimIndent() + "\n" +
            // …then merely known, alphabetically.
            listOf(
                "hie", "hell", "helm", "help", "hero", "tea", "ted", "ten", "the", "thee",
                "them", "then", "theory", "thin", "keyboards", "keyed", "yield", "your",
            ).sorted().joinToString("\n"),
    )

    private val corrector = Corrector(words)

    // -------------------------------------------------------- leaving things alone

    @Test fun `a word that is spelled correctly is not touched`() {
        assertNull(corrector.correct("hello"))
        assertNull(corrector.correct("keyboard"))
    }

    @Test fun `a word the user added is never corrected`() {
        val mine = Corrector(words, userWords = setOf("smantics"))
        assertNull(mine.correct("smantics"))
    }

    @Test fun `something very short is left alone`() {
        // Two letters is as likely to be a deliberate abbreviation as a typo.
        assertNull(corrector.correct("hl"))
    }

    @Test fun `anything with a digit in it is not a word and is not ours`() {
        assertNull(corrector.correct("h3llo"))
        assertNull(corrector.correct("2fa"))
    }

    @Test fun `a word too far from anything known is left as typed`() {
        // Better a strange word than a confident wrong one.
        assertNull(corrector.correct("zxqwv"))
    }

    // -------------------------------------------------------------- fixing typos

    @Test fun `a neighbouring-key slip is corrected`() {
        assertEquals("hello", corrector.correct("hellp"))
    }

    @Test fun `a transposition is corrected`() {
        assertEquals("the", corrector.correct("teh"))
    }

    @Test fun `a missing letter is corrected`() {
        assertEquals("hello", corrector.correct("helo"))
    }

    @Test fun `a doubled letter is corrected`() {
        assertEquals("hello", corrector.correct("helllo"))
    }

    /** The commoner of two equally-close words wins, which is nearly always right. */
    @Test fun `frequency breaks the tie`() {
        assertEquals("the", corrector.correct("thw"))
    }

    /** The user's own vocabulary outranks the shipped list. */
    @Test fun `a user word is preferred as a correction`() {
        val mine = Corrector(words, userWords = setOf("smantics"))
        assertEquals("smantics", mine.correct("smantica"))
    }

    // ------------------------------------------------------------------- case

    @Test fun `a capitalised typo comes back capitalised`() {
        assertEquals("Hello", corrector.correct("Helo"))
    }

    @Test fun `a shouted typo comes back shouting`() {
        assertEquals("HELLO", corrector.correct("HELO"))
    }

    // ------------------------------------------------------------ suggestions

    @Test fun `suggestions complete rather than guess`() {
        val suggestions = corrector.suggest("the")
        assertTrue("expected words starting with 'the', got $suggestions", suggestions.isNotEmpty())
        assertTrue(suggestions.all { it.startsWith("the", ignoreCase = true) })
    }

    @Test fun `the word already typed is not offered back`() {
        assertTrue(corrector.suggest("the").none { it == "the" })
    }

    @Test fun `common completions come first`() {
        assertEquals("there", corrector.suggest("ther", limit = 1).first())
    }

    @Test fun `one letter is not yet a prefix worth completing`() {
        assertEquals(emptyList<String>(), corrector.suggest("t"))
    }

    @Test fun `the user's own words are offered first`() {
        val mine = Corrector(words, userWords = setOf("thermodynamics"))
        assertEquals("thermodynamics", mine.suggest("ther", limit = 1).first())
    }

    // ------------------------------------------------------------- the word list

    @Test fun `a malformed list is empty rather than fatal`() {
        // A keyboard must still type when its dictionary is broken.
        val broken = WordList.parse("")
        assertEquals(0, broken.size)
        assertNull(Corrector(broken).correct("helo"))
    }

    @Test fun `ranked words sort before merely known ones`() {
        assertTrue(words.rankOf("the") < words.rankOf("thee"))
        assertEquals(WordList.UNRANKED, words.rankOf("thee"))
    }
}
