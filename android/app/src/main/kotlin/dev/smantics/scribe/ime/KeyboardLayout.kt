package dev.smantics.scribe.ime

/**
 * The typing half of the keyboard.
 *
 * Scribe shipped as voice-only, and on a real phone that turned out to be a trap: dictating
 * a message and then wanting to fix one word meant switching to another keyboard and back.
 * A dictation keyboard you have to leave in order to type is not a keyboard, it is a
 * dialog.
 *
 * So this is a plain QWERTY, written from scratch — no AOSP fork, no FUTO code, no licence
 * to reconcile with Scribe's MIT. It is deliberately unambitious: no prediction, no
 * autocorrect, no swipe, no learned dictionary. Those are what make a keyboard a
 * years-long project, and all three of them would want to see everything you type, which
 * is the opposite of what this app is for.
 */

/** What a key does when pressed. */
sealed interface Key {
    /** Types its own text. */
    data class Char(val lower: String, val upper: String = lower.uppercase()) : Key

    data object Shift : Key
    data object Backspace : Key
    data object Space : Key
    data object Enter : Key

    /** Switches between letters and the numbers/symbols layer. */
    data object Layer : Key

    /** Starts or stops dictating. */
    data object Mic : Key

    /** Back to the previous keyboard. */
    data object SwitchIme : Key
}

/** Which set of keys is showing. */
enum class KeyboardLayer { LETTERS, SYMBOLS }

/** Shift's three states: off, one character, or locked until pressed again. */
enum class ShiftState { OFF, ONCE, LOCKED }

object KeyboardLayout {

    private fun row(letters: String): List<Key> =
        letters.map { Key.Char(it.toString()) }

    val letterRows: List<List<Key>> = listOf(
        row("qwertyuiop"),
        row("asdfghjkl"),
        listOf(Key.Shift) + row("zxcvbnm") + listOf(Key.Backspace),
    )

    val symbolRows: List<List<Key>> = listOf(
        row("1234567890"),
        listOf("@", "#", "$", "&", "-", "+", "(", ")", "/").map { Key.Char(it, it) },
        listOf(Key.Shift) + listOf("*", "\"", "'", ":", ";", "!", "?").map { Key.Char(it, it) } +
            listOf(Key.Backspace),
    )

    /** The second symbols page, reached with shift while on symbols. */
    val symbolRowsShifted: List<List<Key>> = listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { Key.Char(it, it) },
        listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}").map { Key.Char(it, it) },
        listOf(Key.Shift) + listOf("\\", "©", "®", "™", "%", "[", "]").map { Key.Char(it, it) } +
            listOf(Key.Backspace),
    )

    /** The bottom row is the same shape in every layer. */
    val bottomRow: List<Key> = listOf(
        Key.Layer,
        Key.Char(",", ","),
        Key.Mic,
        Key.Space,
        Key.Char(".", "."),
        Key.Enter,
        Key.SwitchIme,
    )

    fun rowsFor(layer: KeyboardLayer, shift: ShiftState): List<List<Key>> = when {
        layer == KeyboardLayer.LETTERS -> letterRows
        shift == ShiftState.OFF -> symbolRows
        else -> symbolRowsShifted
    }

    /** The text a character key produces given the current shift state. */
    fun textFor(key: Key.Char, layer: KeyboardLayer, shift: ShiftState): String =
        if (layer == KeyboardLayer.LETTERS && shift != ShiftState.OFF) key.upper else key.lower

    /**
     * How wide a key is relative to a plain letter.
     *
     * Space earns its width; shift and backspace are wider because they are the two keys
     * people hit by accident when they are narrow.
     */
    fun weightOf(key: Key): Float = when (key) {
        is Key.Space -> 4f
        is Key.Shift, is Key.Backspace, is Key.Enter, is Key.Layer -> 1.5f
        is Key.Mic -> 1.5f
        else -> 1f
    }
}
