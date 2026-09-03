package dev.smantics.scribe.core.clean

/**
 * The stages ported directly from the desktop `postproc.py`, plus their extensions.
 *
 * Where behaviour differs from the Python original it is because the original had a bug or
 * because this pipeline can now emit newlines (lists, "new paragraph") and the original
 * could not. Each such difference is called out at the point it occurs.
 */

// ---------------------------------------------------------------- annotations

/**
 * Drop Whisper's non-speech annotations.
 *
 * This is the stage the entire silence path depends on. Whisper returns `[BLANK_AUDIO]` for
 * a clip with no speech, and the desktop engine deliberately does *not* gate recording on
 * VAD — an earlier attempt at that dropped real speech on quiet microphones and was
 * reverted. So silence arrives here as text, and leaves here as "".
 *
 * Two deliberate improvements on the Python version, which tested only whether the whole
 * string started and ended with a bracket:
 *
 *  - `"[a] real words [b]"` no longer collapses to "" (it started with `[` and ended with
 *    `]`, so the original emptied it);
 *  - `"(keyboard clacking) yes I agree"` now keeps "yes I agree" instead of keeping the
 *    annotation too.
 *
 * Square brackets are treated as always-annotation because Whisper only emits them for
 * annotations. Parentheses and musical notes are only stripped when their contents actually
 * look like a non-speech marker, so a genuine parenthetical survives.
 */
object StripAnnotations : CleanStage {
    override val id = "strip_annotations"

    private val BRACKETED = Regex("""\[[^\[\]]*]""")
    private val PARENTHESISED = Regex("""\([^()]*\)""")
    private val MUSICAL = Regex("""♪[^♪]*♪""")

    /** Non-speech markers Whisper and the ggml models actually produce. */
    private val NON_SPEECH = Regex(
        """^\s*(?:blank[_ ]?audio|silence|silent|inaudible|unintelligible|no[_ ]speech|""" +
            """music|musical?[_ ]notes?|singing|humming|applause|clapping|laughter|laughs|""" +
            """laughing|sighs?|sighing|coughs?|coughing|breathing|footsteps|typing|""" +
            """keyboard[_ ]?(?:clacking|clicking|typing)?|clicking|clacking|beep(?:ing)?|""" +
            """static|noise|background[_ ]noise|wind|door[_ ]?(?:opens?|closes?)?|""" +
            """phone[_ ]?(?:rings?|ringing)?|speaking[_ ]in[_ ]\w+|foreign|\.{2,})\s*$""",
        RegexOption.IGNORE_CASE,
    )

    override fun apply(text: String, ctx: CleanContext): String {
        var out = BRACKETED.replace(text) { "" }
        out = PARENTHESISED.replace(out) { m ->
            val inner = m.value.substring(1, m.value.length - 1)
            if (NON_SPEECH.containsMatchIn(inner)) "" else m.value
        }
        out = MUSICAL.replace(out) { "" }
        return if (out.isBlank()) "" else out
    }
}

/**
 * Remove the pause markers the transcriber inserted.
 *
 * Clean mode turns them into paragraph breaks; Raw has no use for them and must certainly
 * not type them. They are an invisible control character, so a missed one would be a
 * genuinely baffling bug report.
 */
object StripPauseMarkers : CleanStage {
    override val id = "pause_markers"

    override fun apply(text: String, ctx: CleanContext): String =
        text.replace(Regex("""\s*$PAUSE_MARKER\s*"""), " ")
}

// ------------------------------------------------------------------- spacing

/**
 * Collapse doubled spaces and close the gaps every earlier stage leaves behind.
 *
 * Differs from the Python original in one way that matters: the original stripped
 * whitespace before punctuation with `\s+`, which would swallow a newline. This pipeline
 * emits newlines (lists, "new paragraph"), so only spaces and tabs are collapsed here, and
 * runs of blank lines are capped at one.
 */
object NormalizeSpacing : CleanStage {
    override val id = "normalize_spacing"

