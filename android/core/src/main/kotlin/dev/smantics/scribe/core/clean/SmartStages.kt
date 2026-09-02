package dev.smantics.scribe.core.clean

/**
 * The stages that close the Wispr Flow feature gap: spoken punctuation, spoken
 * self-correction, number and list formatting, and sentence casing.
 *
 * All of them are rules. None of them invent words. Where a rule cannot be confident it
 * leaves the text exactly as it found it — a dictation tool that guesses wrong is worse
 * than one that does nothing, because the user has to read every output to find out which
 * happened.
 */

// -------------------------------------------------------- spoken punctuation

/**
 * Turn spoken punctuation commands into marks: "comma", "new paragraph", "open quote".
 *
 * Some of these words are also ordinary English. "A period of time" and "the dash to the
 * door" must survive, so the ambiguous ones ([AMBIGUOUS]) are only converted when they are
 * not preceded by a determiner and not followed by "of". The unambiguous ones — nobody says
 * "exclamation mark" in the course of a sentence — convert unconditionally.
 */
object SpokenPunctuation : CleanStage {
    override val id = "spoken_punctuation"

    /** Spoken form to replacement. Longest phrases first so "full stop" beats "stop". */
    private val MARKS: List<Pair<String, String>> = listOf(
        "new paragraph" to "\n\n",
        "new line" to "\n",
        "newline" to "\n",
        "line break" to "\n",
        "exclamation mark" to "!",
        "exclamation point" to "!",
        "question mark" to "?",
        "full stop" to ".",
        "open parenthesis" to "(",
        "close parenthesis" to ")",
        "open paren" to "(",
        "close paren" to ")",
        "open bracket" to "(",
        "close bracket" to ")",
        "open quote" to "“",
        "close quote" to "”",
        "unquote" to "”",
        "end quote" to "”",
        "semicolon" to ";",
        "semi colon" to ";",
        "ellipsis" to "…",
        "dot dot dot" to "…",
        "ampersand" to "&",
        "asterisk" to "*",
        "hashtag" to "#",
        "percent sign" to "%",
        "dollar sign" to "$",
        "at sign" to "@",
        "comma" to ",",
        "hyphen" to "-",
    )

    /** Real words as often as they are commands; converted only under the guard below. */
    private val AMBIGUOUS: List<Pair<String, String>> = listOf(
        "period" to ".",
        "colon" to ":",
        "dash" to "—",
        "quote" to "“",
        "slash" to "/",
        "hash" to "#",
    )

    private val DETERMINERS = setOf(
        "a", "an", "the", "this", "that", "each", "per", "one", "any", "some", "no",
        "my", "your", "his", "her", "its", "our", "their", "every", "another",
    )

