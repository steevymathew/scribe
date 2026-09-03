package dev.smantics.scribe.core.model

/**
 * The models Scribe can use, what they cost, and what they are for.
 *
 * Sizes and SHA-256 digests below were read from Hugging Face's own metadata, not guessed.
 * Every download is verified against its digest before use: a truncated model does not
 * fail loudly, it fails as bad transcription, which is much harder to diagnose from the
 * user's side.
 */

enum class ModelKind { SPEECH, POLISH }

/**
 * How demanding a model is, used to pick a sensible default on first run and to warn
 * before a download that will disappoint on the device holding it.
 */
enum class Tier(val label: String, val blurb: String) {
    FEATHER("Feather", "For older or budget phones. Fast, and noticeably rougher."),
    EVERYDAY("Everyday", "The default. Good on any phone from the last few years."),
    SHARP("Sharp", "Better with names, jargon and accents. Best on a recent flagship."),
    MAX("Max", "The most accurate model there is. Slow unless the phone is quick."),
}

data class ModelSpec(
    val id: String,
    val displayName: String,
    val kind: ModelKind,
    val tier: Tier,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Rough working set once loaded, used to keep a model off a phone that cannot hold it. */
    val approxRamMb: Int,
    /** True for the one shipped inside the APK. */
    val bundled: Boolean = false,
    val notes: String = "",
) {
    val sizeMb: Int get() = (sizeBytes / (1024 * 1024)).toInt()
}

object ModelRegistry {

    private const val WHISPER_BASE =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    val FEATHER = ModelSpec(
        id = "tiny.en",
        displayName = "Tiny (English)",
        kind = ModelKind.SPEECH,
        tier = Tier.FEATHER,
        fileName = "ggml-tiny.en-q5_1.bin",
        url = WHISPER_BASE + "ggml-tiny.en-q5_1.bin",
        sizeBytes = 32_166_155,
        sha256 = "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b",
        approxRamMb = 120,
        notes = "Use when a phone struggles with Base. Expect more mistakes on names.",
    )

    /**
     * Shipped inside the APK so Scribe works the moment it is installed, with no network
     * and no download — which is the point of a private dictation app.
     */
    val EVERYDAY = ModelSpec(
        id = "base.en",
        displayName = "Base (English)",
        kind = ModelKind.SPEECH,
        tier = Tier.EVERYDAY,
        fileName = "ggml-base.en-q5_1.bin",
        url = WHISPER_BASE + "ggml-base.en-q5_1.bin",
        sizeBytes = 59_721_011,
        sha256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f",
        approxRamMb = 200,
        bundled = true,
        notes = "Included with Scribe. Works offline from the first launch.",
    )

    val SHARP = ModelSpec(
        id = "small.en",
        displayName = "Small (English)",
        kind = ModelKind.SPEECH,
        tier = Tier.SHARP,
        fileName = "ggml-small.en-q5_1.bin",
        url = WHISPER_BASE + "ggml-small.en-q5_1.bin",
        sizeBytes = 190_098_681,
        sha256 = "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30",
        approxRamMb = 500,
        notes = "The recommended upgrade on a recent flagship.",
    )

    val MAX = ModelSpec(
        id = "large-v3-turbo",
        displayName = "Large v3 Turbo",
        kind = ModelKind.SPEECH,
        tier = Tier.MAX,
        fileName = "ggml-large-v3-turbo-q5_0.bin",
        url = WHISPER_BASE + "ggml-large-v3-turbo-q5_0.bin",
        sizeBytes = 574_041_195,
        sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        approxRamMb = 1_400,
        notes = "Multilingual as well as English. Reserve it for the high-accuracy key.",
    )

    /**
     * Polish models.
     *
     * **Both are optional, and neither is recommended.** Tested on real dictation, a
     * 270M-parameter model is below the threshold where it helps: it rephrases where it
     * should tidy, and it cannot produce the lists and formatting people actually want —
     * which the rule pipeline does anyway, deterministically and instantly.
     *
     * This is not a tuning problem. Wispr Flow gets its formatting from a *fine-tuned
     * Llama running in a datacentre*, and the gap between that and a quarter-billion
     * parameters on a phone is not something a better prompt closes. Clean mode is
     * complete without either of these, and they are left here as an experiment rather
     * than a feature.
     *
     * Qwen 3 0.6B carries an extra caveat: it is a reasoning model and will sometimes emit
     * a thinking block instead of the cleaned text. PolishGuard rejects that outright, so
     * the failure mode is "polish did nothing", not "polish pasted its reasoning into your
     * message".
     */
    val POLISH_DEFAULT = ModelSpec(
        id = "gemma-3-270m-it",
        displayName = "Gemma 3 270M",
        kind = ModelKind.POLISH,
        tier = Tier.EVERYDAY,
        fileName = "gemma-3-270m-it-Q8_0.gguf",
        url = "https://huggingface.co/ggml-org/gemma-3-270m-it-GGUF/resolve/main/" +
            "gemma-3-270m-it-Q8_0.gguf",
        sizeBytes = 291_545_600,
        sha256 = "0ef57d2c838458a1952664260dcba38e5bdda37494f3af732f06e4add24068e3",
        approxRamMb = 450,
        notes = "Not recommended. At this size the model tends to make transcripts worse " +
            "rather than better, and it cannot do the formatting the rules already do.",
    )

    val POLISH_STRONG = ModelSpec(
        id = "qwen3-0.6b",
        displayName = "Qwen 3 0.6B",
        kind = ModelKind.POLISH,
        tier = Tier.SHARP,
        fileName = "Qwen3-0.6B-Q4_0.gguf",
        url = "https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF/resolve/main/" +
            "Qwen3-0.6B-Q4_0.gguf",
        sizeBytes = 428_970_080,
        sha256 = "da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4",
        approxRamMb = 900,
        notes = "Stronger than the 270M and still not clearly better than the rules alone. " +
            "Occasionally shows its reasoning instead of an answer, which Scribe discards.",
    )

    val speech: List<ModelSpec> = listOf(FEATHER, EVERYDAY, SHARP, MAX)
    val polish: List<ModelSpec> = listOf(POLISH_DEFAULT, POLISH_STRONG)
    val all: List<ModelSpec> = speech + polish

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    /**
     * Pick a default speech model from what the device can actually do.
     *
     * [decodeRealTimeFactor] is measured on first run by decoding a short bundled clip:
     * below 1.0 means the phone transcribes faster than people speak. The thresholds are
     * chosen so the everyday model never feels slow, rather than to show off the biggest
     * model a device can technically hold.
     */
    fun recommend(totalRamMb: Int, decodeRealTimeFactor: Double?): ModelSpec = when {
        totalRamMb < 4_000 -> FEATHER
        decodeRealTimeFactor == null -> EVERYDAY
        decodeRealTimeFactor > 1.2 -> FEATHER
        decodeRealTimeFactor > 0.55 -> EVERYDAY
        totalRamMb >= 8_000 -> SHARP
        else -> EVERYDAY
    }

    /** Whether a model has any business being offered on this device. */
    fun fits(spec: ModelSpec, totalRamMb: Int): Boolean =
        spec.approxRamMb <= totalRamMb / 3
}
