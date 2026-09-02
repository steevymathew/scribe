package dev.smantics.scribe.ime

import android.content.Intent
import android.util.Log
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.View
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

    private val sink = InputConnectionSink { currentInputConnection }

    override fun onCreate() {
        super.onCreate()
        host.onCreate()
        engine = ScribeEngine.get(this)
        engine.warmUp()
        scope.launch {
            engine.settings.config.collect { handedness.value = it.handedness }
        }
    }

    override fun onCreateInputView(): View {
        val view = ComposeView(this).apply {
            // The panel is torn down with its window rather than with the composition, so
            // a fold/unfold rebuilds cleanly instead of leaking the previous composition.
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val state by engine.state.collectAsState()
                val hand by handedness.collectAsState()
                ScribeTheme(handedness = hand) {
                    ScribePanel(state = state, actions = panelActions)
                }
            }
        }
        host.attachTo(view)
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
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
        override fun startRecording() = engine.startRecording()
        override fun stopRecording() = engine.stopRecording()
        override fun toggleMode() = engine.toggleMode()
        override fun setBoost(held: Boolean) = engine.setBoost(held)

        override fun backspace() {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        override fun space() {
            currentInputConnection?.commitText(" ", 1)
        }

        /**
         * Enter means "send" in a chat app and "new line" in a note. The editor tells us
         * which through its IME action, and honouring that is the difference between a
         * keyboard that works in WhatsApp and one that inserts a stray line break.
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

        override fun moveCursor(delta: Int) {
            val ic = currentInputConnection ?: return
            val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }

        /**
         * One tap back to the previous keyboard.
         *
         * Choosing Scribe must never feel like a commitment. `switchToPreviousInputMethod`
         * returns to whatever the user was using before; if the system has nothing to go
         * back to, the picker is the honest fallback rather than doing nothing.
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
         * background-activity-start rules that vary by Android version and by OEM. If the
         * system refuses, the exception would otherwise propagate out of a click handler
         * and take the whole keyboard down — turning "I could not open the app" into "my
         * keyboard crashed", which is a far worse failure than the one it came from.
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