    private val HORIZONTAL_RUN = Regex("""[ \t]{2,}""")
    private val SPACE_BEFORE_PUNCT = Regex("""[ \t]+([.,!?;:])""")
    private val SPACE_AROUND_NEWLINE = Regex("""[ \t]*\n[ \t]*""")
    private val EXCESS_BLANK_LINES = Regex("""\n{3,}""")
    private val DOUBLED_PUNCT = Regex("""([,;:])\1+""")
    private val COMMA_THEN_PUNCT = Regex(""",[ \t]*([.,!?;:])""")
    private val OPEN_BRACKET_GAP = Regex("""([(“])[ \t]+""")
    private val CLOSE_BRACKET_GAP = Regex("""[ \t]+([)”])""")
    private val MISSING_SPACE_AFTER = Regex("""([,;:])(?=[\p{L}])""")
    private val MISSING_SPACE_BEFORE_OPEN = Regex("""([\p{L}\p{N}])([(“])""")

    override fun apply(text: String, ctx: CleanContext): String {
        var out = text
        out = HORIZONTAL_RUN.replace(out, " ")
        out = SPACE_AROUND_NEWLINE.replace(out, "\n")
        out = EXCESS_BLANK_LINES.replace(out, "\n\n")
        // "word , ." — the residue of a filler or self-correction removed mid-clause.
        out = COMMA_THEN_PUNCT.replace(out) { it.groupValues[1] }
        out = SPACE_BEFORE_PUNCT.replace(out) { it.groupValues[1] }
        out = DOUBLED_PUNCT.replace(out) { it.groupValues[1] }
        // Spoken punctuation lands the mark hard against its neighbours; reopen the gap.
        out = OPEN_BRACKET_GAP.replace(out) { it.groupValues[1] }
        out = CLOSE_BRACKET_GAP.replace(out) { it.groupValues[1] }
        out = MISSING_SPACE_AFTER.replace(out) { it.groupValues[1] + " " }
        out = MISSING_SPACE_BEFORE_OPEN.replace(out) {
            it.groupValues[1] + " " + it.groupValues[2]
        }
        return out.trim()
    }
}

// ---------------------------------------------------------------- dictionary

/**
 * Replace spoken forms with the user's preferred spellings.
 *
 * Ported verbatim in behaviour from `postproc.apply_dictionary`: keys match
 * case-insensitively on whole words, multi-word keys are supported, and the replacement is
 * used literally so that backslashes, `$` and regex metacharacters in the user's text
 * survive intact.
 *
 * On the desktop this also feeds Whisper's `initial_prompt` to bias recognition itself. The
 * Android engine does the same, but the prompt is fixed when the model loads, so an edit
 * made mid-session reaches this stage immediately and the prompt only on the next load —
 * the same asymmetry the desktop has.
 */
object ApplyDictionary : CleanStage {
    override val id = "dictionary"

    override fun apply(text: String, ctx: CleanContext): String {
        if (!ctx.options.applyDictionary || ctx.dictionary.isEmpty()) return text
        var out = text
        for ((spoken, replacement) in ctx.dictionary) {
            if (spoken.isBlank()) continue
            val pattern = Regex(
                """(?<![\p{L}\p{N}])""" + Regex.escape(spoken) + """(?![\p{L}\p{N}])""",
                RegexOption.IGNORE_CASE,
            )
            out = pattern.replace(out) { replacement }
        }
        return out
    }
}

// ------------------------------------------------------------------ snippets

/**
 * Voice shortcuts: a trigger phrase expands to saved text.
 *
 * Wispr calls these Snippets ("add disclaimer", "insert API link"). Same idea, except the
 * expansions live in a file on the phone rather than in an account.
 *
 * Longest trigger first, so "insert my address" cannot be shadowed by "insert my".
 */
object ExpandSnippets : CleanStage {
    override val id = "snippets"

