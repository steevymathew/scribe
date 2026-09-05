package dev.smantics.scribe.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout is data, so it can be checked without rendering anything.
 *
 * These are the assertions that would otherwise need a person to look at a phone and count
 * keys: that every letter carries the symbol the design gave it, that the split repeats
 * the middle key instead of stranding it in the gap, and that the pages form a run with
 * ends rather than a carousel.
 */
class KeyboardLayoutTest {

    private fun chars(keys: List<Key>) =
        keys.filterIsInstance<Key.Char>().joinToString("") { it.lower }

    private fun secondaries(keys: List<Key>) =
        keys.filterIsInstance<Key.Char>().joinToString("") { it.secondary.orEmpty() }

    // ------------------------------------------------------------------ letters

    @Test fun `the letters are a qwerty`() {
        assertEquals("qwertyuiop", chars(KeyboardLayout.letterRows[0]))
        assertEquals("asdfghjkl", chars(KeyboardLayout.letterRows[1]))
        assertEquals("zxcvbnm", chars(KeyboardLayout.letterRows[2]))
    }

    @Test fun `the digits are their own row`() {
        assertEquals("1234567890", chars(KeyboardLayout.numberRow))
    }

    /** The symbols on the shoulders, exactly as the design lays them out. */
    @Test fun `every letter carries its shoulder symbol`() {
        assertEquals("-\\|=[]<>{}", secondaries(KeyboardLayout.letterRows[0]))
        assertEquals("@#$%&-+()", secondaries(KeyboardLayout.letterRows[1]))
        assertEquals("*\"':;!?", secondaries(KeyboardLayout.letterRows[2]))
    }

    @Test fun `nothing on the letters is missing a shoulder`() {
        KeyboardLayout.letterRows.flatten().filterIsInstance<Key.Char>().forEach {
            assertTrue("'${it.lower}' has no secondary", !it.secondary.isNullOrEmpty())
        }
    }

    // ------------------------------------------------------------ uniform grid

    /**
     * Every row is ten key-widths across, padded if it has to be.
     *
     * A row laid out purely by weight makes nine keys wider than ten, so `asdfghjkl` came
     * out visibly fatter than `qwertyuiop` and a thumb aiming between the rows landed on
     * the wrong letter. This is the stagger a physical keyboard has.
     */
    @Test fun `every row is ten key widths across`() {
        val rows = listOf(
            KeyboardLayout.numberRow,
            KeyboardLayout.letterRows[0],
            KeyboardLayout.letterRows[1],
            listOf(Key.Shift) + KeyboardLayout.letterRows[2] + listOf(Key.Backspace),
            KeyboardLayout.bottomRow,
            KeyboardLayout.emojiBottomRow,
        ) + KeyboardLayout.symbolRows + KeyboardLayout.symbolRowsShifted

        rows.forEach { row ->
            val used = KeyboardLayout.weightsFor(row).sum()
            val padded = used + KeyboardLayout.indentFor(row) * 2
            assertEquals(
                "row ${chars(row)} is $used units, padded to $padded",
                KeyboardLayout.ROW_UNITS.toDouble(),
                padded.toDouble(),
                0.001,
            )
        }
    }

    @Test fun `the nine key rows are indented by half a key at each end`() {
        assertEquals(0.5f, KeyboardLayout.indentFor(KeyboardLayout.letterRows[1]))
        assertEquals(0f, KeyboardLayout.indentFor(KeyboardLayout.letterRows[0]))
    }

    /** The default action of the bottom row, and sized like one. */
    @Test fun `the space bar is the widest key on the board`() {
        val widths = KeyboardLayout.weightsFor(KeyboardLayout.bottomRow)
        val space = widths[KeyboardLayout.bottomRow.indexOf(Key.Space)]
        assertTrue("space is $space", space > 4.5f)
        assertTrue(widths.all { it <= space })
    }

