package dev.smantics.scribe.core.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests for the Raw and Clean pipelines.
 *
 * The first block ports `tests/test_postproc.py` from the desktop build so the two products
 * cannot silently drift apart. Two ports diverge on purpose and say so.
 */
class CleanPipelineTest {

    private fun clean(text: String, ctx: CleanContext = CleanContext()) =
        CleanPipeline.run(text, ctx)

    private fun raw(text: String) = RawPipeline.run(text)

    // ------------------------------------------------ ported: strip_annotations

    @Test fun `normal text is unchanged`() {
        assertEquals("Hello, world.", StripAnnotations.apply("Hello, world.", CleanContext()))
    }

    @Test fun `blank audio is dropped`() {
        assertEquals("", StripAnnotations.apply("[BLANK_AUDIO]", CleanContext()))
    }

    @Test fun `parenthetical non-speech is dropped`() {
        assertEquals("", StripAnnotations.apply("(keyboard clacking)", CleanContext()))
    }

    @Test fun `music notes are dropped`() {
        assertEquals("", StripAnnotations.apply("♪ humming ♪", CleanContext()))
    }

    @Test fun `repeated blank audio is still empty`() {
        assertEquals("", StripAnnotations.apply("[BLANK_AUDIO] [BLANK_AUDIO]", CleanContext()))
    }

    /**
     * Deliberate divergence from the desktop. The Python version kept "[cough] take two"
     * whole, because it only checked whether the string started and ended with a bracket.
     * Typing a stage direction into someone's message is never what the user wanted.
     */
    @Test fun `annotation mixed with speech keeps only the speech`() {
        assertEquals("Take two", clean("[cough] take two"))
        assertEquals("take two", raw("[cough] take two"))
    }

    /** A genuine parenthetical is speech, not an annotation, and survives. */
    @Test fun `real parenthetical survives`() {
        assertEquals(
            "The build (finally) passed.",
            StripAnnotations.apply("The build (finally) passed.", CleanContext()),
        )
    }

    // ------------------------------------------------------ ported: fillers

    @Test fun `clean text has no fillers removed`() {
        assertEquals("The quick brown fox jumps.", clean("The quick brown fox jumps."))
    }

    @Test fun `leading filler with comma`() {
        assertEquals("Hello there.", clean("Um, hello there."))
    }

    /**
     * Deliberate divergence from the desktop, which produced "I think, we should go." —
     * a comma left behind separating nothing. A filler fenced by commas takes both.
     */
    @Test fun `mid sentence filler takes its commas with it`() {
        assertEquals("I think we should go.", clean("I think, um, we should go."))
    }

    @Test fun `trailing filler`() {
        assertEquals("I will do it.", clean("I will do it, uh."))
    }

    @Test fun `all filler variants`() {
        assertEquals("Done", clean("um uh uhm erm er done"))
    }

    /** The regression the desktop pins: no substring matches, ever. */
    @Test fun `words containing fillers are untouched`() {
        assertEquals(
            "Her summer error costume shudder.",
            clean("Her summer error costume shudder."),
        )
    }

    @Test fun `verbal tics need an explicit opt in`() {
        // Off by default: "you know" and "I mean" are also real phrases.
        assertEquals("It is, you know, fine.", clean("It is, you know, fine."))
        val on = CleanContext(options = CleanOptions(removeVerbalTics = true))
        assertEquals("It is fine.", clean("It is, you know, fine.", on))
    }

    // --------------------------------------------------- ported: dictionary

    @Test fun `dictionary fixes casing`() {
        val ctx = CleanContext(dictionary = mapOf("jira" to "Jira"))
        assertEquals("We track it in Jira now.", clean("we track it in jira now.", ctx))
    }

    @Test fun `dictionary matches whole words only`() {
        val ctx = CleanContext(dictionary = mapOf("jira" to "Jira"))
        assertEquals("Jirafication stays.", clean("jirafication stays.", ctx))
    }

    @Test fun `dictionary handles multi word spoken forms`() {
        val ctx = CleanContext(dictionary = mapOf("postgre sequel" to "PostgreSQL"))
        assertEquals("Ping me on PostgreSQL.", clean("ping me on postgre sequel.", ctx))
    }

    @Test fun `dictionary replacement is literal`() {
        val ctx = CleanContext(dictionary = mapOf("my site" to """C:\www\${'$'}ite"""))
        assertEquals("""See C:\www\${'$'}ite.""", clean("see my site.", ctx))
    }

