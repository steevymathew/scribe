package dev.smantics.scribe.ime

import android.content.Intent
import android.util.Log
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.smantics.scribe.dictation.ScribeEngine
import dev.smantics.scribe.ui.MainActivity
import dev.smantics.scribe.ui.theme.Handedness
import dev.smantics.scribe.ui.theme.ScribeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Scribe as a system keyboard.
 *
 * This is the architectural decision the whole Android port turns on. Wispr Flow injects
 * text from a floating bubble, and its Android users report that the bubble "breaks every
 * five to ten minutes" because Android's battery management stops the process behind it;
 * getting it working at all involves developer options. An input method has none of those
 * problems by construction: the system binds it, keeps it alive for exactly as long as a
 * text field has focus, and asks for no overlay permission, no accessibility service and
 * no battery exemption. Text goes in through `InputConnection`, which every app accepts
 * and which never touches the clipboard.
 *
 * The service is deliberately thin. It owns the view and the input connection; the model,
 * the recording and the last transcript live in [ScribeEngine] at process scope, because
 * this view is destroyed and rebuilt every time the Fold is opened or closed.
 */
class ScribeInputMethodService : InputMethodService() {

    private companion object { const val TAG = "ScribeIME" }

    private lateinit var engine: ScribeEngine
    private val host = ImeComposeHost()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handedness = MutableStateFlow(Handedness.RIGHT)
    private val keysShown = MutableStateFlow(false)

    private val sink = InputConnectionSink { currentInputConnection }

    override fun onCreate() {
        super.onCreate()
        host.onCreate()
        engine = ScribeEngine.get(this)
        engine.warmUp()
        scope.launch {
            engine.settings.config.collect {
                handedness.value = it.handedness
                keysShown.value = it.keyboardShown
            }
        }
    }