    /**
     * The emoji page gets its own bottom row. A comma is no use while picking a face, and
     * backspace is the first thing anybody reaches for after picking the wrong one.
     */
    @Test fun `the emoji page has a backspace and no punctuation`() {
        val row = KeyboardLayout.bottomRowFor(KeyboardPage.EMOJI)
        assertTrue(row.contains(Key.Backspace))
        assertTrue(row.none { it is Key.Char })
        assertTrue(row.none { it is Key.Emoji })
    }

    @Test fun `the letters and symbols pages keep the ordinary bottom row`() {
        assertEquals(KeyboardLayout.bottomRow, KeyboardLayout.bottomRowFor(KeyboardPage.LETTERS))
        assertEquals(KeyboardLayout.bottomRow, KeyboardLayout.bottomRowFor(KeyboardPage.SYMBOLS))
    }

    // ---------------------------------------------------------------- splitting

    /**
     * Only the letters split. A number row cut in two puts `5` and `6` a hand apart for no
     * reason — the stagger exists so each *hand* keeps its own half of the alphabet.
     */
    @Test fun `only the letter rows split`() {
        KeyboardLayout.letterRows.forEach {
            assertTrue(KeyboardLayout.splittable(KeyboardPage.LETTERS, it))
        }
        assertFalse(KeyboardLayout.splittable(KeyboardPage.LETTERS, KeyboardLayout.numberRow))
        assertFalse(KeyboardLayout.splittable(KeyboardPage.LETTERS, KeyboardLayout.bottomRow))
        KeyboardLayout.symbolRows.forEach {
            assertFalse(KeyboardLayout.splittable(KeyboardPage.SYMBOLS, it))
        }
    }

    /**
     * All three pages are built to the same grid, so the keyboard does not change height
     * when you swipe sideways — which would re-lay out the app behind it every time.
     */
    @Test fun `the letters and symbols pages have the same number of rows`() {
        assertEquals(
            KeyboardLayout.rowsFor(KeyboardPage.LETTERS, ShiftState.OFF).size,
            KeyboardLayout.rowsFor(KeyboardPage.SYMBOLS, ShiftState.OFF).size,
        )
        assertEquals(
            KeyboardLayout.rowsFor(KeyboardPage.SYMBOLS, ShiftState.OFF).size,
            KeyboardLayout.rowsFor(KeyboardPage.SYMBOLS, ShiftState.LOCKED).size,
        )
    }

    /** The digits are a shared row, not a row the symbols page spends on its own. */
    @Test fun `the symbols page does not repeat the digits`() {
        val digits = chars(KeyboardLayout.numberRow).toSet()
        val symbols = KeyboardLayout.symbolRows.flatten().let(::chars).toSet() +
            KeyboardLayout.symbolRowsShifted.flatten().let(::chars).toSet()
        assertEquals(emptySet<Char>(), digits intersect symbols)
    }

    // -------------------------------------------------------------------- split

    /**
     * The middle key of an odd row goes on **both** halves.
     *
     * Otherwise `g` and `v` land in the gap and belong to one thumb, which means reaching
     * across an eight-inch display for two of the commonest letters in English.
     */
    @Test fun `an odd row repeats its middle key on both halves`() {
        val (left, right) = KeyboardLayout.split(KeyboardLayout.letterRows[1])
        assertEquals("asdfg", chars(left))
        assertEquals("ghjkl", chars(right))
    }

    @Test fun `and so does the bottom letter row`() {
        val (left, right) = KeyboardLayout.split(KeyboardLayout.letterRows[2])
        assertEquals("zxcv", chars(left))
        assertEquals("vbnm", chars(right))
    }

    @Test fun `an even row splits down the middle with nothing repeated`() {
        val (left, right) = KeyboardLayout.split(KeyboardLayout.letterRows[0])
        assertEquals("qwert", chars(left))
        assertEquals("yuiop", chars(right))
    }

    @Test fun `every letter survives the split`() {
        KeyboardLayout.letterRows.forEach { row ->
            val (left, right) = KeyboardLayout.split(row)
            val halves = (chars(left) + chars(right)).toSet()
            assertEquals(chars(row).toSet(), halves)
        }
    }

