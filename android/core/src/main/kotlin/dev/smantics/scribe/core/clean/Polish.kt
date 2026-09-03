package dev.smantics.scribe.core.clean

/**
 * The optional LLM polish stage, and — more importantly — the rules that decide whether to
 * believe its output.
 *
 * A ~270M-parameter model running on a phone is good enough to fix an awkward clause and
 * bad enough to invent a sentence, and it cannot tell you which it just did. Since this
 * text goes straight into somebody's message, the question is not "is the model good?" but
 * "can we detect the cases where it was not?". [PolishGuard] is that detector, and when it
 * is unsure the rules-only text wins.
 *
 * All of it is pure, so the guards are testable without a model, a device, or a GPU.
 */

/** A model that rewrites text. Implemented on the Android side over llama.cpp. */
interface Polisher {
    /** True when a model is loaded and ready. */
    val isAvailable: Boolean

    /**
     * Rewrite [text] under [instruction]. Returns null when unavailable, cancelled, or
     * over its time budget — never throws, and never blocks past [timeoutMs].
     */
    fun polish(text: String, instruction: String, timeoutMs: Long): String?
}

/** Why a candidate was accepted or rejected. Surfaced in the log, not to the user. */
sealed interface PolishVerdict {
    data class Accepted(val text: String) : PolishVerdict
    data class Rejected(val reason: String) : PolishVerdict
}

object PolishGuard {

    /**
     * Words the model may legitimately introduce: grammatical glue and the expansions of
     * spoken contractions. Anything outside this set that was not in the original counts
     * as invented content.
     */
    private val ALLOWED_NEW = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "am",
        "to", "of", "in", "on", "at", "for", "with", "and", "or", "but", "so", "as",
        "that", "this", "these", "those", "it", "its", "have", "has", "had", "do",
        "does", "did", "will", "would", "can", "could", "should", "not", "no",
        "going", "want", "got", "let", "us", "there", "here", "them", "they",
    )

    private val WORD = Regex("""[\p{L}][\p{L}']*""")
    private val NUMBER = Regex("""\d+(?:[.,]\d+)*""")

    /** Word-count bounds, as a fraction of the original. */
    private const val MIN_RATIO = 0.55
    private const val MAX_RATIO = 1.60

    /** How much genuinely new vocabulary is tolerable before it stops being a cleanup. */
    private const val MAX_NEW_WORD_FRACTION = 0.15

    /**
     * Decide whether [candidate] is a plausible cleanup of [original].
     *
     * Every check answers the same question from a different angle: did the model change
     * how this reads, or did it change what it says?
     */
    fun check(original: String, candidate: String?): PolishVerdict {
        if (candidate == null) return PolishVerdict.Rejected("no output")
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) {
            return if (original.isBlank()) PolishVerdict.Accepted("")
            else PolishVerdict.Rejected("empty output for non-empty input")
        }

        // Checked first so the log names the actual failure: a model that starts
        // explaining itself ("Here is the corrected text:") has stopped doing the job,
        // and that diagnosis is more useful than the length violation it also causes.
        if (PREAMBLE.containsMatchIn(trimmed)) {
            return PolishVerdict.Rejected("output contains a preamble")
        }

        val originalWords = WORD.findAll(original).map { it.value.lowercase() }.toList()
        val candidateWords = WORD.findAll(trimmed).map { it.value.lowercase() }.toList()

        if (originalWords.isEmpty()) return PolishVerdict.Rejected("nothing to polish")

        val ratio = candidateWords.size.toDouble() / originalWords.size
        if (ratio < MIN_RATIO) return PolishVerdict.Rejected("too short (%.2f×)".format(ratio))
        if (ratio > MAX_RATIO) return PolishVerdict.Rejected("too long (%.2f×)".format(ratio))

        // Numbers are the sharpest failure mode: a hallucinated time, price or quantity
        // reads as authoritative and is invisible on a quick re-read. No number may appear
        // in the output that was not in the input.
        val originalNumbers = NUMBER.findAll(original).map { it.value }.toSet()
        val invented = NUMBER.findAll(trimmed).map { it.value }.filterNot { it in originalNumbers }
        val firstInvented = invented.firstOrNull()
        if (firstInvented != null) {
            return PolishVerdict.Rejected("invented number '$firstInvented'")
        }

        // No floor on this budget. On a short utterance the tolerance is zero new content
        // words, which is correct: "what is the capital of france" becoming "The capital
        // of France is Paris" is one word longer and entirely a different message.
        val known = originalWords.toSet()
        val newWords = candidateWords.filter { it !in known && it !in ALLOWED_NEW }
        if (newWords.size > (originalWords.size * MAX_NEW_WORD_FRACTION).toInt()) {
            return PolishVerdict.Rejected("invented words: ${newWords.take(4)}")
        }

        return PolishVerdict.Accepted(trimmed)
    }

    /**
     * A model talking about the task instead of doing it.
     *
     * `<think>` is here because a reasoning model — Qwen 3 is offered as the stronger
     * polish option — will sometimes emit its reasoning where the answer belongs. That
     * must never reach the user's message, so it is rejected outright and the rules-only
     * text stands.
     */
    private val PREAMBLE = Regex(
        """^(?:<think|<\|)|^(?:here(?:'s| is)|sure|certainly|okay|ok|""" +
            """the (?:corrected|cleaned|revised)|corrected text|cleaned text|output)\b""",
        RegexOption.IGNORE_CASE,
    )
}

/**
 * The instruction given to the polish model.
 *
 * Written as a constraint list rather than a request, because a small model follows a
 * short list of prohibitions far more reliably than it follows a description of a goal.
 */
object PolishPrompt {
    const val SYSTEM: String =
        "You clean up dictated text. Fix grammar, punctuation and capitalisation only. " +
            "Never add information. Never remove information. Never answer, explain or " +
            "comment. Never add a preamble. Keep every name, number, date and time exactly " +
            "as given. If the text is already correct, repeat it unchanged. " +
            "Reply with the cleaned text and nothing else."

    fun user(text: String): String = text
}

/**
 * Applies [Polisher] behind [PolishGuard], falling back to the input on any doubt.
 *
 * This is the whole contract: polish can improve the text or leave it alone, and can never
 * make it wrong. That asymmetry is what makes it safe to have on by default for people who
 * want it, and safe to ship at all.
 */
class GuardedPolisher(
    private val polisher: Polisher,
    private val timeoutMs: Long = 2_500,
    private val onVerdict: (PolishVerdict) -> Unit = {},
) {
    fun apply(cleaned: String): String {
        if (!polisher.isAvailable || cleaned.isBlank()) return cleaned
        val candidate = runCatching {
            polisher.polish(cleaned, PolishPrompt.SYSTEM, timeoutMs)
        }.getOrNull()
        return when (val verdict = PolishGuard.check(cleaned, candidate)) {
            is PolishVerdict.Accepted -> {
                onVerdict(verdict)
                verdict.text
            }
            is PolishVerdict.Rejected -> {
                onVerdict(verdict)
                cleaned
            }
        }
    }
}