    /** Runs after sentence casing, so a deliberate lowercase brand is not capitalised. */
    @Test fun `dictionary wins over sentence casing`() {
        val ctx = CleanContext(dictionary = mapOf("iphone" to "iPhone"))
        assertEquals("iPhone is fine.", clean("iphone is fine.", ctx))
    }

    // ------------------------------------------------------ ported: spacing

    @Test fun `double spaces collapse`() {
        assertEquals("a b c", NormalizeSpacing.apply("a  b   c", CleanContext()))
    }

    @Test fun `space before punctuation is removed`() {
        assertEquals("wait, what?", NormalizeSpacing.apply("wait , what ?", CleanContext()))
    }

    // ------------------------------------------------------------- RAW mode

    @Test fun `raw keeps fillers and casing exactly as spoken`() {
        assertEquals("um, i think we should, uh, go", raw("um, i think we should, uh, go"))
    }

    @Test fun `raw still drops a silent clip`() {
        assertEquals("", raw("[BLANK_AUDIO]"))
    }

    @Test fun `raw does not convert spoken punctuation`() {
        assertEquals("add a comma here", raw("add a comma here"))
    }

    // ------------------------------------------------- spoken punctuation

    @Test fun `spoken comma becomes a mark`() {
        assertEquals("Hello, world.", clean("hello comma world period"))
    }

    @Test fun `new paragraph becomes a blank line`() {
        assertEquals(
            "Hello there.\n\nGoodbye.",
            clean("hello there period new paragraph goodbye period"),
        )
    }

    /** "a period of time" is speech, not a command. */
    @Test fun `period after a determiner stays a word`() {
        assertEquals("It took a period of time.", clean("it took a period of time."))
    }

    @Test fun `question mark is unambiguous`() {
        assertEquals("Are you there?", clean("are you there question mark"))
    }

    @Test fun `parentheses get their spacing back`() {
        assertEquals("The build (finally) passed.", clean("the build open paren finally close paren passed period"))
    }

    // -------------------------------------------------- self-correction

    @Test fun `time correction replaces the earlier time`() {
        assertEquals("Let's meet at 3pm.", clean("let's meet at 4pm, actually 3pm."))
    }

    @Test fun `name correction replaces the earlier name`() {
        assertEquals("Send it to Jane.", clean("Send it to John, I mean Jane."))
    }

    @Test fun `number correction replaces the earlier number`() {
        assertEquals("We need 12 chairs.", clean("we need 20 chairs, no wait 12 chairs."))
            .also { }
    }

    /** "actually" as an ordinary adverb must never delete anything. */
    @Test fun `adverbial actually is left alone`() {
        assertEquals("I actually agree with that.", clean("I actually agree with that."))
    }

    /** No earlier token of the same kind means no confident correction, so no edit. */
    @Test fun `correction with nothing to correct is left alone`() {
        assertEquals("Actually Tuesday works.", clean("actually Tuesday works."))
    }

    @Test fun `scratch that erases the clause before it`() {
        assertEquals("Send it Tuesday.", clean("Book the room for Monday. Scratch that. Send it Tuesday."))
    }

    // -------------------------------------------------------- disfluency

    @Test fun `stutter collapses`() {
        assertEquals("I think so.", clean("I I I think so."))
    }

    @Test fun `legitimate double survives`() {
        assertEquals("I had had enough.", clean("I had had enough."))
    }

    @Test fun `triple of a legitimate double is still a stutter`() {
        assertEquals("I had enough.", clean("I had had had enough."))
    }

    // ------------------------------------------------------------ numbers

    @Test fun `split meridiem is joined`() {
        assertEquals("See you at 4pm tomorrow.", clean("see you at 4 p. m. tomorrow."))
    }

    /** The abbreviation's dot doubles as the full stop at the end of a sentence. */
    @Test fun `sentence final meridiem keeps its full stop`() {
        assertEquals("See you at 4pm.", clean("see you at 4 p.m."))
    }

    @Test fun `spelled percent becomes a symbol`() {
        assertEquals("About 20% done.", clean("about twenty percent done."))
    }

    @Test fun `spelled currency becomes a symbol`() {
        assertEquals("It cost ${'$'}50.", clean("it cost fifty dollars."))
    }

    /** General word-to-digit conversion is not done; this must stay English. */
    @Test fun `ordinary number words are left alone`() {
        assertEquals("One of the reasons is clear.", clean("one of the reasons is clear."))
    }

