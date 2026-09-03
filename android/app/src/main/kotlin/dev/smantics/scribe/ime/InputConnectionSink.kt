package dev.smantics.scribe.ime

import android.view.inputmethod.InputConnection
import dev.smantics.scribe.core.dictation.TextSink

/**
 * Puts text into whatever field has focus, through the system's own input channel.
 *
 * `commitText` is the sanctioned path: it inserts at the cursor, works in every app that
 * accepts typing, respects undo, and — the part that matters for a privacy-first tool —
 * **never touches the clipboard**. The desktop build makes the same promise on Windows and
 * Linux, and it is the reason Scribe types character-accurate text without stepping on
 * whatever the user had copied.
 *
 * [replace] is what makes the Raw/Clean toggle reversible after insertion. It refuses
 * rather than guesses: if the text immediately before the cursor is not exactly what
 * Scribe last inserted — the user typed, moved the cursor, or the app rewrote the field —
 * it changes nothing and reports false.
 */
class InputConnectionSink(
    private val connection: () -> InputConnection?,
) : TextSink {

    override fun commit(text: String) {
        val ic = connection() ?: error("no input connection")
        ic.beginBatchEdit()
        try {
            // newCursorPosition = 1 leaves the caret after the inserted text, which is
            // where someone who just dictated a sentence expects it to be.
            ic.commitText(text, 1)
        } finally {
            ic.endBatchEdit()
        }
    }

    override fun replace(previous: String, next: String): Boolean {
        if (previous.isEmpty()) return false
        val ic = connection() ?: return false

        val before = ic.getTextBeforeCursor(previous.length, 0)?.toString() ?: return false
        if (before != previous) {
            // Something else has happened to this field since Scribe wrote to it. Silently
            // rewriting whatever is there now would be a data-loss bug in someone's
            // message, so this path does nothing at all.
            return false
        }

        ic.beginBatchEdit()
        return try {
            ic.deleteSurroundingText(previous.length, 0)
            ic.commitText(next, 1)
        } finally {
            ic.endBatchEdit()
        }
    }
}