    private fun literal(phrase: String) = Regex(
        """[ \t]*(?<![\p{L}\p{N}])""" + Regex.escape(phrase) + """(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )

    override fun apply(text: String, ctx: CleanContext): String {
        if (!ctx.options.spokenPunctuation) return text
        var out = text
        for ((phrase, mark) in MARKS) {
            out = literal(phrase).replace(out) { mark }
        }
        for ((phrase, mark) in AMBIGUOUS) {
            out = literal(phrase).replace(out) { m ->
                if (isCommand(out, m.range, phrase)) mark else m.value
            }
        }
        return out
    }

    /** A determiner before it, or "of" after it, means the user said the noun. */
    private fun isCommand(text: String, range: IntRange, phrase: String): Boolean {
        val before = text.substring(0, range.first).trimEnd()
        val lastWord = before.takeLastWhile { it.isLetter() || it == '\'' }.lowercase()
        if (lastWord in DETERMINERS) return false
        val after = text.substring(minOf(range.last + 1, text.length)).trimStart()
        val nextWord = after.takeWhile { it.isLetter() }.lowercase()
        if (nextWord == "of") return false
        // A command needs something to attach to; a bare opener is almost always the noun.
        return before.isNotEmpty()
    }
}

// --------------------------------------------------------- self-correction

/**
 * Apply corrections the speaker made out loud.
 *
 * "Let's meet at 4pm, actually 3pm" becomes "Let's meet at 3pm". This is the single feature
 * people quote when they describe why Wispr Flow feels different from ordinary dictation,
 * and it needs no model to do — only the observation that a correction replaces something
 * of the same *kind* as itself.
 *
 * The algorithm: find a correction cue; classify the first token after it; scan backwards
 * for the nearest earlier token of the same class; delete from there through the cue.
 *
 * If no earlier token of that class exists, nothing is removed. That is the important case:
 * "actually" is a common ordinary adverb ("I actually agree"), and the cost of being wrong
 * here is deleting words the user said. Silence beats damage.
 */
object SelfCorrection : CleanStage {
    override val id = "self_correction"

    private val CUES = listOf(
        "no wait", "or rather", "i mean", "actually", "rather", "sorry",
    )

    /** Cues that discard the whole clause before them rather than one token. */
    private val ERASE_CUES = listOf("scratch that", "delete that", "strike that", "ignore that")

    private val TOKEN = Regex("""[\p{L}\p{N}][\p{L}\p{N}'.:$%-]*""")

    private enum class Kind { TIME, MONEY, NUMBER, WEEKDAY, MONTH, PROPER, WORD }

    private val WEEKDAYS = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    )
    private val MONTHS = setOf(
        "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december",
    )

    private fun classify(token: String): Kind {
        val lower = token.lowercase().trimEnd('.', ',')
        return when {
            Regex("""^\d{1,2}([:.]\d{2})?\s*(am|pm)$""", RegexOption.IGNORE_CASE).matches(lower) -> Kind.TIME
            Regex("""^\d{1,2}:\d{2}$""").matches(lower) -> Kind.TIME
            Regex("""^[$£€]\d""").containsMatchIn(lower) -> Kind.MONEY
            Regex("""^\d+([.,]\d+)?%?$""").matches(lower) -> Kind.NUMBER
            lower in WEEKDAYS -> Kind.WEEKDAY
            lower in MONTHS -> Kind.MONTH
            token.first().isUpperCase() -> Kind.PROPER
            else -> Kind.WORD
        }
    }

    override fun apply(text: String, ctx: CleanContext): String {
        if (!ctx.options.selfCorrection) return text
        var out = text
        for (cue in ERASE_CUES) {
            out = eraseClauseBefore(out, cue)
        }
        for (cue in CUES) {
            out = correct(out, cue)
        }
        return out
    }

    /**
     * "Book the room for Monday. Scratch that. Send it Tuesday" keeps only the last part.
     *
     * The cue discards the sentence *before* it, not merely the words on its own side of
     * the nearest full stop — "scratch that" is always about what was just said.
     */
    private fun eraseClauseBefore(text: String, cue: String): String {
        val re = Regex(
            """(?<![\p{L}\p{N}])""" + Regex.escape(cue) + """(?![\p{L}\p{N}])[\s,.!?]*""",
            RegexOption.IGNORE_CASE,
        )
        val m = re.find(text) ?: return text
        // Drop the terminator of the sentence being retracted, then cut back to the end of
        // the one before it.
        val head = text.substring(0, m.range.first).trimEnd().trimEnd('.', '!', '?')
        val clauseStart = head.lastIndexOfAny(charArrayOf('.', '!', '?', '\n')).let {
            if (it < 0) 0 else it + 1
        }
        return (text.substring(0, clauseStart) + text.substring(m.range.last + 1)).trim()
    }

    private fun correct(text: String, cue: String): String {
        val re = Regex(
            """[ \t]*,?[ \t]*(?<![\p{L}\p{N}])""" + Regex.escape(cue) +
                """(?![\p{L}\p{N}])[ \t]*,?[ \t]*""",
            RegexOption.IGNORE_CASE,
        )
        var out = text
        var searchFrom = 0
        while (true) {
            val m = re.find(out, searchFrom) ?: return out
            val replacement = TOKEN.find(out, m.range.last + 1)
            if (replacement == null) {
                searchFrom = m.range.last + 1
                continue
            }
            val kind = classify(replacement.value)
            // A correction that replaces an ordinary word is indistinguishable from the
            // adverb "actually". Only act when the replacement is something specific.
            if (kind == Kind.WORD) {
                searchFrom = m.range.last + 1
                continue
            }
            val head = out.substring(0, m.range.first)
            val target = TOKEN.findAll(head).lastOrNull { classify(it.value) == kind }
            if (target == null) {
                searchFrom = m.range.last + 1
                continue
            }
            out = out.substring(0, target.range.first) + out.substring(m.range.last + 1)
            searchFrom = target.range.first
        }
    }
}

// ------------------------------------------------------------------ numbers

/**
 * Format numbers the way people expect to see them written.
 *
 * Deliberately narrow. Whisper already writes most digits as digits, so this only fixes the
 * cases it reliably gets wrong: split-out meridiems ("4 p. m."), spelled numbers directly
 * attached to a unit, and currency and percent read aloud as words. General
 * word-to-digit conversion is *not* done — "one of the reasons" must not become
 * "1 of the reasons".
 */
object FormatNumbers : CleanStage {
    override val id = "numbers"

