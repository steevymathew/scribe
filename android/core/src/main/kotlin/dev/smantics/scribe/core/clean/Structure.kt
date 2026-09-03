package dev.smantics.scribe.core.clean

/**
 * Turning a wall of text into something shaped like writing.
 *
 * This is the stage that was missing. Everything else in Clean mode works at the level of
 * words — a filler dropped, a correction applied, a comma inserted — and none of it stops
 * two minutes of speech arriving as one unbroken block. Nobody writes that way, so nobody
 * wants to read it back, and it was the single loudest difference between what Scribe
 * produced and what a person would have typed.
 *
 * Three things happen here, in order:
 *
 *  1. **Sentences get broken apart.** Long runs joined by "and then", "so", "but" become
 *     separate sentences, because that is what they were when spoken.
 *  2. **Enumerations become lists.** "one, two, three" spoken as items — with or without
 *     the word "list" anywhere — becomes one item per line.
 *  3. **Paragraphs appear.** Where the speaker changed subject, or paused long enough for
 *     the transcriber to mark it, a blank line goes in.
 *
 * Everything here is conservative in the same way the rest of the pipeline is: a rule that
 * cannot tell leaves the text alone. Splitting a sentence wrongly is recoverable and
 * obvious; deleting or reordering words is not, and nothing here does either — the words
 * are exactly the words that went in, only the whitespace changes.
 */

/**
 * A pause long enough to be a paragraph break.
 *
 * The transcriber marks where it heard silence between segments. Around three quarters of
 * a second is a breath; past about one and a half, the speaker has finished a thought.
 */
const val PARAGRAPH_PAUSE_MS = 1_500L

/** The marker the ASR layer uses to record a pause it heard, stripped before display. */
const val PAUSE_MARKER = ""

/**
 * Break a monologue into sentences and paragraphs.
 *
 * Pause markers, if the transcriber supplied any, are authoritative — a measured silence
 * beats any guess made from the words. Where there are none, the discourse markers people
 * actually speak in ("and then", "so anyway", "next") stand in for them.
 */
object Paragraphs : CleanStage {
    override val id = "paragraphs"

    /**
     * Phrases that start a new thought when they open a clause.
     *
     * Kept short on purpose. Each of these is a word people also use mid-sentence, so the
     * rule only fires when one follows a completed sentence, which is the case where the
     * split is safe.
     */
    private val NEW_THOUGHT = listOf(
        "and then", "so then", "so anyway", "anyway", "after that", "next up",
        "meanwhile", "in any case", "on top of that", "also", "by the way",
    )

    private val SENTENCE_END = Regex("""[.!?]["')\]]?\s+""")

    override fun apply(text: String, ctx: CleanContext): String {
        if (!ctx.options.paragraphs) return text.replace(PAUSE_MARKER, " ")

        // A measured silence is better evidence than any word, so it wins outright.
        var out = text.replace(Regex("""\s*$PAUSE_MARKER\s*"""), "\n\n")

        // Then the spoken markers, but only where a sentence has already finished — "and
        // then" in the middle of a clause is a conjunction, not a new paragraph.
        for (marker in NEW_THOUGHT) {
            val pattern = Regex(
                """([.!?])\s+(${Regex.escape(marker)})(?![\p{L}\p{N}])""",
                RegexOption.IGNORE_CASE,
            )
            out = pattern.replace(out) { m ->
                m.groupValues[1] + "\n\n" + m.groupValues[2].replaceFirstChar { it.uppercase() }
            }
        }
        return out
    }

    /** Whether the text already reads as more than one block. */
    fun hasStructure(text: String): Boolean =
        text.contains("\n") || SENTENCE_END.findAll(text).count() >= 2
}

/**
 * Split run-on speech into sentences.
 *
 * People speak in long chains — "I went to the shop and then I picked up the parcel and
 * then I came home" — and a transcriber faithfully renders that as one sentence, because
 * it was one breath. Written down it needs full stops.
 *
 * Only conjunctions that genuinely join two independent clauses are split, and only when
 * both sides are long enough to stand alone. A short "and" is almost always joining a list
 * of nouns, and breaking that would be worse than leaving the run-on.
 */
object SplitRunOns : CleanStage {
    override val id = "run_ons"

    private val JOINERS = listOf("and then", "but then", "so then", "and so")

    /**
     * Below this many words a fragment is not a sentence, whatever joined it.
     *
     * Three, not four: "I came home" is a complete sentence and was being left glued to
     * the clause before it.
     */
    private const val MIN_CLAUSE_WORDS = 3

    override fun apply(text: String, ctx: CleanContext): String {
        if (!ctx.options.splitSentences) return text
        var out = text
        for (joiner in JOINERS) {
            val pattern = Regex(
                """(?<=[\p{L}\p{N}])\s+${Regex.escape(joiner)}\s+""",
                RegexOption.IGNORE_CASE,
            )
            out = buildString {
                var last = 0
                for (m in pattern.findAll(out)) {
                    val before = out.substring(last, m.range.first)
                    val after = out.substring(m.range.last + 1)
                    if (words(before) >= MIN_CLAUSE_WORDS && words(after) >= MIN_CLAUSE_WORDS) {
                        append(before)
                        append(". ")
                        last = m.range.last + 1
                    }
                }
                append(out.substring(last))
            }
        }
        return out
    }

    private fun words(text: String) =
        text.trim().split(Regex("""\s+""")).count { it.isNotBlank() }
}

/**
 * Spoken enumerations, without anyone having to say the word "list".
 *
 * "We need milk, eggs and bread" stays a sentence — that is how it would be written. But
 * "one, milk. two, eggs. three, bread" is somebody reading out a list, and so is a run of
 * items introduced by a colon. Those become one item per line.
 *
 * The distinction is whether the speaker *numbered* the items. Numbering out loud is a
 * deliberate act; commas are not.
 */
object SpokenEnumeration : CleanStage {
    override val id = "enumeration"

    private val SPOKEN_NUMBERS = listOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    )

    /** "one, …" / "two. …" — a spoken number opening an item. */
    private val ITEM = Regex(
        """(^|[.;!?\n])[ \t]*(?<![\p{L}\p{N}])(""" +
            SPOKEN_NUMBERS.joinToString("|") + """|\d{1,2})""" +
            """(?![\p{L}\p{N}])[ \t]*[,.:;-]?[ \t]+""",
        RegexOption.IGNORE_CASE,
    )

    override fun apply(text: String, ctx: CleanContext): String {
        if (!ctx.options.detectLists) return text

        val matches = ITEM.findAll(text).toList()
        if (matches.size < 2) return text

        // Only when the numbers actually count upward from one. "Two of us went, three
        // came back" is not a list, and the sequence is what proves the intent.
        val values = matches.map { numberOf(it.groupValues[2]) }
        if (values != List(values.size) { it + 1 }) return text

        var n = 0
        return ITEM.replace(text) { m ->
            n++
            val boundary = m.groupValues[1]
            when {
                n == 1 -> "$boundary$n. "
                boundary == "\n" -> "\n$n. "
                else -> "$boundary\n$n. "
            }
        }.trim()
    }

    private fun numberOf(token: String): Int =
        token.toIntOrNull() ?: (SPOKEN_NUMBERS.indexOf(token.lowercase()) + 1)
}
