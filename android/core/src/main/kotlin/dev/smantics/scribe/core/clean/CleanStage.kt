package dev.smantics.scribe.core.clean

/**
 * The transcript post-processing pipeline.
 *
 * Ported from the desktop build's `src/scribe/postproc.py` and extended to close the
 * Wispr Flow gaps listed in `ROADMAP.md` §3. The desktop pipeline had four steps and one
 * toggle; §7.4 asks for "ordered, each step toggleable in config", which is what this is.
 *
 * Every stage is a pure function of (text, context). That is not incidental:
 *
 *  - it makes the whole pipeline testable on a JVM with no Android and no device, which
 *    is the only kind of testing available on the build machine;
 *  - it is what lets the keyboard re-clean text that has *already* been inserted, because
 *    the raw string can be replayed through a different pipeline at any later moment.
 *
 * Order matters and is fixed by [CleanPipeline]. Annotations are stripped first (so a
 * silence-only clip becomes empty before anything else can act on it) and spacing is
 * normalised last (so every earlier stage may leave loose whitespace behind).
 */
interface CleanStage {
    /** Stable identifier, used by settings toggles and by test golden files. */
    val id: String

    fun apply(text: String, ctx: CleanContext): String
}

/**
 * Which locale-ish conventions a stage should assume, and what the user has turned on.
 *
 * [tone] is the per-app profile — the Wispr "adapts to how you write in different apps"
 * feature, done without Wispr's method of shipping your screen contents to a server. All
 * we use is the package name of the field being typed into, which never leaves the device.
 */
data class CleanContext(
    val dictionary: Map<String, String> = emptyMap(),
    val snippets: Map<String, String> = emptyMap(),
    val tone: ToneProfile = ToneProfile.NEUTRAL,
    val options: CleanOptions = CleanOptions(),
)

/**
 * Per-stage toggles.
 *
 * Defaults are deliberately close to the desktop build's behaviour, with the exception of
 * [removeFillers], which is on here because Clean mode's entire purpose is to clean. Raw
 * mode does not consult these at all — see [RawPipeline].
 */
data class CleanOptions(
    val spokenPunctuation: Boolean = true,
    val removeDisfluencies: Boolean = true,
    val removeFillers: Boolean = true,
    /**
     * "you know", "I mean" as fillers. Off by default and deliberately so: the desktop
     * roadmap flags "you know" as excluded "until tested" (§7.4), because it is also a
     * real phrase. Left as an explicit opt-in rather than quietly enabled.
     */
    val removeVerbalTics: Boolean = false,
    val selfCorrection: Boolean = true,
    val formatNumbers: Boolean = true,
    val detectLists: Boolean = true,
    val sentenceCase: Boolean = true,
    val applyDictionary: Boolean = true,
    val expandSnippets: Boolean = true,
)

/**
 * How finished the text should look.
 *
 * This is a light touch on purpose. A dictation tool that rewrites your voice into someone
 * else's register has stopped being a dictation tool, so tone here only decides how much
 * *typographic* tidying to do — never word choice.
 */
enum class ToneProfile {
    /** No opinion: capitalise sentences, but do not force a full stop onto a fragment. */
    NEUTRAL,

    /** Chat and messaging. Terse; a trailing full stop on a one-liner reads as cold. */
    CASUAL,

    /** Mail, documents, issue trackers. Sentences end in punctuation. */
    FORMAL,
}
