package dev.smantics.scribe.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.smantics.scribe.core.clean.CleanContext
import dev.smantics.scribe.core.clean.CleanOptions
import dev.smantics.scribe.core.clean.Mode
import dev.smantics.scribe.core.clean.ToneProfile
import dev.smantics.scribe.ui.theme.Handedness
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("scribe")

/**
 * Everything the user can change, and everything Scribe remembers.
 *
 * Keys are deliberately the same names as the desktop build's `config.DEFAULTS` where the
 * concept survives the move to a phone (`model`, `heavy_model`, `language`, `beam_size`,
 * `remove_fillers`, `dictionary`, `history_enabled`). Hotkeys do not survive; a keyboard
 * has no push-to-talk key. Mode, handedness, tone profiles and polish are new.
 *
 * Defaults carry the desktop's judgements with them, including the awkward ones:
 * [historyEnabled] is off, so nothing is written to disk until the user says so.
 */
data class ScribeConfig(
    // Models
    val model: String = "base.en",
    val heavyModel: String = "small.en",
    val language: String = "en",
    val beamSize: Int = 1,

    // The toggle beside the waveform
    val mode: Mode = Mode.CLEAN,

    // Clean pipeline
    val removeFillers: Boolean = true,
    val removeVerbalTics: Boolean = false,
    val spokenPunctuation: Boolean = true,
    val selfCorrection: Boolean = true,
    val formatNumbers: Boolean = true,
    val detectLists: Boolean = true,
    val sentenceCase: Boolean = true,

    /**
     * Break the text into sentences, paragraphs and list items.
     *
     * This is most of what makes Clean look different from Raw over a long dictation —
     * without it, two minutes of speech arrives as one block.
     */
    val paragraphs: Boolean = true,
    val splitSentences: Boolean = true,

    // Optional LLM polish. Off until a model is installed and the user turns it on.
    val polishEnabled: Boolean = false,
    val polishModel: String = "gemma-3-270m-it",

    // Per-app tone. Key is the package being typed into; unknown apps get NEUTRAL.
    val tonePerApp: Map<String, ToneProfile> = emptyMap(),

    // User vocabulary
    val dictionary: Map<String, String> = emptyMap(),
    val snippets: Map<String, String> = emptyMap(),

    // Privacy
    val historyEnabled: Boolean = false,

    // Layout
    /**
     * Whether the letters are showing.
     *
     * Remembered across every appearance of the keyboard: if they were up when it was last
     * dismissed they come back up. Voice is the primary mode, so the first run starts with
     * them away — after that it is the user's choice, not the app's.
     */
    val keyboardShown: Boolean = false,
    val handedness: Handedness = Handedness.RIGHT,

    /**
     * Whether the keys are drawn in two halves.
     *
     * A setting rather than a key on the keyboard. Splitting is a decision about *this
     * device in this posture* — it is right on a Fold's inner display and absurd on a
     * cover screen — and a decision made once does not need a permanent button on a
     * surface that sits on top of what the user is writing. [SplitMode.AUTO] follows the
     * screen width, which is the answer almost everybody wants.
     */
    val keyboardSplit: SplitMode = SplitMode.AUTO,

    /**
     * How much of the width the keyboard fills.
     *
     * Also a setting, for the same reason, and also because the control it replaced cycled
     * blindly through three values with no way to see which one you were on.
     */
    val keyboardWidth: KeyboardWidth = KeyboardWidth.FULL,

    /**
     * A bubble above the key under your thumb.
     *
     * **Off by default**, and that is a considered choice rather than caution: it is the
     * standard answer to a finger hiding its own target, and it is also the one thing on
     * this keyboard that interrupts the calm of the card. Somebody who wants it will find
     * it; somebody who does not should never have to turn it off.
     */
    val keyPreview: Boolean = false,

    /** A capital at the start of a sentence. On: nearly everybody wants this. */
    val autoCapitalise: Boolean = true,

    /** Double space for a full stop, and punctuation that clings to the word before it. */
    val smartPunctuation: Boolean = true,

    /**
     * Fix a mistyped word when the space lands.
     *
     * Off by default. A corrector that changes a word you meant is the most irritating
     * thing a keyboard can do, and the user should meet that behaviour because they asked
     * for it. Everything it uses is on the phone — the shipped dictionary and the words in
     * [dictionary] below — and nothing it does is written down.
     */
    val autocorrect: Boolean = false,

    /** Offer completions above the keys while typing. */
    val suggestions: Boolean = false,
    val bubbleEnabled: Boolean = false,

    // Onboarding
    val onboardingComplete: Boolean = false,

    /**
     * How long decoding takes relative to the length of what was said, averaged over
     * recent dictations. Below 1.0 means the phone transcribes faster than people speak.
     *
     * Measured from real use rather than a synthetic benchmark at first launch: the
     * numbers are then true for this phone, this model and this person's utterance
     * lengths, and nobody pays a startup cost for a measurement that a real dictation
     * would have produced anyway.
     */
    val measuredRealTimeFactor: Double? = null,

    /**
     * Packages Scribe has actually been used in, most recent first. Populated only by
     * dictating; Android's package list is a restricted permission and is never requested.
     * This is what the tone screen offers, so it can only ever list apps the user has
     * already dictated into.
     */
    val recentApps: List<String> = emptyList(),
) {
    /**
     * The clean-pipeline context for a given app.
     *
     * Tone is looked up by package name only — Scribe never reads the field's contents,
     * the window title, or anything else on screen. This is the whole feature Wispr Flow
     * implements by sending screen context to its servers, done with the one piece of
     * information Android hands an input method for free.
     */
    fun cleanContext(packageName: String?): CleanContext = CleanContext(
        dictionary = dictionary,
        snippets = snippets,
        tone = tonePerApp[packageName] ?: ToneProfile.NEUTRAL,
        options = CleanOptions(
            spokenPunctuation = spokenPunctuation,
            removeFillers = removeFillers,
            removeVerbalTics = removeVerbalTics,
            selfCorrection = selfCorrection,
            formatNumbers = formatNumbers,
            detectLists = detectLists,
            sentenceCase = sentenceCase,
            paragraphs = paragraphs,
            splitSentences = splitSentences,
        ),
    )

    /**
     * The dictionary as an initial prompt for Whisper, so a name Scribe has been told
     * about is more likely to be *heard* right rather than only corrected afterwards.
     * Same trick as the desktop's ct2 backend.
     */
    fun initialPrompt(): String? =
        dictionary.values.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/**
 * The handful of values the keyboard has to know *before* it draws its first frame.
 *
 * DataStore is asynchronous by design, and that is right for everything except layout:
 * the panel was composing with the defaults, then re-composing a frame or two later when
 * the real settings arrived, so a user who kept the letters up saw the keyboard open
 * short and then grow — the window resizing under the app mid-animation. There is no
 * amount of animation tuning that fixes a first frame with the wrong height in it.
 */
data class LayoutSnapshot(
    val keyboardShown: Boolean,
    val handedness: Handedness,
    val split: SplitMode,
    val width: KeyboardWidth,
)

/** Whether the keys are drawn in two halves. See [ScribeConfig.keyboardSplit]. */
enum class SplitMode(val label: String) {
    /** Split on a screen wide enough to need it, which is the Fold open and little else. */
    AUTO("Automatic"),
    NEVER("Never split"),
    ALWAYS("Always split"),
}

/** How much of the width the keyboard fills. See [ScribeConfig.keyboardWidth]. */
enum class KeyboardWidth(val fraction: Float, val label: String) {
    FULL(1f, "Full width"),
    WIDE(0.86f, "Slightly narrower"),
    COMPACT(0.68f, "Compact"),
}

class SettingsRepository(private val context: Context) {

    val config: Flow<ScribeConfig> = context.dataStore.data
        .catch { e ->
            // A corrupt preferences file must not stop the keyboard from working.
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { it.toConfig() }

    /**
     * A synchronous mirror of the layout settings, for [LayoutSnapshot].
     *
     * Deliberately a plain SharedPreferences file and not a second source of truth:
     * DataStore remains the record, this is written through on every change, and nothing
     * ever reads it except the keyboard's first frame. If it is missing or stale by one
     * change the panel simply opens the way it did last time, which is the same thing the
     * user saw a moment ago and never worse than the default.
     */
    private val layoutMirror by lazy {
        context.getSharedPreferences(LAYOUT_MIRROR, Context.MODE_PRIVATE)
    }

    /** Read on the main thread, in the microseconds before the panel is composed. */
    val layoutSnapshot: LayoutSnapshot
        get() {
            val defaults = ScribeConfig()
            return LayoutSnapshot(
                keyboardShown = layoutMirror.getBoolean(M_KEYS_SHOWN, defaults.keyboardShown),
                handedness = layoutMirror.getString(M_HANDEDNESS, null)
                    ?.let { runCatching { Handedness.valueOf(it) }.getOrNull() }
                    ?: defaults.handedness,
                split = layoutMirror.getString(M_SPLIT, null)
                    ?.let { runCatching { SplitMode.valueOf(it) }.getOrNull() }
                    ?: defaults.keyboardSplit,
                width = layoutMirror.getString(M_WIDTH, null)
                    ?.let { runCatching { KeyboardWidth.valueOf(it) }.getOrNull() }
                    ?: defaults.keyboardWidth,
            )
        }

    suspend fun update(transform: (ScribeConfig) -> ScribeConfig) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toConfig())
            layoutMirror.edit()
                .putBoolean(M_KEYS_SHOWN, next.keyboardShown)
                .putString(M_HANDEDNESS, next.handedness.name)
                .putString(M_SPLIT, next.keyboardSplit.name)
                .putString(M_WIDTH, next.keyboardWidth.name)
                .apply()
            prefs[K_MODEL] = next.model
            prefs[K_HEAVY_MODEL] = next.heavyModel
            prefs[K_LANGUAGE] = next.language
            prefs[K_BEAM] = next.beamSize
            prefs[K_MODE] = next.mode.name
            prefs[K_FILLERS] = next.removeFillers
            prefs[K_TICS] = next.removeVerbalTics
            prefs[K_PUNCT] = next.spokenPunctuation
            prefs[K_CORRECTION] = next.selfCorrection
            prefs[K_NUMBERS] = next.formatNumbers
            prefs[K_LISTS] = next.detectLists
            prefs[K_CASE] = next.sentenceCase
            prefs[K_PARAGRAPHS] = next.paragraphs
            prefs[K_SPLIT] = next.splitSentences
            prefs[K_POLISH] = next.polishEnabled
            prefs[K_POLISH_MODEL] = next.polishModel
            prefs[K_TONE] = next.tonePerApp.mapValues { it.value.name }.toJson()
            prefs[K_DICTIONARY] = next.dictionary.toJson()
            prefs[K_SNIPPETS] = next.snippets.toJson()
            prefs[K_HISTORY] = next.historyEnabled
            prefs[K_KEYS_SHOWN] = next.keyboardShown
            prefs[K_KB_SPLIT] = next.keyboardSplit.name
            prefs[K_KB_WIDTH] = next.keyboardWidth.name
            prefs[K_KEY_PREVIEW] = next.keyPreview
            prefs[K_AUTO_CAPS] = next.autoCapitalise
            prefs[K_SMART_PUNCT] = next.smartPunctuation
            prefs[K_AUTOCORRECT] = next.autocorrect
            prefs[K_SUGGESTIONS] = next.suggestions
            prefs[K_HANDEDNESS] = next.handedness.name
            prefs[K_BUBBLE] = next.bubbleEnabled
            prefs[K_ONBOARDED] = next.onboardingComplete
            prefs[K_RECENT_APPS] = next.recentApps.take(MAX_RECENT_APPS).joinToString("\n")
            next.measuredRealTimeFactor?.let { prefs[K_RTF] = it.toString() }
        }
    }

    private fun Preferences.toConfig(): ScribeConfig {
        val defaults = ScribeConfig()
        return ScribeConfig(
            model = this[K_MODEL] ?: defaults.model,
            heavyModel = this[K_HEAVY_MODEL] ?: defaults.heavyModel,
            language = this[K_LANGUAGE] ?: defaults.language,
            beamSize = this[K_BEAM] ?: defaults.beamSize,
            mode = this[K_MODE]?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: defaults.mode,
            removeFillers = this[K_FILLERS] ?: defaults.removeFillers,
            removeVerbalTics = this[K_TICS] ?: defaults.removeVerbalTics,
            spokenPunctuation = this[K_PUNCT] ?: defaults.spokenPunctuation,
            selfCorrection = this[K_CORRECTION] ?: defaults.selfCorrection,
            formatNumbers = this[K_NUMBERS] ?: defaults.formatNumbers,
            detectLists = this[K_LISTS] ?: defaults.detectLists,
            sentenceCase = this[K_CASE] ?: defaults.sentenceCase,
            paragraphs = this[K_PARAGRAPHS] ?: defaults.paragraphs,
            splitSentences = this[K_SPLIT] ?: defaults.splitSentences,
            polishEnabled = this[K_POLISH] ?: defaults.polishEnabled,
            polishModel = this[K_POLISH_MODEL] ?: defaults.polishModel,
            tonePerApp = (this[K_TONE]?.fromJson() ?: emptyMap()).mapNotNull { (k, v) ->
                runCatching { k to ToneProfile.valueOf(v) }.getOrNull()
            }.toMap(),
            dictionary = this[K_DICTIONARY]?.fromJson() ?: defaults.dictionary,
            snippets = this[K_SNIPPETS]?.fromJson() ?: defaults.snippets,
            historyEnabled = this[K_HISTORY] ?: defaults.historyEnabled,
            keyboardShown = this[K_KEYS_SHOWN] ?: defaults.keyboardShown,
            keyboardSplit = this[K_KB_SPLIT]?.let {
                runCatching { SplitMode.valueOf(it) }.getOrNull()
            } ?: defaults.keyboardSplit,
            keyboardWidth = this[K_KB_WIDTH]?.let {
                runCatching { KeyboardWidth.valueOf(it) }.getOrNull()
            } ?: defaults.keyboardWidth,
            keyPreview = this[K_KEY_PREVIEW] ?: defaults.keyPreview,
            autoCapitalise = this[K_AUTO_CAPS] ?: defaults.autoCapitalise,
            smartPunctuation = this[K_SMART_PUNCT] ?: defaults.smartPunctuation,
            autocorrect = this[K_AUTOCORRECT] ?: defaults.autocorrect,
            suggestions = this[K_SUGGESTIONS] ?: defaults.suggestions,
            handedness = this[K_HANDEDNESS]?.let {
                runCatching { Handedness.valueOf(it) }.getOrNull()
            } ?: defaults.handedness,
            bubbleEnabled = this[K_BUBBLE] ?: defaults.bubbleEnabled,
            onboardingComplete = this[K_ONBOARDED] ?: defaults.onboardingComplete,
            measuredRealTimeFactor = this[K_RTF]?.toDoubleOrNull(),
            recentApps = this[K_RECENT_APPS]?.split("\n")?.filter { it.isNotBlank() }
                ?: defaults.recentApps,
        )
    }

    private companion object {
        /** The synchronous layout mirror; see [layoutMirror]. */
        const val LAYOUT_MIRROR = "scribe-layout"
        const val M_KEYS_SHOWN = "keyboard_shown"
        const val M_HANDEDNESS = "handedness"
        const val M_SPLIT = "keyboard_split"
        const val M_WIDTH = "keyboard_width"

        val K_MODEL = stringPreferencesKey("model")
        val K_HEAVY_MODEL = stringPreferencesKey("heavy_model")
        val K_LANGUAGE = stringPreferencesKey("language")
        val K_BEAM = intPreferencesKey("beam_size")
        val K_MODE = stringPreferencesKey("mode")
        val K_FILLERS = booleanPreferencesKey("remove_fillers")
        val K_TICS = booleanPreferencesKey("remove_verbal_tics")
        val K_PUNCT = booleanPreferencesKey("spoken_punctuation")
        val K_CORRECTION = booleanPreferencesKey("self_correction")
        val K_NUMBERS = booleanPreferencesKey("format_numbers")
        val K_LISTS = booleanPreferencesKey("detect_lists")
        val K_CASE = booleanPreferencesKey("sentence_case")
        val K_PARAGRAPHS = booleanPreferencesKey("paragraphs")
        val K_SPLIT = booleanPreferencesKey("split_sentences")
        val K_POLISH = booleanPreferencesKey("polish_enabled")
        val K_POLISH_MODEL = stringPreferencesKey("polish_model")
        val K_TONE = stringPreferencesKey("tone_per_app")
        val K_DICTIONARY = stringPreferencesKey("dictionary")
        val K_SNIPPETS = stringPreferencesKey("snippets")
        val K_HISTORY = booleanPreferencesKey("history_enabled")
        val K_KEYS_SHOWN = booleanPreferencesKey("keyboard_shown")
        val K_KB_SPLIT = stringPreferencesKey("keyboard_split")
        val K_KB_WIDTH = stringPreferencesKey("keyboard_width")
        val K_KEY_PREVIEW = booleanPreferencesKey("key_preview")
        val K_AUTO_CAPS = booleanPreferencesKey("auto_capitalise")
        val K_SMART_PUNCT = booleanPreferencesKey("smart_punctuation")
        val K_AUTOCORRECT = booleanPreferencesKey("autocorrect")
        val K_SUGGESTIONS = booleanPreferencesKey("suggestions")
        val K_HANDEDNESS = stringPreferencesKey("handedness")
        val K_BUBBLE = booleanPreferencesKey("bubble_enabled")
        val K_ONBOARDED = booleanPreferencesKey("onboarding_complete")
        val K_RTF = stringPreferencesKey("measured_rtf")
        val K_RECENT_APPS = stringPreferencesKey("recent_apps")

        /** Enough to cover the handful of apps anyone actually dictates into. */
        const val MAX_RECENT_APPS = 12
    }
}

// org.json rather than a serialization plugin: two flat string maps do not justify
// another compiler plugin in the build, and org.json is already on every Android device.
private fun Map<String, String>.toJson(): String =
    JSONObject(this as Map<*, *>).toString()

private fun String.fromJson(): Map<String, String> = runCatching {
    val obj = JSONObject(this)
    buildMap {
        obj.keys().forEach { key -> put(key, obj.optString(key)) }
    }
}.getOrDefault(emptyMap())
