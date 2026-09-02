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
    val handedness: Handedness = Handedness.RIGHT,
    val bubbleEnabled: Boolean = false,

    // Onboarding
    val onboardingComplete: Boolean = false,
    val measuredRealTimeFactor: Double? = null,
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

class SettingsRepository(private val context: Context) {

    val config: Flow<ScribeConfig> = context.dataStore.data
        .catch { e ->
            // A corrupt preferences file must not stop the keyboard from working.
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { it.toConfig() }

    suspend fun update(transform: (ScribeConfig) -> ScribeConfig) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toConfig())
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
            prefs[K_POLISH] = next.polishEnabled
            prefs[K_POLISH_MODEL] = next.polishModel
            prefs[K_TONE] = next.tonePerApp.mapValues { it.value.name }.toJson()
            prefs[K_DICTIONARY] = next.dictionary.toJson()
            prefs[K_SNIPPETS] = next.snippets.toJson()
            prefs[K_HISTORY] = next.historyEnabled
            prefs[K_HANDEDNESS] = next.handedness.name
            prefs[K_BUBBLE] = next.bubbleEnabled
            prefs[K_ONBOARDED] = next.onboardingComplete
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
            polishEnabled = this[K_POLISH] ?: defaults.polishEnabled,
            polishModel = this[K_POLISH_MODEL] ?: defaults.polishModel,
            tonePerApp = (this[K_TONE]?.fromJson() ?: emptyMap()).mapNotNull { (k, v) ->
                runCatching { k to ToneProfile.valueOf(v) }.getOrNull()
            }.toMap(),
            dictionary = this[K_DICTIONARY]?.fromJson() ?: defaults.dictionary,
            snippets = this[K_SNIPPETS]?.fromJson() ?: defaults.snippets,
            historyEnabled = this[K_HISTORY] ?: defaults.historyEnabled,
            handedness = this[K_HANDEDNESS]?.let {
                runCatching { Handedness.valueOf(it) }.getOrNull()
            } ?: defaults.handedness,
            bubbleEnabled = this[K_BUBBLE] ?: defaults.bubbleEnabled,
            onboardingComplete = this[K_ONBOARDED] ?: defaults.onboardingComplete,
            measuredRealTimeFactor = this[K_RTF]?.toDoubleOrNull(),
        )
    }

    private companion object {
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
        val K_POLISH = booleanPreferencesKey("polish_enabled")
        val K_POLISH_MODEL = stringPreferencesKey("polish_model")
        val K_TONE = stringPreferencesKey("tone_per_app")
        val K_DICTIONARY = stringPreferencesKey("dictionary")
        val K_SNIPPETS = stringPreferencesKey("snippets")
        val K_HISTORY = booleanPreferencesKey("history_enabled")
        val K_HANDEDNESS = stringPreferencesKey("handedness")
        val K_BUBBLE = booleanPreferencesKey("bubble_enabled")
        val K_ONBOARDED = booleanPreferencesKey("onboarding_complete")
        val K_RTF = stringPreferencesKey("measured_rtf")
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
