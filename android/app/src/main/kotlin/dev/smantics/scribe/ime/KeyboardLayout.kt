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
 * autocorrect, no swipe-to-type, no learned dictionary. Those are what make a keyboard a
 * years-long project, and all three of them would want to see everything you type, which
 * is the opposite of what this app is for.
 *
 * The arrangement follows the owner's layout: a number row across the top, a symbol on the
 * shoulder of every letter reachable by holding it, and — on a wide screen — a split with
 * the middle key of each odd row repeated on both halves, so neither thumb has to reach
 * across the hinge for `g` or `v`.
 */

/** What a key does when pressed. */
sealed interface Key {
    /**
     * Types its own text, and a second character when held.
     *
     * [secondary] is drawn small in the top-right corner. It is how a keyboard with no
     * symbol layer still reaches `@` or `[` without a trip through `?123`, and it is the
     * one piece of density this layout deliberately keeps.
     */
    data class Char(
        val lower: String,
        val upper: String = lower.uppercase(),
        val secondary: String? = null,
    ) : Key

    data object Shift : Key
    data object Backspace : Key
    data object Space : Key
    data object Enter : Key

    /** Switches to the symbols page. */
    data object Layer : Key

    /** Switches to the emoji page. */
    data object Emoji : Key

    /** Starts or stops dictating. */
    data object Mic : Key
}

/** Which page of the keyboard is showing. */
enum class KeyboardPage {
    /** Letters, the number row, and the symbols on their shoulders. */
    LETTERS,

    /** Punctuation and symbols. Swipe right, or the `?123` key. */
    SYMBOLS,

    /** Swipe left, or the smiley key. */
    EMOJI,
    ;

    /** The page to the left of this one, or null at the end of the run. */
    fun left(): KeyboardPage? = when (this) {
        SYMBOLS -> LETTERS
        LETTERS -> EMOJI
        EMOJI -> null
    }

    /** The page to the right of this one, or null at the end of the run. */
    fun right(): KeyboardPage? = when (this) {
        EMOJI -> LETTERS
        LETTERS -> SYMBOLS
        SYMBOLS -> null
    }
}

/** Shift's three states: off, one character, or locked until pressed again. */
enum class ShiftState { OFF, ONCE, LOCKED }

object KeyboardLayout {

    /**
     * A row of letters, each carrying the symbol that appears when it is held.
     *
     * `letters` and `symbols` are read in step, so they must be the same length — the
     * check is here rather than in a test because a mismatch is a silent misalignment
     * that puts `[` on the wrong key.
     */
    private fun row(letters: String, symbols: String): List<Key> {
        require(letters.length == symbols.length) {
            "'$letters' and '$symbols' must line up, one symbol per letter"
        }
        return letters.mapIndexed { i, c ->
            Key.Char(c.toString(), secondary = symbols[i].toString())
        }
    }

    /** Digits, in the row across the top. */
    val numberRow: List<Key> =
        "1234567890".map { Key.Char(it.toString(), it.toString()) }

    val letterRows: List<List<Key>> = listOf(
        row("qwertyuiop", "-\\|=[]<>{}"),
        row("asdfghjkl", "@#$%&-+()"),
        row("zxcvbnm", "*\"':;!?"),
    )

    private fun symbols(vararg items: String): List<Key> =
        items.map { Key.Char(it, it) }

    /**
     * The symbols, in **the same shape as the letters**: three rows under the same number
     * row, with the third indented.
     *
     * The digits used to be the symbols page's first row, which made that page one row
     * shorter than the letters — so the keyboard changed height when you swiped sideways,
     * and every app behind it re-laid out. Sharing the number row costs nothing (the
     * digits were on both pages anyway) and buys a keyboard whose height does not move.
     */
    val symbolRows: List<List<Key>> = listOf(
        symbols("@", "#", "$", "%", "&", "*", "-", "+", "(", ")"),
        symbols("=", "/", "\\", ":", ";", "\"", "'", "!", "?", "~"),
        symbols("<", ">", "[", "]", "{", "}", "|", "^", "°"),
    )

    /** The second symbols page, reached with shift while on symbols. */
    val symbolRowsShifted: List<List<Key>> = listOf(
        symbols("`", "•", "√", "π", "÷", "×", "¶", "∆", "£", "¢"),
        symbols("€", "¥", "©", "®", "™", "§", "±", "≠", "≈", "…"),
        symbols("_", "—", "“", "”", "‘", "’", "«", "»", "¿"),
    )

    /**
     * The bottom row, in the arrangement every Android keyboard uses.
     *
     * `?123 | ,(⚙) | ☺ | space | .(') | ⏎`. Enter belongs at the right-hand end of this
     * row, where the thumb expects it. The gear on the comma is Android's own convention
     * for "the keyboard's settings", and it is how Scribe is opened from here now that
     * there is no utility bar to hold a button for it.
     *
     * There is no key that switches to another keyboard. Android's own keyboard picker
     * does that, from the system's navigation bar, and putting a second one on the primary
     * keyboard invites a tap that leaves.
     */
    val bottomRow: List<Key> = listOf(
        Key.Layer,
        Key.Char(",", ",", secondary = "⚙"),
        Key.Emoji,
        Key.Space,
        Key.Char(".", ".", secondary = "'"),
        Key.Enter,
    )