    private val WORD_NUMBERS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90, "hundred" to 100,
    )

    /**
     * The trailing dot of "p.m." is the abbreviation's own — but at the end of a sentence
     * it is doing double duty as the full stop. It is consumed here and put back by
     * [restoreTerminator] when the match sits at a sentence boundary, so
     * "see you at 4 p.m." keeps its stop and "at 4 p. m. tomorrow" does not gain one.
     */
    private val MERIDIEM = Regex(
        """(\d{1,2}(?::\d{2})?)[ \t]*([ap])[ \t]*\.?[ \t]*m[ \t]*(\.?)(?![\p{L}])""",
        RegexOption.IGNORE_CASE,
    )
    private val OCLOCK = Regex(
        """(?<![\p{L}\p{N}])(\p{L}+)[ \t]+o'?clock(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )
    private val SPELLED_MERIDIEM = Regex(
        """(?<![\p{L}\p{N}])(\p{L}+)[ \t]+([ap])[ \t]*\.?[ \t]*m[ \t]*(\.?)(?![\p{L}])""",
        RegexOption.IGNORE_CASE,
    )
    private val PERCENT = Regex(
        """(?<![\p{L}\p{N}])(\d+(?:\.\d+)?|\p{L}+)[ \t]+per[ \t]?cent(?:age)?(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )
    private val CURRENCY = Regex(
        """(?<![\p{L}\p{N}])(\d+(?:\.\d+)?|\p{L}+)[ \t]+(dollars?|pounds?|euros?)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )

    private fun digits(token: String): String? =
        token.toIntOrNull()?.toString() ?: WORD_NUMBERS[token.lowercase()]?.toString()

    /**
     * Whether a dot swallowed at [endExclusive] was also ending a sentence, and so must be
     * written back. It was if nothing but whitespace follows, or the next word starts with
     * a capital.
     */
    private fun restoreTerminator(text: String, endExclusive: Int, hadDot: Boolean): String {
        if (!hadDot) return ""
        val rest = text.substring(endExclusive)
        val next = rest.trimStart()
        return if (next.isEmpty() || next.first().isUpperCase()) "." else ""
    }

    override fun apply(text: String, ctx: CleanContext): String {
        if (!ctx.options.formatNumbers) return text
        var out = text
        out = SPELLED_MERIDIEM.replace(out) { m ->
            val n = digits(m.groupValues[1]) ?: return@replace m.value
            val dot = restoreTerminator(out, m.range.last + 1, m.groupValues[3] == ".")
            n + m.groupValues[2].lowercase() + "m" + dot
        }
        out = MERIDIEM.replace(out) { m ->
            val dot = restoreTerminator(out, m.range.last + 1, m.groupValues[3] == ".")
            m.groupValues[1] + m.groupValues[2].lowercase() + "m" + dot
        }
        out = OCLOCK.replace(out) { m ->
            val n = digits(m.groupValues[1]) ?: return@replace m.value
            "$n o'clock"
        }
        out = PERCENT.replace(out) { m ->
            val n = digits(m.groupValues[1]) ?: return@replace m.value
            "$n%"
        }
        out = CURRENCY.replace(out) { m ->
            val n = digits(m.groupValues[1]) ?: return@replace m.value
            val symbol = when (m.groupValues[2].lowercase().trimEnd('s')) {
                "pound" -> "£"
                "euro" -> "€"
                else -> "$"
            }
            symbol + n
        }
        return out
    }
}

// -------------------------------------------------------------------- lists

/**
 * Turn a spoken list into a written one.
 *
 * Wispr's "dictate multiple items and it formats them into a proper list". Two forms are
 * recognised: explicit ordinals ("first… second… third…") and explicit bullets ("bullet
 * point X"). At least two markers are required, because a sentence that merely opens with
 * "First," is not a list.
 */
object DetectLists : CleanStage {
    override val id = "lists"

    private val ORDINALS = listOf(
        "first", "second", "third", "fourth", "fifth",
        "sixth", "seventh", "eighth", "ninth", "tenth",
    )

    private val BULLET = Regex(
        """(?<![\p{L}\p{N}])(?:bullet point|bullet|new bullet|dash point)(?![\p{L}\p{N}])[ \t]*,?[ \t]*""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The sentence boundary before a marker is captured rather than consumed — eating it
     * would strip the full stop off the previous list item.
     */
    private val ORDINAL_MARKER = Regex(
        """(^|[.;!?\n])[ \t]*(?<![\p{L}\p{N}])(""" +
            ORDINALS.joinToString("|") + """)(?:ly)?(?![\p{L}\p{N}])[ \t]*,?[ \t]*""",
        RegexOption.IGNORE_CASE,
    )

    override fun apply(text: String, ctx: CleanContext): String {
        if (!ctx.options.detectLists) return text
        var out = text

        val bullets = BULLET.findAll(out).count()
        if (bullets >= 2) {
            out = BULLET.replace(out) { "\n- " }
        }

        val markers = ORDINAL_MARKER.findAll(out).toList()
        if (markers.size >= 2) {
            // Only renumber when the ordinals actually run in sequence from "first";
            // "second thoughts… third parties" is not a list.
            val indices = markers.map { ORDINALS.indexOf(it.groupValues[2].lowercase()) }
            if (indices == indices.indices.toList()) {
                var n = 0
                out = ORDINAL_MARKER.replace(out) { m ->
                    n++
                    val boundary = m.groupValues[1]
                    when {
                        n == 1 -> "$boundary$n. "
                        boundary == "\n" -> "\n$n. "
                        else -> "$boundary\n$n. "
                    }
                }
            }
        }
        return out.trim()
    }
}

// ------------------------------------------------------------ sentence case

/**
 * Capitalise sentences and the pronoun "I", and finish the text according to [ToneProfile].
 *
 * Whisper's own casing is usually good, so this mostly repairs what earlier stages broke:
 * removing a filler from the head of a sentence leaves a lowercase word where a capital
 * belongs.
 *
 * Only [ToneProfile.FORMAL] adds a missing full stop. In a chat box a trailing full stop
 * changes the tone of the message, which is the sort of "helpful" edit that makes people
 * stop trusting a dictation tool.
 */
object SentenceCase : CleanStage {
    override val id = "sentence_case"

    private val SENTENCE_START = Regex("""(^|[.!?]\s+|\n\s*|^\s*-\s*|\n-\s*)(\p{Ll})""")
    private val LONE_I = Regex("""(?<![\p{L}\p{N}'])i(?![\p{L}\p{N}])""")
    private val I_CONTRACTION = Regex("""(?<![\p{L}\p{N}'])i('(?:m|ll|ve|d))(?![\p{L}])""",
        RegexOption.IGNORE_CASE)

    /**
     * Weekdays only — not months. "May", "March" and "August" are all ordinary English
     * words as well as months, and capitalising "we may ship it" is a worse error than
     * leaving a lowercase month alone. No weekday has that problem.
     */
    private val WEEKDAY = Regex(
        """(?<![\p{L}\p{N}])(monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )

    override fun apply(text: String, ctx: CleanContext): String {
        if (!ctx.options.sentenceCase) return text
        if (text.isBlank()) return text
        var out = text
        out = SENTENCE_START.replace(out) { m ->
            m.groupValues[1] + m.groupValues[2].uppercase()
        }
        out = LONE_I.replace(out) { "I" }
        out = I_CONTRACTION.replace(out) { "I" + it.groupValues[1].lowercase() }
        out = WEEKDAY.replace(out) { m ->
            m.value.replaceFirstChar { c -> c.uppercaseChar() }
        }
        if (ctx.tone == ToneProfile.FORMAL) {
            val last = out.trimEnd().lastOrNull()
            if (last != null && (last.isLetterOrDigit() || last == ')' || last == '"')) {
                out = out.trimEnd() + "."
            }
        }
        return out
    }
}
