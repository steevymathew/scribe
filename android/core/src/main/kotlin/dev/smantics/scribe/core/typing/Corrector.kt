package dev.smantics.scribe.core.typing

import kotlin.math.min

/**
 * Autocorrect, entirely on this phone.
 *
 * **The privacy position, because it is the whole reason this is written rather than
 * borrowed.** Nothing here reaches a network, nothing is uploaded, and nothing is written
 * to disk by this class at all — it is a pure function of a word list and the word you just
 * typed. The shipped list is a fixed English dictionary in the APK. The user's own words
 * come from the vocabulary they already curate in Settings, which they can read and edit.
 * Learning from what you type is a *separate*, opt-in thing on top; this class has no
 * memory of its own and cannot acquire one.
 *
 * **How the correction is chosen.** A typo on a phone is nearly always a near-miss with the
 * thumb, so the distance that matters is not "how many letters differ" but "how far apart
 * are those letters on the keyboard". `hekki` becomes `hello` because `k` sits next to `l`;
 * it does not become `hecki`, which is no further away by letters but much further away by
 * thumbs. That single weighting is most of what makes a corrector feel like it is reading
 * your intent rather than shuffling characters.
 *
 * Frequency breaks ties, because between two words the same distance away the common one is
 * almost always the one meant.
 */
