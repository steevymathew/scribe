package dev.smantics.scribe.ime

import android.content.res.AssetManager
import android.util.Log
import android.view.inputmethod.InputConnection
import dev.smantics.scribe.core.typing.Corrector
import dev.smantics.scribe.core.typing.TypingRules
import dev.smantics.scribe.core.typing.WordList
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The typing help: capitals, punctuation, completions and corrections.
 *
 * **Everything here happens on this phone and nowhere else.** There is no network call in
 * this file or anything it touches; the dictionary is a text file inside the APK, the user's
 * own words come from the vocabulary they curate in Settings, and nothing is written down.
 * The corrector itself is stateless by construction — it cannot accumulate a record of what
 * you typed even if someone later wanted it to. That is the condition on which autocorrect
 * belongs in an app like this at all, and it is why every piece of it is in `core` with no
 * Android imports, where a test can hold it to account.
 *
 * Each feature is separately switchable, because they are separately opinionated. Capitals
 * and punctuation are conventions almost everybody wants; a corrector that changes a word
 * you meant is the single most irritating thing a keyboard can do, so it is off until asked
 * for.
 */
class TypingAssistant(private val assets: AssetManager) {

    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scribe-dictionary").apply { isDaemon = true }
    }

    @Volatile private var corrector: Corrector = Corrector(WordList.EMPTY)
    @Volatile private var words: WordList = WordList.EMPTY
    @Volatile private var userWords: Set<String> = emptySet()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())

    /** What to offer above the keys, or empty. Read by the panel's strip. */
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    /**
     * Load the dictionary off the main thread.
     *
     * A missing or unreadable asset leaves an empty list, and an empty list is a working
     * keyboard with no suggestions — never a keyboard that fails to start. A dictionary is
     * an enhancement; typing is the product.
     */
    fun warmUp() {
        loader.execute {
            val loaded = runCatching {
                assets.open(DICTIONARY).bufferedReader().use { WordList.parse(it.readText()) }
            }.getOrElse {
                Log.w(TAG, "no dictionary; typing help is off", it)
                WordList.EMPTY
            }
            words = loaded
            corrector = Corrector(loaded, userWords)
            Log.i(TAG, "dictionary loaded: ${loaded.size} words")
        }
    }

    /** The user's own vocabulary, which outranks the shipped list and is never corrected. */
    fun setUserWords(dictionary: Map<String, String>, snippets: Map<String, String>) {
        val mine = (dictionary.keys + dictionary.values + snippets.keys)
            .map { it.lowercase() }
            .filter { it.isNotBlank() && it.all { c -> c in 'a'..'z' } }
            .toSet()
        userWords = mine
        corrector = Corrector(words, mine)
    }

    /**
     * Insert [text], applying whatever help is switched on.
     *
     * The text already in the field is read rather than remembered, so the rules are never
     * wrong about a field the user edited by hand, moved the cursor in, or dictated into.
     */
    fun type(ic: InputConnection, text: String, options: Options) {
        val before = ic.getTextBeforeCursor(LOOKBEHIND, 0)?.toString().orEmpty()

        // A space is where a word finishes, which is the only moment a correction is safe.
        if (text == " " && options.autocorrect) {
            val word = TypingRules.wordBefore(before)
            val fix = corrector.correct(word)
            if (fix != null && fix != word) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(word.length, 0)
                ic.commitText(fix, 1)
                ic.endBatchEdit()
                commit(ic, " ")
                refresh(ic, options)
                return
            }
        }

        if (text == " " && options.smartPunctuation) {
            when (val outcome = TypingRules.doubleSpace(before)) {
                is TypingRules.Outcome.Replace -> {
                    replace(ic, outcome)
                    refresh(ic, options)
                    return
                }
                TypingRules.Outcome.AsTyped -> Unit
            }
        }

        if (options.smartPunctuation) {
            when (val outcome = TypingRules.tidyPunctuation(before, text)) {
                is TypingRules.Outcome.Replace -> {
                    replace(ic, outcome)
                    refresh(ic, options)
                    return
                }
                TypingRules.Outcome.AsTyped -> Unit
            }
        }

        // Auto-capitalise applies to the letter itself, not to a key state, so shift is
        // never left looking as though the user pressed it.
        val out = if (
            options.autoCapitalise &&
            text.length == 1 &&
            text[0].isLowerCase() &&
            TypingRules.shouldCapitalise(before)
        ) {
            text.uppercase()
        } else {
            text
        }
        commit(ic, out)
        refresh(ic, options)
    }

    /** Replace the word before the cursor with a suggestion the user picked. */
    fun accept(ic: InputConnection, suggestion: String, options: Options) {
        val before = ic.getTextBeforeCursor(LOOKBEHIND, 0)?.toString().orEmpty()
        val word = TypingRules.wordBefore(before)
        ic.beginBatchEdit()
        if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
        ic.commitText("$suggestion ", 1)
        ic.endBatchEdit()
        refresh(ic, options)
    }

    /** Recompute what to offer. Called after every edit, including backspace. */
    fun refresh(ic: InputConnection?, options: Options) {
        if (!options.suggestions || ic == null) {
            _suggestions.value = emptyList()
            return
        }
        val before = ic.getTextBeforeCursor(LOOKBEHIND, 0)?.toString().orEmpty()
        val word = TypingRules.wordBefore(before)
        _suggestions.value = if (word.isEmpty()) emptyList() else corrector.suggest(word)
    }

    fun clear() {
        _suggestions.value = emptyList()
    }

    private fun commit(ic: InputConnection, text: String) {
        ic.beginBatchEdit()
        try {
            ic.commitText(text, 1)
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun replace(ic: InputConnection, outcome: TypingRules.Outcome.Replace) {
        ic.beginBatchEdit()
        try {
            if (outcome.deleteBefore > 0) ic.deleteSurroundingText(outcome.deleteBefore, 0)
            ic.commitText(outcome.insert, 1)
        } finally {
            ic.endBatchEdit()
        }
    }

    /** Which of the four helpers are switched on. */
    data class Options(
        val autoCapitalise: Boolean,
        val smartPunctuation: Boolean,
        val autocorrect: Boolean,
        val suggestions: Boolean,
    )

    private companion object {
        const val TAG = "ScribeTyping"
        const val DICTIONARY = "dict/en.txt"

        /** Enough context to find a word boundary without reading the whole field. */
        const val LOOKBEHIND = 96
    }
}