    override fun apply(text: String, ctx: CleanContext): String {
        if (!ctx.options.expandSnippets || ctx.snippets.isEmpty()) return text
        var out = text
        for ((trigger, expansion) in ctx.snippets.entries.sortedByDescending { it.key.length }) {
            if (trigger.isBlank()) continue
            val pattern = Regex(
                """(?<![\p{L}\p{N}])""" + Regex.escape(trigger) + """(?![\p{L}\p{N}])""",
                RegexOption.IGNORE_CASE,
            )
            out = pattern.replace(out) { expansion }
        }
        return out
    }
}

// ------------------------------------------------------------------- fillers

/**
 * Remove filler words.
 *
 * The core list is the desktop's, unchanged: `um|uh|uhm|erm|er`, whole words only, so
 * "her", "summer" and "error" are never touched. Doubled-letter variants people actually
 * say ("umm", "uhh") and the hesitation sounds "ah"/"hmm" are added.
 *
 * "you know" and "I mean" are *not* in that list. They are behind
 * [CleanOptions.removeVerbalTics] because they are also real phrases — "I mean what I say"
 * must survive. The desktop roadmap makes the same call for the same reason.
 */
object RemoveFillers : CleanStage {
    override val id = "fillers"

    private const val FILLER_WORDS = """u+m+h?|u+h+m?|e+r+m?|a+h+|h+m+|mhm|mm"""
    private const val TIC_WORDS = """you know|i mean|sort of|kind of"""

    private fun bare(words: String) = Regex(
        """(?<![\p{L}\p{N}])(?:$words)(?![\p{L}\p{N}]),?[ \t]*""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A filler set off by commas on both sides takes both commas with it.
     *
     * The desktop version left the opening one behind — "I think, um, we should go" became
     * "I think, we should go" with a comma that now separates nothing. Clean mode's whole
     * job is to produce text you would not edit afterwards, so the comma goes too.
     */
    private fun commaWrapped(words: String) = Regex(
        """[ \t]*,[ \t]*(?:$words)(?![\p{L}\p{N}])[ \t]*,""",
        RegexOption.IGNORE_CASE,
    )

    private val FILLERS = bare(FILLER_WORDS)
    private val FILLERS_WRAPPED = commaWrapped(FILLER_WORDS)
    private val TICS = bare(TIC_WORDS)
    private val TICS_WRAPPED = commaWrapped(TIC_WORDS)

    override fun apply(text: String, ctx: CleanContext): String {
        var out = text
        if (ctx.options.removeFillers) {
            out = FILLERS_WRAPPED.replace(out) { "" }
            out = FILLERS.replace(out) { "" }
        }
        if (ctx.options.removeVerbalTics) {
            out = TICS_WRAPPED.replace(out) { "" }
            out = TICS.replace(out) { "" }
        }
        return out
    }
}

// --------------------------------------------------------------- disfluency

/**
 * Collapse the stutter of ordinary speech: "I I I think" becomes "I think".
 *
 * Only immediately-repeated single words are collapsed, and only when the repetition is not
 * one English actually uses. "I had had enough" and "that that happened" are real; they are
 * on an allow-list and survive. Anything repeated three or more times is always a stutter,
 * allow-list or not, because nobody says "had had had".
 */
object RemoveDisfluencies : CleanStage {
    override val id = "disfluency"

    /** Doubles that are grammatical in English and must not be collapsed. */
    private val LEGITIMATE_DOUBLES = setOf(
        "had", "that", "is", "is-is", "she", "he", "no", "very", "so", "well",
    )

    private val REPEAT = Regex(
        """(?<![\p{L}\p{N}])(\p{L}[\p{L}']*)((?:[ \t]+\1)+)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )

    override fun apply(text: String, ctx: CleanContext): String {
        if (!ctx.options.removeDisfluencies) return text
        return REPEAT.replace(text) { m ->
            val word = m.groupValues[1]
            val repeats = m.groupValues[2].trim().split(Regex("""[ \t]+""")).size
            if (repeats == 1 && word.lowercase() in LEGITIMATE_DOUBLES) m.value else word
        }
    }
}
