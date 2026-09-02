package dev.smantics.scribe.core.clean

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Clean mode may change how text reads. It may never change what it says.
 *
 * Every case here is a real defect found by dictating ordinary English at the pipeline and
 * reading what came back. They are pinned because the failure mode is quiet: the sentence
 * still looks like a sentence, and the user has no reason to re-read it.
 *
 * The bias these encode: **a missed command is recoverable — the user says it again —
 * while a corrupted sentence may never be noticed.** When a rule cannot be sure, it does
 * nothing.
 */
class MeaningPreservedTest {

    private fun clean(text: String) = CleanPipeline.run(text)

    // ------------------------------- punctuation words used as ordinary words

    /** Was: "She said. Drama is her favourite" — a sentence split in half. */
    @Test fun `period after a speech verb is the word, not a full stop`() {
        assertEquals(
            "She said period drama is her favourite",
            clean("she said period drama is her favourite"),
        )
    }

    /** Was: "Give me a colon and a;" */
    @Test fun `colon and semicolon after an article are the words`() {
        assertEquals(
            "Give me a colon and a semicolon",
            clean("give me a colon and a semicolon"),
        )
    }

    /** Was: "Put a, here and a. There" */
    @Test fun `comma and full stop after an article are the words`() {
        assertEquals(
            "Put a comma here and a full stop there",
            clean("put a comma here and a full stop there"),
        )
    }

    /** Was: "We need to/ the budget" */
    @Test fun `slash after an infinitive marker is the verb`() {
        assertEquals("We need to slash the budget", clean("we need to slash the budget"))
    }

    /** Was: "He lives on— Street" */
    @Test fun `dash in a street name is not a dash`() {
        assertEquals("He lives on Dash Street", clean("he lives on Dash Street"))
    }

    @Test fun `period of time survives`() {
        assertEquals(
            "I'll be there in a period of about ten minutes",
            clean("I'll be there in a period of about ten minutes"),
        )
    }

    @Test fun `the commands still work where they are commands`() {
        assertEquals("Hello, world.", clean("hello comma world period"))
        assertEquals("Are you there?", clean("are you there question mark"))
        assertEquals("One; two.", clean("one semicolon two period"))
    }

    // ----------------------------------------------------------- money and numbers

    /** Was: "Send the invoice for two $100" — a wrong amount, stated confidently. */
    @Test fun `a multi-word amount is read as one number`() {
        assertEquals(
            "Send the invoice for ${'$'}200",
            clean("send the invoice for two hundred dollars"),
        )
    }

    @Test fun `compound numbers parse correctly`() {
        assertEquals("It came to ${'$'}250.", clean("it came to two hundred and fifty dollars."))
        assertEquals("About 25% done.", clean("about twenty five percent done."))
        assertEquals("Roughly £1500.", clean("roughly one thousand five hundred pounds."))
    }

    /** A number the parser cannot read must be left exactly as it was. */
    @Test fun `an unparseable amount is left alone rather than guessed`() {
        assertEquals(
            "Send the invoice for umpteen dollars",
            clean("send the invoice for umpteen dollars"),
        )
    }

    @Test fun `ordinary number words are not digitised`() {
        assertEquals("Call me at four thirty", clean("call me at four thirty"))
        assertEquals("It is a quarter past nine", clean("it is a quarter past nine"))
        assertEquals("She is 5 foot 4", clean("she is 5 foot 4"))
    }

    // ------------------------------------------------------------ self-correction

    @Test fun `adverbial actually never deletes anything`() {
        assertEquals(
            "Let me be clear, I actually agree with you completely",
            clean("let me be clear, I actually agree with you completely"),
        )
        assertEquals("I actually think we should wait", clean("I actually think we should wait"))
    }

    @Test fun `a correction with no matching antecedent changes nothing`() {
        assertEquals("Sorry I mean the other one", clean("sorry I mean the other one"))
    }

    @Test fun `a real correction replaces only the corrected item`() {
        assertEquals(
            "The review is due Wednesday, and the draft Friday",
            clean("the review is due Monday, actually Wednesday, and the draft Friday"),
        )
    }

    // ------------------------------------------------------------------ ordinals

    @Test fun `quarters and firsts in prose are not a list`() {
        assertEquals(
            "The first quarter was strong and the second quarter was weak",
            clean("the first quarter was strong and the second quarter was weak"),
        )
        assertEquals("First of all I want to say thanks", clean("first of all I want to say thanks"))
    }

    // ------------------------------------------------------------------ doubles

    @Test fun `grammatical doubles survive`() {
        assertEquals("I had had enough of that that day", clean("I had had enough of that that day"))
        assertEquals("I think that that approach works", clean("I think that that approach works"))
    }
}