    /**
     * Build the panel.
     *
     * The owners are applied to the window's decor as well as to the ComposeView, and the
     * lifecycle is moved to RESUMED here rather than waiting for `onStartInputView` —
     * Compose's frame clock starts paused and only runs from `ON_START`, so a host still
     * at CREATED composes and then draws nothing at all.
     *
     * The whole thing is wrapped. If composition cannot start, the alternative to a
     * fallback view is an input method that is listed, can be enabled, and silently never
     * appears — which is precisely how this failed the first time it was put on a phone.
     * A keyboard that says why it is broken is worth far more than one that vanishes.
     */
    override fun onCreateInputView(): View {
        host.attachToWindowRoot(window?.window?.decorView)
        host.onResume()

        return runCatching {
            ComposeView(this).apply {
                // Torn down with its window rather than with the composition, so a
                // fold/unfold rebuilds cleanly instead of leaking the previous one.
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    val state by engine.state.collectAsState()
                    val hand by handedness.collectAsState()
                    val keys by keysShown.collectAsState()
                    val wide = resources.configuration.screenWidthDp >= SPLIT_WIDTH_DP
                    ScribeTheme(handedness = hand) {
                        ScribePanel(
                            state = state,
                            actions = panelActions,
                            keysShown = keys,
                            onToggleKeys = {
                                scope.launch {
                                    engine.settings.update {
                                        it.copy(keyboardShown = !it.keyboardShown)
                                    }
                                }
                            },
                            splitAvailable = wide,
                        )
                    }
                }
                host.attachTo(this)
            }
        }.getOrElse { failure ->
            Log.e(TAG, "the Compose panel could not be created", failure)
            fallbackView(failure)
        }
    }

    /**
     * A plain-View panel for when Compose will not start.
     *
     * Deliberately built from nothing but framework classes, so it cannot fail for the
     * same reason the real panel did. It reports the failure and offers the one action
     * that always works — going back to the previous keyboard.
     */
    private fun fallbackView(failure: Throwable): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF0B0F14.toInt())
        setPadding(48, 48, 48, 48)
        addView(
            TextView(context).apply {
                text = "Scribe's keyboard could not start on this device."
                setTextColor(0xFFE7EEF4.toInt())
                textSize = 15f
            },
        )
        addView(
            TextView(context).apply {
                text = "${failure::class.java.simpleName}: ${failure.message}"
                setTextColor(0xFF93A1B0.toInt())
                textSize = 12f
                setPadding(0, 16, 0, 24)
            },
        )
        addView(
            Button(context).apply {
                text = "Switch keyboard"
                setOnClickListener { panelActions.switchKeyboard() }
            },
        )
    }

    /**
     * Never take over the screen in landscape.
     *
     * By default an input method switches to "extract" mode on a short screen: the app is
     * hidden and replaced by a full-screen editing box owned by the keyboard. That is why
     * rotating turned the field into a giant text area with the conversation gone — it was
     * not a layout bug, it was the framework doing what it does unless told otherwise.
     *
     * Scribe's panel is short, and the whole point of dictating is watching the text land
     * where it is going, so the app stays visible.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // The decor exists for certain by now, even if it did not when the view was built.
        host.attachToWindowRoot(window?.window?.decorView)
        host.onResume()
        // The package being typed into is the only context Scribe ever reads, and it is
        // used for one thing: choosing a tone profile. Nothing about the field's contents
        // is inspected, and nothing leaves the device.
        engine.attach(sink, info?.packageName)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        host.onPause()
        // Abandon any decode in flight. The user has moved on; making them wait for a
        // transcript they will never see would only cost battery.
        engine.detach()
    }

    override fun onDestroy() {
        host.onDestroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------- the actions

    private val panelActions = object : PanelActions {

        /**
         * One tap starts, one tap stops. The transcript is inserted after the reveal, not
         * at the moment the microphone closes, so what lands in the field is what the
         * user just watched being assembled.
         */
        override fun toggleDictation() {
            engine.toggleDictation(sink, currentInputEditorInfo?.packageName)
        }

        override fun cancelDictation() = engine.cancelToggle()
        override fun toggleMode() = engine.toggleMode()
        override fun setBoost(held: Boolean) = engine.setBoost(held)

        override fun type(text: String) {
            currentInputConnection?.commitText(text, 1)
        }

        override fun backspace() {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        /**
         * Delete the word before the cursor, plus the whitespace that followed it.
         *
         * Dictation produces whole phrases, so clearing a bad one character by character
         * means holding backspace for twenty seconds. The text before the cursor is read
         * rather than assumed, so this deletes exactly one word wherever the cursor is —
         * and falls back to a single character when there is no word boundary to find.
         */
        override fun deleteWord() {
            val ic = currentInputConnection ?: return
            val before = ic.getTextBeforeCursor(WORD_LOOKBEHIND, 0)?.toString().orEmpty()
            if (before.isEmpty()) return

            var end = before.length
            while (end > 0 && before[end - 1].isWhitespace()) end--
            var start = end
            while (start > 0 && !before[start - 1].isWhitespace()) start--

            val count = (before.length - start).coerceAtLeast(1)
            ic.deleteSurroundingText(count, 0)
        }

        override fun space() {
            currentInputConnection?.commitText(" ", 1)
        }

        /**
         * Enter means "send" in a chat app and "new line" in a note. The editor says which
         * through its IME action, and honouring that is the difference between a keyboard
         * that works in WhatsApp and one that inserts a stray line break.
         */
        override fun enter() {
            val ic = currentInputConnection ?: return
            val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            when (action) {
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_NEXT,
                EditorInfo.IME_ACTION_DONE,
                -> ic.performEditorAction(action)
                else -> {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
        }

        /**
         * One tap back to the previous keyboard.
         *
         * Scribe can type now, so this is a convenience rather than an escape hatch — but
         * choosing Scribe must still never feel like a commitment.
         */
        override fun switchKeyboard() {
            val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                false
            }
            if (!switched) {
                runCatching {
                    @Suppress("DEPRECATION")
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showInputMethodPicker()
                }.onFailure { Log.w(TAG, "could not show the keyboard picker", it) }
            }
        }

        /**
         * Open Scribe so the user can grant the microphone.
         *
         * Wrapped, because starting an Activity from an input method runs into
         * background-activity-start rules that vary by version and by OEM. If the system
         * refuses, an unhandled exception here would take the whole keyboard down —
         * turning "I could not open the app" into "my keyboard crashed".
         */
        override fun openApp() {
            val intent = Intent(this@ScribeInputMethodService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_REQUEST_MIC, true)
            }
            runCatching { startActivity(intent) }.onFailure {
                Log.w(TAG, "could not open Scribe from the keyboard", it)
            }
        }
    }
}

/** Enough context to find a word boundary without reading the whole field. */
private const val WORD_LOOKBEHIND = 96

/** Past this width a split layout is worth offering — roughly a small tablet. */
private const val SPLIT_WIDTH_DP = 600