    // -------------------------------------------------------------- lists

    @Test fun `spoken ordinals become a numbered list`() {
        val out = clean("first, book the room. second, send the invite. third, order lunch.")
        assertEquals("1. Book the room.\n2. Send the invite.\n3. Order lunch.", out)
    }

    /** One ordinal is a sentence opener, not a list. */
    @Test fun `a single ordinal is not a list`() {
        assertEquals("First we should talk about it.", clean("first we should talk about it."))
    }

    @Test fun `out of order ordinals are not a list`() {
        val text = "Second thoughts came first for me."
        assertEquals(text, clean(text))
    }

    /**
     * The form people actually use. Formatting a spoken list was one of the things that
     * seemed to need a cloud model; said explicitly, it is a rule.
     */
    @Test fun `an explicit list instruction formats what follows`() {
        assertEquals(
            "- Eggs\n- Milk\n- Bread",
            clean("bullet list eggs, milk, bread"),
        )
        assertEquals(
            "1. Book the room\n2. Send the invite\n3. Order lunch",
            clean("make a numbered list book the room, send the invite, order lunch"),
        )
    }

    @Test fun `number one and number two make a list`() {
        assertEquals(
            "1. Call the bank.\n2. Cancel the card",
            clean("number one call the bank. number two cancel the card"),
        )
    }

    /** One item is not a list, and must not be mangled into one. */
    @Test fun `a list instruction with a single item is left alone`() {
        assertTrue(clean("bullet list eggs").contains("eggs"))
    }

    @Test fun `spoken bullets become a bulleted list`() {
        val out = clean("bullet point milk bullet point eggs bullet point bread")
        assertEquals("- Milk\n- Eggs\n- Bread", out)
    }

    // ------------------------------------------------------------- tone

    @Test fun `formal tone finishes the sentence`() {
        val ctx = CleanContext(tone = ToneProfile.FORMAL)
        assertEquals("Thanks for the update.", clean("thanks for the update", ctx))
    }

    @Test fun `casual tone leaves a fragment unpunctuated`() {
        val ctx = CleanContext(tone = ToneProfile.CASUAL)
        assertEquals("On my way", clean("on my way", ctx))
    }

    // --------------------------------------------------------- snippets

    @Test fun `snippet expands`() {
        val ctx = CleanContext(snippets = mapOf("my address" to "12 Rue Example, Paris"))
        assertEquals("Send it to 12 Rue Example, Paris.", clean("send it to my address.", ctx))
    }

    @Test fun `longest snippet trigger wins`() {
        val ctx = CleanContext(
            snippets = mapOf("insert" to "X", "insert the disclaimer" to "All views are my own"),
        )
        assertEquals("All views are my own.", clean("insert the disclaimer.", ctx))
    }

    // ------------------------------------------------------- end to end

    @Test fun `full pipeline order matches the desktop golden`() {
        val ctx = CleanContext(dictionary = mapOf("jira" to "Jira"))
        assertEquals("File the Jira ticket, please.", clean("um, file the jira ticket , please.", ctx))
    }

    @Test fun `empty input stays empty in both modes`() {
        assertEquals("", clean(""))
        assertEquals("", raw(""))
    }

    @Test fun `a realistic utterance`() {
        val spoken = "um so i think we should ship it on friday comma actually thursday comma " +
            "and uh tell the team question mark"
        assertEquals("So I think we should ship it on Thursday, and tell the team?", clean(spoken))
    }

    // --------------------------------------------------------- invariants

    /** Clean must never be able to produce more words than were spoken. */
    @Test fun `clean never adds words`() {
        val samples = listOf(
            "hello comma world period",
            "um i i think so",
            "let's meet at 4pm, actually 3pm",
            "first, one. second, two.",
            "[BLANK_AUDIO]",
            "",
        )
        for (s in samples) {
            val before = s.split(Regex("""\s+""")).count { it.isNotBlank() }
            val after = clean(s).split(Regex("""\s+""")).count { it.isNotBlank() }
            assertTrue("clean grew '$s' from $before to $after words", after <= before)
        }
    }

    /** Every stage must be a no-op on empty input, or the fold below it breaks. */
    @Test fun `every stage tolerates empty input`() {
        for (stage in CleanPipeline.stages) {
            assertEquals("stage ${stage.id} on empty", "", stage.apply("", CleanContext()).trim())
        }
    }
}