    /**
     * The bottom row the emoji page needs, which is not the one the letters need.
     *
     * A comma and a full stop are no use while picking emoji, and the emoji key would send
     * you to the page you are already on. **Backspace is what is actually wanted** — the
     * one thing you reach for immediately after putting the wrong face in — so it takes the
     * space the two punctuation keys were wasting, and the way back to the letters keeps
     * its place at the left where it is on every other page.
     */
    val emojiBottomRow: List<Key> = listOf(
        Key.Layer,
        Key.Space,
        Key.Backspace,
        Key.Enter,
    )

    /** The bottom row for a page. */
    fun bottomRowFor(page: KeyboardPage): List<Key> =
        if (page == KeyboardPage.EMOJI) emojiBottomRow else bottomRow

    /** The letter or symbol rows for a page, without the number row or the bottom row. */
    fun rowsFor(page: KeyboardPage, shift: ShiftState): List<List<Key>> = when {
        page != KeyboardPage.SYMBOLS -> letterRows
        shift == ShiftState.OFF -> symbolRows
        else -> symbolRowsShifted
    }

    /**
     * Every row is this many key-widths across, and that is what makes the letters uniform.
     *
     * A row is laid out by weight, so nine keys sharing the full width come out wider than
     * ten — which is why the middle row used to be visibly fatter than the one above it,
     * and why a thumb aiming between rows landed on the wrong key. The row is padded to a
     * fixed ten units instead, exactly as a physical keyboard staggers its rows: `asdfghjkl`
     * gets half a key of air at each end, and every letter on the board is the same size.
     */
    const val ROW_UNITS = 10f

    /** The air at each end of [row], in key-widths, to keep every letter the same size. */
    fun indentFor(row: List<Key>): Float {
        val used = weightsFor(row).sum()
        return ((ROW_UNITS - used) / 2f).coerceAtLeast(0f)
    }

    /** The text a character key produces given the current page and shift state. */
    fun textFor(key: Key.Char, page: KeyboardPage, shift: ShiftState): String =
        if (page == KeyboardPage.LETTERS && shift != ShiftState.OFF) key.upper else key.lower

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

    /**
     * The widths of one row, which always add up to [ROW_UNITS].
     *
     * The bottom rows are given explicitly rather than derived, because they are the one
     * place where the keys are not interchangeable. **The space bar is the default action
     * of that row** — it is where a thumb lands when it is not aiming at anything — so it
     * takes most of a whole key from the three narrow keys crowding it. And the emoji page
     * needs a different row again: a comma is no use while picking a face, and backspace is
     * the first thing anybody reaches for after picking the wrong one.
     */
    fun weightsFor(row: List<Key>): List<Float> = when (row) {
        bottomRow -> BOTTOM_WEIGHTS
        emojiBottomRow -> EMOJI_BOTTOM_WEIGHTS
        else -> row.map { weightOf(it) }
    }

    /** `?123 · , · emoji · space · . · enter` */
    private val BOTTOM_WEIGHTS = listOf(1.3f, 0.9f, 0.9f, 4.7f, 0.9f, 1.3f)

    /** `ABC · space · backspace · enter` */
    private val EMOJI_BOTTOM_WEIGHTS = listOf(1.5f, 5.5f, 1.5f, 1.5f)

    /**
     * Whether a row is split down the middle on a wide screen.
     *
     * Every character row of the letters card, digits included — the number row reads as
     * part of that card and looked wrong left whole above two split halves. The symbols
     * page is not split, and the bottom row never is: the space bar is one key and cannot
     * be in two places.
     */
    fun splittable(page: KeyboardPage, row: List<Key>): Boolean =
        page == KeyboardPage.LETTERS && row.all { it is Key.Char }

    /**
     * Split a row for a wide screen, **repeating the middle key on both halves**.
     *
     * On the Fold's inner display a full-width keyboard puts the letters eight inches
     * apart, which is unusable with two thumbs. Splitting keeps each half under a thumb —
     * but an odd row has a key that lands exactly in the gap, and giving it to one side
     * means reaching across for `g` or `v` all day. So it goes on both, which is what the
     * owner's layout does and what the better split keyboards do.
     *
     * `asdfghjkl` becomes `asdfg` and `ghjkl`; `zxcvbnm` becomes `zxcv` and `vbnm`. An
     * even row splits down the middle with nothing repeated.
     */
    fun split(row: List<Key>): Pair<List<Key>, List<Key>> {
        if (row.isEmpty()) return emptyList<Key>() to emptyList()
        val pivot = (row.size + 1) / 2
        val rightStart = if (row.size % 2 == 0) pivot else pivot - 1
        return row.take(pivot) to row.drop(rightStart)
    }
}
