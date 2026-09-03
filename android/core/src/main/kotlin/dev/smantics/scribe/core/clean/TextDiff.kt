package dev.smantics.scribe.core.clean

/**
 * What Clean mode actually changed, word by word.
 *
 * This exists so the app can *show its working*. Dictation tools that silently rewrite what
 * you said are unnerving — you cannot tell what was heard wrong from what was tidied — so
 * Scribe replays the edit: the raw transcript with removals struck through, then the
 * cleaned text with additions marked, then the finished line. Three seconds of animation
 * that answer "what did it do to my words?" without the user having to ask.
 *
 * A standard longest-common-subsequence diff over words. Word-level rather than
 * character-level because the changes are word-shaped — a filler dropped, a correction
 * applied, a mark added — and a character diff of "4pm" against "3pm" highlights the digit
 * while losing the fact that a whole phrase disappeared.
 */
object TextDiff {

    enum class Kind { UNCHANGED, REMOVED, ADDED }

    /** One run of words that survived, vanished, or appeared. */
    data class Segment(val text: String, val kind: Kind)

    /**
     * Split into words while keeping the whitespace attached, so segments can be
     * concatenated back into readable text without inventing or losing spaces.
     */
    private fun tokenize(text: String): List<String> =
        Regex("""\S+\s*""").findAll(text).map { it.value }.toList()

    /** Words compare on their letters and digits alone: "Friday," matches "Friday". */
    private fun key(token: String): String =
        token.filter { it.isLetterOrDigit() }.lowercase()

    /**
     * Diff [raw] against [cleaned].
     *
     * Returns segments in reading order: everything from the raw text (kept or removed)
     * interleaved with everything the cleaner added.
     */
    fun diff(raw: String, cleaned: String): List<Segment> {
        val a = tokenize(raw)
        val b = tokenize(cleaned)
        if (a.isEmpty() && b.isEmpty()) return emptyList()
        if (a.isEmpty()) return listOf(Segment(cleaned, Kind.ADDED))
        if (b.isEmpty()) return listOf(Segment(raw, Kind.REMOVED))

        // Classic LCS table. Utterances are short — a long one is a couple of hundred
        // words — so the quadratic table is far cheaper than the transcription that
        // produced the text.
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (key(a[i]) == key(b[j]) && key(a[i]).isNotEmpty()) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        val out = mutableListOf<Segment>()
        fun push(text: String, kind: Kind) {
            if (text.isEmpty()) return
            val last = out.lastOrNull()
            // Merge neighbouring runs so the UI draws one strikethrough over a phrase
            // rather than one per word.
            if (last != null && last.kind == kind) {
                out[out.lastIndex] = last.copy(text = last.text + text)
            } else {
                out += Segment(text, kind)
            }
        }

        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                key(a[i]) == key(b[j]) && key(a[i]).isNotEmpty() -> {
                    // Same word, but the cleaner may have recased or repunctuated it;
                    // show the finished form so the text reads correctly.
                    push(b[j], Kind.UNCHANGED)
                    i++
                    j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> push(a[i++], Kind.REMOVED)
                else -> push(b[j++], Kind.ADDED)
            }
        }
        while (i < a.size) push(a[i++], Kind.REMOVED)
        while (j < b.size) push(b[j++], Kind.ADDED)
        return out
    }

    /** The text as it reads with the removals still in place — the "before". */
    fun before(segments: List<Segment>): String =
        segments.filter { it.kind != Kind.ADDED }.joinToString("") { it.text }

    /** The text as it reads once the edit is applied — the "after". */
    fun after(segments: List<Segment>): String =
        segments.filter { it.kind != Kind.REMOVED }.joinToString("") { it.text }
}
