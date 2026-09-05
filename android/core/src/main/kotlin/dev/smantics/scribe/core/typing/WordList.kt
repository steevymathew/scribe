package dev.smantics.scribe.core.typing

/**
 * The words Scribe knows, and how common each one is.
 *
 * Loaded once from a plain text asset: the ranked words first, most-used at the top, then a
 * blank line, then the rest of the dictionary alphabetically. Two tiers rather than a full
 * frequency table because that is what the job needs — rank only has to answer "between
 * these two candidates, which is the ordinary word", and past the first few hundred entries
 * the answer stops changing anything a user would notice.
 *
 * Deliberately dumb and deliberately inspectable. It is a text file in the APK; anyone who
 * wants to know what the keyboard thinks a word is can read it.
 */
class WordList private constructor(
    private val ranks: Map<String, Int>,
    private val byLength: Map<Int, List<String>>,
    private val all: Set<String>,
    private val sorted: List<String>,
) {

    fun contains(word: String): Boolean = word in all

    /** Position in the common-words list, or [UNRANKED] for a word that is merely known. */
    fun rankOf(word: String): Int = ranks[word] ?: UNRANKED

    /**
     * Every word within one letter of [length].
     *
     * The corrector walks this rather than generating misspellings: a single edit of an
     * eight-letter word is thousands of strings, while the words that could possibly be
     * within one edit are only those of a similar length.
     */
    fun ofLengthNear(length: Int): Sequence<String> = sequence {
        for (l in (length - 1)..(length + 1)) {
            byLength[l]?.let { yieldAll(it) }
        }
    }

    /** Known words beginning with [prefix], commonest first, for the suggestion strip. */
    fun startingWith(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val from = sorted.binarySearch { it.compareTo(prefix) }.let { if (it < 0) -it - 1 else it }
        val found = mutableListOf<String>()
        var i = from
        while (i < sorted.size && sorted[i].startsWith(prefix) && found.size < limit * 8) {
            found += sorted[i]
            i++
        }
        return found.sortedBy { rankOf(it) }.take(limit)
    }

    val size: Int get() = all.size

    companion object {
        /** Known, but not one of the common words. Sorts after everything ranked. */
        const val UNRANKED = Int.MAX_VALUE

        /**
         * Parse the asset. Ranked words above the blank line, the rest below it.
         *
         * Tolerant on purpose: a file with no blank line is simply all-unranked, and an
         * empty one produces an empty list rather than throwing. A keyboard must not fail
         * to start because a dictionary is malformed — it types perfectly well without one.
         */
        fun parse(text: String): WordList {
            val ranks = HashMap<String, Int>()
            val all = HashSet<String>()
            var ranking = true
            var rank = 0
            text.lineSequence().forEach { raw ->
                val word = raw.trim()
                if (word.isEmpty()) {
                    ranking = false
                    return@forEach
                }
                if (ranking) ranks[word] = rank++
                all += word
            }
            val byLength = all.groupBy { it.length }
            return WordList(ranks, byLength, all, all.sorted())
        }

        val EMPTY = WordList(emptyMap(), emptyMap(), emptySet(), emptyList())
    }
}
