package dev.smantics.scribe.core.clean

/**
 * The two modes the user switches between with the toggle beside the waveform.
 *
 * The distinction is deliberately absolute rather than a slider: RAW is what you said,
 * CLEAN is what you meant. A user who cannot predict which one they will get has to proof-
 * read every result, which costs more than dictation saves.
 */
enum class Mode { RAW, CLEAN }

/**
 * RAW — a faithful conversion of anything said into text.
 *
 * Not quite "no processing": Whisper reports a silent clip as the literal string
 * `[BLANK_AUDIO]`, and spacing artefacts come out of the decoder rather than out of the
 * speaker. Neither is something the user said, so both are removed. Nothing else is.
 */
object RawPipeline {
    private val stages: List<CleanStage> = listOf(StripAnnotations, StripPauseMarkers, NormalizeSpacing)

    fun run(text: String, ctx: CleanContext = CleanContext()): String =
        stages.fold(text) { acc, stage -> stage.apply(acc, ctx) }
}

/**
 * CLEAN — formatted, punctuated, de-filled text.
 *
 * Order is load-bearing:
 *
 *  - annotations go first, so a silent clip is empty before any stage can act on it;
 *  - spoken punctuation goes early, so later stages see real sentence boundaries;
 *  - disfluencies before fillers, so "I I um I think" collapses cleanly;
 *  - self-correction after both, so the corrector cue is adjacent to its replacement;
 *  - structure — run-ons split, lists found, paragraphs opened — before casing, so every
 *    sentence and list item that appears gets its capital letter;
 *  - sentence casing before the dictionary and snippets, so a user's deliberate lowercase
 *    ("iPhone", "eBay") is not "corrected" into a capital at the start of a sentence;
 *  - spacing last, because every stage above may leave a gap behind.
 */
object CleanPipeline {
    val stages: List<CleanStage> = listOf(
        StripAnnotations,
        SpokenPunctuation,
        RemoveDisfluencies,
        RemoveFillers,
        SelfCorrection,
        FormatNumbers,
        // Structure before casing, so every new sentence and list item gets its capital.
        // Paragraphs runs before the list stages, not after: it turns the transcriber's
        // pause markers into real line breaks, and the list rules look for an item at the
        // start of a line. Behind a marker they saw no boundary and found no list.
        SplitRunOns,
        Paragraphs,
        DetectLists,
        SpokenEnumeration,
        SentenceCase,
        ApplyDictionary,
        ExpandSnippets,
        NormalizeSpacing,
    )

    fun run(text: String, ctx: CleanContext = CleanContext()): String =
        stages.fold(text) { acc, stage -> stage.apply(acc, ctx) }

    /** Run only up to and including [stageId]. Used by the settings preview and by tests. */
    fun runThrough(stageId: String, text: String, ctx: CleanContext = CleanContext()): String {
        var out = text
        for (stage in stages) {
            out = stage.apply(out, ctx)
            if (stage.id == stageId) break
        }
        return out
    }
}

/**
 * Entry point used by the dictation engine and by the keyboard's re-clean action.
 *
 * Holding onto [raw] is what makes the toggle reversible after the text has already been
 * inserted: the keyboard can replace what it committed with the other mode's rendering of
 * the same utterance, with no re-dictation and no model call.
 */
data class Transcript(
    val raw: String,
    val ctx: CleanContext = CleanContext(),
) {
    fun render(mode: Mode): String = when (mode) {
        Mode.RAW -> RawPipeline.run(raw, ctx)
        Mode.CLEAN -> CleanPipeline.run(raw, ctx)
    }
}