class Corrector(
    /** The shipped list, most-used first for the ranked part. */
    private val dictionary: WordList,
    /** The user's own words, which always win: they were added on purpose. */
    private val userWords: Set<String> = emptySet(),
) {

    /**
     * What to do with [typed], now that the user has finished the word.
     *
     * Called when a space or punctuation lands, which is the moment a word stops being a
     * guess. Returns null when there is nothing to say — which is most of the time, and is
     * the case that has to be cheap.
     */
    fun correct(typed: String): String? {
        if (typed.length < MIN_LENGTH) return null
        val word = typed.lowercase()
        // Never correct something the user has told us is a word, and never correct a word
        // that already is one. A corrector that "fixes" correct spelling is a vandal.
        if (word in userWords || dictionary.contains(word)) return null
        // Anything with a digit or a symbol in it is not a word and is not ours to touch.
        if (!word.all { it in 'a'..'z' }) return null

        val best = candidates(word).firstOrNull() ?: return null
        return matchCase(typed, best)
    }

    /**
     * Words that could be meant by [prefix], most likely first, for the suggestion strip.
     *
     * Prefix matches only — this completes, it does not guess. Somebody four letters into a
     * word is telling you what they want, and offering them something that starts
     * differently is noise.
     */
    fun suggest(prefix: String, limit: Int = 3): List<String> {
        if (prefix.length < MIN_SUGGEST_LENGTH) return emptyList()
        val lower = prefix.lowercase()
        if (!lower.all { it in 'a'..'z' }) return emptyList()

        val fromUser = userWords.filter { it.startsWith(lower) && it != lower }
        val fromDictionary = dictionary.startingWith(lower, limit * 2).filter { it != lower }
        return (fromUser + fromDictionary)
            .distinct()
            .take(limit)
            .map { matchCase(prefix, it) }
    }

    /**
     * The corrections worth considering, best first.
     *
     * Only words within [MAX_EDITS] edits are looked at, and the search is over the
     * dictionary rather than over generated misspellings: generating every string one edit
     * away from an eight-letter word is thousands of candidates, while walking a word list
     * of the right lengths is tens of thousands of cheap comparisons and gets the weighting
     * for free.
     */
    private fun candidates(word: String): List<String> {
        val pool = userWords.asSequence().filter { lengthPlausible(word, it) } +
            dictionary.ofLengthNear(word.length)

        return pool
            .map { it to distance(word, it) }
            // Admission is on the raw distance: a word has to be genuinely close before
            // being common is allowed to help it.
            .filter { (_, d) -> d <= MAX_COST }
            .map { (w, d) -> w to d - commonnessBonus(w) }
            .sortedWith(compareBy({ (_, score) -> score }, { (w, _) -> dictionary.rankOf(w) }))
            .map { (w, _) -> w }
            .toList()
    }

    /**
     * How much being an ordinary word is worth, in edits.
     *
     * **Frequency has to outweigh distance, not merely break its ties**, and the two tests
     * that proved it are worth keeping in mind: `helo` is one *neighbouring-key* edit from
     * `hell` and a whole inserted letter from `hello`, and `teh` is a neighbour-slip from
     * `ten` and a transposition from `the`. On raw distance the wrong answer wins both
     * times. Nobody types `helo` meaning `hell`.
     *
     * Bounded at half an edit and decaying with rank, so it tips a close race and cannot
     * drag a distant word past a near one. A word that is merely known earns nothing.
     */
    private fun commonnessBonus(word: String): Float {
        val rank = dictionary.rankOf(word)
        if (rank == WordList.UNRANKED) return 0f
        val decay = 1f - (rank.toFloat() / RANK_SCALE)
        return MAX_BONUS * decay.coerceAtLeast(0.25f)
    }

    private fun lengthPlausible(a: String, b: String) = kotlin.math.abs(a.length - b.length) <= 1

    /**
     * Levenshtein distance, but a substitution costs less when the two keys are neighbours.
     *
     * This is the part that makes it a *keyboard's* corrector. A swapped pair of adjacent
     * letters — the commonest typo there is — is also discounted, because `teh` for `the`
     * is one slip of two fingers rather than two separate mistakes.
     */
    private fun distance(typed: String, candidate: String): Float {
        val n = typed.length
        val m = candidate.length
        if (kotlin.math.abs(n - m) > MAX_EDITS) return Float.MAX_VALUE

        var previous = FloatArray(m + 1) { it.toFloat() }
        var current = FloatArray(m + 1)
        var beforePrevious = FloatArray(m + 1)

        for (i in 1..n) {
            current[0] = i.toFloat()
            for (j in 1..m) {
                val a = typed[i - 1]
                val b = candidate[j - 1]
                val substitution = previous[j - 1] + when {
                    a == b -> 0f
                    Keyboard.adjacent(a, b) -> NEAR_MISS
                    else -> 1f
                }
                var best = min(substitution, min(previous[j] + 1f, current[j - 1] + 1f))
                // Transposition: `teh` -> `the`.
                if (i > 1 && j > 1 && a == candidate[j - 2] && typed[i - 2] == b) {
                    best = min(best, beforePrevious[j - 2] + TRANSPOSE)
                }
                current[j] = best
            }
            val spare = beforePrevious
            beforePrevious = previous
            previous = current
            current = spare
        }
        return previous[m]
    }

    /** `Hello` typed as `Helo` comes back capitalised; `HELLO` comes back shouting. */
    private fun matchCase(typed: String, replacement: String): String = when {
        typed.all { it.isUpperCase() } && typed.length > 1 -> replacement.uppercase()
        typed.firstOrNull()?.isUpperCase() == true -> replacement.replaceFirstChar { it.uppercase() }
        else -> replacement
    }

    private companion object {
        /** Below this, a "typo" is more likely a deliberate abbreviation. */
        const val MIN_LENGTH = 3

        /** Below this, a prefix is not yet telling you anything. */
        const val MIN_SUGGEST_LENGTH = 2

        const val MAX_EDITS = 2

        /**
         * The most a correction may be wrong by and still be offered.
         *
         * Under two whole edits. Two adjacent-key slips are plausible; two arbitrary
         * substitutions are a different word, and offering it is worse than offering
         * nothing — the user has to notice and undo it.
         */
        const val MAX_COST = 1.6f

        /** A slip onto the neighbouring key, which is what most typos are. */
        const val NEAR_MISS = 0.55f

        /** Two fingers out of order, counted as one mistake because it is one. */
        const val TRANSPOSE = 0.6f

        /** The most that being a common word can be worth, in edits. */
        const val MAX_BONUS = 0.5f

        /** Past this rank a word is common enough to keep only a quarter of the bonus. */
        const val RANK_SCALE = 400f
    }
}

/** Which keys sit next to which, for the near-miss weighting. */
internal object Keyboard {
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private val neighbours: Map<Char, Set<Char>> = buildMap {
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, key ->
                val near = mutableSetOf<Char>()
                for (dr in -1..1) for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val other = rows.getOrNull(r + dr)?.getOrNull(c + dc) ?: continue
                    near += other
                }
                put(key, near)
            }
        }
    }

    fun adjacent(a: Char, b: Char): Boolean = neighbours[a]?.contains(b) == true
}
