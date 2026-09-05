package dev.smantics.scribe.core.typing

/**
 * The small conventions every keyboard follows, which together are most of what "finished"
 * means.
 *
 * None of these is clever and none of them needs a dictionary. They are the difference
 * between a keyboard that types exactly what you press and one that behaves the way you
 * already expect — and they were the cheapest thing missing from this one.
 *
 * Pure functions of the text before the cursor, so all of it is testable without a phone,
 * and none of it holds any state: the keyboard reads what is already in the field rather
 * than remembering what it typed, which also means it is never wrong about a field the user
 * has edited by hand.
 */
object TypingRules {

    /** What a keypress should do beyond inserting the character. */
    sealed interface Outcome {
        /** Type this instead of what was pressed. */
        data class Replace(val deleteBefore: Int, val insert: String) : Outcome

        /** Nothing special; insert what was pressed. */
        data object AsTyped : Outcome
    }

    /**
     * Should the next letter be a capital?
     *
     * True at the start of a field, and after a sentence has ended and been spaced. Not
     * after an abbreviation the user is still typing, which is why the rule requires the
     * space: `e.g.` keeps its lower case, `Yes. And` gets its capital.
     */
    fun shouldCapitalise(before: String): Boolean {
        val trimmed = before.trimEnd(' ')
        if (trimmed.isEmpty()) return before.isEmpty() || before.isBlank()
        // A sentence end only counts once it has been followed by a space, so the capital
        // arrives with the next word rather than the moment a full stop is typed.
        if (before.length == trimmed.length) return false
        return trimmed.last() in SENTENCE_ENDS
    }

    /**
     * Two spaces become a full stop and a space, as they do on every phone.
     *
     * Only after a word — `hello  ` becomes `hello. `, while a double space used to indent
     * something is left alone, because there is no sentence there to end.
     */
    fun doubleSpace(before: String): Outcome {
        if (!before.endsWith(" ")) return Outcome.AsTyped
        val body = before.dropLast(1)
        val last = body.lastOrNull() ?: return Outcome.AsTyped
        if (!last.isLetterOrDigit()) return Outcome.AsTyped
        return Outcome.Replace(deleteBefore = 1, insert = ". ")
    }

    /**
     * Punctuation typed after a space moves back to where it belongs.
     *
     * Dictation and thumbs both produce `hello ,` and nobody means it. The space is taken
     * out and put back on the other side, which is where the reader expects it.
     */
    fun tidyPunctuation(before: String, typed: String): Outcome {
        if (typed.length != 1 || typed[0] !in CLINGING) return Outcome.AsTyped
        if (!before.endsWith(" ") || before.endsWith("  ")) return Outcome.AsTyped
        val body = before.dropLast(1)
        if (body.lastOrNull()?.isLetterOrDigit() != true) return Outcome.AsTyped
        return Outcome.Replace(deleteBefore = 1, insert = typed)
    }

    /**
     * The word immediately before the cursor, or "" if the cursor is not after one.
     *
     * This is what autocorrect is offered, and what the suggestion strip completes. It stops
     * at anything that is not a letter, so `don't` is `t` — which is deliberate: correcting
     * across an apostrophe is how a keyboard turns `don't` into `dont`.
     */
    fun wordBefore(before: String): String {
        val end = before.length
        var start = end
        while (start > 0 && before[start - 1].isLetter()) start--
        return before.substring(start, end)
    }

    /** Characters that end a sentence, after which the next letter is capitalised. */
    private val SENTENCE_ENDS = charArrayOf('.', '!', '?')

    /** Punctuation that belongs against the word before it, never after a space. */
    private val CLINGING = charArrayOf(',', '.', '!', '?', ';', ':')
}