    @Test fun `splitting nothing is not a crash`() {
        val (left, right) = KeyboardLayout.split(emptyList())
        assertTrue(left.isEmpty() && right.isEmpty())
    }

    // -------------------------------------------------------------------- pages

    /**
     * A run, not a carousel. Swiping past the end doing nothing is a dead stop the user
     * can feel; wrapping around from emoji to symbols is a page arriving from nowhere.
     */
    @Test fun `the pages run emoji to letters to symbols and stop`() {
        assertEquals(KeyboardPage.LETTERS, KeyboardPage.EMOJI.right())
        assertEquals(KeyboardPage.SYMBOLS, KeyboardPage.LETTERS.right())
        assertNull(KeyboardPage.SYMBOLS.right())

        assertEquals(KeyboardPage.LETTERS, KeyboardPage.SYMBOLS.left())
        assertEquals(KeyboardPage.EMOJI, KeyboardPage.LETTERS.left())
        assertNull(KeyboardPage.EMOJI.left())
    }

    // -------------------------------------------------------------------- shift

    @Test fun `shift capitalises on the letters and does nothing to a symbol`() {
        val a = Key.Char("a")
        assertEquals("a", KeyboardLayout.textFor(a, KeyboardPage.LETTERS, ShiftState.OFF))
        assertEquals("A", KeyboardLayout.textFor(a, KeyboardPage.LETTERS, ShiftState.ONCE))
        assertEquals("A", KeyboardLayout.textFor(a, KeyboardPage.LETTERS, ShiftState.LOCKED))

        val hash = Key.Char("#", "#")
        assertEquals("#", KeyboardLayout.textFor(hash, KeyboardPage.SYMBOLS, ShiftState.LOCKED))
    }

    /** Shift on the symbols page reaches a second set rather than upper-casing them. */
    @Test fun `shift on the symbols page is a second page of symbols`() {
        val plain = KeyboardLayout.rowsFor(KeyboardPage.SYMBOLS, ShiftState.OFF)
        val shifted = KeyboardLayout.rowsFor(KeyboardPage.SYMBOLS, ShiftState.LOCKED)
        assertTrue(plain != shifted)
        assertEquals(KeyboardLayout.symbolRows, plain)
        assertEquals(KeyboardLayout.symbolRowsShifted, shifted)
    }

    /** No symbol appears on both symbol pages: a duplicate is a wasted key. */
    @Test fun `the two symbol pages do not overlap`() {
        val first = KeyboardLayout.symbolRows.flatten().let(::chars).toSet()
        val second = KeyboardLayout.symbolRowsShifted.flatten().let(::chars).toSet()
        assertEquals(emptySet<Char>(), first intersect second)
    }

    // ------------------------------------------------------------- bottom row

    /**
     * The microphone is not one of the keys.
     *
     * It lives in the voice strip above them, where it stays reachable with the keys swiped
     * away — which is the state a voice-first keyboard spends most of its time in. A second
     * one down here would be the same control twice.
     *
     * There is no switch-to-another-keyboard key either, and that one is enforced by the
     * type system: `Key.SwitchIme` was deleted rather than left unused.
     */
    @Test fun `the microphone is not one of the keys`() {
        val everything = KeyboardLayout.letterRows.flatten() +
            KeyboardLayout.symbolRows.flatten() +
            KeyboardLayout.symbolRowsShifted.flatten() +
            KeyboardLayout.numberRow +
            KeyboardLayout.bottomRow
        assertTrue(everything.none { it is Key.Mic })
    }

    @Test fun `the bottom row ends with enter`() {
        assertEquals(Key.Enter, KeyboardLayout.bottomRow.last())
    }

    @Test fun `holding the comma reaches the app, as Android users expect`() {
        val comma = KeyboardLayout.bottomRow.filterIsInstance<Key.Char>().first { it.lower == "," }
        assertEquals("⚙", comma.secondary)
    }
}
