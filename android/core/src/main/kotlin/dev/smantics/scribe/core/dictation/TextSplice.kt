package dev.smantics.scribe.core.dictation

/**
 * Where a transcript goes inside text that is already there.
 *
 * The keyboard never needs this: `InputConnection.commitText` inserts at the cursor and
 * the app does the rest. The **bubble** does, because the only way an accessibility
 * service can type into another app's field is `ACTION_SET_TEXT`, which replaces the
 * field's entire contents. So the existing text has to be read, the transcript spliced in
 * at the cursor, and the whole thing written back — and getting that wrong means deleting
 * what somebody had already written.
 *
 * It lives in `core` with no Android imports for exactly that reason: it is the one part
 * of the bubble's insertion path that can be tested on a machine with no phone attached.
 * Finding the field and persuading the app to accept the write cannot be.
 */
object TextSplice {

    /** The text to write back, and where the cursor should end up in it. */
    data class Result(val text: String, val caret: Int)

    /**
     * Splice [insertion] into [existing] over the selection `[start, end)`.
     *
     * A collapsed selection (start == end) is an insertion point; a real one is replaced,
     * which is what a user who selected a phrase and then dictated over it expects.
     * Out-of-range values are clamped rather than rejected: a field with no selection
     * reports -1, and several report positions from before their last edit.
     *
     * A single space is added on **either side** where the splice would otherwise glue two
     * words together — so dictating twice in a row does not run the sentences into each
     * other, and dictating into the middle of a half-written line does not weld the
     * transcript onto the word after the cursor. Only where a word actually abuts one:
     * never after a newline, never beside an existing space, and never where the
     * transcript brings its own.
     */
    fun into(existing: String, start: Int, end: Int, insertion: String): Result {
        // Clamped independently and *then* ordered. Clamping the end against the start
        // instead — which is what this did — turns a selection reported back-to-front into
        // an append at the far end of it, so dictating over a phrase selected right-to-left
        // left the phrase in place and put the new words after it.
        val a = start.coerceIn(0, existing.length)
        val b = end.coerceIn(0, existing.length)
        val from = minOf(a, b)
        val to = maxOf(a, b)

        val joinsBefore = from > 0 &&
            !existing[from - 1].isWhitespace() &&
            insertion.isNotEmpty() &&
            !insertion.first().isWhitespace()
        // Only a *word* on the far side earns a space. Punctuation does not: pushing the
        // full stop off the end of the sentence you just dictated would be worse than the
        // gluing this is here to prevent.
        val joinsAfter = to < existing.length &&
            existing[to].isLetterOrDigit() &&
            insertion.isNotEmpty() &&
            !insertion.last().isWhitespace()

        val text = buildString {
            append(existing, 0, from)
            if (joinsBefore) append(' ')
            append(insertion)
            if (joinsAfter) append(' ')
            append(existing, to, existing.length)
        }

        // After what was just inserted, not at the end of the field and not past the space
        // added behind it: the user carries on from where they were, and anything that
        // followed the cursor is still ahead of them.
        val caret = from + (if (joinsBefore) 1 else 0) + insertion.length
        return Result(text, caret)
    }
}
